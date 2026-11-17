package nostr

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"sync"

	"github.com/coder/websocket"
	gn "github.com/nbd-wtf/go-nostr"
)

// relayConn is a minimal NIP-01 client: publish with OK, subscribe, and
// query until EOSE. It is used instead of go-nostr's Relay, whose Close
// races with its own goroutines and whose Publish reports success when the
// connection drops before the relay answers.
type relayConn struct {
	url    string
	ws     *websocket.Conn
	ctx    context.Context // done when the connection is gone
	cancel context.CancelFunc

	mu   sync.Mutex
	oks  map[string]chan okResult
	subs map[string]*clientSub
}

type okResult struct {
	ok     bool
	reason string
}

type clientSub struct {
	events chan *gn.Event // never closed; select on done as well
	eose   chan struct{}
	done   chan struct{} // closed when the subscription ends
	once   sync.Once
	eonce  sync.Once
}

func (s *clientSub) end() { s.once.Do(func() { close(s.done) }) }

var (
	errConnLost = errors.New("nostr: relay connection lost")
	errRejected = errors.New("nostr: relay rejected event")
)

// maxRelayMessage bounds what we read from a relay. A gift wrap of a
// Kengele message is about 2 KB.
const maxRelayMessage = 256 << 10

func dialRelay(ctx context.Context, url string) (*relayConn, error) {
	ws, _, err := websocket.Dial(ctx, url, nil)
	if err != nil {
		return nil, err
	}
	ws.SetReadLimit(maxRelayMessage)
	lctx, cancel := context.WithCancel(context.Background())
	c := &relayConn{
		url:    url,
		ws:     ws,
		ctx:    lctx,
		cancel: cancel,
		oks:    map[string]chan okResult{},
		subs:   map[string]*clientSub{},
	}
	go c.readLoop()
	return c, nil
}

func (c *relayConn) connected() bool { return c.ctx.Err() == nil }

func (c *relayConn) close() {
	c.cancel()
	_ = c.ws.CloseNow()
}

func (c *relayConn) readLoop() {
	defer func() {
		c.close()
		c.mu.Lock()
		for id, s := range c.subs {
			s.end()
			delete(c.subs, id)
		}
		c.mu.Unlock()
	}()
	for {
		_, data, err := c.ws.Read(c.ctx)
		if err != nil {
			return
		}
		switch env := gn.ParseMessage(string(data)).(type) {
		case *gn.OKEnvelope:
			c.mu.Lock()
			ch := c.oks[env.EventID]
			c.mu.Unlock()
			if ch != nil {
				select {
				case ch <- okResult{ok: env.OK, reason: env.Reason}:
				default:
				}
			}
		case *gn.EventEnvelope:
			if env.SubscriptionID == nil {
				continue
			}
			ev := env.Event
			c.mu.Lock()
			s := c.subs[*env.SubscriptionID]
			c.mu.Unlock()
			if s == nil {
				continue
			}
			select {
			case s.events <- &ev:
			case <-s.done:
			case <-c.ctx.Done():
				return
			}
		case *gn.EOSEEnvelope:
			c.mu.Lock()
			s := c.subs[string(*env)]
			if s != nil {
				s.eonce.Do(func() { close(s.eose) })
			}
			c.mu.Unlock()
		case *gn.ClosedEnvelope:
			c.mu.Lock()
			if s := c.subs[env.SubscriptionID]; s != nil {
				s.end()
				delete(c.subs, env.SubscriptionID)
			}
			c.mu.Unlock()
		}
	}
}

func (c *relayConn) write(ctx context.Context, env gn.Envelope) error {
	b, err := env.MarshalJSON()
	if err != nil {
		return err
	}
	return c.ws.Write(ctx, websocket.MessageText, b)
}

// publish sends ev and waits for the relay's OK. Only an explicit OK true
// counts as accepted; a dropped connection or a timeout does not.
func (c *relayConn) publish(ctx context.Context, ev gn.Event) error {
	ch := make(chan okResult, 1)
	c.mu.Lock()
	c.oks[ev.ID] = ch
	c.mu.Unlock()
	defer func() {
		c.mu.Lock()
		delete(c.oks, ev.ID)
		c.mu.Unlock()
	}()
	if err := c.write(ctx, &gn.EventEnvelope{Event: ev}); err != nil {
		return err
	}
	select {
	case r := <-ch:
		if !r.ok {
			return errRejected
		}
		return nil
	case <-c.ctx.Done():
		return errConnLost
	case <-ctx.Done():
		return ctx.Err()
	}
}

// subscribe opens a subscription. Events arrive on sub.events until
// sub.done closes (the relay closed it, it was unsubscribed or the
// connection dropped). sub.eose closes when stored events have been sent.
func (c *relayConn) subscribe(ctx context.Context, filters gn.Filters) (id string, sub *clientSub, err error) {
	var b [8]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", nil, err
	}
	id = hex.EncodeToString(b[:])
	s := &clientSub{events: make(chan *gn.Event, 64), eose: make(chan struct{}), done: make(chan struct{})}
	c.mu.Lock()
	c.subs[id] = s
	c.mu.Unlock()
	if err := c.write(ctx, &gn.ReqEnvelope{SubscriptionID: id, Filters: filters}); err != nil {
		c.unsubscribe(id)
		return "", nil, err
	}
	return id, s, nil
}

func (c *relayConn) unsubscribe(id string) {
	c.mu.Lock()
	s := c.subs[id]
	delete(c.subs, id)
	c.mu.Unlock()
	if s != nil {
		s.end()
	}
	if c.connected() {
		cl := gn.CloseEnvelope(id)
		_ = c.write(c.ctx, &cl)
	}
}

// querySync returns the stored events matching filter, up to EOSE.
func (c *relayConn) querySync(ctx context.Context, filter gn.Filter) ([]*gn.Event, error) {
	id, sub, err := c.subscribe(ctx, gn.Filters{filter})
	if err != nil {
		return nil, err
	}
	defer c.unsubscribe(id)
	var out []*gn.Event
	for {
		select {
		case ev := <-sub.events:
			out = append(out, ev)
		case <-sub.eose:
			// Collect what arrived before EOSE.
			for {
				select {
				case ev := <-sub.events:
					out = append(out, ev)
				default:
					return out, nil
				}
			}
		case <-sub.done:
			return out, errConnLost
		case <-ctx.Done():
			return out, ctx.Err()
		}
	}
}
