package bitchat

import (
	"bytes"
	"testing"
	"time"

	"github.com/altradits/kengele/internal/message"
	"github.com/altradits/kengele/internal/transport"
	"github.com/btcsuite/btcd/btcec/v2"
)

// Made-up keys only, generated fresh per test. Never use them for
// anything real.
func seal(t *testing.T, seq uint32, from *btcec.PrivateKey, to [32]byte) []byte {
	t.Helper()
	wire, err := message.Seal(message.Event{
		Kind:      message.KindCheckIn,
		CreatedAt: time.Date(2026, 1, 15, 14, 7, 0, 0, time.UTC),
		Seq:       seq,
		Body:      []byte("Kisumu"),
	}, from, to)
	if err != nil {
		t.Fatalf("seal: %v", err)
	}
	return wire
}

func TestWireFitsMeshLimits(t *testing.T) {
	if message.WireSize > MaxPayload {
		t.Fatalf("wire %d bytes exceeds mesh payload %d", message.WireSize, MaxPayload)
	}
	if got := FragmentsFor(make([]byte, message.WireSize)); got != 2 {
		t.Fatalf("FragmentsFor(wire) = %d, want 2", got)
	}
	if got := FragmentsFor(nil); got != 0 {
		t.Fatalf("FragmentsFor(nil) = %d, want 0", got)
	}
}

func TestBroadcastCollectRoundTrip(t *testing.T) {
	activist, _ := btcec.NewPrivateKey()
	guardian, _ := btcec.NewPrivateKey()
	wire := seal(t, 1, activist, message.PublicKey(guardian))

	var c MemoryCarrier
	if err := c.Broadcast(wire); err != nil {
		t.Fatalf("broadcast: %v", err)
	}
	got := c.Collect()
	if len(got) != 1 {
		t.Fatalf("collect = %d deliveries, want 1", len(got))
	}
	if got[0].Via != transport.Mesh {
		t.Fatalf("via = %v, want mesh", got[0].Via)
	}
	if !bytes.Equal(got[0].Wire, wire) {
		t.Fatal("carried bytes changed in transit")
	}
	// The carried bytes still open: carriage is transparent.
	e, err := message.Open(got[0].Wire, guardian, [][32]byte{message.PublicKey(activist)}, time.Now())
	if err != nil {
		t.Fatalf("open carried message: %v", err)
	}
	if e.Seq != 1 || string(e.Body) != "Kisumu" {
		t.Fatalf("opened %+v, want seq 1 Kisumu", e)
	}
	if again := c.Collect(); len(again) != 0 {
		t.Fatalf("second collect = %d deliveries, want 0", len(again))
	}
}

func TestBroadcastCopiesBytes(t *testing.T) {
	activist, _ := btcec.NewPrivateKey()
	guardian, _ := btcec.NewPrivateKey()
	wire := seal(t, 1, activist, message.PublicKey(guardian))

	var c MemoryCarrier
	if err := c.Broadcast(wire); err != nil {
		t.Fatalf("broadcast: %v", err)
	}
	wire[10] ^= 0xff // mutating the caller's slice must not corrupt the queue
	got := c.Collect()
	if _, err := message.Open(got[0].Wire, guardian, [][32]byte{message.PublicKey(activist)}, time.Now()); err != nil {
		t.Fatalf("queued copy corrupted: %v", err)
	}
}

func TestDuplicateBroadcastDropped(t *testing.T) {
	activist, _ := btcec.NewPrivateKey()
	guardian, _ := btcec.NewPrivateKey()
	wire := seal(t, 1, activist, message.PublicKey(guardian))

	var c MemoryCarrier
	if err := c.Broadcast(wire); err != nil {
		t.Fatalf("broadcast: %v", err)
	}
	if err := c.Broadcast(bytes.Clone(wire)); err != nil {
		t.Fatalf("repeat broadcast: %v", err)
	}
	if got := c.Collect(); len(got) != 1 {
		t.Fatalf("collect = %d deliveries, want 1", len(got))
	}
}

func TestBroadcastRejects(t *testing.T) {
	activist, _ := btcec.NewPrivateKey()
	guardian, _ := btcec.NewPrivateKey()
	good := seal(t, 1, activist, message.PublicKey(guardian))

	badVersion := bytes.Clone(good)
	badVersion[0] = 0x7f

	cases := []struct {
		name string
		wire []byte
		want error
	}{
		{"nil", nil, ErrSize},
		{"empty", []byte{}, ErrSize},
		{"truncated", good[:100], ErrSize},
		{"one byte short", good[:len(good)-1], ErrSize},
		{"one byte over", append(bytes.Clone(good), 0), ErrSize},
		{"bad version", badVersion, ErrVersion},
	}
	for _, tc := range cases {
		var c MemoryCarrier
		if err := c.Broadcast(tc.wire); err != tc.want {
			t.Errorf("%s: broadcast = %v, want %v", tc.name, err, tc.want)
		}
		if got := c.Collect(); len(got) != 0 {
			t.Errorf("%s: collect = %d deliveries, want 0", tc.name, len(got))
		}
	}
}

func TestDistinctMessagesBothDelivered(t *testing.T) {
	activist, _ := btcec.NewPrivateKey()
	guardian, _ := btcec.NewPrivateKey()
	var c MemoryCarrier
	if err := c.Broadcast(seal(t, 1, activist, message.PublicKey(guardian))); err != nil {
		t.Fatalf("broadcast 1: %v", err)
	}
	if err := c.Broadcast(seal(t, 2, activist, message.PublicKey(guardian))); err != nil {
		t.Fatalf("broadcast 2: %v", err)
	}
	if got := c.Collect(); len(got) != 2 {
		t.Fatalf("collect = %d deliveries, want 2", len(got))
	}
}
