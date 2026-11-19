package meshexit

import (
	"context"
	"errors"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestBackoffForAttempt(t *testing.T) {
	def := ReconnectConfig{}
	if got := BackoffForAttempt(0, def); got != 500*time.Millisecond {
		t.Fatalf("attempt 0: %v", got)
	}
	if got := BackoffForAttempt(1, def); got != time.Second {
		t.Fatalf("attempt 1: %v", got)
	}
	if got := BackoffForAttempt(2, def); got != 2*time.Second {
		t.Fatalf("attempt 2: %v", got)
	}
	if got := BackoffForAttempt(20, def); got != 30*time.Second {
		t.Fatalf("capped: %v", got)
	}
	custom := ReconnectConfig{Initial: 100 * time.Millisecond, Max: 250 * time.Millisecond, Factor: 3}
	if got := BackoffForAttempt(0, custom); got != 100*time.Millisecond {
		t.Fatalf("custom 0: %v", got)
	}
	if got := BackoffForAttempt(1, custom); got != 250*time.Millisecond {
		t.Fatalf("custom capped: %v", got)
	}
}

type logCapture struct {
	mu   sync.Mutex
	msgs []string
}

func (l *logCapture) fn() func(string) {
	return func(s string) {
		l.mu.Lock()
		defer l.mu.Unlock()
		l.msgs = append(l.msgs, s)
	}
}

func (l *logCapture) joined() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return strings.Join(l.msgs, "\n")
}

func TestReconnectingMeshSurvivesDrop(t *testing.T) {
	first := NewSimulator(SimConfig{})
	second := NewSimulator(SimConfig{})
	logs := &logCapture{}
	calls := 0
	redial := func() (Mesh, error) {
		calls++
		return second, nil
	}
	m := NewReconnectingMesh(first, redial, ReconnectConfig{
		Initial: 5 * time.Millisecond,
		Max:     10 * time.Millisecond,
		Log:     logs.fn(),
	})
	defer m.Close()

	first.Inject("node-1", []byte("before"))
	if got := recv(t, m.Receive()); string(got.Payload) != "before" {
		t.Fatalf("before drop: %q", got.Payload)
	}
	if err := first.Close(); err != nil {
		t.Fatal(err)
	}
	waitFor(t, "redial", func() bool {
		return strings.Contains(logs.joined(), "umerudi")
	})
	second.Inject("node-2", []byte("after"))
	if got := recv(t, m.Receive()); string(got.Payload) != "after" {
		t.Fatalf("after drop: %+v", got)
	}
	if calls != 1 {
		t.Fatalf("redials=%d, want 1", calls)
	}
	// Both lines must be bilingual.
	joined := logs.joined()
	if !strings.Contains(joined, "mesh link dropped") || !strings.Contains(joined, "umekatika") {
		t.Fatalf("drop line not bilingual:\n%s", joined)
	}
	if !strings.Contains(joined, "mesh link restored") || !strings.Contains(joined, "umerudi") {
		t.Fatalf("restore line not bilingual:\n%s", joined)
	}
}

func TestReconnectingMeshSendDuringOutage(t *testing.T) {
	first := NewSimulator(SimConfig{})
	second := NewSimulator(SimConfig{})
	tries := 0
	redial := func() (Mesh, error) {
		tries++
		if tries < 3 {
			return nil, errors.New("gateway still down")
		}
		return second, nil
	}
	m := NewReconnectingMesh(first, redial, ReconnectConfig{Initial: 20 * time.Millisecond, Max: 20 * time.Millisecond})
	defer m.Close()

	if err := first.Close(); err != nil {
		t.Fatal(err)
	}
	// The link is down and the first redials fail: Send must say so
	// instead of blocking or silently dropping.
	waitFor(t, "reconnecting send", func() bool {
		return errors.Is(m.Send(context.Background(), []byte("x")), ErrMeshReconnecting)
	})
	// Eventually the redial succeeds and Send forwards again.
	waitFor(t, "send after restore", func() bool {
		return m.Send(context.Background(), []byte("ok")) == nil
	})
	if sent := second.Sent(); len(sent) != 1 || string(sent[0]) != "ok" {
		t.Fatalf("sent=%q", sent)
	}
}

func TestReconnectingMeshNilRedialRetries(t *testing.T) {
	good := NewSimulator(SimConfig{})
	logs := &logCapture{}
	n := 0
	redial := func() (Mesh, error) {
		n++
		if n == 1 {
			return nil, nil // buggy gateway factory: must not wedging
		}
		return good, nil
	}
	m := NewReconnectingMesh(nil, redial, ReconnectConfig{
		Initial: time.Millisecond,
		Max:     5 * time.Millisecond,
		Log:     logs.fn(),
	})
	defer m.Close()
	waitFor(t, "restore", func() bool {
		return strings.Contains(logs.joined(), "umerudi")
	})
	if err := m.Send(context.Background(), []byte("up")); err != nil {
		t.Fatalf("send after nil redial: %v", err)
	}
}

func TestReconnectingMeshCloseEndsReceive(t *testing.T) {
	sim := NewSimulator(SimConfig{})
	m := NewReconnectingMesh(sim, func() (Mesh, error) { return NewSimulator(SimConfig{}), nil }, ReconnectConfig{})
	if err := m.Close(); err != nil {
		t.Fatal(err)
	}
	if err := m.Close(); err != nil {
		t.Fatal(err) // idempotent
	}
	select {
	case _, ok := <-m.Receive():
		if ok {
			t.Fatal("Receive must close after Close")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Receive did not close")
	}
}
