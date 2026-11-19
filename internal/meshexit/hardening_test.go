package meshexit

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	gn "github.com/nbd-wtf/go-nostr"
)

// --- Gift-wrap validation ---

func signWrap(t *testing.T, tags gn.Tags, content string) []byte {
	t.Helper()
	ev := gn.Event{
		Kind:      gn.KindGiftWrap,
		CreatedAt: gn.Timestamp(exitNow.Unix()),
		Tags:      tags,
		Content:   content,
	}
	if err := ev.Sign(exitActivistHex); err != nil {
		t.Fatal(err)
	}
	out, err := json.Marshal(ev)
	if err != nil {
		t.Fatal(err)
	}
	return out
}

func TestValidateDatagramStrict(t *testing.T) {
	if _, err := ValidateDatagram(giftWrapDatagram(t)); err != nil {
		t.Fatalf("valid: %v", err)
	}
	validP := strings.Repeat("c3", 32) // 64 hex chars, 32 bytes
	cases := map[string][]byte{
		"empty content": signWrap(t, gn.Tags{{"p", validP}}, ""),
		"p short":       signWrap(t, gn.Tags{{"p", "abc"}}, "x"),
		"p non-hex":     signWrap(t, gn.Tags{{"p", strings.Repeat("zz", 32)}}, "x"),
		"p empty":       signWrap(t, gn.Tags{{"p", ""}}, "x"),
		"p single":      signWrap(t, gn.Tags{{"p"}}, "x"),
		"no tags":       signWrap(t, gn.Tags{}, "x"),
		"wrong tag":     signWrap(t, gn.Tags{{"e", validP}}, "x"),
	}
	for name, raw := range cases {
		t.Run(name, func(t *testing.T) {
			if _, err := ValidateDatagram(raw); !errors.Is(err, ErrNotGiftWrap) {
				t.Fatalf("got %v, want ErrNotGiftWrap", err)
			}
		})
	}
	// One valid p tag among noise still counts as addressed.
	mixed := signWrap(t, gn.Tags{{"p", "bad"}, {"p", validP}}, "x")
	if _, err := ValidateDatagram(mixed); err != nil {
		t.Fatalf("mixed tags: %v", err)
	}
}

// --- OK quorum ---

func TestRelayPublisherQuorumTooHighSkipsNetwork(t *testing.T) {
	r := newFakeRelay(t)
	p := &RelayPublisher{Relays: []string{r.url}, MinAccepted: 2, Timeout: 5 * time.Second}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	err := p.Publish(ctx, giftWrapDatagram(t))
	if !errors.Is(err, ErrNotAccepted) {
		t.Fatalf("got %v, want ErrNotAccepted", err)
	}
	if !strings.Contains(err.Error(), "need 2 but only 1") {
		t.Fatalf("opaque quorum error: %v", err)
	}
	if r.count() != 0 {
		t.Fatal("impossible quorum must not dial any relay")
	}
}

func TestRelayPublisherInvalidNeverDials(t *testing.T) {
	r := newFakeRelay(t)
	p := &RelayPublisher{Relays: []string{r.url}, Timeout: 5 * time.Second}
	if err := p.Publish(context.Background(), []byte("nope")); !errors.Is(err, ErrNotGiftWrap) {
		t.Fatalf("got %v", err)
	}
	if r.count() != 0 {
		t.Fatal("invalid datagram reached the network")
	}
}

func TestRelayPublisherSlowRelayTimeout(t *testing.T) {
	fast := newFakeRelay(t)
	slow := newFakeRelay(t)
	slow.mu.Lock()
	slow.delay = 2 * time.Second
	slow.mu.Unlock()
	// MinAccepted 1: the fast relay carries it while the slow one times out.
	p := &RelayPublisher{Relays: []string{fast.url, slow.url}, MinAccepted: 1, Timeout: 300 * time.Millisecond}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := p.Publish(ctx, giftWrapDatagram(t)); err != nil {
		t.Fatalf("with one fast relay: %v", err)
	}
	// MinAccepted 2: the slow relay's timeout fails the quorum.
	p.MinAccepted = 2
	if err := p.Publish(ctx, giftWrapDatagram(t)); !errors.Is(err, ErrNotAccepted) {
		t.Fatalf("slow quorum: %v", err)
	}
}

// scriptedRelay answers one EVENT with a fixed script of raw messages.
func scriptedRelay(t *testing.T, script []string) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		if strings.Contains(err.Error(), "operation not permitted") {
			t.Skipf("cannot bind: %v", err)
		}
		t.Fatal(err)
	}
	srv := &http.Server{
		Handler: http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
			c, err := websocket.Accept(w, req, nil)
			if err != nil {
				return
			}
			defer c.CloseNow()
			ctx := context.Background()
			_, data, err := c.Read(ctx)
			if err != nil {
				return
			}
			env, ok := gn.ParseMessage(string(data)).(*gn.EventEnvelope)
			if !ok {
				return
			}
			for _, s := range script {
				msg := strings.ReplaceAll(s, "$ID", env.Event.ID)
				if err := c.Write(ctx, websocket.MessageText, []byte(msg)); err != nil {
					return
				}
			}
		}),
		ReadHeaderTimeout: 5 * time.Second,
	}
	go func() { _ = srv.Serve(ln) }()
	t.Cleanup(func() { _ = srv.Close() })
	return "ws://" + ln.Addr().String()
}

func TestRelayPublisherNoiseCases(t *testing.T) {
	raw := giftWrapDatagram(t)
	var ev gn.Event
	if err := json.Unmarshal(raw, &ev); err != nil {
		t.Fatal(err)
	}
	cases := map[string]struct {
		script []string
		accept bool
	}{
		"notice then ok": {
			script: []string{`["NOTICE","hi"]`, `["OK","$ID",true,""]`},
			accept: true,
		},
		"wrong id then ok": {
			script: []string{`["OK","` + strings.Repeat("0", 64) + `",true,""]`, `["OK","$ID",true,""]`},
			accept: true,
		},
		"duplicate true counts": {
			script: []string{`["OK","$ID",true,"duplicate:"]`},
			accept: true,
		},
		"false never counts": {
			script: []string{`["OK","$ID",false,"duplicate:"]`},
			accept: false,
		},
		"eose ignored then ok": {
			script: []string{`["EOSE","sub"]`, `["OK","$ID",true,""]`},
			accept: true,
		},
	}
	for name, tc := range cases {
		t.Run(name, func(t *testing.T) {
			url := scriptedRelay(t, tc.script)
			p := &RelayPublisher{Relays: []string{url}, Timeout: 5 * time.Second}
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			err := p.Publish(ctx, raw)
			if tc.accept && err != nil {
				t.Fatalf("rejected: %v", err)
			}
			if !tc.accept && !errors.Is(err, ErrNotAccepted) {
				t.Fatalf("accepted, want ErrNotAccepted (err=%v)", err)
			}
		})
	}
}

func TestRelayPublisherCloseWithoutOK(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		if strings.Contains(err.Error(), "operation not permitted") {
			t.Skipf("cannot bind: %v", err)
		}
		t.Fatal(err)
	}
	srv := &http.Server{
		Handler: http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
			c, err := websocket.Accept(w, req, nil)
			if err != nil {
				return
			}
			// Read the EVENT, then hang up without any OK.
			_, _, _ = c.Read(context.Background())
			c.CloseNow()
		}),
		ReadHeaderTimeout: 5 * time.Second,
	}
	go func() { _ = srv.Serve(ln) }()
	t.Cleanup(func() { _ = srv.Close() })
	p := &RelayPublisher{Relays: []string{"ws://" + ln.Addr().String()}, Timeout: 2 * time.Second}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := p.Publish(ctx, giftWrapDatagram(t)); !errors.Is(err, ErrNotAccepted) {
		t.Fatalf("hangup: %v", err)
	}
}

func TestRelayPublisherCancelledContext(t *testing.T) {
	p := &RelayPublisher{Relays: []string{"ws://127.0.0.1:1"}, Timeout: 5 * time.Second}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := p.Publish(ctx, giftWrapDatagram(t)); !errors.Is(err, ErrNotAccepted) {
		t.Fatalf("cancelled: %v", err)
	}
}

// --- Queue durability ---

func TestFileQueueMakesParentDirs(t *testing.T) {
	path := filepath.Join(t.TempDir(), "a", "b", "queue.json")
	q, err := OpenFileQueue(path)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UTC().Truncate(time.Second)
	if err := q.Put(testEntry("a", now)); err != nil {
		t.Fatal(err)
	}
	st, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if st.Mode().Perm() != 0o600 {
		t.Fatalf("mode=%o", st.Mode().Perm())
	}
	again, err := OpenFileQueue(path)
	if err != nil {
		t.Fatal(err)
	}
	if got := listIDs(t, again); fmt.Sprint(got) != "[a]" {
		t.Fatalf("after reopen: %v", got)
	}
}

func TestFileQueueTightensPerms(t *testing.T) {
	path := filepath.Join(t.TempDir(), "queue.json")
	if err := os.WriteFile(path, []byte("[]"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := OpenFileQueue(path); err != nil {
		t.Fatal(err)
	}
	st, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if st.Mode().Perm() != 0o600 {
		t.Fatalf("mode=%o, want 600", st.Mode().Perm())
	}
}

func TestFileQueueNoTempLeft(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "queue.json")
	q, err := OpenFileQueue(path)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UTC()
	if err := q.Put(testEntry("a", now)); err != nil {
		t.Fatal(err)
	}
	if err := q.Put(testEntry("b", now)); err != nil {
		t.Fatal(err)
	}
	if err := q.Remove("a"); err != nil {
		t.Fatal(err)
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	for _, e := range entries {
		if strings.HasPrefix(e.Name(), ".exit-queue-") {
			t.Fatalf("temp file left: %s", e.Name())
		}
	}
}

func TestFileQueueRollbackOnSaveFailure(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "queue.json")
	q, err := OpenFileQueue(path)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UTC()
	if err := q.Put(testEntry("a", now)); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(dir, 0o500); err != nil {
		t.Fatal(err)
	}
	defer func() { _ = os.Chmod(dir, 0o700) }()
	if err := q.Put(testEntry("b", now)); err == nil {
		t.Fatal("Put into a read-only dir accepted")
	}
	if got := listIDs(t, q); fmt.Sprint(got) != "[a]" {
		t.Fatalf("failed Put leaked into memory: %v", got)
	}
	if err := q.Remove("a"); err == nil {
		t.Fatal("Remove with a read-only dir accepted")
	}
	if got := listIDs(t, q); fmt.Sprint(got) != "[a]" {
		t.Fatalf("failed Remove leaked into memory: %v", got)
	}
}

// --- Exit edge cases ---

func TestExitFlushDropsInvalid(t *testing.T) {
	sim := NewSimulator(SimConfig{})
	defer sim.Close()
	q := NewMemoryQueue()
	now := time.Now().UTC()
	if err := q.Put(Entry{ID: "bad", Data: []byte("not a gift wrap"), From: "n", ReceivedAt: now}); err != nil {
		t.Fatal(err)
	}
	raw := giftWrapDatagram(t)
	if err := q.Put(Entry{ID: "good", Data: raw, From: "n", ReceivedAt: now}); err != nil {
		t.Fatal(err)
	}
	// No relays: the good entry stays queued, the bad one is dropped.
	pub := &RelayPublisher{Relays: nil}
	ex, err := New(sim, pub, Config{Queue: q})
	if err != nil {
		t.Fatal(err)
	}
	pending, err := ex.Flush(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if pending != 1 {
		t.Fatalf("pending=%d, want 1", pending)
	}
	if got := listIDs(t, q); fmt.Sprint(got) != "[good]" {
		t.Fatalf("queue=%v", got)
	}
	if st := ex.Stats(); st.Dropped != 1 {
		t.Fatalf("stats=%+v", st)
	}
}

type disagreePublisher struct{}

func (disagreePublisher) Publish(_ context.Context, _ []byte) error { return ErrNotGiftWrap }

func TestExitPublisherDisagreesDrops(t *testing.T) {
	sim := NewSimulator(SimConfig{})
	defer sim.Close()
	ex, err := New(sim, disagreePublisher{}, Config{})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	log := &syncBuffer{}
	done := make(chan error, 1)
	go func() { done <- ex.Run(ctx, log) }()
	defer func() {
		cancel()
		<-done
	}()
	feed(t, sim, giftWrapDatagram(t), 0xD15A6E)
	waitFor(t, "drop", func() bool { return ex.Stats().Dropped == 1 })
	if st := ex.Stats(); st.Queued != 0 {
		t.Fatalf("disputed datagram queued: %+v", st)
	}
}

func TestExitFlushAfterSuccessLogs(t *testing.T) {
	sim := NewSimulator(SimConfig{})
	defer sim.Close()
	pub := &stubPublisher{}
	ex, log, _ := runExit(t, sim, pub, Config{})
	feed(t, sim, giftWrapDatagram(t), 0xA11CE1)
	waitFor(t, "queue", func() bool { return ex.Stats().Queued == 1 })
	pub.setOnline(true)
	// A new arrival forwards and drags the queued one with it.
	feed(t, sim, giftWrapDatagram(t), 0xA11CE2)
	waitFor(t, "both forwarded", func() bool { return ex.Stats().Forwarded == 2 })
	waitFor(t, "flush line", func() bool { return strings.Contains(log.String(), "kutoka foleni") })
}

func TestExitQueueFullDrops(t *testing.T) {
	q := NewMemoryQueue()
	now := time.Now().UTC()
	for i := 0; i < MaxQueuedEntries; i++ {
		if err := q.Put(testEntry(fmt.Sprintf("fill-%04d", i), now)); err != nil {
			t.Fatal(err)
		}
	}
	sim := NewSimulator(SimConfig{})
	defer sim.Close()
	pub := &stubPublisher{} // offline: everything queues
	ex, log, _ := runExit(t, sim, pub, Config{Queue: q})
	feed(t, sim, giftWrapDatagram(t), 0xF01111)
	waitFor(t, "drop on full", func() bool { return ex.Stats().Dropped == 1 })
	if s := log.String(); !strings.Contains(s, "foleni imeshindikana") {
		t.Fatalf("log lacks the queue-failed line:\n%s", s)
	}
}

func TestSimulatorInjectNeverBlocks(t *testing.T) {
	s := NewSimulator(SimConfig{})
	// Flood without a reader: Inject drops past the 256 buffer instead
	// of blocking, so this returns at once and Close never wedges.
	finished := make(chan struct{})
	go func() {
		defer close(finished)
		for i := 0; i < 600; i++ {
			s.Inject("flood", []byte{byte(i)})
		}
	}()
	select {
	case <-finished:
	case <-time.After(5 * time.Second):
		t.Fatal("Inject blocked on a full buffer")
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	s.Inject("flood", []byte{1}) // after Close: ignored, no panic
	count := 0
	for range s.Receive() {
		count++
	}
	if count != 256 {
		t.Fatalf("buffered=%d, want 256", count)
	}
}
