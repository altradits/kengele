package message

import (
	"bytes"
	"encoding/hex"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/btcsuite/btcd/btcec/v2"
	"github.com/btcsuite/btcd/btcec/v2/schnorr"
	"github.com/nbd-wtf/go-nostr/nip44"
)

// Made-up test keys. Never use these for anything real.
func key(t *testing.T, h string) *btcec.PrivateKey {
	t.Helper()
	b, err := hex.DecodeString(h)
	if err != nil {
		t.Fatal(err)
	}
	k, _ := btcec.PrivKeyFromBytes(b)
	return k
}

const (
	activistHex  = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"
	guardian1Hex = "b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2"
	guardian2Hex = "c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3"
	strangerHex  = "d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4"
)

var now = time.Date(2026, 1, 15, 14, 7, 0, 0, time.UTC)

var allKinds = []Kind{
	KindCheckIn, KindDuressCheckIn, KindPanic, KindMissed,
	KindGuardianConfirm, KindAllClear, KindGoingDark,
}

// gsm7Basic is the GSM 03.38 default alphabet (basic set, no escapes).
const gsm7Basic = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?" +
	"¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"

func TestRoundTripEveryKind(t *testing.T) {
	activist := key(t, activistHex)
	g1 := key(t, guardian1Hex)
	stranger := key(t, strangerHex)
	senders := [][32]byte{PublicKey(stranger), PublicKey(activist)}

	bodies := map[string][]byte{
		"empty": nil,
		"place": []byte("Kenyatta Ave, Kisumu"),
		"max":   bytes.Repeat([]byte{0xff}, MaxBodySize),
		"utf8":  []byte("Mahakama ya Milimani — saa nane"),
	}
	for _, k := range allKinds {
		for name, body := range bodies {
			t.Run(k.String()+"/"+name, func(t *testing.T) {
				in := Event{Kind: k, CreatedAt: now.Add(-time.Hour), Seq: 42, Body: body}
				wire, err := Seal(in, activist, PublicKey(g1))
				if err != nil {
					t.Fatal(err)
				}
				text := EncodeText(wire)
				back, err := DecodeText(text)
				if err != nil {
					t.Fatal(err)
				}
				got, err := Open(back, g1, senders, now)
				if err != nil {
					t.Fatal(err)
				}
				if got.Kind != k || got.Seq != 42 || !got.CreatedAt.Equal(in.CreatedAt) ||
					!bytes.Equal(got.Body, body) || got.Signer != PublicKey(activist) {
					t.Fatalf("round trip mismatch: %+v", got)
				}
			})
		}
	}
}

func TestSealAllOpensForEachGuardian(t *testing.T) {
	activist := key(t, activistHex)
	gs := []*btcec.PrivateKey{key(t, guardian1Hex), key(t, guardian2Hex)}
	recips := [][32]byte{PublicKey(gs[0]), PublicKey(gs[1])}
	wires, err := SealAll(Event{Kind: KindPanic, CreatedAt: now, Seq: 7}, activist, recips)
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Equal(wires[0], wires[1]) {
		t.Fatal("each guardian must get a different ciphertext")
	}
	for i, g := range gs {
		e, err := Open(wires[i], g, [][32]byte{PublicKey(activist)}, now)
		if err != nil || e.Kind != KindPanic {
			t.Fatalf("guardian %d: %v %+v", i, err, e)
		}
	}
}

func TestSMSTextFitsTwoSegments(t *testing.T) {
	activist := key(t, activistHex)
	g1 := key(t, guardian1Hex)
	for _, k := range allKinds {
		wire, err := Seal(Event{Kind: k, CreatedAt: now, Body: bytes.Repeat([]byte("x"), MaxBodySize)}, activist, PublicKey(g1))
		if err != nil {
			t.Fatal(err)
		}
		text := EncodeText(wire)
		if len(text) > MaxSMSChars || len(text) != TextSize {
			t.Fatalf("%s: %d chars, want %d and <= %d", k, len(text), TextSize, MaxSMSChars)
		}
		for _, r := range text {
			if !strings.ContainsRune(gsm7Basic, r) {
				t.Fatalf("%s: %q is not in the GSM-7 basic set", k, r)
			}
		}
	}
}

func TestEveryKindHasTheSameLength(t *testing.T) {
	activist := key(t, activistHex)
	g1 := PublicKey(key(t, guardian1Hex))
	normal, err := Seal(Event{Kind: KindCheckIn, CreatedAt: now, Body: []byte("x")}, activist, g1)
	if err != nil {
		t.Fatal(err)
	}
	for _, k := range allKinds {
		for _, body := range [][]byte{nil, bytes.Repeat([]byte("y"), MaxBodySize)} {
			w, err := Seal(Event{Kind: k, CreatedAt: now, Body: body}, activist, g1)
			if err != nil {
				t.Fatal(err)
			}
			if len(w) != len(normal) || len(EncodeText(w)) != len(EncodeText(normal)) {
				t.Fatalf("%s with %d-byte body has a different length from a check-in", k, len(body))
			}
		}
	}
}

func TestRejects(t *testing.T) {
	activist := key(t, activistHex)
	g1 := key(t, guardian1Hex)
	g2 := key(t, guardian2Hex)
	fromActivist := [][32]byte{PublicKey(activist)}

	good, err := Seal(Event{Kind: KindCheckIn, CreatedAt: now, Seq: 1}, activist, PublicKey(g1))
	if err != nil {
		t.Fatal(err)
	}
	goodText := EncodeText(good)

	// A plaintext with a valid layout but a signature that does not verify.
	badSig, _ := sign(Event{Kind: KindPanic, CreatedAt: now}, activist)
	badSig[len(badSig)-1] ^= 1
	badSigWire, err := encrypt(badSig, activist, PublicKey(g1), nil)
	if err != nil {
		t.Fatal(err)
	}

	// Guardian 1 shares a NIP-44 conversation key with the activist, so it
	// can produce ciphertext that decrypts "as from the activist". The inner
	// signature is what stops it: an all-clear signed by guardian 1 must not
	// pass as the activist's.
	forged, _ := sign(Event{Kind: KindAllClear, CreatedAt: now}, g1)
	forgedWire, err := encrypt(forged, activist, PublicKey(g1), nil)
	if err != nil {
		t.Fatal(err)
	}

	// Non-zero padding after the body.
	padded, _ := sign(Event{Kind: KindCheckIn, CreatedAt: now, Body: []byte("a")}, activist)
	padded[12] = 'z'
	paddedResigned := resign(t, padded, activist)
	paddedWire, _ := encrypt(paddedResigned, activist, PublicKey(g1), nil)

	// Unknown kind inside a validly signed plaintext.
	unknownKind, _ := sign(Event{Kind: KindCheckIn, CreatedAt: now}, activist)
	unknownKind[0] = 99
	unknownKindWire, _ := encrypt(resign(t, unknownKind, activist), activist, PublicKey(g1), nil)

	future, _ := Seal(Event{Kind: KindCheckIn, CreatedAt: now.Add(MaxClockSkew + time.Minute)}, activist, PublicKey(g1))

	wrongVersion := append([]byte(nil), good...)
	wrongVersion[0] = 2

	altered := []byte(goodText)
	if altered[100] == 'A' {
		altered[100] = 'B'
	} else {
		altered[100] = 'A'
	}

	tests := []struct {
		name string
		run  func() error
		want error
	}{
		{"bad signature", func() error { _, err := Open(badSigWire, g1, fromActivist, now); return err }, ErrSignature},
		{"guardian forges activist", func() error { _, err := Open(forgedWire, g1, fromActivist, now); return err }, ErrSignature},
		{"wrong recipient", func() error { _, err := Open(good, g2, fromActivist, now); return err }, ErrNoSender},
		{"unknown sender", func() error { _, err := Open(good, g1, [][32]byte{PublicKey(g2)}, now); return err }, ErrNoSender},
		{"altered text", func() error {
			w, err := DecodeText(string(altered))
			if err != nil {
				return err
			}
			_, err = Open(w, g1, fromActivist, now)
			return err
		}, ErrNoSender},
		{"truncated text", func() error { _, err := DecodeText(goodText[:len(goodText)-4]); return err }, ErrSize},
		{"text with padding char", func() error { _, err := DecodeText(goodText[:len(goodText)-1] + "="); return err }, ErrEncoding},
		{"unknown version", func() error { _, err := Open(wrongVersion, g1, fromActivist, now); return err }, ErrVersion},
		{"short wire", func() error { _, err := Open(good[:WireSize-1], g1, fromActivist, now); return err }, ErrSize},
		{"future timestamp", func() error { _, err := Open(future, g1, fromActivist, now); return err }, ErrTime},
		{"non-zero padding", func() error { _, err := Open(paddedWire, g1, fromActivist, now); return err }, ErrBody},
		{"unknown kind on open", func() error { _, err := Open(unknownKindWire, g1, fromActivist, now); return err }, ErrKind},
		{"unknown kind on seal", func() error { _, err := Seal(Event{Kind: 0, CreatedAt: now}, activist, PublicKey(g1)); return err }, ErrKind},
		{"body too large", func() error {
			_, err := Seal(Event{Kind: KindCheckIn, CreatedAt: now, Body: make([]byte, MaxBodySize+1)}, activist, PublicKey(g1))
			return err
		}, ErrBody},
		{"zero time", func() error { _, err := Seal(Event{Kind: KindCheckIn}, activist, PublicKey(g1)); return err }, ErrTime},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := tt.run()
			if !errors.Is(err, tt.want) {
				t.Fatalf("got %v, want %v", err, tt.want)
			}
		})
	}
}

// resign replaces the signature on a hand-edited plaintext so that a test
// reaches the field checks rather than stopping at the signature.
func resign(t *testing.T, inner []byte, k *btcec.PrivateKey) []byte {
	t.Helper()
	e := append([]byte(nil), inner...)
	h := signedHash(e[:signedSize])
	sig, err := schnorr.Sign(k, h[:])
	if err != nil {
		t.Fatal(err)
	}
	copy(e[signedSize:], sig.Serialize())
	return e
}

// goldenText is the SMS text form of the golden vector (see TestGoldenVector
// and docs/message-format.md).
const goldenText = "AQIBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAUyZvPhQzh4ngEDhik-Aoido1CqOVI9_xNhkd6hmipmQnA2x0QkKn0zUw8Jg5DvBZU1e8gdqmMXi65zaDewNNnqYJ5Dd1i1gqcxEKeClRfmUg0R9Z_yvuO9ry8VPUB8faT5H2FQlDjEZmQ0CZnh9qSR3rFFD8EDFPQvS0aS1xXXTX5YKaCFjdWmWfDJe1cJ3Ui0bn4W0ObVUFwP3AODrSYCA95H6CVCASuZbGR9E3bLRszlh_vE7gvgVg6XCpukuGZEx"

// TestNIP44OfficialVector checks that the NIP-44 library we depend on
// matches the specification's first valid encrypt_decrypt vector
// (https://github.com/paulmillr/nip44, nip44.vectors.json, v2.valid.encrypt_decrypt[0]).
func TestNIP44OfficialVector(t *testing.T) {
	const (
		sec1     = "0000000000000000000000000000000000000000000000000000000000000001"
		sec2     = "0000000000000000000000000000000000000000000000000000000000000002"
		convKey  = "c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d"
		nonceHex = "0000000000000000000000000000000000000000000000000000000000000001"
		payload  = "AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABee0G5VSK0/9YypIObAtDKfYEAjD35uVkHyB0F4DwrcNaCXlCWZKaArsGrY6M9wnuTMxWfp1RTN9Xga8no+kF5Vsb"
	)
	k2 := key(t, sec2)
	pub2 := PublicKey(k2)
	ck, err := nip44.GenerateConversationKey(hex.EncodeToString(pub2[:]), sec1)
	if err != nil {
		t.Fatal(err)
	}
	if hex.EncodeToString(ck[:]) != convKey {
		t.Fatalf("conversation key %x", ck)
	}
	nonce, _ := hex.DecodeString(nonceHex)
	got, err := nip44.Encrypt("a", ck, nip44.WithCustomNonce(nonce))
	if err != nil {
		t.Fatal(err)
	}
	if got != payload {
		t.Fatalf("payload mismatch:\n got %s\nwant %s", got, payload)
	}
}

// TestGoldenVector pins the exact wire output for made-up keys and a fixed
// NIP-44 nonce. docs/message-format.md quotes this vector; if the format
// changes on purpose, bump Version and update both.
func TestGoldenVector(t *testing.T) {
	activist := key(t, activistHex)
	g1 := key(t, guardian1Hex)
	nonce := bytes.Repeat([]byte{0x01}, 32)
	e := Event{Kind: KindCheckIn, CreatedAt: now, Seq: 1, Body: []byte("Kisumu")}
	wire, err := seal(e, activist, PublicKey(g1), nonce)
	if err != nil {
		t.Fatal(err)
	}
	text := EncodeText(wire)
	if text != goldenText {
		t.Fatalf("golden vector changed:\n%s", text)
	}
	got, err := Open(wire, g1, [][32]byte{PublicKey(activist)}, now)
	if err != nil || got.Kind != KindCheckIn || string(got.Body) != "Kisumu" {
		t.Fatalf("golden vector does not open: %v %+v", err, got)
	}
}
