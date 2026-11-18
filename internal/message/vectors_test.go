package message

// Shared test vectors for the Kotlin port (android/, issue #15).
//
// testdata/vectors.json is the single source both sides test against: this
// file checks every value against the live Go implementation, and the
// Android unit tests read the same file. All keys are made up; the values
// must never be used for anything real.

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"testing"

	"github.com/nbd-wtf/go-nostr/nip44"
	"golang.org/x/crypto/chacha20"
	"golang.org/x/crypto/hkdf"
)

func loadVectors(t *testing.T) map[string]any {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("testdata", "vectors.json"))
	if err != nil {
		t.Fatal(err)
	}
	var v map[string]any
	if err := json.Unmarshal(raw, &v); err != nil {
		t.Fatal(err)
	}
	return v
}

func vecHex(t *testing.T, v map[string]any, name string) []byte {
	t.Helper()
	s, ok := v[name].(string)
	if !ok {
		t.Fatalf("vectors: %s is missing or not a string", name)
	}
	b, err := hex.DecodeString(s)
	if err != nil {
		t.Fatalf("vectors: %s: %v", name, err)
	}
	return b
}

func vecStr(t *testing.T, v map[string]any, name string) string {
	t.Helper()
	s, ok := v[name].(string)
	if !ok {
		t.Fatalf("vectors: %s is missing or not a string", name)
	}
	return s
}

func vecNum(t *testing.T, v map[string]any, name string) int64 {
	t.Helper()
	f, ok := v[name].(float64)
	if !ok {
		t.Fatalf("vectors: %s is missing or not a number", name)
	}
	return int64(f)
}

// TestSharedVectorsFormat pins the constants and kind values the Kotlin
// port must match exactly.
func TestSharedVectorsFormat(t *testing.T) {
	v := loadVectors(t)
	if got := vecNum(t, v, "format_version"); got != int64(Version) {
		t.Fatalf("format_version %d, want %d", got, Version)
	}
	for name, want := range map[string]int{
		"wire_size": WireSize, "text_size": TextSize,
		"max_body_size": MaxBodySize, "max_sms_chars": MaxSMSChars,
	} {
		if got := vecNum(t, v, name); got != int64(want) {
			t.Fatalf("%s %d, want %d", name, got, want)
		}
	}
	for name, want := range map[string]Kind{
		"kind_check_in": KindCheckIn, "kind_duress": KindDuressCheckIn,
		"kind_panic": KindPanic, "kind_missed": KindMissed,
		"kind_confirm": KindGuardianConfirm, "kind_all_clear": KindAllClear,
		"kind_going_dark": KindGoingDark,
	} {
		if got := vecNum(t, v, name); got != int64(want) {
			t.Fatalf("%s %d, want %d", name, got, want)
		}
	}
}

// TestSharedVectorsGolden checks the pinned end-to-end vector: the signed
// inner plaintext, its hash and signature, the NIP-44 payload and the SMS
// text form, plus the key schedule that produced it.
func TestSharedVectorsGolden(t *testing.T) {
	v := loadVectors(t)
	activist := key(t, vecStr(t, v, "activist_priv_hex"))
	g1 := key(t, vecStr(t, v, "guardian_priv_hex"))

	apub := PublicKey(activist)
	gpub := PublicKey(g1)
	if got := hex.EncodeToString(apub[:]); got != vecStr(t, v, "activist_pub_hex") {
		t.Fatalf("activist pubkey %s", got)
	}
	if got := hex.EncodeToString(gpub[:]); got != vecStr(t, v, "guardian_pub_hex") {
		t.Fatalf("guardian pubkey %s", got)
	}
	ck, err := conversationKey(activist, gpub)
	if err != nil {
		t.Fatal(err)
	}
	if got := hex.EncodeToString(ck[:]); got != vecStr(t, v, "conv_key_hex") {
		t.Fatalf("conversation key %s", got)
	}

	// The pinned NIP-44 payload decrypts to the pinned signed plaintext.
	payloadB64 := vecStr(t, v, "payload_b64")
	innerStr, err := nip44.Decrypt(payloadB64, ck)
	if err != nil {
		t.Fatal(err)
	}
	inner := []byte(innerStr)
	if got := hex.EncodeToString(inner); got != vecStr(t, v, "inner_hex") {
		t.Fatal("inner plaintext does not match the pinned vector")
	}
	if len(inner) != innerSize {
		t.Fatalf("inner size %d, want %d", len(inner), innerSize)
	}
	h := signedHash(inner[:signedSize])
	if got := hex.EncodeToString(h[:]); got != vecStr(t, v, "signed_hash_hex") {
		t.Fatalf("signed hash %s", got)
	}
	if got := hex.EncodeToString(inner[signedSize:]); got != vecStr(t, v, "signature_hex") {
		t.Fatal("signature does not match the pinned vector")
	}

	// The pinned plaintext opens to the pinned event fields.
	ev, err := parse(inner, PublicKey(activist), now)
	if err != nil {
		t.Fatal(err)
	}
	if int64(ev.Kind) != vecNum(t, v, "event_kind") ||
		ev.CreatedAt.Unix() != vecNum(t, v, "event_created_unix") ||
		int64(ev.Seq) != vecNum(t, v, "event_seq") ||
		string(ev.Body) != vecStr(t, v, "event_body") {
		t.Fatalf("event mismatch: %+v", ev)
	}

	// The pinned payload plus the version byte is the golden SMS text.
	rawPayload, err := base64.StdEncoding.DecodeString(payloadB64)
	if err != nil {
		t.Fatal(err)
	}
	wire := append([]byte{Version}, rawPayload...)
	if got := EncodeText(wire); got != vecStr(t, v, "golden_text") {
		t.Fatal("golden text does not match the pinned vector")
	}
	if vecStr(t, v, "golden_text") != goldenText {
		t.Fatal("vectors.json golden_text drifted from TestGoldenVector")
	}
	if len(vecHex(t, v, "nonce_hex")) != 32 {
		t.Fatal("nonce must be 32 bytes")
	}

	// The pinned HKDF key schedule for the golden conversation key + nonce.
	nonce := vecHex(t, v, "nonce_hex")
	r := hkdf.Expand(sha256.New, ck[:], nonce)
	for _, tc := range []struct {
		name string
		size int
	}{
		{"msg_cc20_key_hex", 32}, {"msg_cc20_nonce_hex", 12}, {"msg_hmac_key_hex", 32},
	} {
		buf := make([]byte, tc.size)
		if _, err := io.ReadFull(r, buf); err != nil {
			t.Fatal(err)
		}
		if got := hex.EncodeToString(buf); got != vecStr(t, v, tc.name) {
			t.Fatalf("%s %s", tc.name, got)
		}
	}
}

// TestSharedVectorsNIP44 checks the pinned specification vector for NIP-44
// (v2.valid.encrypt_decrypt[0]): with the pinned conversation key and
// nonce, "a" encrypts to the pinned payload.
func TestSharedVectorsNIP44(t *testing.T) {
	v := loadVectors(t)
	ckBytes := vecHex(t, v, "nip44_conv_hex")
	if len(ckBytes) != 32 {
		t.Fatalf("nip44 conversation key is %d bytes", len(ckBytes))
	}
	var ck [32]byte
	copy(ck[:], ckBytes)
	got, err := nip44.Encrypt(vecStr(t, v, "nip44_plain"), ck,
		nip44.WithCustomNonce(vecHex(t, v, "nip44_nonce_hex")))
	if err != nil {
		t.Fatal(err)
	}
	if got != vecStr(t, v, "nip44_payload") {
		t.Fatalf("nip44 payload mismatch:\n got %s", got)
	}
	back, err := nip44.Decrypt(got, ck)
	if err != nil || back != vecStr(t, v, "nip44_plain") {
		t.Fatalf("nip44 round trip: %q %v", back, err)
	}
}

// TestSharedVectorsChaCha20 checks the pinned raw ChaCha20 vector (IETF
// variant, 12-byte nonce, initial counter 0) against x/crypto.
func TestSharedVectorsChaCha20(t *testing.T) {
	v := loadVectors(t)
	ciph, err := chacha20.NewUnauthenticatedCipher(
		vecHex(t, v, "chacha_key_hex"), vecHex(t, v, "chacha_nonce_hex"))
	if err != nil {
		t.Fatal(err)
	}
	plain := vecHex(t, v, "chacha_plain_hex")
	out := make([]byte, len(plain))
	ciph.XORKeyStream(out, plain)
	if got := hex.EncodeToString(out); got != vecStr(t, v, "chacha_cipher_hex") {
		t.Fatalf("chacha20 cipher %s", got)
	}
}
