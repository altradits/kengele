package meshexit

import (
	"bytes"
	"context"
	"strings"
	"sync"
	"testing"
	"time"
)

// stubPublisher records what it was asked to publish. While offline it
// refuses everything, like unreachable relays would.
type stubPublisher struct {
	mu        sync.Mutex
	online    bool
	published [][]byte
}

func (p *stubPublisher) Publish(_ context.Context, datagram []byte) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if !p.online {
		return ErrNotAccepted
	}
	p.published = append(p.published, append([]byte(nil), datagram...))
	return nil
}

func (p *stubPublisher) setOnline(v bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.online = v
}

func (p *stubPublisher) count() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return len(p.published)
}

func feed(t *testing.T, sim *Simulator, datagram []byte, msgID uint32) {
	t.Helper()
	parts, err := Split(datagram, msgID)
	if err != nil {
		t.Fatal(err)
	}
	for _, p := range parts {
		sim.Inject("node-1", p)
	}
}

func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", what)
}

// syncBuffer is a bytes.Buffer safe to read while Run writes to it.
type syncBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (s *syncBuffer) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.Write(p)
}

func (s *syncBuffer) String() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.String()
}

func runExit(t *testing.T, sim *Simulator, pub *stubPublisher, cfg Config) (*Exit, *syncBuffer, context.CancelFunc) {
	t.Helper()
	ex, err := New(sim, pub, cfg)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	log := &syncBuffer{}
	done := make(chan error, 1)
	go func() { done <- ex.Run(ctx, log) }()
	t.Cleanup(func() {
		cancel()
		if err := <-done; err != nil {
			t.Errorf("Run: %v", err)
		}
	})
	return ex, log, cancel
}

func TestExitForwardsWhenOnline(t *testing.T) {
	sim := NewSimulator(SimConfig{})
	defer sim.Close()
	pub := &stubPublisher{online: true}
	ex, log, _ := runExit(t, sim, pub, Config{})

	raw := giftWrapDatagram(t)
	feed(t, sim, raw, 0x111111)

	waitFor(t, "forward", func() bool { return ex.Stats().Forwarded == 1 })
	if pub.count() != 1 {
		t.Fatalf("published=%d", pub.count())
	}
	if s := log.String(); !strings.Contains(s, "forwarded to Nostr") || !strings.Contains(s, "imetumwa kwa Nostr") {
		t.Fatalf("log lacks the bilingual forward line:\n%s", s)
	}
}

func TestExitQueuesWhenOfflineAndFlushes(t *testing.T) {
	sim := NewSimulator(SimConfig{})
	defer sim.Close()
	pub := &stubPublisher{}
	ex, log, _ := runExit(t, sim, pub, Config{Queue: NewMemoryQueue()})

	feed(t, sim, giftWrapDatagram(t), 0x222222)
	waitFor(t, "queue", func() bool { return ex.Stats().Queued == 1 })
	if pub.count() != 0 {
		t.Fatal("nothing may publish while offline")
	}
	if s := log.String(); !strings.Contains(s, "Nostr unreachable") || !strings.Contains(s, "haipatikani") {
		t.Fatalf("log lacks the bilingual queued line:\n%s", s)
	}

	pub.setOnline(true)
	pending, err := ex.Flush(context.Background())
	if err != nil || pending != 0 {
		t.Fatalf("flush: pending=%d err=%v", pending, err)
	}
	if pub.count() != 1 {
		t.Fatalf("published=%d after flush", pub.count())
	}
	if st := ex.Stats(); st.Forwarded != 1 {
		t.Fatalf("stats=%+v", st)
	}
	// Flushing an empty queue is a no-op.
	if pending, err := ex.Flush(context.Background()); err != nil || pending != 0 {
		t.Fatalf("second flush: pending=%d err=%v", pending, err)
	}
}

func TestExitRetriesQueueOnTick(t *testing.T) {
	sim := NewSimulator(SimConfig{})
	defer sim.Close()
	pub := &stubPublisher{}
	ex, _, _ := runExit(t, sim, pub, Config{RetryInterval: 20 * time.Millisecond})

	feed(t, sim, giftWrapDatagram(t), 0x333333)
	waitFor(t, "queue", func() bool { return ex.Stats().Queued == 1 })
	pub.setOnline(true)
	waitFor(t, "tick retry", func() bool { return pub.count() == 1 })
}

func TestExitDropsInvalid(t *testing.T) {
	sim := NewSimulator(SimConfig{})
	defer sim.Close()
	pub := &stubPublisher{online: true}
	ex, log, _ := runExit(t, sim, pub, Config{})

	sim.Inject("node-9", []byte("tourist chatter on the mesh"))
	feed(t, sim, []byte("valid fragments, not a gift wrap"), 0x444444)
	waitFor(t, "drop", func() bool { return ex.Stats().Dropped == 1 })
	if pub.count() != 0 {
		t.Fatal("an invalid datagram must never publish")
	}
	if s := log.String(); !strings.Contains(s, "ujumbe batili umetupwa") {
		t.Fatalf("log lacks the drop line:\n%s", s)
	}
	if st := ex.Stats(); st.Received != 1 || st.Forwarded != 0 || st.Queued != 0 {
		t.Fatalf("stats=%+v", st)
	}
}

func TestExitDropsBadFragments(t *testing.T) {
	sim := NewSimulator(SimConfig{})
	defer sim.Close()
	pub := &stubPublisher{online: true}
	ex, log, _ := runExit(t, sim, pub, Config{})

	sim.Inject("node-9", []byte("KG\x01bad")) // Kengele magic, malformed
	waitFor(t, "drop", func() bool { return ex.Stats().Dropped == 1 })
	if s := log.String(); !strings.Contains(s, "kipande kibaya") {
		t.Fatalf("log lacks the bad-fragment line:\n%s", s)
	}
}

func TestExitIgnoresDuplicates(t *testing.T) {
	sim := NewSimulator(SimConfig{})
	defer sim.Close()
	pub := &stubPublisher{online: true}
	ex, log, _ := runExit(t, sim, pub, Config{})

	raw := giftWrapDatagram(t)
	feed(t, sim, raw, 0x555555)
	feed(t, sim, raw, 0x555556) // the phone sends it again
	waitFor(t, "forward", func() bool { return ex.Stats().Forwarded == 1 })
	waitFor(t, "duplicate", func() bool { return strings.Contains(log.String(), "marudio") })
	if pub.count() != 1 {
		t.Fatalf("published=%d, want 1", pub.count())
	}
}

func TestExitFragmentedGiftWrapOverLossyMesh(t *testing.T) {
	// The simulator repeats every third packet, like a mesh would. The
	// gift wrap still arrives once and forwards once.
	sim := NewSimulator(SimConfig{DupeEvery: 3})
	defer sim.Close()
	pub := &stubPublisher{online: true}
	ex, _, _ := runExit(t, sim, pub, Config{})

	feed(t, sim, giftWrapDatagram(t), 0x666666)
	waitFor(t, "forward", func() bool { return ex.Stats().Forwarded == 1 })
	if pub.count() != 1 {
		t.Fatalf("published=%d, want 1", pub.count())
	}
}

func TestExitFragmentLossNeedsResend(t *testing.T) {
	// The exit has no radio-level retries: a lost fragment means the phone
	// must send the datagram again under a new id.
	sim := NewSimulator(SimConfig{LossEvery: 5})
	defer sim.Close()
	pub := &stubPublisher{online: true}
	ex, _, _ := runExit(t, sim, pub, Config{ReassembleTimeout: time.Minute})

	raw := giftWrapDatagram(t)
	parts, err := Split(raw, 0x777777)
	if err != nil {
		t.Fatal(err)
	}
	if len(parts) < 5 {
		t.Fatalf("test needs 5+ fragments, gift wrap gave %d", len(parts))
	}
	for _, p := range parts {
		sim.Inject("node-1", p)
	}
	select {
	case <-time.After(300 * time.Millisecond):
	}
	if st := ex.Stats(); st.Received != 0 || st.Forwarded != 0 {
		t.Fatalf("incomplete datagram must not forward: %+v", st)
	}
	// The resend, with loss switched off, completes and forwards.
	sim2 := NewSimulator(SimConfig{})
	defer sim2.Close()
	ex2, err := New(sim2, pub, Config{})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- ex2.Run(ctx, nil) }()
	defer func() {
		cancel()
		<-done
	}()
	feed(t, sim2, raw, 0x777778)
	waitFor(t, "resend forward", func() bool { return ex2.Stats().Forwarded == 1 })
}

func TestExitSendDatagram(t *testing.T) {
	sim := NewSimulator(SimConfig{})
	defer sim.Close()
	pub := &stubPublisher{}
	ex, err := New(sim, pub, Config{})
	if err != nil {
		t.Fatal(err)
	}
	raw := giftWrapDatagram(t)
	if err := ex.SendDatagram(context.Background(), raw, 0x888888); err != nil {
		t.Fatal(err)
	}
	re := NewReassembler(time.Minute, nil)
	var out []byte
	var done bool
	for _, p := range sim.Sent() {
		out, _, done, err = re.Add(p)
		if err != nil {
			t.Fatal(err)
		}
	}
	if !done || string(out) != string(raw) {
		t.Fatal("sent fragments do not reassemble")
	}
	if err := ex.SendDatagram(context.Background(), nil, 1); err == nil {
		t.Fatal("empty datagram accepted")
	}
}

func TestExitRunEndsOnClose(t *testing.T) {
	sim := NewSimulator(SimConfig{})
	pub := &stubPublisher{}
	ex, err := New(sim, pub, Config{})
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { done <- ex.Run(context.Background(), nil) }()
	if err := sim.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("Run: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Run did not end after Close")
	}
}

func TestNewRejects(t *testing.T) {
	sim := NewSimulator(SimConfig{})
	defer sim.Close()
	if _, err := New(nil, &stubPublisher{}, Config{}); err == nil {
		t.Error("nil mesh accepted")
	}
	if _, err := New(sim, nil, Config{}); err == nil {
		t.Error("nil publisher accepted")
	}
}
