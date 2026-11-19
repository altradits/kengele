package fund

import (
	"bytes"
	"encoding/binary"
	"strings"
	"testing"
)

// reencodeAsTpub swaps an xpub's version bytes to tpub and fixes the
// checksum, so BIP32 mainnet vectors can exercise the parser (which
// refuses mainnet input) and the derivation math (which is
// version-independent).
func reencodeAsTpub(t *testing.T, xpub string) string {
	t.Helper()
	body, err := DecodeBase58Check(xpub)
	if err != nil {
		t.Fatal(err)
	}
	binary.BigEndian.PutUint32(body[:4], verTPUB)
	return EncodeBase58Check(body)
}

func TestDeriveMatchesBIP32Vector1(t *testing.T) {
	// BIP32 test vector 1, seed 000102030405060708090a0b0c0d0e0f.
	parent := reencodeAsTpub(t, "xpub6D4BDPcP2GT577Vvch3R8wDkScZWzQzMMUm3PWbmWvVJrZwQY4VUNgqFJPMM3No2dFDFGTsxxpG5uJh7n7epu4trkrX7x7DogT5Uv6fcLW5")
	x, err := ParseXPub(parent, "test")
	if err != nil {
		t.Fatal(err)
	}
	child, err := x.Derive(2)
	if err != nil {
		t.Fatal(err)
	}
	want, err := DecodeBase58Check("xpub6FHa3pjLCk84BayeJxFW2SP4XRrFd1JYnxeLeU8EqN3vDfZmbqBqaGJAyiLjTAwm6ZLRQUMv1ZACTj37sR62cfN7fe5JnJ7dh8zL4fiyLHV")
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(child.Payload()[4:], want[4:]) {
		t.Fatalf("m/0H/1/2H/2 mismatch:\n got %x\nwant %x", child.Payload()[4:], want[4:])
	}
	grand, err := x.Derive(2, 1000000000)
	if err != nil {
		t.Fatal(err)
	}
	want2, err := DecodeBase58Check("xpub6H1LXWLaKsWFhvm6RVpEL9P4KfRZSW7abD2ttkWP3SSQvnyA8FSVqNTEcYFgJS2UaFcxupHiYkro49S8yGasTvXEYBVPamhGW6cFJodrTHy")
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(grand.Payload()[4:], want2[4:]) {
		t.Fatalf("m/0H/1/2H/2/1000000000 mismatch:\n got %x\nwant %x", grand.Payload()[4:], want2[4:])
	}
	if grand.Depth != 5 || grand.ChildNum != 1000000000 {
		t.Errorf("depth=%d child=%d", grand.Depth, grand.ChildNum)
	}
}

func TestDeriveRefusesHardened(t *testing.T) {
	x, err := ParseXPub(testTpub(t, "hardened"), "test")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := x.Derive(HardenedBit); err == nil {
		t.Error("accepted hardened derivation")
	}
	if _, err := x.Derive(0, HardenedBit|1); err == nil {
		t.Error("accepted hardened second step")
	}
	if _, err := x.Derive(0, 0); err != nil {
		t.Errorf("refused /0/0: %v", err)
	}
}

func TestParseXPubRefusals(t *testing.T) {
	good := testTpub(t, "accept")
	if _, err := ParseXPub(good, "test"); err != nil {
		t.Fatalf("refused test tpub: %v", err)
	}
	// A well-formed tprv: BIP32 vector 1 master xprv re-encoded with
	// the tprv version so the private-key refusal path is exercised.
	prvBody, err := DecodeBase58Check("xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi")
	if err != nil {
		t.Fatal(err)
	}
	binary.BigEndian.PutUint32(prvBody[:4], verTPRV)
	tprv := EncodeBase58Check(prvBody)
	cases := []struct {
		name string
		key  string
		want string // required substring in the error
	}{
		{"mainnet xpub", "xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet8", "mainnet"},
		{"mainnet xprv", "xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi", "never accepted"},
		{"testnet tprv", tprv, "never accepted"},
		{"garbage", "not a key at all", "not a valid extended key"},
		{"empty", "", "not a valid extended key"},
		{"wif", "5HueCGU8rMjxEXxiPuD5BDku4MkFqeZyd4d1j3YinZ9vhuC2x22", "never accepted"},
		{"raw hex", "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", "never accepted"},
		{"bad checksum", good[:len(good)-1] + flipLast(good), "not a valid extended key"},
	}
	for _, c := range cases {
		_, err := ParseXPub(c.key, "test")
		if err == nil {
			t.Errorf("%s: accepted", c.name)
			continue
		}
		if !strings.Contains(strings.ToLower(err.Error()), c.want) {
			t.Errorf("%s: error %q lacks %q", c.name, err, c.want)
		}
	}
}

// flipLast returns a base58 character different from s's last one.
func flipLast(s string) string {
	if s[len(s)-1] != '1' {
		return "1"
	}
	return "2"
}

func TestParseXPubWrongVersion(t *testing.T) {
	body, err := DecodeBase58Check(testTpub(t, "version"))
	if err != nil {
		t.Fatal(err)
	}
	binary.BigEndian.PutUint32(body[:4], 0xdeadbeef)
	if _, err := ParseXPub(EncodeBase58Check(body), "test"); err == nil {
		t.Error("accepted unknown version")
	}
	body[45] = 0x00 // uncompressed/private marker
	if _, err := ParseXPub(EncodeBase58Check(body), "test"); err == nil {
		t.Error("accepted non-compressed key data")
	}
}

func TestFingerprintStable(t *testing.T) {
	a, _ := ParseXPub(testTpub(t, "fp-a"), "test")
	b, _ := ParseXPub(testTpub(t, "fp-b"), "test")
	if a.FingerprintHex() == b.FingerprintHex() {
		t.Error("distinct keys share a fingerprint")
	}
	if len(a.FingerprintHex()) != 8 {
		t.Errorf("fingerprint = %q", a.FingerprintHex())
	}
	mid, err := a.Derive(0)
	if err != nil {
		t.Fatal(err)
	}
	c, err := mid.Derive(0)
	if err != nil {
		t.Fatal(err)
	}
	if c.ParentFP != mid.Fingerprint() {
		t.Error("child parent fingerprint does not match")
	}
}
