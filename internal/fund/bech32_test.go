package fund

import (
	"encoding/hex"
	"strings"
	"testing"
)

func TestBech32ValidVectors(t *testing.T) {
	// Valid Bech32 strings from BIP173.
	valid := []string{
		"A12UEL5L",
		"a12uel5l",
		"an83characterlonghumanreadablepartthatcontainsthenumber1andtheexcludedcharactersbio1tt5tgs",
		"abcdef1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxw",
		"11qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqc8247j",
		"split1checkupstagehandshakeupstreamerranterredcaperred2y9e3w",
		"?1ezyfcl",
	}
	for _, s := range valid {
		hrp, data, err := DecodeBech32(s)
		if err != nil {
			t.Errorf("decode %q: %v", s, err)
			continue
		}
		back, err := EncodeBech32(hrp, data)
		if err != nil {
			t.Errorf("re-encode %q: %v", s, err)
			continue
		}
		if back != strings.ToLower(s) {
			t.Errorf("round trip %q -> %q", s, back)
		}
	}
}

func TestBech32InvalidVectors(t *testing.T) {
	invalid := []string{
		"\x201nwldj5",
		"\x7f1axkwrx",
		"pzry9x0s0muk",
		"1pzry9x0s0muk",
		"x1b4n0q5v",
		"li1dgmt3",
		"A1G7SGD8",
		"10a06t8",
		"1qzzfhee",
		"abc1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxz",
	}
	for _, s := range invalid {
		if _, _, err := DecodeBech32(s); err == nil {
			t.Errorf("accepted %q", s)
		}
	}
}

func TestSegwitP2WSHVector(t *testing.T) {
	// BIP173: script <G> OP_CHECKSIG where G is the generator point
	// hashes to the program below with this testnet address.
	script, _ := hex.DecodeString("210279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798ac")
	addr, err := P2WSHAddress("tb", script)
	if err != nil {
		t.Fatal(err)
	}
	if want := "tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7"; addr != want {
		t.Fatalf("P2WSH address:\n got %s\nwant %s", addr, want)
	}
	hrp, prog, err := DecodeSegwitAddress(addr)
	if err != nil {
		t.Fatal(err)
	}
	if hrp != "tb" {
		t.Errorf("hrp = %q", hrp)
	}
	if want := "1863143c14c5166804bd19203356da136c985678cd4d27a1b8c6329604903262"; hex.EncodeToString(prog) != want {
		t.Errorf("program = %x, want %s", prog, want)
	}
}

func TestSegwitAddressFailures(t *testing.T) {
	// P2WSHAddress takes a script of any length; the fixed 20/32-byte
	// rule applies to witness programs in SegwitAddress.
	if _, err := SegwitAddress("tb", make([]byte, 31)); err == nil {
		t.Error("accepted 31-byte program")
	}
	if _, err := P2WSHAddress("", make([]byte, 32)); err == nil {
		t.Error("accepted empty HRP")
	}
	if _, _, err := DecodeSegwitAddress("tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k8"); err == nil {
		t.Error("accepted bad checksum")
	}
}
