package nostr

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"sort"
	"sync"
	"time"

	"github.com/altradits/kengele/internal/message"
	"github.com/altradits/kengele/internal/transport"
	"github.com/btcsuite/btcd/btcec/v2"
	gn "github.com/nbd-wtf/go-nostr"
)

// Status is a snapshot of how many relays this transport can reach.
type Status struct {
	Reachable int
	Total     int
	At        time.Time
}

// Connectivity receives relay reachability after every publish round and
// whenever a receive connection opens or drops. The watcher (#7) uses it to
// tell a network shutdown apart from a missed check-in. It must not block.
type Connectivity interface {
	RelayStatus(Status)
}

// Config sets up a Transport. Only Relays is required.
type Config struct {
	// Relays the user chose. Every message goes to all of them (plus any
	// inbox relays set for the recipient).
	Relays []string
	// MinAccepted is how many relays must accept a gift wrap for Send to
	// count it as sent. Default 1.
	MinAccepted int
	// PublishTimeout bounds one relay's connect and publish. Default 10s.
	PublishTimeout time.Duration
	// RetryMin and RetryMax bound the backoff of Run and of receive
	// reconnects. Defaults 5s and 5m.
	RetryMin, RetryMax time.Duration
	// Senders are the keys whose messages Receive accepts (for a guardian:
	// the activists they watch over). Other gift wraps are dropped.
	Senders [][32]byte
	// Since is how far back the first subscription looks. Default: now
	// minus MaxJitter, because gift wraps are backdated by up to that much.
	Since time.Time
	// Outbox keeps unsent messages. Default: NewMemoryOutbox().
	Outbox Outbox
	// Connectivity, if set, is told about relay reachability.
	Connectivity Connectivity
	// DedupSize is how many recent messages Receive remembers. Default 10000.
	DedupSize int
	// Now and Rand are for tests. Defaults time.Now and crypto/rand.
	Now  func() time.Time
	Rand io.Reader
}

// Result says what happened to one Send.
type Result struct {
	// Accepted is how many relays acknowledged the gift wrap.
	Accepted int
	// Queued is true when fewer than MinAccepted relays accepted it; the
	// wrap is in the outbox and Flush or Run will retry it.
	Queued bool
}

// Delivery is one verified message received over Nostr.
type Delivery struct {
	Event *message.Event
	Wire  []byte
	// Via is the route the message arrived on: always transport.Nostr here.
	Via transport.Kind
}

// Transport sends and receives Kengele messages over Nostr relays. Its key is
// the same key the message package signs and decrypts with.
type Transport struct {
	key *btcec.PrivateKey
	pub [32]byte
	cfg Config

	mu     sync.Mutex
	conns  map[string]*relayConn // publish connections
	up     map[string]bool       // last known reachability per relay
	inbox  map[[32]byte][]string
	notify chan struct{}

	dedup *seenSet
}

// ErrNoRelays means there is nowhere to publish.
var ErrNoRelays = errors.New("nostr: no relays configured")

// New returns a Transport for key.
func New(key *btcec.PrivateKey, cfg Config) (*Transport, error) {
	if key == nil {
		return nil, errors.New("nostr: no key")
	}
	if len(cfg.Relays) == 0 {
		return nil, ErrNoRelays
	}
	if cfg.MinAccepted <= 0 {
		cfg.MinAccepted = 1
	}
	if cfg.PublishTimeout <= 0 {
		cfg.PublishTimeout = 10 * time.Second
	}
	if cfg.RetryMin <= 0 {
		cfg.RetryMin = 5 * time.Second
	}
	if cfg.RetryMax < cfg.RetryMin {
		cfg.RetryMax = 5 * time.Minute
		if cfg.RetryMax < cfg.RetryMin {
			cfg.RetryMax = cfg.RetryMin
		}
	}
	if cfg.Outbox == nil {
		cfg.Outbox = NewMemoryOutbox()
	}
	if cfg.DedupSize <= 0 {
		cfg.DedupSize = 10000
	}
	if cfg.Now == nil {
		cfg.Now = time.Now
	}
	cfg.Relays = normalizeAll(cfg.Relays)
	return &Transport{
		key:    key,
		pub:    message.PublicKey(key),
		cfg:    cfg,
		conns:  make(map[string]*relayConn),
		up:     make(map[string]bool),
		inbox:  make(map[[32]byte][]string),
		notify: make(chan struct{}, 1),
		dedup:  newSeenSet(cfg.DedupSize),
	}, nil
}

// SetInboxRelays adds a recipient's NIP-17 inbox relays (from their kind
// 10050 list) to the relays their messages are published to.
func (t *Transport) SetInboxRelays(recipient [32]byte, relays []string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.inbox[recipient] = normalizeAll(relays)
}

// Send gift-wraps wire for recipient and publishes it. If fewer than
// MinAccepted relays accept it, the wrap goes to the outbox and Result.Queued
// is true; the message is not lost. The error is only for bad input or a
// failing outbox.
func (t *Transport) Send(ctx context.Context, wire []byte, recipient [32]byte) (Result, error) {
	gw, err := Wrap(wire, t.key, recipient, t.cfg.Now(), t.cfg.Rand)
	if err != nil {
		return Result{}, err
	}
	it := Item{
		Event:    gw,
		Relays:   t.relaysFor(recipient),
		Accepted: map[string]bool{},
		Queued:   t.cfg.Now(),
	}
	t.publishItem(ctx, &it)
	n := len(it.Accepted)
	if n >= t.cfg.MinAccepted {
		return Result{Accepted: n}, nil
	}
	if err := t.cfg.Outbox.Put(it); err != nil {
		return Result{Accepted: n}, err
	}
	select {
	case t.notify <- struct{}{}:
	default:
	}
	return Result{Accepted: n, Queued: true}, nil
}

// SendAll sends wires[i] to recipients[i], for the output of
// message.SealAll.
func (t *Transport) SendAll(ctx context.Context, wires [][]byte, recipients [][32]byte) ([]Result, error) {
	if len(wires) != len(recipients) {
		return nil, errors.New("nostr: wires and recipients differ in length")
	}
	out := make([]Result, len(wires))
	for i := range wires {
		r, err := t.Send(ctx, wires[i], recipients[i])
		if err != nil {
			return out, err
		}
		out[i] = r
	}
	return out, nil
}

// Flush retries every queued gift wrap once and returns how many are still
// waiting.
func (t *Transport) Flush(ctx context.Context) (int, error) {
	items, err := t.cfg.Outbox.List()
	if err != nil {
		return 0, err
	}
	pending := 0
	for i := range items {
		it := items[i]
		t.publishItem(ctx, &it)
		if len(it.Accepted) >= t.cfg.MinAccepted {
			if err := t.cfg.Outbox.Remove(it.Event.ID); err != nil {
				return pending, err
			}
			continue
		}
		pending++
		if err := t.cfg.Outbox.Put(it); err != nil {
			return pending, err
		}
	}
	return pending, nil
}

// Run retries the outbox until ctx ends: at once when Send queues
// something, then with exponential backoff from RetryMin to RetryMax while
// anything is still waiting.
func (t *Transport) Run(ctx context.Context) {
	delay := t.cfg.RetryMin
	for {
		pending, _ := t.Flush(ctx)
		if pending == 0 {
			delay = t.cfg.RetryMin
			select {
			case <-ctx.Done():
				return
			case <-t.notify:
			}
			continue
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(delay):
		}
		delay *= 2
		if delay > t.cfg.RetryMax {
			delay = t.cfg.RetryMax
		}
	}
}

// Close drops the publish connections.
func (t *Transport) Close() {
	t.mu.Lock()
	defer t.mu.Unlock()
	for u, c := range t.conns {
		c.close()
		delete(t.conns, u)
	}
}

// Receive subscribes on every configured relay for gift wraps addressed to
// this key, and delivers each verified message once, however many relays
// carry it. Messages from keys not in Config.Senders are dropped. The
// channel closes when ctx ends.
func (t *Transport) Receive(ctx context.Context) <-chan Delivery {
	out := make(chan Delivery)
	since := t.cfg.Since
	if since.IsZero() {
		since = t.cfg.Now().Add(-MaxJitter)
	}
	var wg sync.WaitGroup
	for _, u := range t.cfg.Relays {
		wg.Add(1)
		go func(u string) {
			defer wg.Done()
			t.receiveLoop(ctx, u, since, out)
		}(u)
	}
	go func() {
		wg.Wait()
		close(out)
	}()
	return out
}

func (t *Transport) receiveLoop(ctx context.Context, url string, since time.Time, out chan<- Delivery) {
	delay := t.cfg.RetryMin
	ownHex := hex.EncodeToString(t.pub[:])
	for ctx.Err() == nil {
		c, err := t.dial(ctx, url)
		if err != nil {
			t.setUp(url, false)
			if !sleep(ctx, delay) {
				return
			}
			delay = minDur(delay*2, t.cfg.RetryMax)
			continue
		}
		t.setUp(url, true)
		delay = t.cfg.RetryMin
		connectedAt := t.cfg.Now()
		s := gn.Timestamp(since.Unix())
		id, sub, err := c.subscribe(ctx, gn.Filters{{
			Kinds: []int{gn.KindGiftWrap},
			Tags:  gn.TagMap{"p": {ownHex}},
			Since: &s,
		}})
		if err == nil {
			t.consume(ctx, c, sub, out)
			c.unsubscribe(id)
		}
		c.close()
		t.setUp(url, false)
		// After a drop, look back far enough to catch anything backdated
		// that was published while we were away. Dedup hides repeats.
		since = connectedAt.Add(-MaxJitter)
		if !sleep(ctx, delay) {
			return
		}
	}
}

func (t *Transport) consume(ctx context.Context, c *relayConn, sub *clientSub, out chan<- Delivery) {
	for {
		select {
		case <-ctx.Done():
			return
		case <-c.ctx.Done():
			return
		case <-sub.done:
			return
		case ev := <-sub.events:
			if d, ok := t.open(ev); ok {
				select {
				case out <- d:
				case <-ctx.Done():
					return
				}
			}
		}
	}
}

// open unwraps and verifies one gift wrap. Anything wrong is dropped
// silently: nothing about it is logged.
func (t *Transport) open(ev *gn.Event) (Delivery, bool) {
	if ev == nil || t.dedup.seen("e:"+ev.ID) {
		return Delivery{}, false
	}
	wire, sealer, err := Unwrap(ev, t.key)
	if err != nil {
		return Delivery{}, false
	}
	if !t.isSender(sealer) {
		return Delivery{}, false
	}
	e, err := message.Open(wire, t.key, [][32]byte{sealer}, t.cfg.Now())
	if err != nil {
		return Delivery{}, false
	}
	// The same message can arrive in different gift wraps (one per relay
	// round, or re-sent by the sender); keep only the first.
	sum := sha256.Sum256(wire)
	if t.dedup.seen("w:" + hex.EncodeToString(sum[:])) {
		return Delivery{}, false
	}
	return Delivery{Event: e, Wire: wire, Via: transport.Nostr}, true
}

func (t *Transport) isSender(k [32]byte) bool {
	for _, s := range t.cfg.Senders {
		if s == k {
			return true
		}
	}
	return false
}

// publishItem tries every relay of it that has not accepted it yet, in
// parallel, and records which accept.
func (t *Transport) publishItem(ctx context.Context, it *Item) {
	it.Attempts++
	var targets []string
	for _, u := range it.Relays {
		if !it.Accepted[u] {
			targets = append(targets, u)
		}
	}
	accepted := make([]bool, len(targets))
	var wg sync.WaitGroup
	for i, u := range targets {
		wg.Add(1)
		go func(i int, u string) {
			defer wg.Done()
			ok, reached := t.publishOne(ctx, u, it.Event)
			t.setUp(u, reached)
			accepted[i] = ok
		}(i, u)
	}
	wg.Wait()
	for i, u := range targets {
		if accepted[i] {
			it.Accepted[u] = true
		}
	}
	t.report()
}

// publishOne returns whether the relay accepted ev and whether it could be
// reached at all.
func (t *Transport) publishOne(ctx context.Context, url string, ev gn.Event) (accepted, reached bool) {
	pctx, cancel := context.WithTimeout(ctx, t.cfg.PublishTimeout)
	defer cancel()
	c, err := t.conn(pctx, url)
	if err != nil {
		return false, false
	}
	err = c.publish(pctx, ev)
	if err == nil {
		return true, true
	}
	if errors.Is(err, errRejected) {
		// The relay answered "no": it was reachable.
		return false, true
	}
	t.dropConn(url, c)
	return false, false
}

func (t *Transport) conn(ctx context.Context, url string) (*relayConn, error) {
	t.mu.Lock()
	c := t.conns[url]
	t.mu.Unlock()
	if c != nil && c.connected() {
		return c, nil
	}
	c, err := t.dial(ctx, url)
	if err != nil {
		return nil, err
	}
	t.mu.Lock()
	if old := t.conns[url]; old != nil && old != c {
		old.close()
	}
	t.conns[url] = c
	t.mu.Unlock()
	return c, nil
}

func (t *Transport) dropConn(url string, c *relayConn) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.conns[url] == c {
		delete(t.conns, url)
	}
	c.close()
}

func (t *Transport) dial(ctx context.Context, url string) (*relayConn, error) {
	dctx, cancel := context.WithTimeout(ctx, t.cfg.PublishTimeout)
	defer cancel()
	return dialRelay(dctx, url)
}

func (t *Transport) relaysFor(recipient [32]byte) []string {
	t.mu.Lock()
	defer t.mu.Unlock()
	set := map[string]bool{}
	for _, u := range t.cfg.Relays {
		set[u] = true
	}
	for _, u := range t.inbox[recipient] {
		set[u] = true
	}
	out := make([]string, 0, len(set))
	for u := range set {
		out = append(out, u)
	}
	sort.Strings(out)
	return out
}

func (t *Transport) setUp(url string, up bool) {
	t.mu.Lock()
	changed := t.up[url] != up
	_, known := t.up[url]
	t.up[url] = up
	t.mu.Unlock()
	if changed || !known {
		t.report()
	}
}

func (t *Transport) report() {
	if t.cfg.Connectivity == nil {
		return
	}
	t.mu.Lock()
	s := Status{Total: len(t.up), At: t.cfg.Now()}
	for _, up := range t.up {
		if up {
			s.Reachable++
		}
	}
	t.mu.Unlock()
	t.cfg.Connectivity.RelayStatus(s)
}

func normalizeAll(urls []string) []string {
	set := map[string]bool{}
	var out []string
	for _, u := range urls {
		n := gn.NormalizeURL(u)
		if n == "" || set[n] {
			continue
		}
		set[n] = true
		out = append(out, n)
	}
	return out
}

func sleep(ctx context.Context, d time.Duration) bool {
	select {
	case <-ctx.Done():
		return false
	case <-time.After(d):
		return true
	}
}

func minDur(a, b time.Duration) time.Duration {
	if a < b {
		return a
	}
	return b
}

// seenSet remembers the last n keys.
type seenSet struct {
	mu   sync.Mutex
	n    int
	set  map[string]struct{}
	ring []string
	next int
}

func newSeenSet(n int) *seenSet {
	return &seenSet{n: n, set: make(map[string]struct{}, n), ring: make([]string, 0, n)}
}

// seen reports whether k was already recorded, and records it.
func (s *seenSet) seen(k string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.set[k]; ok {
		return true
	}
	if len(s.ring) < s.n {
		s.ring = append(s.ring, k)
	} else {
		delete(s.set, s.ring[s.next])
		s.ring[s.next] = k
		s.next = (s.next + 1) % s.n
	}
	s.set[k] = struct{}{}
	return false
}
