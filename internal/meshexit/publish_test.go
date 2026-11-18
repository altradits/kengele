package meshexit

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"sync"
	"testing"
	"time"

	"github.com/altradits/kengele/internal/message"
	"github.com/altradits/kengele/internal/transport/nostr"
	"github.com/btcsuite/btcd/btcec/v2"
	"github.com/coder/websocket"
	gn "github.com/nbd-wtf/go-nostr"
)

// Made-up test keys. Never use these for anything real.
const (
	exitActivistHex = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"
	exitGuardHex    = "b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2"
)

func exitKey(t *testing.T, h string) *btcec.PrivateKey {
	t.Helper()
	b, err := hex.DecodeString(h)
	if err != nil {
		t.Fatal(err)
	}
	k, _ := btcec.PrivKeyFromBytes(b)
	return k
}

var exitNow = time.Date(2026, 3, 2, 9, 0, 0, 0, time.UTC)

// giftWrapDatagram builds what the activist's phone puts on the air: a
// Kengele event, sealed and gift-wrapped, as JSON.
func giftWrapDatagram(t *testing.T) []byte {
	t.Helper()
	activist := exitKey(t, exitActivistHex)
	guard := exitKey(t, exitGuardHex)
	wire, err := message.Seal(message.Event{
		Kind:      message.KindCheckIn,
		CreatedAt: exitNow,
		Seq:       3,
		Body:      []byte("soko"),
	}, activist, message.PublicKey(guard))
	if err != nil {
		t.Fatal(err)
	}
	ev, err := nostr.Wrap(wire, activist, message.PublicKey(guard), exitNow, nil)
	if err != nil {
		t.Fatal(err)
	}
	raw, err := json.Marshal(ev)
	if err != nil {
		t.Fatal(err)
	}
	if len(raw) > MaxDatagram {
		t.Fatalf("gift wrap of %d bytes exceeds the datagram limit", len(raw))
	}
	return raw
}

// fakeRelay is a minimal Nostr relay for tests: it answers EVENT with OK,
// optionally rejecting everything, and counts what it stored.
type fakeRelay struct {
	t      *testing.T
	url    string
	srv    *http.Server
	mu     sync.Mutex
	stored int
	reject bool
}

func newFakeRelay(t *testing.T) *fakeRelay {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	r := &fakeRelay{t: t, url: "ws://" + ln.Addr().String()}
	r.srv = &http.Server{Handler: http.HandlerFunc(r.handle), ReadHeaderTimeout: 5 * time.Second}
	go func() { _ = r.srv.Serve(ln) }()
	t.Cleanup(func() { _ = r.srv.Close() })
	return r
}

func (r *fakeRelay) handle(w http.ResponseWriter, req *http.Request) {
	c, err := websocket.Accept(w, req, nil)
	if err != nil {
		return
	}
	defer c.CloseNow()
	c.SetReadLimit(1 << 20)
	ctx := context.Background()
	for {
		_, data, err := c.Read(ctx)
		if err != nil {
			return
		}
		env, ok := gn.ParseMessage(string(data)).(*gn.EventEnvelope)
		if !ok {
			continue
		}
		r.mu.Lock()
		reject := r.reject
		r.mu.Unlock()
		ev := env.Event
		okSig, _ := ev.CheckSignature()
		accept := okSig && ev.ID == ev.GetID() && !reject
		if accept {
			r.mu.Lock()
			r.stored++
			r.mu.Unlock()
		}
		b, _ := (&gn.OKEnvelope{EventID: ev.ID, OK: accept}).MarshalJSON()
		if err := c.Write(ctx, websocket.MessageText, b); err != nil {
			return
		}
	}
}

func (r *fakeRelay) count() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.stored
}

func TestValidateDatagram(t *testing.T) {
	raw := giftWrapDatagram(t)
	if _, err := ValidateDatagram(raw); err != nil {
		t.Fatalf("valid gift wrap: %v", err)
	}
	cases := map[string][]byte{
		"empty":   nil,
		"garbage": []byte("this is not json"),
		"oversize": func() []byte {
			b := make([]byte, MaxDatagram+1)
			b[0] = '{'
			return b
		}(),
		"wrong kind": func() []byte {
			var ev gn.Event
			if err := json.Unmarshal(raw, &ev); err != nil {
				t.Fatal(err)
			}
			ev.Kind = gn.KindTextNote
			out, _ := json.Marshal(ev)
			return out
		}(),
		"tampered": func() []byte {
			out := append([]byte(nil), raw...)
			out[len(out)/2] ^= 0x01
			return out
		}(),
		"bad signature": func() []byte {
			var ev gn.Event
			if err := json.Unmarshal(raw, &ev); err != nil {
				t.Fatal(err)
			}
			last := ev.Sig[len(ev.Sig)-1]
			if last == '0' {
				last = '1'
			} else {
				last = '0'
			}
			ev.Sig = ev.Sig[:len(ev.Sig)-1] + string(last)
			out, _ := json.Marshal(ev)
			return out
		}(),
		"no recipient": func() []byte {
			// A well-signed kind 1059 with no p tag is still refused:
			// the exit only forwards addressed gift wraps.
			ev := gn.Event{
				Kind:      gn.KindGiftWrap,
				CreatedAt: gn.Timestamp(exitNow.Unix()),
				Tags:      gn.Tags{},
				Content:   "nothing",
			}
			if err := ev.Sign(exitActivistHex); err != nil {
				t.Fatal(err)
			}
			out, _ := json.Marshal(ev)
			return out
		}(),
	}
	for name, raw := range cases {
		t.Run(name, func(t *testing.T) {
			if _, err := ValidateDatagram(raw); !errors.Is(err, ErrNotGiftWrap) {
				t.Fatalf("got %v, want ErrNotGiftWrap", err)
			}
		})
	}
}

func TestRelayPublisherOnline(t *testing.T) {
	r := newFakeRelay(t)
	p := &RelayPublisher{Relays: []string{r.url}, Timeout: 5 * time.Second}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := p.Publish(ctx, giftWrapDatagram(t)); err != nil {
		t.Fatal(err)
	}
	if r.count() != 1 {
		t.Fatalf("relay stored %d events", r.count())
	}
}

func TestRelayPublisherFailures(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	raw := giftWrapDatagram(t)

	p := &RelayPublisher{Relays: nil}
	if err := p.Publish(ctx, raw); !errors.Is(err, ErrNoRelays) {
		t.Fatalf("no relays: %v", err)
	}
	p = &RelayPublisher{Relays: []string{"ws://127.0.0.1:1"}, Timeout: 200 * time.Millisecond}
	if err := p.Publish(ctx, raw); !errors.Is(err, ErrNotAccepted) {
		t.Fatalf("dead relay: %v", err)
	}
	r := newFakeRelay(t)
	r.reject = true
	p = &RelayPublisher{Relays: []string{r.url}, Timeout: 5 * time.Second}
	if err := p.Publish(ctx, raw); !errors.Is(err, ErrNotAccepted) {
		t.Fatalf("rejecting relay: %v", err)
	}
	if r.count() != 0 {
		t.Fatal("rejected event must not be stored")
	}
	// One good relay of two is enough for MinAccepted 1 but not 2.
	r.reject = false
	p = &RelayPublisher{Relays: []string{r.url, "ws://127.0.0.1:1"}, MinAccepted: 2, Timeout: time.Second}
	if err := p.Publish(ctx, raw); !errors.Is(err, ErrNotAccepted) {
		t.Fatalf("min-accepted 2: %v", err)
	}
	p.MinAccepted = 1
	if err := p.Publish(ctx, raw); err != nil {
		t.Fatalf("min-accepted 1: %v", err)
	}
	// Invalid datagrams never reach the network.
	if err := p.Publish(ctx, []byte("nope")); !errors.Is(err, ErrNotGiftWrap) {
		t.Fatalf("invalid datagram: %v", err)
	}
}
