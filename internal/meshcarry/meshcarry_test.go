package meshcarry

import (
	"bytes"
	"errors"
	"testing"
)

// Made-up test datagrams only: fixed patterns, never real traffic.

// testDatagram returns a deterministic 228-byte datagram with the given
// filler byte and a position marker so order corruptions show.
func testDatagram(fill byte) []byte {
	dg := make([]byte, MaxDatagram)
	for i := range dg {
		dg[i] = fill + byte(i%251)
	}
	return dg
}

// T1: a 228-byte datagram splits into 2 fragments that each fit the
// radio, with exact header fields.
func TestSplitSizes(t *testing.T) {
	dg := testDatagram(0xA5)
	frags, err := Split(dg, 0x123456, DefaultTTL)
	if err != nil {
		t.Fatal(err)
	}
	if len(frags) != 2 {
		t.Fatalf("got %d fragments, want 2", len(frags))
	}
	if len(frags[0]) != FragmentMTU {
		t.Errorf("frag 0 is %d bytes, want full %d-byte MTU", len(frags[0]), FragmentMTU)
	}
	for i, f := range frags {
		if len(f) > FragmentMTU {
			t.Errorf("frag %d is %d bytes, over the %d MTU", i, len(f), FragmentMTU)
		}
	}
	if got := len(frags[0]) + len(frags[1]) - 2*HeaderLen; got != MaxDatagram {
		t.Errorf("fragments carry %d bytes, want %d", got, MaxDatagram)
	}
	for i, f := range frags {
		h, chunk, err := ParseHeader(f)
		if err != nil {
			t.Fatalf("frag %d: %v", i, err)
		}
		if h.TTL != DefaultTTL || h.Total != 2 || h.Index != uint8(i) || h.MsgID != 0x123456 {
			t.Errorf("frag %d header = %+v, want ttl 7 total 2 index %d msg 0x123456", i, h, i)
		}
		if len(f) != HeaderLen+len(chunk) {
			t.Errorf("frag %d: header+chunk mismatch", i)
		}
	}
	if len(frags[1]) != HeaderLen+87 {
		t.Errorf("frag 1 is %d bytes, want header + 87", len(frags[1]))
	}
}

// T3: Split rejects anything that is not exactly one 228-byte datagram
// and any message id that does not fit 24 bits.
func TestSplitRejects(t *testing.T) {
	for _, n := range []int{0, 1, 227, 229, 300} {
		if _, err := Split(make([]byte, n), 1, DefaultTTL); !errors.Is(err, ErrDatagramSize) {
			t.Errorf("Split(%d bytes) err = %v, want ErrDatagramSize", n, err)
		}
	}
	if _, err := Split(testDatagram(1), maxMsgID+1, DefaultTTL); !errors.Is(err, ErrFragment) {
		t.Errorf("Split(msgID 24-bit+1) err = %v, want ErrFragment", err)
	}
}

// T3: ParseHeader tells other traffic apart from malformed Kengele
// fragments, and checks every header field strictly.
func TestParseRejects(t *testing.T) {
	frags, err := Split(testDatagram(2), 0xABCDEF, 6)
	if err != nil {
		t.Fatal(err)
	}
	good := frags[0]
	t.Run("not Kengele, silently ignored", func(t *testing.T) {
		for name, f := range map[string][]byte{
			"empty":     {},
			"one byte":  {'K'},
			"bad magic": bytes.Repeat([]byte{'X'}, 20),
			"meshexit":  append([]byte{'K', 'G'}, bytes.Repeat([]byte{1}, 20)...),
		} {
			if _, _, err := ParseHeader(f); err != ErrNotKengele {
				t.Errorf("%s: err = %v, want bare ErrNotKengele", name, err)
			}
		}
		// Two bytes of bare "KC" magic are a truncated Kengele
		// fragment, not other traffic.
		if _, _, err := ParseHeader([]byte{0x4B, 0x43}); !errors.Is(err, ErrFragment) {
			t.Errorf("short frag err = %v, want ErrFragment", err)
		}
	})
	mutate := func(f []byte, i int, v byte) []byte {
		cp := bytes.Clone(f)
		cp[i] = v
		return cp
	}
	for name, f := range map[string][]byte{
		"bad version":    mutate(good, 2, 0x02),
		"total zero":     mutate(good, 4, 0),
		"index past end": mutate(good, 5, 2),
		"truncated":      good[:HeaderLen],
		"oversized":      bytes.Clone(append(good, bytes.Repeat([]byte{0}, FragmentMTU)...)),
		"short non-last": good[:HeaderLen+100],
	} {
		if _, _, err := ParseHeader(f); !errors.Is(err, ErrFragment) {
			t.Errorf("%s: err = %v, want ErrFragment", name, err)
		}
	}
	// TTL 0 parses: expiry is enforced by the Node, not the parser.
	h, _, err := ParseHeader(mutate(good, 3, 0))
	if err != nil || h.TTL != 0 {
		t.Errorf("TTL-0 frag: header = %+v err = %v, want TTL 0 accepted", h, err)
	}
}
