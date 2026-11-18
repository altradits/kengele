package meshexit

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"
)

func recv(t *testing.T, ch <-chan Packet) Packet {
	t.Helper()
	select {
	case p, ok := <-ch:
		if !ok {
			t.Fatal("mesh closed")
		}
		return p
	case <-time.After(5 * time.Second):
		t.Fatal("timed out waiting for a packet")
		return Packet{}
	}
}

func TestSimulatorSendReceive(t *testing.T) {
	s := NewSimulator(SimConfig{})
	defer s.Close()
	payload := []byte("Kengele fragment")
	s.Inject("node-1", payload)
	payload[0] = 'X' // the mesh must have copied
	got := recv(t, s.Receive())
	if got.From != "node-1" || string(got.Payload) != "Kengele fragment" {
		t.Fatalf("got %+v", got)
	}
	if got.At.IsZero() {
		t.Fatal("packet needs a receive time")
	}

	ctx := context.Background()
	if err := s.Send(ctx, []byte("reply")); err != nil {
		t.Fatal(err)
	}
	sent := s.Sent()
	if len(sent) != 1 || string(sent[0]) != "reply" {
		t.Fatalf("sent=%q", sent)
	}
}

func TestSimulatorMTU(t *testing.T) {
	s := NewSimulator(SimConfig{})
	defer s.Close()
	big := make([]byte, MeshMTU+1)
	if err := s.Send(context.Background(), big); !errors.Is(err, ErrPacketTooLarge) {
		t.Fatalf("oversize send: %v", err)
	}
	if len(s.Sent()) != 0 {
		t.Fatal("oversize packet must not be recorded")
	}
}

func TestSimulatorLoss(t *testing.T) {
	s := NewSimulator(SimConfig{LossEvery: 2})
	defer s.Close()
	for i := 0; i < 4; i++ {
		s.Inject("n", []byte{byte(i)})
	}
	ch := s.Receive()
	var got []byte
	timeout := time.After(2 * time.Second)
	for len(got) < 2 {
		select {
		case p := <-ch:
			got = append(got, p.Payload[0])
		case <-timeout:
			t.Fatalf("got %d packets, want 2", len(got))
		}
	}
	// Packets 1 and 3 survive (1-based counting drops 2 and 4).
	if got[0] != 0 || got[1] != 2 {
		t.Fatalf("got %v, want [0 2]", got)
	}
	select {
	case p := <-ch:
		t.Fatalf("extra packet %v", p)
	case <-time.After(50 * time.Millisecond):
	}
}

func TestSimulatorDupe(t *testing.T) {
	s := NewSimulator(SimConfig{DupeEvery: 2})
	defer s.Close()
	s.Inject("n", []byte{1})
	s.Inject("n", []byte{2})
	ch := s.Receive()
	var got []byte
	for range 3 {
		got = append(got, recv(t, ch).Payload[0])
	}
	if got[0] != 1 || got[1] != 2 || got[2] != 2 {
		t.Fatalf("got %v, want [1 2 2]", got)
	}
}

func TestSimulatorClose(t *testing.T) {
	s := NewSimulator(SimConfig{})
	s.Inject("n", []byte{1})
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	if err := s.Close(); err != nil {
		t.Fatal(err) // idempotent
	}
	first := recv(t, s.Receive())
	if first.Payload[0] != 1 {
		t.Fatalf("got %+v", first)
	}
	if _, ok := <-s.Receive(); ok {
		t.Fatal("channel must close after Close")
	}
	s.Inject("n", []byte{2}) // ignored, must not panic
	if err := s.Send(context.Background(), []byte{3}); !errors.Is(err, ErrMeshClosed) {
		t.Fatalf("send after close: %v", err)
	}
}

func TestFramedMeshRoundTrip(t *testing.T) {
	a, b := net.Pipe()
	ma, mb := newFramedMesh(a, "a", 0), newFramedMesh(b, "b", 0)
	defer ma.Close()
	defer mb.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := ma.Send(ctx, []byte("over the air")); err != nil {
		t.Fatal(err)
	}
	got := recv(t, mb.Receive())
	if got.From != "b" || string(got.Payload) != "over the air" {
		t.Fatalf("got %+v", got)
	}
	if err := mb.Send(ctx, []byte("back")); err != nil {
		t.Fatal(err)
	}
	if back := recv(t, ma.Receive()); string(back.Payload) != "back" {
		t.Fatalf("got %+v", back)
	}
}

func TestFramedMeshMTUAndEmpty(t *testing.T) {
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	m := newFramedMesh(a, "a", 0)
	defer m.Close()
	ctx := context.Background()
	if err := m.Send(ctx, make([]byte, MeshMTU+1)); !errors.Is(err, ErrPacketTooLarge) {
		t.Fatalf("oversize: %v", err)
	}
	if err := m.Send(ctx, nil); err == nil {
		t.Fatal("empty packet accepted")
	}
}

func TestFramedMeshCloseEndsReceive(t *testing.T) {
	a, b := net.Pipe()
	ma, mb := newFramedMesh(a, "a", 0), newFramedMesh(b, "b", 0)
	if err := ma.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case _, ok := <-mb.Receive():
		if ok {
			t.Fatal("peer close must end the channel")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("timed out waiting for close")
	}
	mb.Close()
}

func TestFramedMeshBadFrameDropsLink(t *testing.T) {
	raw, framed := net.Pipe()
	m := newFramedMesh(framed, "f", 0)
	defer m.Close()
	defer raw.Close()
	// A zero length is a protocol violation.
	if _, err := raw.Write([]byte{0, 0}); err != nil {
		t.Fatal(err)
	}
	select {
	case _, ok := <-m.Receive():
		if ok {
			t.Fatal("bad frame must drop the link")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("timed out waiting for the drop")
	}
}
