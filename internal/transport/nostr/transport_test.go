package nostr

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/altradits/kengele/internal/message"
	"github.com/btcsuite/btcd/btcec/v2"
	gn "github.com/nbd-wtf/go-nostr"
	"github.com/nbd-wtf/go-nostr/nip44"
)

func newKey(t *testing.T) *btcec.PrivateKey {
	t.Helper()
	k, err := btcec.NewPrivateKey()
	if err != nil {
		t.Fatal(err)
	}
	return k
}

func seal(t *testing.T, kind message.Kind, seq uint32, from *btcec.PrivateKey, to [32]byte) []byte {
	t.Helper()
	wire, err := message.Seal(message.Event{
		Kind:      kind,
		CreatedAt: time.Now().Truncate(time.Second),
		Seq:       seq,
		Body:      []byte("Kenyatta Ave"),
	}, from, to)
	if err != nil {
		t.Fatal(err)
	}
	return wire
}

func fastConfig(relays ...string) Config {
	return Config{
		Relays:         relays,
		PublishTimeout: 2 * time.Second,
		RetryMin:       20 * time.Millisecond,
		RetryMax:       200 * time.Millisecond,
	}
}

func receiveOne(t *testing.T, ch <-chan Delivery, within time.Duration) Delivery {
	t.Helper()
	select {
	case d, ok := <-ch:
		if !ok {
			t.Fatal("receive channel closed")
		}
		return d
	case <-time.After(within):
		t.Fatal("no delivery in time")
	}
	return Delivery{}
}

func expectNone(t *testing.T, ch <-chan Delivery, within time.Duration) {
	t.Helper()
	select {
	case d := <-ch:
		t.Fatalf("unexpected extra delivery: kind %v seq %d", d.Event.Kind, d.Event.Seq)
	case <-time.After(within):
	}
}

// waitSubscribed gives receive loops time to connect and send their REQ.
func waitSubscribed(t *testing.T, relays ...*testRelay) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		all := true
		for _, r := range relays {
			r.mu.Lock()
			n := 0
			for _, s := range r.subs {
				n += len(s)
			}
			r.mu.Unlock()
			if n == 0 {
				all = false
			}
		}
		if all {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("receivers did not subscribe")
}

func TestWrapUnwrapRoundTrip(t *testing.T) {
	activist, guardian := newKey(t), newKey(t)
	wire := seal(t, message.KindPanic, 1, activist, message.PublicKey(guardian))
	gw, err := Wrap(wire, activist, message.PublicKey(guardian), time.Now(), nil)
	if err != nil {
		t.Fatal(err)
	}
	got, sealer, err := Unwrap(&gw, guardian)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != string(wire) {
		t.Fatal("wire changed in transit")
	}
	if sealer != message.PublicKey(activist) {
		t.Fatal("wrong sealer")
	}
	e, err := message.Open(got, guardian, [][32]byte{sealer}, time.Now())
	if err != nil || e.Kind != message.KindPanic {
		t.Fatalf("open: %v %v", e, err)
	}
}

// The outer event must not show the sender's key, the real time or anything
// readable from the message.
func TestOuterEventHidesSenderTimeAndContent(t *testing.T) {
	activist, guardian := newKey(t), newKey(t)
	apub := message.PublicKey(activist)
	apubHex := hex.EncodeToString(apub[:])
	gpub := message.PublicKey(guardian)
	wire := seal(t, message.KindDuressCheckIn, 7, activist, gpub)
	text := message.EncodeText(wire)
	now := time.Unix(1_800_000_000, 0)

	wrapKeys := map[string]bool{}
	times := map[gn.Timestamp]bool{}
	for i := 0; i < 50; i++ {
		gw, err := Wrap(wire, activist, gpub, now, nil)
		if err != nil {
			t.Fatal(err)
		}
		raw, _ := json.Marshal(gw)
		s := string(raw)
		if strings.Contains(s, apubHex) {
			t.Fatal("sender key visible in outer event")
		}
		if strings.Contains(s, text) || strings.Contains(s, text[:40]) {
			t.Fatal("message text visible in outer event")
		}
		if gw.Kind != gn.KindGiftWrap {
			t.Fatalf("kind %d", gw.Kind)
		}
		if gw.PubKey == apubHex || wrapKeys[gw.PubKey] {
			t.Fatal("wrapper key reused or equal to sender")
		}
		wrapKeys[gw.PubKey] = true
		at := gw.CreatedAt.Time()
		if at.After(now) || now.Sub(at) >= MaxJitter {
			t.Fatalf("created_at %v outside [now-%v, now]", at, MaxJitter)
		}
		times[gw.CreatedAt] = true
		if len(gw.Tags) != 1 || gw.Tags[0][0] != "p" || gw.Tags[0][1] != hex.EncodeToString(gpub[:]) {
			t.Fatalf("unexpected tags %v", gw.Tags)
		}
	}
	if times[gn.Timestamp(now.Unix())] && len(times) == 1 {
		t.Fatal("created_at is the real time")
	}
	if len(times) < 45 {
		t.Fatalf("created_at not random enough: %d distinct of 50", len(times))
	}
}

func TestUnwrapRejects(t *testing.T) {
	activist, guardian, other := newKey(t), newKey(t), newKey(t)
	gpub := message.PublicKey(guardian)
	wire := seal(t, message.KindCheckIn, 1, activist, gpub)
	good, err := Wrap(wire, activist, gpub, time.Now(), nil)
	if err != nil {
		t.Fatal(err)
	}

	t.Run("wrong recipient", func(t *testing.T) {
		if _, _, err := Unwrap(&good, other); err != ErrNotForUs {
			t.Fatalf("got %v", err)
		}
	})
	t.Run("tampered content", func(t *testing.T) {
		bad := good
		bad.Content = bad.Content[:len(bad.Content)-4] + "AAAA"
		if _, _, err := Unwrap(&bad, guardian); err == nil {
			t.Fatal("accepted tampered wrap")
		}
	})
	t.Run("wrong kind", func(t *testing.T) {
		bad := good
		bad.Kind = gn.KindDirectMessage
		if _, _, err := Unwrap(&bad, guardian); err != ErrMalformed {
			t.Fatalf("got %v", err)
		}
	})
	t.Run("rumor author differs from sealer", func(t *testing.T) {
		// "other" seals a rumor that claims to be from the activist.
		gw := forge(t, wire, other, message.PublicKey(activist), gpub)
		if _, _, err := Unwrap(&gw, guardian); err != ErrMalformed {
			t.Fatalf("got %v", err)
		}
	})
	t.Run("not a kengele message", func(t *testing.T) {
		if _, err := Wrap([]byte("hello"), activist, gpub, time.Now(), nil); err == nil {
			t.Fatal("wrapped a non-Kengele payload")
		}
	})
}

// forge builds a gift wrap whose seal is signed by signer but whose rumor
// claims claimed as its author.
func forge(t *testing.T, wire []byte, signer *btcec.PrivateKey, claimed, recipient [32]byte) gn.Event {
	t.Helper()
	sec := hex.EncodeToString(signer.Serialize())
	rh := hex.EncodeToString(recipient[:])
	rumor := gn.Event{Kind: gn.KindDirectMessage, PubKey: hex.EncodeToString(claimed[:]),
		CreatedAt: gn.Now(), Tags: gn.Tags{{"p", rh}}, Content: message.EncodeText(wire)}
	rumor.ID = rumor.GetID()
	ck, _ := nip44.GenerateConversationKey(rh, sec)
	c1, _ := nip44.Encrypt(rumor.String(), ck)
	sl := gn.Event{Kind: gn.KindSeal, CreatedAt: gn.Now(), Tags: gn.Tags{}, Content: c1}
	if err := sl.Sign(sec); err != nil {
		t.Fatal(err)
	}
	one := gn.GeneratePrivateKey()
	ck2, _ := nip44.GenerateConversationKey(rh, one)
	c2, _ := nip44.Encrypt(sl.String(), ck2)
	gw := gn.Event{Kind: gn.KindGiftWrap, CreatedAt: gn.Now(), Tags: gn.Tags{{"p", rh}}, Content: c2}
	if err := gw.Sign(one); err != nil {
		t.Fatal(err)
	}
	return gw
}

// An activist sends a panic to three guardians over two relays; each
// guardian gets it exactly once.
func TestActivistToGuardiansOverRelays(t *testing.T) {
	r1, r2 := newTestRelay(t), newTestRelay(t)
	activist := newKey(t)
	guardians := []*btcec.PrivateKey{newKey(t), newKey(t), newKey(t)}
	apub := message.PublicKey(activist)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	var chans []<-chan Delivery
	var recipients [][32]byte
	for _, g := range guardians {
		cfg := fastConfig(r1.url(), r2.url())
		cfg.Senders = [][32]byte{apub}
		tr, err := New(g, cfg)
		if err != nil {
			t.Fatal(err)
		}
		chans = append(chans, tr.Receive(ctx))
		recipients = append(recipients, message.PublicKey(g))
	}
	waitSubscribed(t, r1, r2)

	sender, err := New(activist, fastConfig(r1.url(), r2.url()))
	if err != nil {
		t.Fatal(err)
	}
	defer sender.Close()
	wires, err := message.SealAll(message.Event{
		Kind: message.KindPanic, CreatedAt: time.Now().Truncate(time.Second), Seq: 3,
	}, activist, recipients)
	if err != nil {
		t.Fatal(err)
	}
	res, err := sender.SendAll(ctx, wires, recipients)
	if err != nil {
		t.Fatal(err)
	}
	for i, r := range res {
		if r.Accepted != 2 || r.Queued {
			t.Fatalf("guardian %d: %+v", i, r)
		}
	}
	for i, ch := range chans {
		d := receiveOne(t, ch, 3*time.Second)
		if d.Event.Kind != message.KindPanic || d.Event.Seq != 3 || d.Event.Signer != apub {
			t.Fatalf("guardian %d got %+v", i, d.Event)
		}
		expectNone(t, ch, 200*time.Millisecond)
	}
}

// The same message reaching a guardian from three relays, and again in a
// fresh gift wrap, is delivered once.
func TestDuplicateSuppression(t *testing.T) {
	r1, r2, r3 := newTestRelay(t), newTestRelay(t), newTestRelay(t)
	activist, guardian := newKey(t), newKey(t)
	gpub := message.PublicKey(guardian)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	cfg := fastConfig(r1.url(), r2.url(), r3.url())
	cfg.Senders = [][32]byte{message.PublicKey(activist)}
	rx, _ := New(guardian, cfg)
	ch := rx.Receive(ctx)
	waitSubscribed(t, r1, r2, r3)

	tx, _ := New(activist, fastConfig(r1.url(), r2.url(), r3.url()))
	defer tx.Close()
	wire := seal(t, message.KindCheckIn, 1, activist, gpub)
	if _, err := tx.Send(ctx, wire, gpub); err != nil {
		t.Fatal(err)
	}
	if _, err := tx.Send(ctx, wire, gpub); err != nil { // re-wrapped copy
		t.Fatal(err)
	}
	receiveOne(t, ch, 3*time.Second)
	expectNone(t, ch, 400*time.Millisecond)
}

// One relay refuses, one accepts: MinAccepted decides whether it is sent.
func TestPartialAcceptance(t *testing.T) {
	ok, refuse := newTestRelay(t), newTestRelay(t)
	refuse.setReject(true)
	activist, guardian := newKey(t), newKey(t)
	gpub := message.PublicKey(guardian)
	ctx := context.Background()

	tx1, _ := New(activist, fastConfig(ok.url(), refuse.url()))
	defer tx1.Close()
	res, err := tx1.Send(ctx, seal(t, message.KindCheckIn, 1, activist, gpub), gpub)
	if err != nil || res.Accepted != 1 || res.Queued {
		t.Fatalf("min 1: %+v %v", res, err)
	}

	cfg := fastConfig(ok.url(), refuse.url())
	cfg.MinAccepted = 2
	tx2, _ := New(activist, cfg)
	defer tx2.Close()
	res, err = tx2.Send(ctx, seal(t, message.KindCheckIn, 2, activist, gpub), gpub)
	if err != nil || res.Accepted != 1 || !res.Queued {
		t.Fatalf("min 2: %+v %v", res, err)
	}
	items, _ := tx2.cfg.Outbox.List()
	if len(items) != 1 || !items[0].Accepted[gn.NormalizeURL(ok.url())] {
		t.Fatalf("outbox: %+v", items)
	}
	// Once the second relay accepts, Flush clears it without republishing
	// to the first.
	refuse.setReject(false)
	before := len(ok.stored())
	pending, err := tx2.Flush(ctx)
	if err != nil || pending != 0 {
		t.Fatalf("flush: %d %v", pending, err)
	}
	if len(ok.stored()) != before {
		t.Fatal("republished to a relay that had already accepted")
	}
}

type statusLog struct {
	mu sync.Mutex
	s  []Status
}

func (l *statusLog) RelayStatus(s Status) {
	l.mu.Lock()
	l.s = append(l.s, s)
	l.mu.Unlock()
}

func (l *statusLog) last() Status {
	l.mu.Lock()
	defer l.mu.Unlock()
	if len(l.s) == 0 {
		return Status{}
	}
	return l.s[len(l.s)-1]
}

// All relays down: the message is queued, connectivity says 0 reachable,
// and after the relay comes back Run delivers it.
func TestOutageQueuesThenRetryAfterReconnect(t *testing.T) {
	relay := newTestRelay(t)
	activist, guardian := newKey(t), newKey(t)
	gpub := message.PublicKey(guardian)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	relay.stop()
	log := &statusLog{}
	cfg := fastConfig(relay.url())
	cfg.Connectivity = log
	tx, _ := New(activist, cfg)
	defer tx.Close()

	res, err := tx.Send(ctx, seal(t, message.KindPanic, 9, activist, gpub), gpub)
	if err != nil || res.Accepted != 0 || !res.Queued {
		t.Fatalf("send during outage: %+v %v", res, err)
	}
	if s := log.last(); s.Reachable != 0 || s.Total != 1 {
		t.Fatalf("status during outage: %+v", s)
	}
	go tx.Run(ctx)

	time.Sleep(100 * time.Millisecond)
	relay.start()

	deadline := time.Now().Add(5 * time.Second)
	for {
		items, _ := tx.cfg.Outbox.List()
		if len(items) == 0 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("outbox did not drain after reconnect")
		}
		time.Sleep(20 * time.Millisecond)
	}
	if n := len(relay.stored()); n != 1 {
		t.Fatalf("relay has %d events", n)
	}
	if s := log.last(); s.Reachable != 1 {
		t.Fatalf("status after reconnect: %+v", s)
	}

	// The guardian, coming online later, still gets it.
	rcfg := fastConfig(relay.url())
	rcfg.Senders = [][32]byte{message.PublicKey(activist)}
	rx, _ := New(guardian, rcfg)
	d := receiveOne(t, rx.Receive(ctx), 3*time.Second)
	if d.Event.Kind != message.KindPanic || d.Event.Seq != 9 {
		t.Fatalf("got %+v", d.Event)
	}
}

// A receiver whose relay drops reconnects and keeps receiving.
func TestReceiveReconnects(t *testing.T) {
	relay := newTestRelay(t)
	activist, guardian := newKey(t), newKey(t)
	gpub := message.PublicKey(guardian)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	rcfg := fastConfig(relay.url())
	rcfg.Senders = [][32]byte{message.PublicKey(activist)}
	rx, _ := New(guardian, rcfg)
	ch := rx.Receive(ctx)
	waitSubscribed(t, relay)

	relay.stop()
	time.Sleep(50 * time.Millisecond)
	relay.start()
	waitSubscribed(t, relay)

	tx, _ := New(activist, fastConfig(relay.url()))
	defer tx.Close()
	if _, err := tx.Send(ctx, seal(t, message.KindCheckIn, 4, activist, gpub), gpub); err != nil {
		t.Fatal(err)
	}
	if d := receiveOne(t, ch, 3*time.Second); d.Event.Seq != 4 {
		t.Fatalf("got %+v", d.Event)
	}
}

// Messages from keys the guardian does not watch are dropped.
func TestUnknownSenderDropped(t *testing.T) {
	relay := newTestRelay(t)
	stranger, guardian, activist := newKey(t), newKey(t), newKey(t)
	gpub := message.PublicKey(guardian)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	rcfg := fastConfig(relay.url())
	rcfg.Senders = [][32]byte{message.PublicKey(activist)}
	rx, _ := New(guardian, rcfg)
	ch := rx.Receive(ctx)
	waitSubscribed(t, relay)

	tx, _ := New(stranger, fastConfig(relay.url()))
	defer tx.Close()
	if _, err := tx.Send(ctx, seal(t, message.KindPanic, 1, stranger, gpub), gpub); err != nil {
		t.Fatal(err)
	}
	expectNone(t, ch, 400*time.Millisecond)
}

func TestInboxRelayList(t *testing.T) {
	relay := newTestRelay(t)
	guardian, activist := newKey(t), newKey(t)
	gpub := message.PublicKey(guardian)

	ev, err := InboxRelayList(guardian, []string{"wss://inbox.example", "wss://inbox.example/", relay.url()}, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	urls, err := ParseInboxRelays(&ev, gpub)
	if err != nil || len(urls) != 2 {
		t.Fatalf("parse: %v %v", urls, err)
	}
	if _, err := ParseInboxRelays(&ev, message.PublicKey(activist)); err == nil {
		t.Fatal("accepted list for wrong author")
	}

	ctx := context.Background()
	pub, _ := New(guardian, fastConfig(relay.url()))
	defer pub.Close()
	c, err := pub.conn(ctx, gn.NormalizeURL(relay.url()))
	if err != nil {
		t.Fatal(err)
	}
	if err := c.publish(ctx, ev); err != nil {
		t.Fatal(err)
	}
	tx, _ := New(activist, fastConfig(relay.url()))
	defer tx.Close()
	got, err := tx.LookupInboxRelays(ctx, gpub)
	if err != nil || len(got) != 2 {
		t.Fatalf("lookup: %v %v", got, err)
	}
	tx.SetInboxRelays(gpub, got)
	if n := len(tx.relaysFor(gpub)); n != 2 { // relay.url() is in both lists
		t.Fatalf("relaysFor: %d", n)
	}
}

func TestSeenSetEvicts(t *testing.T) {
	s := newSeenSet(2)
	if s.seen("a") || s.seen("b") || !s.seen("a") {
		t.Fatal("basic")
	}
	s.seen("c") // evicts a
	if s.seen("a") {
		t.Fatal("a should have been evicted")
	}
}
