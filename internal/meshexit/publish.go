package meshexit

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/coder/websocket"
	gn "github.com/nbd-wtf/go-nostr"
)

var (
	// ErrNoRelays means there is nowhere to publish.
	ErrNoRelays = errors.New("meshexit: no relays configured")
	// ErrNotAccepted means fewer than MinAccepted relays acknowledged the
	// gift wrap. The message is not lost: the exit queues it.
	ErrNotAccepted = errors.New("meshexit: too few relays accepted the message")
	// ErrNotGiftWrap means a datagram is not a signed kind-1059 gift wrap.
	// The exit only forwards gift wraps, never anything else.
	ErrNotGiftWrap = errors.New("meshexit: datagram is not a signed gift wrap")
)

// Publisher forwards one datagram to Nostr.
type Publisher interface {
	Publish(ctx context.Context, datagram []byte) error
}

// ValidateDatagram checks that datagram is a signed Nostr gift wrap (kind
// 1059) addressed to someone: valid JSON, a matching id, a valid signature
// and a recipient tag. It never decrypts anything: the exit has no keys.
// Anything failing a check is dropped silently by the caller.
func ValidateDatagram(datagram []byte) (gn.Event, error) {
	var ev gn.Event
	if len(datagram) == 0 || len(datagram) > MaxDatagram {
		return ev, ErrNotGiftWrap
	}
	if err := json.Unmarshal(datagram, &ev); err != nil {
		return ev, ErrNotGiftWrap
	}
	if ev.Kind != gn.KindGiftWrap {
		return ev, ErrNotGiftWrap
	}
	if ev.ID == "" || ev.ID != ev.GetID() {
		return ev, ErrNotGiftWrap
	}
	ok, _ := ev.CheckSignature()
	if !ok {
		return ev, ErrNotGiftWrap
	}
	if !hasPTag(ev) {
		return ev, ErrNotGiftWrap
	}
	return ev, nil
}

func hasPTag(ev gn.Event) bool {
	for _, t := range ev.Tags {
		if len(t) >= 2 && t[0] == "p" && t[1] != "" {
			return true
		}
	}
	return false
}

// RelayPublisher publishes datagrams to Nostr relays. It holds no keys: the
// activist's phone gift-wrapped the message before it reached the mesh, so
// publishing only replays bytes the phone already signed.
type RelayPublisher struct {
	// Relays the organisation chose.
	Relays []string
	// MinAccepted is how many relays must acknowledge a gift wrap for
	// Publish to count it as sent. Default 1.
	MinAccepted int
	// Timeout bounds one relay's dial and publish. Default 10s.
	Timeout time.Duration
}

func (p *RelayPublisher) minAccepted() int {
	if p.MinAccepted <= 0 {
		return 1
	}
	return p.MinAccepted
}

func (p *RelayPublisher) timeout() time.Duration {
	if p.Timeout <= 0 {
		return 10 * time.Second
	}
	return p.Timeout
}

// Publish validates datagram and sends the gift wrap to every relay in
// parallel. Only an explicit OK true counts as accepted.
func (p *RelayPublisher) Publish(ctx context.Context, datagram []byte) error {
	ev, err := ValidateDatagram(datagram)
	if err != nil {
		return err
	}
	if len(p.Relays) == 0 {
		return ErrNoRelays
	}
	var wg sync.WaitGroup
	accepted := make([]bool, len(p.Relays))
	for i, u := range p.Relays {
		wg.Add(1)
		go func(i int, u string) {
			defer wg.Done()
			accepted[i] = publishOne(ctx, u, ev, p.timeout())
		}(i, u)
	}
	wg.Wait()
	n := 0
	for _, ok := range accepted {
		if ok {
			n++
		}
	}
	if n < p.minAccepted() {
		return fmt.Errorf("%w: %d of %d relays", ErrNotAccepted, n, len(p.Relays))
	}
	return nil
}

// publishOne sends ev to one relay and reports whether it answered OK true.
// Anything else (refused connection, timeout, OK false) is just false: the
// caller queues the message and tries again later.
func publishOne(ctx context.Context, url string, ev gn.Event, timeout time.Duration) bool {
	pctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	ws, _, err := websocket.Dial(pctx, url, nil)
	if err != nil {
		return false
	}
	defer ws.CloseNow()
	ws.SetReadLimit(1 << 20)
	b, err := (&gn.EventEnvelope{Event: ev}).MarshalJSON()
	if err != nil {
		return false
	}
	if err := ws.Write(pctx, websocket.MessageText, b); err != nil {
		return false
	}
	for {
		_, data, err := ws.Read(pctx)
		if err != nil {
			return false
		}
		okEnv, ok := gn.ParseMessage(string(data)).(*gn.OKEnvelope)
		if !ok || okEnv.EventID != ev.ID {
			continue
		}
		return okEnv.OK
	}
}
