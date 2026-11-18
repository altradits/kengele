package main

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/altradits/kengele/internal/message"
	"github.com/altradits/kengele/internal/schedule"
	"github.com/altradits/kengele/internal/transport"
)

const planJSON = `{"activists": [{"label": "A", "pubkey": "0101010101010101010101010101010101010101010101010101010101010101",
 "start": "2026-01-15T10:00:00Z", "end": "2026-01-15T18:00:00Z", "interval": "90m", "grace": "15m", "reminder": "10m"}]}`

const pass = "correct horse battery"

func TestCLI(t *testing.T) {
	dir := t.TempDir()
	in := filepath.Join(dir, "plan.json")
	dst := filepath.Join(dir, "plan.kgp")
	if err := os.WriteFile(in, []byte(planJSON), 0o600); err != nil {
		t.Fatal(err)
	}
	var out bytes.Buffer
	if err := cli([]string{"version"}, &out, ""); err != nil || !strings.Contains(out.String(), version) {
		t.Fatalf("version: %v %q", err, out.String())
	}
	if err := cli([]string{"seal-plan", "-in", in, "-out", dst}, &out, ""); err == nil {
		t.Error("sealed without a passphrase")
	}
	if err := cli([]string{"seal-plan", "-in", in, "-out", dst}, &out, pass); err != nil {
		t.Fatal(err)
	}
	if st, err := os.Stat(dst); err != nil || st.Mode().Perm() != 0o600 {
		t.Fatalf("plan file: %v", err)
	}
	if err := cli([]string{"run", "-plan", dst}, &out, "wrong passphrase!"); err == nil {
		t.Error("ran with the wrong passphrase")
	}
	if err := cli([]string{"run", "-plan", dst, "-down", "mesh"}, &out, pass); err == nil {
		t.Error("ran with an unsupported transport in -down")
	}
	if err := cli([]string{"nope"}, &out, ""); err == nil {
		t.Error("unknown command accepted")
	}
}

type fakeTransport struct {
	mu  sync.Mutex
	evs []delivery
}

func (f *fakeTransport) Receive() []delivery {
	f.mu.Lock()
	defer f.mu.Unlock()
	e := f.evs
	f.evs = nil
	return e
}

func (f *fakeTransport) send(via transport.Kind, e message.Event) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.evs = append(f.evs, delivery{via: via, ev: e})
}

func TestWatchLoop(t *testing.T) {
	plan, err := schedule.ParsePlan([]byte(planJSON))
	if err != nil {
		t.Fatal(err)
	}
	start := time.Date(2026, 1, 15, 10, 0, 0, 0, time.UTC)
	now := func() time.Time { return start }
	ticks := make(chan time.Time)
	var out bytes.Buffer
	done := make(chan error)
	k, _ := plan.Activists[0].Key()
	tr := &fakeTransport{}
	go func() {
		done <- watch(context.Background(), plan, ticks, now, staticProbe{}, tr, &out)
	}()
	for _, m := range []int{30, 60, 106} {
		clock := start.Add(time.Duration(m) * time.Minute)
		if m == 60 {
			tr.send(transport.Nostr, message.Event{Kind: message.KindCheckIn, CreatedAt: clock.Add(-time.Minute), Seq: 1, Signer: k})
		}
		ticks <- clock
	}
	close(ticks)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	s := out.String()
	for _, want := range []string{"started", "not for real use"} {
		if !strings.Contains(s, want) {
			t.Errorf("output lacks %q:\n%s", want, s)
		}
	}
	if strings.Contains(s, "missed") {
		t.Errorf("check-in at +59 should cover +106:\n%s", s)
	}
}

// With Nostr down, an SMS activist checking in by SMS stays covered while a
// Nostr-only activist pauses (#28).
func TestWatchLoopNostrDown(t *testing.T) {
	js := `{"version": 1, "activists": [
 {"label": "SMS", "pubkey": "0101010101010101010101010101010101010101010101010101010101010101",
  "start": "2026-01-15T10:00:00Z", "end": "2026-01-15T18:00:00Z", "interval": "90m", "grace": "15m", "reminder": "10m",
  "transports": ["nostr", "sms"]},
 {"label": "NOSTRONLY", "pubkey": "0202020202020202020202020202020202020202020202020202020202020202",
  "start": "2026-01-15T10:00:00Z", "end": "2026-01-15T18:00:00Z", "interval": "90m", "grace": "15m", "reminder": "10m"}]}`
	plan, err := schedule.ParsePlan([]byte(js))
	if err != nil {
		t.Fatal(err)
	}
	start := time.Date(2026, 1, 15, 10, 0, 0, 0, time.UTC)
	ticks := make(chan time.Time)
	var out bytes.Buffer
	done := make(chan error)
	k, _ := plan.Activists[0].Key()
	tr := &fakeTransport{}
	go func() {
		done <- watch(context.Background(), plan, ticks, func() time.Time { return start }, staticProbe{transport.Nostr: true}, tr, &out)
	}()
	for _, m := range []int{30, 60, 106} {
		clock := start.Add(time.Duration(m) * time.Minute)
		if m == 60 {
			tr.send(transport.SMS, message.Event{Kind: message.KindCheckIn, CreatedAt: clock.Add(-time.Minute), Seq: 1, Signer: k})
		}
		ticks <- clock
	}
	close(ticks)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	s := out.String()
	if !strings.Contains(s, "paused") || !strings.Contains(s, "NOSTRONLY") {
		t.Errorf("Nostr-only activist should pause:\n%s", s)
	}
	if strings.Contains(s, "missed") {
		t.Errorf("nobody should miss: SMS check-in covers one, the other is paused:\n%s", s)
	}
}
