package meshexit

import (
	"bytes"
	"errors"
	"strconv"
	"testing"
	"time"
)

func testBytes(n int) []byte {
	b := make([]byte, n)
	for i := range b {
		b[i] = byte(i*7 + 1)
	}
	return b
}

func TestSplitReassembleRoundTrip(t *testing.T) {
	for _, n := range []int{1, 199, 200, 201, 228, 2000, MaxDatagram} {
		t.Run(strconv.Itoa(n), func(t *testing.T) {
			in := testBytes(n)
			parts, err := Split(in, 0xabcdef)
			if err != nil {
				t.Fatal(err)
			}
			for _, p := range parts {
				if len(p) > MeshMTU {
					t.Fatalf("packet of %d bytes exceeds the MTU", len(p))
				}
			}
			re := NewReassembler(time.Minute, nil)
			var out []byte
			var done bool
			for i, p := range parts {
				out, _, done, err = re.Add(p)
				if err != nil {
					t.Fatal(err)
				}
				if done != (i == len(parts)-1) {
					t.Fatalf("part %d of %d: done=%v", i, len(parts), done)
				}
			}
			if !done || !bytes.Equal(out, in) {
				t.Fatal("reassembled datagram differs")
			}
		})
	}
}

func TestReassembleOutOfOrderAndDuplicates(t *testing.T) {
	in := testBytes(1000)
	parts, err := Split(in, 42)
	if err != nil {
		t.Fatal(err)
	}
	// Reversed, each fragment twice: the mesh repeats packets. The second
	// copy of the final fragment is skipped: by then the group is complete
	// and gone, so a repeat would open a new group (the exit's dedup, not
	// the reassembler, owns repeats across datagrams).
	re := NewReassembler(time.Minute, nil)
	var out []byte
	var done bool
	for i := len(parts) - 1; i >= 0; i-- {
		for k := range 2 {
			if i == 0 && k == 1 {
				break
			}
			out, _, done, err = re.Add(parts[i])
			if err != nil {
				t.Fatal(err)
			}
		}
	}
	if !done || !bytes.Equal(out, in) {
		t.Fatal("out-of-order reassembly failed")
	}
}

func TestSplitRejects(t *testing.T) {
	if _, err := Split(nil, 1); !errors.Is(err, ErrFragment) {
		t.Errorf("empty: %v", err)
	}
	if _, err := Split(testBytes(MaxDatagram+1), 1); !errors.Is(err, ErrDatagramSize) {
		t.Errorf("oversize: %v", err)
	}
	if _, err := Split(testBytes(10), 1<<24); !errors.Is(err, ErrFragment) {
		t.Errorf("message id: %v", err)
	}
}

func TestParseChunkRejects(t *testing.T) {
	good, err := Split(testBytes(500), 7)
	if err != nil {
		t.Fatal(err)
	}
	mutate := func(p []byte, f func([]byte)) []byte {
		c := append([]byte(nil), p...)
		f(c)
		return c
	}
	cases := []struct {
		name string
		pkt  []byte
		want error
	}{
		{"other traffic", []byte("hello mesh"), ErrNotKengele},
		{"short", []byte("K"), ErrNotKengele},
		{"empty", nil, ErrNotKengele},
		{"header only", good[0][:HeaderLen], ErrFragment},
		{"too long", append(append([]byte(nil), good[0]...), testBytes(MaxChunkData)...), ErrFragment},
		{"bad version", mutate(good[0], func(c []byte) { c[2] = 9 }), ErrFragment},
		{"zero total", mutate(good[0], func(c []byte) { c[3] = 0 }), ErrFragment},
		{"index past total", mutate(good[0], func(c []byte) { c[4] = c[3] }), ErrFragment},
		{"short non-last", good[0][:HeaderLen+100], ErrFragment},
		{"total too big", mutate(good[0], func(c []byte) { c[3] = 30 }), ErrDatagramSize},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if _, err := ParseChunk(tc.pkt); !errors.Is(err, tc.want) {
				t.Fatalf("got %v, want %v", err, tc.want)
			}
		})
	}
}

func TestReassemblerIgnoresOtherTraffic(t *testing.T) {
	re := NewReassembler(time.Minute, nil)
	out, _, done, err := re.Add([]byte("not kengele"))
	if err != nil || done || out != nil {
		t.Fatalf("other traffic must be ignored: %v %v %v", out, done, err)
	}
	if re.Pending() != 0 {
		t.Fatal("other traffic must not open a group")
	}
}

func TestReassemblerConflictingTotal(t *testing.T) {
	a, err := Split(testBytes(300), 9)
	if err != nil {
		t.Fatal(err)
	}
	b, err := Split(testBytes(100), 9) // same id, one part
	if err != nil {
		t.Fatal(err)
	}
	re := NewReassembler(time.Minute, nil)
	if _, _, done, err := re.Add(a[0]); err != nil || done {
		t.Fatalf("first part: %v %v", done, err)
	}
	if _, _, _, err := re.Add(b[0]); !errors.Is(err, ErrFragment) {
		t.Fatalf("conflicting total: %v", err)
	}
	if re.Pending() != 0 {
		t.Fatal("conflicting group must be dropped")
	}
}

func TestReassemblerExpiry(t *testing.T) {
	now := time.Now()
	re := NewReassembler(time.Minute, func() time.Time { return now })
	parts, err := Split(testBytes(500), 11)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, done, err := re.Add(parts[0]); err != nil || done {
		t.Fatalf("first part: %v %v", done, err)
	}
	now = now.Add(2 * time.Minute) // first part expires
	var done bool
	for _, p := range parts[1:] {
		if _, _, done, err = re.Add(p); err != nil {
			t.Fatal(err)
		}
	}
	if done {
		t.Fatal("expired first part must not complete")
	}
	if re.Pending() != 1 {
		t.Fatalf("pending=%d, want 1", re.Pending())
	}
}

func TestReassemblerEvictsOldest(t *testing.T) {
	re := NewReassembler(time.Hour, nil)
	// Open the maximum number of groups with one part each.
	first, err := Split(testBytes(300), 1000)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, _, err := re.Add(first[0]); err != nil {
		t.Fatal(err)
	}
	for id := uint32(1001); id < 1000+MaxPendingGroups; id++ {
		p, err := Split(testBytes(300), id)
		if err != nil {
			t.Fatal(err)
		}
		if _, _, _, err := re.Add(p[0]); err != nil {
			t.Fatal(err)
		}
	}
	if re.Pending() != MaxPendingGroups {
		t.Fatalf("pending=%d, want %d", re.Pending(), MaxPendingGroups)
	}
	// One more group evicts the oldest (id 1000).
	p, err := Split(testBytes(300), 2000)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, _, err := re.Add(p[0]); err != nil {
		t.Fatal(err)
	}
	if re.Pending() != MaxPendingGroups {
		t.Fatalf("pending=%d, want %d", re.Pending(), MaxPendingGroups)
	}
	// The evicted group's second part now opens a fresh, incomplete group.
	if _, _, done, err := re.Add(first[1]); err != nil || done {
		t.Fatalf("evicted group must not complete: %v %v", done, err)
	}
}
