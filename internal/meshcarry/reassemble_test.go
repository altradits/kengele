package meshcarry

import (
	"bytes"
	"errors"
	"testing"
	"time"
)

// T2: fragments reassemble in any order, and duplicate fragments are
// dropped without disturbing the group.
func TestReassemblerRoundTrip(t *testing.T) {
	dg := testDatagram(0x3C)
	frags, err := Split(dg, 0x010203, DefaultTTL)
	if err != nil {
		t.Fatal(err)
	}
	for _, order := range [][]int{{0, 1}, {1, 0}} {
		r := NewReassembler(0, 0)
		var out []byte
		var complete bool
		for step, i := range order {
			out, complete, err = r.Add(frags[i])
			if err != nil {
				t.Fatalf("order %v: %v", order, err)
			}
			if step == 0 && complete {
				t.Fatalf("order %v: complete after one fragment", order)
			}
		}
		if !complete || !bytes.Equal(out, dg) {
			t.Fatalf("order %v: complete=%v equal=%v", order, complete, bytes.Equal(out, dg))
		}
		if r.Pending() != 0 {
			t.Errorf("order %v: %d groups pending after completion", order, r.Pending())
		}
	}

	// A duplicate of the first fragment is ignored; the group still
	// completes exactly once.
	r := NewReassembler(0, 0)
	if _, complete, err := r.Add(frags[0]); err != nil || complete {
		t.Fatalf("first frag: complete=%v err=%v", complete, err)
	}
	if _, complete, err := r.Add(frags[0]); err != nil || complete {
		t.Fatalf("duplicate frag: complete=%v err=%v", complete, err)
	}
	out, complete, err := r.Add(frags[1])
	if err != nil || !complete || !bytes.Equal(out, dg) {
		t.Fatalf("final frag: complete=%v equal=%v err=%v", complete, bytes.Equal(out, dg), err)
	}
}

// T8 (reassembly half): interleaved non-Kengele bytes are ignored and
// the Kengele datagram still completes.
func TestReassemblerIgnoresMixedTraffic(t *testing.T) {
	dg := testDatagram(0x77)
	frags, err := Split(dg, 0x0B0B0B, DefaultTTL)
	if err != nil {
		t.Fatal(err)
	}
	r := NewReassembler(0, 0)
	junk := [][]byte{
		[]byte("hello from another mesh"),
		bytes.Repeat([]byte{0}, 150),
		append([]byte{'K', 'G', 1, 1, 0}, bytes.Repeat([]byte{9}, 40)...),
	}
	for _, j := range junk {
		if _, complete, err := r.Add(j); err != nil || complete {
			t.Fatalf("junk %q: complete=%v err=%v", j[:8], complete, err)
		}
	}
	if _, complete, err := r.Add(frags[0]); err != nil || complete {
		t.Fatalf("frag 0: complete=%v err=%v", complete, err)
	}
	for _, j := range junk {
		if _, complete, err := r.Add(j); err != nil || complete {
			t.Fatalf("junk between frags: complete=%v err=%v", complete, err)
		}
	}
	out, complete, err := r.Add(frags[1])
	if err != nil || !complete || !bytes.Equal(out, dg) {
		t.Fatalf("frag 1: complete=%v equal=%v err=%v", complete, bytes.Equal(out, dg), err)
	}
}

// T3: more partial datagrams than the cap evicts the oldest group.
func TestReassemblerEvictsOldest(t *testing.T) {
	r := NewReassembler(0, 2)
	dg := testDatagram(9)
	f0, _ := Split(dg, 0x000001, DefaultTTL)
	f1, _ := Split(dg, 0x000002, DefaultTTL)
	f2, _ := Split(dg, 0x000003, DefaultTTL)
	for _, f := range [][]byte{f0[0], f1[0], f2[0]} {
		if _, _, err := r.Add(f); err != nil {
			t.Fatal(err)
		}
	}
	if r.Pending() != 2 {
		t.Fatalf("pending = %d, want 2", r.Pending())
	}
	// The survivors still complete.
	if _, complete, err := r.Add(f1[1]); err != nil || !complete {
		t.Fatalf("survivor 1: complete=%v err=%v", complete, err)
	}
	if _, complete, err := r.Add(f2[1]); err != nil || !complete {
		t.Fatalf("survivor 2: complete=%v err=%v", complete, err)
	}
	// The evicted group is gone: its second fragment starts a fresh,
	// incomplete group instead of completing anything.
	if _, complete, err := r.Add(f0[1]); err != nil || complete {
		t.Fatalf("evicted group: complete=%v err=%v", complete, err)
	}
	if r.Pending() != 1 {
		t.Fatalf("pending = %d, want the one restarted group", r.Pending())
	}
}

// T3: expired groups are dropped; a late fragment starts over instead
// of joining stale state.
func TestReassemblerExpiry(t *testing.T) {
	r := NewReassembler(time.Minute, 0)
	dg := testDatagram(11)
	frags, err := Split(dg, 0x0E0E0E, DefaultTTL)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := r.Add(frags[0]); err != nil {
		t.Fatal(err)
	}
	r.EvictExpired(time.Now().Add(2 * time.Minute))
	if r.Pending() != 0 {
		t.Fatalf("pending = %d after expiry, want 0", r.Pending())
	}
	if _, complete, err := r.Add(frags[1]); err != nil || complete {
		t.Fatalf("late frag: complete=%v err=%v, want a fresh incomplete group", complete, err)
	}
	out, complete, err := r.Add(frags[0])
	if err != nil || !complete || !bytes.Equal(out, dg) {
		t.Fatalf("resend: complete=%v equal=%v err=%v", complete, bytes.Equal(out, dg), err)
	}
	// Fresh groups survive an eviction pass at the current time.
	r2 := NewReassembler(time.Minute, 0)
	if _, _, err := r2.Add(frags[0]); err != nil {
		t.Fatal(err)
	}
	r2.EvictExpired(time.Now())
	if r2.Pending() != 1 {
		t.Fatalf("pending = %d, want the fresh group kept", r2.Pending())
	}
}

// T3: a conflicting part count under one message id drops the group
// (two senders picked the same id, or corruption).
func TestReassemblerConflictingTotal(t *testing.T) {
	r := NewReassembler(0, 0)
	dg := testDatagram(13)
	frags, err := Split(dg, 0x0F0F0F, DefaultTTL)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := r.Add(frags[0]); err != nil {
		t.Fatal(err)
	}
	clash := bytes.Clone(frags[0])
	clash[4], clash[5] = 1, 0 // same msgID, different total
	if _, _, err := r.Add(clash); !errors.Is(err, ErrFragment) {
		t.Fatalf("conflicting total err = %v, want ErrFragment", err)
	}
	if r.Pending() != 0 {
		t.Fatalf("pending = %d, want the clashed group dropped", r.Pending())
	}
}

// T3: only exact 228-byte completions are datagrams. A single-fragment
// group can never complete to 228 bytes, and a part count whose
// smallest completion already exceeds 228 is rejected up front.
func TestReassemblerWrongSizeCompletion(t *testing.T) {
	// total=1 with a full chunk: completes to 141 bytes, not 228.
	solo := make([]byte, HeaderLen+MaxChunkData)
	solo[0], solo[1], solo[2] = 'K', 'C', FragmentVersion
	solo[3], solo[4], solo[5] = DefaultTTL, 1, 0
	solo[6], solo[7], solo[8] = 0x11, 0x22, 0x33
	r := NewReassembler(0, 0)
	if _, _, err := r.Add(solo); !errors.Is(err, ErrDatagramSize) {
		t.Fatalf("141-byte completion err = %v, want ErrDatagramSize", err)
	}
	// total=3: even the smallest completion (2x141+1) exceeds 228.
	triple := bytes.Clone(solo)
	triple[4], triple[5] = 3, 0
	triple[6], triple[7], triple[8] = 0x44, 0x55, 0x66
	if _, _, err := r.Add(triple); !errors.Is(err, ErrDatagramSize) {
		t.Fatalf("3-part group err = %v, want ErrDatagramSize", err)
	}
}
