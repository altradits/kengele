// Focused tests for the OpenTimestamps wire codec. The exact-byte vectors
// below pin the format the standard `ots` tool reads; they were derived
// from the reference implementation's serialization rules
// (python-opentimestamps, opentimestamps/core/{serialize,op,notary,timestamp}.py),
// not from this package's own encoder.
package ots

import (
	"bytes"
	"encoding/hex"
	"testing"
)

func mustHex(t *testing.T, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(s)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

// fakeDigest is obviously made-up test data, never a real hash.
var fakeDigest = bytes.Repeat([]byte{0xA1}, 32)

func TestVaruintVectors(t *testing.T) {
	vectors := map[uint64]string{
		0:          "00",
		1:          "01",
		127:        "7f",
		128:        "8001",
		300:        "ac02",
		800000:     "80ea30",
		4294967295: "ffffffff0f",
	}
	for v, want := range vectors {
		if got := hex.EncodeToString(appendVaruint(nil, v)); got != want {
			t.Errorf("appendVaruint(%d) = %s, want %s", v, got, want)
		}
		back, n, err := readVaruint(mustHex(t, want))
		if err != nil || back != v || n != len(mustHex(t, want)) {
			t.Errorf("readVaruint(%s) = %d,%d,%v, want %d", want, back, n, err, v)
		}
	}
}

func TestVaruintRejects(t *testing.T) {
	if _, _, err := readVaruint([]byte{0x80}); err == nil {
		t.Error("truncated varuint accepted")
	}
	if _, _, err := readVaruint(bytes.Repeat([]byte{0x80}, 10)); err == nil {
		t.Error("overlong varuint accepted")
	}
}

func TestPendingAttestationExactBytes(t *testing.T) {
	uri := "https://a.pool.opentimestamps.org"
	ts := &Timestamp{Msg: fakeDigest, Attestations: []Attestation{{Tag: tagPending, URI: uri}}}
	got := hex.EncodeToString(ts.Marshal())
	// 00 | pending tag | varbytes(varbytes(uri))
	want := "00" + "83dfe30d2ef90c8e" + "22" + "21" + hex.EncodeToString([]byte(uri))
	if got != want {
		t.Errorf("pending timestamp:\n got %s\nwant %s", got, want)
	}
}

func TestBitcoinAttestationExactBytes(t *testing.T) {
	ts := &Timestamp{Msg: fakeDigest, Attestations: []Attestation{{Tag: tagBitcoin, Height: 800000}}}
	got := hex.EncodeToString(ts.Marshal())
	// 00 | bitcoin tag | varbytes(varuint(800000)); varuint(800000) = 80ea30.
	want := "00" + "0588960d73d71901" + "03" + "80ea30"
	if got != want {
		t.Errorf("bitcoin timestamp:\n got %s\nwant %s", got, want)
	}
}

func TestDetachedFileExactPrefix(t *testing.T) {
	ts := &Timestamp{Msg: fakeDigest, Attestations: []Attestation{{Tag: tagBitcoin, Height: 1}}}
	f := &File{HashOp: Op{Tag: tagSHA256}, Digest: fakeDigest, Stamp: ts}
	got := hex.EncodeToString(f.Marshal())
	wantMagic := "00" + "4f70656e54696d657374616d7073" + "0000" + "50726f6f66" + "00" + "bf89e2e884e89294"
	want := wantMagic + "01" + "08" + hex.EncodeToString(fakeDigest) + "00" + "0588960d73d71901" + "0101"
	if got != want {
		t.Errorf("detached file:\n got %s\nwant %s", got, want)
	}
}

func TestTimestampRoundTripNested(t *testing.T) {
	// digest -Append(nonce)-> sha256 -> commitment, with a pending
	// attestation on the commitment and a bitcoin attestation deeper.
	nonce := bytes.Repeat([]byte{0x07}, 16)
	root := New(fakeDigest)
	afterAppend := root.child(Op{Tag: tagAppend, Arg: nonce})
	commitNode := afterAppend.child(Op{Tag: tagSHA256})
	commitNode.Attestations = []Attestation{{Tag: tagPending, URI: "https://a.pool.opentimestamps.org"}}
	commitNode.child(Op{Tag: tagPrepend, Arg: []byte{0x01, 0x02}}).Attestations =
		[]Attestation{{Tag: tagBitcoin, Height: 800000}}

	enc := root.Marshal()
	back, err := ParseTimestamp(fakeDigest, enc)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if !back.Equal(root) {
		t.Fatal("round trip changed the timestamp")
	}
	if got := hex.EncodeToString(back.Marshal()); got != hex.EncodeToString(enc) {
		t.Fatal("re-serialization is not byte-stable")
	}
	// The commitment is sha256(digest || nonce).
	commit := commitNode.Msg
	if len(commit) != 32 {
		t.Fatalf("commitment length %d", len(commit))
	}
}

// TestUpstreamVectors replays serialization vectors copied from the
// reference implementation's own test suite
// (python-opentimestamps/.../tests/core/test_timestamp.py). They are
// independent of this package: if the reference ever disagrees with them,
// the reference wins and this code must change.
func TestUpstreamVectors(t *testing.T) {
	pending := func(uri string) Attestation { return Attestation{Tag: tagPending, URI: uri} }
	leaf := func(uri string) string {
		return "00" + "83dfe30d2ef90c8e" + "07" + "06" + hex.EncodeToString([]byte(uri))
	}
	// Single pending attestation on msg "foo".
	ts := &Timestamp{Msg: []byte("foo"), Attestations: []Attestation{pending("foobar")}}
	if got, want := hex.EncodeToString(ts.Marshal()), leaf("foobar"); got != want {
		t.Errorf("single pending:\n got %s\nwant %s", got, want)
	}
	// Two attestations sort by URI: barfoo first.
	ts = &Timestamp{Msg: []byte("foo"), Attestations: []Attestation{pending("foobar"), pending("barfoo")}}
	if got, want := hex.EncodeToString(ts.Marshal()), "ff"+leaf("barfoo")+leaf("foobar"); got != want {
		t.Errorf("two pending:\n got %s\nwant %s", got, want)
	}
	// Attestations plus one op: every attestation is flagged, the last
	// op is bare, the child holds "deeper".
	ts = &Timestamp{Msg: []byte("foo"), Attestations: []Attestation{pending("foobar"), pending("barfoo"), pending("foobaz")}}
	ts.child(Op{Tag: tagSHA256}).Attestations = []Attestation{pending("deeper")}
	want := "ff" + leaf("barfoo") + "ff" + leaf("foobar") + "ff" + leaf("foobaz") + "08" + leaf("deeper")
	if got := hex.EncodeToString(ts.Marshal()); got != want {
		t.Errorf("atts plus op:\n got %s\nwant %s", got, want)
	}
	// Detached file for the empty string: sha256("") is e3b0c442...
	empty := "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
	ts = &Timestamp{Msg: mustHex(t, empty), Attestations: []Attestation{pending("foobar")}}
	f := &File{HashOp: Op{Tag: tagSHA256}, Digest: mustHex(t, empty), Stamp: ts}
	magic := "00" + "4f70656e54696d657374616d7073" + "0000" + "50726f6f66" + "00" + "bf89e2e884e89294"
	if got, want := hex.EncodeToString(f.Marshal()), magic+"01"+"08"+empty+leaf("foobar"); got != want {
		t.Errorf("detached:\n got %s\nwant %s", got, want)
	}
	// Every vector must also parse back to the same tree.
	for _, tc := range []struct {
		msg string
		raw string
	}{
		{"foo", leaf("foobar")},
		{"foo", "ff" + leaf("barfoo") + leaf("foobar")},
		{"foo", "ff" + leaf("barfoo") + "ff" + leaf("foobar") + "ff" + leaf("foobaz") + "08" + leaf("deeper")},
	} {
		back, err := ParseTimestamp([]byte(tc.msg), mustHex(t, tc.raw))
		if err != nil {
			t.Errorf("parse %s: %v", tc.raw, err)
			continue
		}
		if got := hex.EncodeToString(back.Marshal()); got != tc.raw {
			t.Errorf("re-encode %s: got %s", tc.raw, got)
		}
	}
	// 256 nested ops exceed the recursion limit and must be rejected.
	deep := append(bytes.Repeat([]byte{0x08}, 256), mustHex(t, leaf("barfoo"))...)
	if _, err := ParseTimestamp(nil, deep); err == nil {
		t.Error("256 nested ops accepted")
	}
	// Keccak is not a detached-file hash op.
	keccak := mustHex(t, magic+"01") // magic + version
	keccak = append(keccak, tagKECCAK256)
	keccak = append(keccak, make([]byte, 32)...)
	keccak = append(keccak, mustHex(t, leaf("x"))...)
	if _, err := ParseFile(keccak); err == nil {
		t.Error("keccak file-hash op accepted")
	}
}

func TestTimestampMerge(t *testing.T) {
	a := &Timestamp{Msg: fakeDigest, Attestations: []Attestation{{Tag: tagPending, URI: "https://a.pool.opentimestamps.org"}}}
	b := &Timestamp{Msg: fakeDigest, Attestations: []Attestation{{Tag: tagBitcoin, Height: 800000}}}
	if err := a.Merge(b); err != nil {
		t.Fatal(err)
	}
	if len(a.Attestations) != 2 {
		t.Fatalf("merged attestations = %d, want 2", len(a.Attestations))
	}
	// Merging twice is idempotent.
	if err := a.Merge(b); err != nil {
		t.Fatal(err)
	}
	if len(a.Attestations) != 2 {
		t.Fatalf("re-merge attestations = %d, want 2", len(a.Attestations))
	}
	if err := a.Merge(&Timestamp{Msg: []byte{0x00}}); err == nil {
		t.Error("merge of different messages accepted")
	}
}

func TestUnknownAttestationRoundTrips(t *testing.T) {
	ts := &Timestamp{Msg: fakeDigest, Attestations: []Attestation{
		{Tag: [8]byte{0xde, 0xad, 0xbe, 0xef, 0x00, 0x11, 0x22, 0x33}, Unknown: []byte{0x09, 0x09}},
	}}
	back, err := ParseTimestamp(fakeDigest, ts.Marshal())
	if err != nil {
		t.Fatal(err)
	}
	if !back.Equal(ts) {
		t.Fatal("unknown attestation did not round-trip")
	}
}

func TestParseRejects(t *testing.T) {
	goodPending := (&Timestamp{Msg: fakeDigest, Attestations: []Attestation{{Tag: tagPending, URI: "https://x.example"}}}).Marshal()
	cases := map[string][]byte{
		"empty":           {},
		"only tag":        {0x00},
		"empty timestamp": {0xff}, // 0xff then nothing: truncated
		"unknown op":      {0x09}, // 0x09 is no known op tag
		"truncated":       goodPending[:len(goodPending)-2],
		"trailing junk":   append(append([]byte(nil), goodPending...), 0x00),
	}
	for name, b := range cases {
		if _, err := ParseTimestamp(fakeDigest, b); err == nil {
			t.Errorf("%s: accepted", name)
		}
	}
	// Empty binary-op argument.
	if _, err := ParseTimestamp(fakeDigest, []byte{0xf0, 0x00, 0x00}); err == nil {
		t.Error("empty append argument accepted")
	}
	// Pending attestation with a hostile URI (query string).
	hostile := &Timestamp{Msg: fakeDigest}
	badURI := appendVarbytes(nil, []byte("https://x.example/timestamp?next=evil"))
	_ = badURI
	_ = hostile
	raw := []byte{0x00}
	raw = append(raw, tagPending[:]...)
	raw = appendVarbytes(raw, appendVarbytes(nil, []byte("https://x.example/?q=1")))
	if _, err := ParseTimestamp(fakeDigest, raw); err == nil {
		t.Error("pending attestation with query string accepted")
	}
	// Bitcoin height above 2^32-1.
	raw = []byte{0x00}
	raw = append(raw, tagBitcoin[:]...)
	raw = appendVarbytes(raw, appendVaruint(nil, 1<<32))
	if _, err := ParseTimestamp(fakeDigest, raw); err == nil {
		t.Error("bitcoin height 2^32 accepted")
	}
}

func TestFileParseRejects(t *testing.T) {
	ts := &Timestamp{Msg: fakeDigest, Attestations: []Attestation{{Tag: tagBitcoin, Height: 1}}}
	good := (&File{HashOp: Op{Tag: tagSHA256}, Digest: fakeDigest, Stamp: ts}).Marshal()
	bad := append([]byte(nil), good...)
	bad[0] ^= 0xff
	if _, err := ParseFile(bad); err == nil {
		t.Error("bad magic accepted")
	}
	bad = append([]byte(nil), good...)
	bad[len(magicHeader)] = 0x02
	if _, err := ParseFile(bad); err == nil {
		t.Error("version 2 accepted")
	}
	if _, err := ParseFile(good[:len(good)-1]); err == nil {
		t.Error("truncated file accepted")
	}
	if _, err := ParseFile(append(append([]byte(nil), good...), 0x00)); err == nil {
		t.Error("trailing garbage accepted")
	}
}

func TestMarshalEmptyTimestampFails(t *testing.T) {
	if got := (&Timestamp{Msg: fakeDigest}).Marshal(); got != nil {
		t.Errorf("empty timestamp serialized to %x", got)
	}
	if got := (&File{HashOp: Op{Tag: tagSHA256}, Digest: fakeDigest, Stamp: &Timestamp{Msg: fakeDigest}}).Marshal(); got != nil {
		t.Error("file with empty timestamp serialized")
	}
}
