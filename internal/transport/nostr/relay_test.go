package nostr

import (
	"context"
	"net"
	"net/http"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
	gn "github.com/nbd-wtf/go-nostr"
)

// testRelay is a minimal in-process Nostr relay (NIP-01 EVENT, REQ, CLOSE)
// for tests. It can reject every event, and it can be stopped and started
// again on the same address to simulate an outage.
type testRelay struct {
	t    *testing.T
	addr string

	mu      sync.Mutex
	events  []gn.Event
	ids     map[string]bool
	subs    map[*websocket.Conn]map[string]gn.Filters
	conns   map[*websocket.Conn]bool
	reject  bool
	srv     *http.Server
	ln      net.Listener
	running bool
}

func newTestRelay(t *testing.T) *testRelay {
	t.Helper()
	r := &testRelay{
		t:     t,
		ids:   map[string]bool{},
		subs:  map[*websocket.Conn]map[string]gn.Filters{},
		conns: map[*websocket.Conn]bool{},
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	r.addr = ln.Addr().String()
	r.serve(ln)
	t.Cleanup(r.stop)
	return r
}

func (r *testRelay) url() string { return "ws://" + r.addr }

func (r *testRelay) serve(ln net.Listener) {
	srv := &http.Server{Handler: http.HandlerFunc(r.handle), ReadHeaderTimeout: 5 * time.Second}
	r.mu.Lock()
	r.srv, r.ln, r.running = srv, ln, true
	r.mu.Unlock()
	go func() { _ = srv.Serve(ln) }()
}

// stop closes the listener and every open connection.
func (r *testRelay) stop() {
	r.mu.Lock()
	if !r.running {
		r.mu.Unlock()
		return
	}
	r.running = false
	srv := r.srv
	conns := make([]*websocket.Conn, 0, len(r.conns))
	for c := range r.conns {
		conns = append(conns, c)
	}
	r.conns = map[*websocket.Conn]bool{}
	r.subs = map[*websocket.Conn]map[string]gn.Filters{}
	r.mu.Unlock()
	_ = srv.Close()
	for _, c := range conns {
		_ = c.CloseNow()
	}
}

// start listens again on the same address, keeping stored events.
func (r *testRelay) start() {
	r.t.Helper()
	var ln net.Listener
	var err error
	for i := 0; i < 50; i++ {
		ln, err = net.Listen("tcp", r.addr)
		if err == nil {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if err != nil {
		r.t.Fatal(err)
	}
	r.serve(ln)
}

func (r *testRelay) setReject(v bool) {
	r.mu.Lock()
	r.reject = v
	r.mu.Unlock()
}

func (r *testRelay) stored() []gn.Event {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]gn.Event(nil), r.events...)
}

func (r *testRelay) handle(w http.ResponseWriter, req *http.Request) {
	c, err := websocket.Accept(w, req, nil)
	if err != nil {
		return
	}
	c.SetReadLimit(1 << 20)
	r.mu.Lock()
	r.conns[c] = true
	r.subs[c] = map[string]gn.Filters{}
	r.mu.Unlock()
	defer func() {
		r.mu.Lock()
		delete(r.conns, c)
		delete(r.subs, c)
		r.mu.Unlock()
		_ = c.CloseNow()
	}()
	ctx := context.Background()
	var wmu sync.Mutex
	send := func(env gn.Envelope) {
		b, _ := env.MarshalJSON()
		wmu.Lock()
		defer wmu.Unlock()
		_ = c.Write(ctx, websocket.MessageText, b)
	}
	for {
		_, data, err := c.Read(ctx)
		if err != nil {
			return
		}
		switch env := gn.ParseMessage(string(data)).(type) {
		case *gn.EventEnvelope:
			ev := env.Event
			r.mu.Lock()
			reject := r.reject
			r.mu.Unlock()
			if ok, _ := ev.CheckSignature(); !ok || reject {
				send(&gn.OKEnvelope{EventID: ev.ID, OK: false, Reason: "blocked: test"})
				continue
			}
			r.mu.Lock()
			dup := r.ids[ev.ID]
			if !dup {
				r.ids[ev.ID] = true
				r.events = append(r.events, ev)
			}
			type target struct {
				c  *websocket.Conn
				id string
			}
			var targets []target
			if !dup {
				for sc, subs := range r.subs {
					for id, fs := range subs {
						if fs.Match(&ev) {
							targets = append(targets, target{sc, id})
						}
					}
				}
			}
			r.mu.Unlock()
			send(&gn.OKEnvelope{EventID: ev.ID, OK: true, Reason: map[bool]string{true: "duplicate:", false: ""}[dup]})
			for _, tg := range targets {
				id := tg.id
				b, _ := (&gn.EventEnvelope{SubscriptionID: &id, Event: ev}).MarshalJSON()
				if tg.c == c {
					wmu.Lock()
					_ = c.Write(ctx, websocket.MessageText, b)
					wmu.Unlock()
				} else {
					_ = tg.c.Write(ctx, websocket.MessageText, b)
				}
			}
		case *gn.ReqEnvelope:
			r.mu.Lock()
			if r.subs[c] != nil {
				r.subs[c][env.SubscriptionID] = env.Filters
			}
			var matches []gn.Event
			for _, ev := range r.events {
				ev := ev
				if env.Filters.Match(&ev) {
					matches = append(matches, ev)
				}
			}
			r.mu.Unlock()
			for _, ev := range matches {
				id := env.SubscriptionID
				send(&gn.EventEnvelope{SubscriptionID: &id, Event: ev})
			}
			eose := gn.EOSEEnvelope(env.SubscriptionID)
			send(&eose)
		case *gn.CloseEnvelope:
			r.mu.Lock()
			if r.subs[c] != nil {
				delete(r.subs[c], string(*env))
			}
			r.mu.Unlock()
		}
	}
}
