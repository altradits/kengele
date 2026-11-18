package meshcarry

import (
	"bytes"
	"errors"
	"testing"
)

// lineSim builds the A->B->C chain: links A-B and B-C only.
func lineSim() *Simulator {
	s := NewSimulator()
	s.AddNode("A")
	s.AddNode("B")
	s.AddNode("C")
	s.Link("A", "B")
	s.Link("B", "C")
	return s
}

// T4: a linked chain delivers at the far end with hop count 2 and the
// payload byte-identical; the TTL falls 7->6->5 across the hops.
func TestMultiHop(t *testing.T) {
	s := lineSim()
	s.SeedMsgID(0x123456)
	dg := testDatagram(0x5A)
	if err := s.Inject("A", dg); err != nil {
		t.Fatal(err)
	}
	// Wave 1 fans A->B (2 relays at B), wave 2 fans B->C (2 relays at
	// C), wave 3 lands C's relays back at B, already seen: silence.
	for step, want := range []int{2, 2, 0} {
		if got := s.Step(); got != want {
			t.Fatalf("step %d relayed %d, want %d", step+1, got, want)
		}
	}
	got := s.Deliveries("C")
	if len(got) != 1 {
		t.Fatalf("C deliveries = %d, want 1", len(got))
	}
	if !bytes.Equal(got[0].Datagram, dg) {
		t.Error("C payload differs from the injected datagram")
	}
	if got[0].From != "B" || got[0].Hops != 2 {
		t.Errorf("C delivery from=%q hops=%d, want from B hops 2", got[0].From, got[0].Hops)
	}
	mid := s.Deliveries("B")
	if len(mid) != 1 || mid[0].From != "A" || mid[0].Hops != 1 {
		t.Fatalf("B deliveries = %+v, want one from A hops 1", mid)
	}
	if len(s.Deliveries("A")) != 0 {
		t.Error("A delivered its own broadcast")
	}
}

func TestTTLDecrementAcrossHops(t *testing.T) {
	s := lineSim()
	var relayTTL []uint8
	s.OnTrace = func(ev TraceEvent) {
		if ev.Kind == TraceRelay {
			relayTTL = append(relayTTL, ev.TTL)
		}
	}
	if err := s.Inject("A", testDatagram(1)); err != nil {
		t.Fatal(err)
	}
	s.Run(10)
	// B relays at TTL 6, C relays at TTL 5: the 7 the sender set falls
	// once per hop.
	if len(relayTTL) != 4 {
		t.Fatalf("relay TTLs = %v, want 4 relays", relayTTL)
	}
	for _, ttl := range relayTTL[:2] {
		if ttl != 6 {
			t.Errorf("B relay TTL = %d, want 6", ttl)
		}
	}
	for _, ttl := range relayTTL[2:] {
		if ttl != 5 {
			t.Errorf("C relay TTL = %d, want 5", ttl)
		}
	}
}

// T5: TTL 1 reaches the first hop only; the far end hears nothing.
func TestTTLExpiry(t *testing.T) {
	s := lineSim()
	s.SendTTL = 1
	if err := s.Inject("A", testDatagram(2)); err != nil {
		t.Fatal(err)
	}
	s.Run(10)
	if len(s.Deliveries("B")) != 1 {
		t.Fatalf("B deliveries = %d, want 1", len(s.Deliveries("B")))
	}
	if len(s.Deliveries("C")) != 0 {
		t.Fatalf("C deliveries = %d, want 0: TTL expired at B", len(s.Deliveries("C")))
	}
	// A TTL-0 fragment is dropped even by a bare node.
	n := &Node{ID: "lonely"}
	frags, err := Split(testDatagram(3), 0x777777, 0)
	if err != nil {
		t.Fatal(err)
	}
	relay, deliver, err := n.Receive(frags[0])
	if err != nil || relay != nil || deliver != nil {
		t.Errorf("TTL-0 receive = (%v, %v, %v), want all nil", relay != nil, deliver != nil, err)
	}
}

// T6: an isolated node delivers nothing. This is the honest-limit
// test: it asserts silence, not an error.
func TestNoRouteNoDelivery(t *testing.T) {
	s := NewSimulator()
	s.AddNode("A")
	if err := s.Inject("A", testDatagram(4)); err != nil {
		t.Fatal(err)
	}
	s.Run(5)
	if n := s.Step(); n != 0 {
		t.Errorf("isolated step relayed %d, want 0", n)
	}
	if len(s.Deliveries("A")) != 0 {
		t.Errorf("isolated A deliveries = %d, want 0", len(s.Deliveries("A")))
	}
}

// T7: a diamond (A->B, A->C, B->D, C->D) delivers exactly once at D:
// the sha256 seen-set drops the second copy.
func TestFloodDedup(t *testing.T) {
	s := NewSimulator()
	for _, id := range []string{"A", "B", "C", "D"} {
		s.AddNode(id)
	}
	s.Link("A", "B")
	s.Link("A", "C")
	s.Link("B", "D")
	s.Link("C", "D")
	if err := s.Inject("A", testDatagram(5)); err != nil {
		t.Fatal(err)
	}
	s.Run(20)
	if got := s.Deliveries("D"); len(got) != 1 {
		t.Fatalf("D deliveries = %d, want exactly 1", len(got))
	}
}

// T8 (simulator half): garbage fed to a middle node changes nothing;
// the chain still delivers byte-identical.
func TestMixedTrafficThroughChain(t *testing.T) {
	s := lineSim()
	if err := s.Inject("A", testDatagram(6)); err != nil {
		t.Fatal(err)
	}
	b := s.Node("B")
	for _, junk := range [][]byte{nil, {}, []byte("not kengele"), bytes.Repeat([]byte{0xFF}, 150)} {
		relay, deliver, err := b.Receive(junk)
		if err != nil || relay != nil || deliver != nil {
			t.Fatalf("junk receive = (%v, %v, %v), want all nil", relay != nil, deliver != nil, err)
		}
	}
	s.Run(10)
	got := s.Deliveries("C")
	if len(got) != 1 || !bytes.Equal(got[0].Datagram, testDatagram(6)) {
		t.Fatalf("C deliveries after junk = %d, want 1 byte-identical", len(got))
	}
}

// A dropping middle node proves the honest limit: nothing arrives and
// the run still completes with exit-clean silence.
func TestDropNode(t *testing.T) {
	s := lineSim()
	s.Node("B").Drop = true
	if err := s.Inject("A", testDatagram(7)); err != nil {
		t.Fatal(err)
	}
	s.Run(10)
	if len(s.Deliveries("C")) != 0 {
		t.Fatalf("C deliveries = %d past a dropping B, want 0", len(s.Deliveries("C")))
	}
}

// A broadcast from a node with 6 or more links is capped at TTL 5
// (bitchat dense-crowd rule).
func TestDenseCrowdTTLCapped(t *testing.T) {
	s := NewSimulator()
	s.AddNode("hub")
	for _, id := range []string{"p1", "p2", "p3", "p4", "p5", "p6"} {
		s.Link("hub", id)
	}
	var broadcastTTL []uint8
	s.OnTrace = func(ev TraceEvent) {
		if ev.Kind == TraceBroadcast {
			broadcastTTL = append(broadcastTTL, ev.TTL)
		}
	}
	if err := s.Inject("hub", testDatagram(8)); err != nil {
		t.Fatal(err)
	}
	for _, ttl := range broadcastTTL {
		if ttl != DenseCrowdTTL {
			t.Fatalf("dense broadcast TTL = %d, want %d", ttl, DenseCrowdTTL)
		}
	}
	if len(broadcastTTL) != 12 { // 2 frags x 6 peers
		t.Fatalf("broadcasts = %d, want 12", len(broadcastTTL))
	}
}

func TestSimulatorPlumbing(t *testing.T) {
	s := lineSim()
	if got := s.Peers("B"); len(got) != 2 || got[0] != "A" || got[1] != "C" {
		t.Errorf("Peers(B) = %v, want [A C] sorted", got)
	}
	if got := s.Peers("ghost"); len(got) != 0 {
		t.Errorf("Peers(ghost) = %v, want none", got)
	}
	if s.Node("ghost") != nil {
		t.Error("Node(ghost) is not nil")
	}
	if got := s.Deliveries("ghost"); len(got) != 0 {
		t.Errorf("Deliveries(ghost) = %d, want 0", len(got))
	}
	if err := s.Inject("ghost", testDatagram(1)); err == nil {
		t.Error("Inject to unknown node succeeded")
	}
	if err := s.Inject("A", make([]byte, 10)); !errors.Is(err, ErrDatagramSize) {
		t.Errorf("Inject(10 bytes) err = %v, want ErrDatagramSize", err)
	}
	if _, ok := s.LastMsgID(); ok {
		t.Error("LastMsgID reports an injection before any happened")
	}
	s.SeedMsgID(0xABCDEF)
	if err := s.Inject("A", testDatagram(1)); err != nil {
		t.Fatal(err)
	}
	if id, _ := s.LastMsgID(); id != 0xABCDEF {
		t.Errorf("LastMsgID = 0x%x, want 0xabcdef", id)
	}
	s.Unlink("A", "B")
	if got := s.Peers("A"); len(got) != 0 {
		t.Errorf("Peers(A) after unlink = %v, want none", got)
	}
	s.Unlink("A", "ghost") // no-op, must not panic
}
