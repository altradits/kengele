package fund

import (
	"crypto/hmac"
	"crypto/sha256"
	"crypto/sha512"
	"encoding/binary"
	"testing"

	"github.com/btcsuite/btcd/btcec/v2"
)

// testTpub builds a deterministic, made-up tpub from a fake seed label.
// These keys are worthless test fixtures: the labels are published, so
// no funds must ever touch them. This helper exists only in tests; the
// shipped tool has no seed or private-key code path.
func testTpub(t *testing.T, label string) string {
	t.Helper()
	seed := sha256.Sum256([]byte("kengele fund test key: " + label))
	mac := hmac.New(sha512.New, []byte("Bitcoin seed"))
	mac.Write(seed[:])
	out := mac.Sum(nil)
	priv, pub := btcec.PrivKeyFromBytes(out[:32])
	priv.Zero()
	var payload [78]byte
	binary.BigEndian.PutUint32(payload[:4], verTPUB)
	copy(payload[13:45], out[32:])
	copy(payload[45:78], pub.SerializeCompressed())
	return EncodeBase58Check(payload[:])
}

func TestBase58RoundTrip(t *testing.T) {
	body := make([]byte, 78)
	for i := range body {
		body[i] = byte(i*7 + 1)
	}
	enc := EncodeBase58Check(body)
	back, err := DecodeBase58Check(enc)
	if err != nil {
		t.Fatal(err)
	}
	if string(back) != string(body) {
		t.Fatalf("round trip mismatch: %x vs %x", back, body)
	}
}

func TestBase58KnownVector(t *testing.T) {
	// BIP32 test vector 1, chain m (mainnet xpub, used only as a
	// base58check fixture, never as fund input).
	raw, err := DecodeBase58Check("xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet8")
	if err != nil {
		t.Fatal(err)
	}
	if len(raw) != 78 || raw[0] != 0x04 || raw[1] != 0x88 || raw[2] != 0xb2 || raw[3] != 0x1e {
		t.Fatalf("bad decoded payload: len=%d prefix=%x", len(raw), raw[:4])
	}
}

func TestBase58Failures(t *testing.T) {
	for _, s := range []string{"", "0OIl", "xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet9"} {
		if _, err := DecodeBase58Check(s); err == nil {
			t.Errorf("accepted %q", s)
		}
	}
}
