// Package message defines Kengele's signed, encrypted events and the
// compact wire format that carries them over Nostr, SMS or any other path.
//
// Every event is signed by its sender (BIP-340 schnorr, the same keys Nostr
// uses), then encrypted separately to each recipient with NIP-44 v2. The
// encrypted form is always the same size whatever the event kind, so a
// carrier cannot tell a duress check-in or a panic from a normal check-in.
//
// The layout is described in docs/message-format.md.
package message

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"time"

	"github.com/btcsuite/btcd/btcec/v2"
	"github.com/btcsuite/btcd/btcec/v2/schnorr"
	"github.com/nbd-wtf/go-nostr/nip44"
)

// Kind is the type of an event. It travels only inside the encryption.
type Kind uint8

// Event kinds. Values are part of the wire format and must never change.
const (
	KindCheckIn         Kind = 1 // activist: "I am safe"
	KindDuressCheckIn   Kind = 2 // activist, forced: looks like a check-in to everyone but guardians
	KindPanic           Kind = 3 // activist: panic trigger
	KindMissed          Kind = 4 // guardian: a check-in deadline passed
	KindGuardianConfirm Kind = 5 // guardian: confirms the alarm (towards 2 of 3)
	KindAllClear        Kind = 6 // activist or guardian: found safe, stand down
	KindGoingDark       Kind = 7 // activist: pre-arming before a shutdown
)

var kindNames = map[Kind]string{
	KindCheckIn:         "check-in",
	KindDuressCheckIn:   "duress-check-in",
	KindPanic:           "panic",
	KindMissed:          "missed",
	KindGuardianConfirm: "guardian-confirm",
	KindAllClear:        "all-clear",
	KindGoingDark:       "going-dark",
}

// String returns the kind's name as used in the docs and issues.
func (k Kind) String() string {
	if n, ok := kindNames[k]; ok {
		return n
	}
	return fmt.Sprintf("unknown(%d)", uint8(k))
}

// Valid reports whether k is a known kind.
func (k Kind) Valid() bool {
	_, ok := kindNames[k]
	return ok
}

// Sizes of the format. Changing any of them needs a new Version.
const (
	// Version is the first byte of every wire message.
	Version byte = 1

	// MaxBodySize is the largest body an event can carry, in bytes.
	MaxBodySize = 86

	// innerSize is the signed plaintext: kind, time, seq, body length,
	// body (zero padded) and the 64-byte signature.
	innerSize  = 1 + 4 + 4 + 1 + MaxBodySize + schnorr.SignatureSize
	signedSize = innerSize - schnorr.SignatureSize

	// nip44Size is the NIP-44 v2 payload for a fixed innerSize plaintext:
	// version + nonce + (2-byte length + padded plaintext) + mac.
	nip44Size = 1 + 32 + 2 + innerSize + 32

	// WireSize is the length of every encoded message in bytes.
	WireSize = 1 + nip44Size

	// TextSize is the length of the SMS-safe text form in characters.
	TextSize = (WireSize*8 + 5) / 6

	// MaxSMSChars is two concatenated GSM-7 SMS segments (2 x 153).
	MaxSMSChars = 306

	// MaxClockSkew is how far in the future a CreatedAt may be before
	// Open rejects the event.
	MaxClockSkew = 10 * time.Minute
)

// signTag separates Kengele signatures from Nostr event signatures and any
// other protocol that uses the same keys (BIP-340 tagged hash).
const signTag = "kengele/event/v1"

// Errors returned by Open and DecodeText. Callers should treat them all as
// "drop this message"; they differ only to help debugging and tests.
var (
	ErrVersion   = errors.New("message: unknown version")
	ErrSize      = errors.New("message: wrong size")
	ErrEncoding  = errors.New("message: invalid text encoding")
	ErrNoSender  = errors.New("message: not addressed to this key by any known sender")
	ErrSignature = errors.New("message: bad signature")
	ErrKind      = errors.New("message: unknown kind")
	ErrBody      = errors.New("message: invalid body or padding")
	ErrTime      = errors.New("message: invalid or future timestamp")
)

// Event is one Kengele message before signing or after opening.
type Event struct {
	Kind Kind
	// CreatedAt is the sender's original time, in whole seconds. It is
	// signed, so it survives store-and-forward unchanged.
	CreatedAt time.Time
	// Seq increases with every event a sender creates. Receivers drop a
	// (Signer, Seq) pair they have already seen; see docs/message-format.md.
	Seq uint32
	// Body is optional free data (for example the place the activist
	// typed), at most MaxBodySize bytes.
	Body []byte
	// Signer is the sender's x-only public key. Open fills it in; it is
	// ignored when sealing.
	Signer [32]byte
}

// PublicKey returns the 32-byte x-only (Nostr-style) public key of priv.
func PublicKey(priv *btcec.PrivateKey) [32]byte {
	var pk [32]byte
	copy(pk[:], schnorr.SerializePubKey(priv.PubKey()))
	return pk
}

// Seal signs e with sender's key and encrypts it to recipient. The signature
// is the same for every recipient; only the encryption differs.
func Seal(e Event, sender *btcec.PrivateKey, recipient [32]byte) ([]byte, error) {
	return seal(e, sender, recipient, nil)
}

// SealAll seals e once per recipient, in the same order.
func SealAll(e Event, sender *btcec.PrivateKey, recipients [][32]byte) ([][]byte, error) {
	inner, err := sign(e, sender)
	if err != nil {
		return nil, err
	}
	out := make([][]byte, 0, len(recipients))
	for _, r := range recipients {
		w, err := encrypt(inner, sender, r, nil)
		if err != nil {
			return nil, err
		}
		out = append(out, w)
	}
	return out, nil
}

func seal(e Event, sender *btcec.PrivateKey, recipient [32]byte, nonce []byte) ([]byte, error) {
	inner, err := sign(e, sender)
	if err != nil {
		return nil, err
	}
	return encrypt(inner, sender, recipient, nonce)
}

// sign builds the fixed-size signed plaintext.
func sign(e Event, sender *btcec.PrivateKey) ([]byte, error) {
	if sender == nil {
		return nil, errors.New("message: nil sender key")
	}
	if !e.Kind.Valid() {
		return nil, ErrKind
	}
	if len(e.Body) > MaxBodySize {
		return nil, ErrBody
	}
	ts := e.CreatedAt.Unix()
	if ts <= 0 || ts > int64(^uint32(0)) {
		return nil, ErrTime
	}

	inner := make([]byte, innerSize)
	inner[0] = byte(e.Kind)
	binary.BigEndian.PutUint32(inner[1:5], uint32(ts))
	binary.BigEndian.PutUint32(inner[5:9], e.Seq)
	inner[9] = byte(len(e.Body))
	copy(inner[10:10+MaxBodySize], e.Body)

	h := signedHash(inner[:signedSize])
	sig, err := schnorr.Sign(sender, h[:])
	if err != nil {
		return nil, fmt.Errorf("message: sign: %w", err)
	}
	copy(inner[signedSize:], sig.Serialize())
	return inner, nil
}

func encrypt(inner []byte, sender *btcec.PrivateKey, recipient [32]byte, nonce []byte) ([]byte, error) {
	ck, err := conversationKey(sender, recipient)
	if err != nil {
		return nil, err
	}
	var b64 string
	if nonce != nil {
		b64, err = nip44.Encrypt(string(inner), ck, nip44.WithCustomNonce(nonce))
	} else {
		b64, err = nip44.Encrypt(string(inner), ck)
	}
	if err != nil {
		return nil, fmt.Errorf("message: encrypt: %w", err)
	}
	payload, err := base64.StdEncoding.DecodeString(b64)
	if err != nil {
		return nil, fmt.Errorf("message: encrypt: %w", err)
	}
	if len(payload) != nip44Size {
		return nil, ErrSize
	}
	wire := make([]byte, 0, WireSize)
	wire = append(wire, Version)
	wire = append(wire, payload...)
	return wire, nil
}

// Open decrypts wire with the recipient's key, trying each known sender
// (the wire format carries no sender key, so carriers cannot see who sent
// it). It verifies the signature, checks every field strictly and rejects
// events dated more than MaxClockSkew after now.
func Open(wire []byte, recipient *btcec.PrivateKey, senders [][32]byte, now time.Time) (*Event, error) {
	if len(wire) != WireSize {
		return nil, ErrSize
	}
	if wire[0] != Version {
		return nil, ErrVersion
	}
	if recipient == nil {
		return nil, errors.New("message: nil recipient key")
	}
	b64 := base64.StdEncoding.EncodeToString(wire[1:])

	for _, s := range senders {
		ck, err := conversationKey(recipient, s)
		if err != nil {
			continue
		}
		plain, err := nip44.Decrypt(b64, ck)
		if err != nil {
			continue // wrong sender or altered message: try the next key
		}
		return parse([]byte(plain), s, now)
	}
	return nil, ErrNoSender
}

func parse(inner []byte, signer [32]byte, now time.Time) (*Event, error) {
	if len(inner) != innerSize {
		return nil, ErrSize
	}
	pub, err := schnorr.ParsePubKey(signer[:])
	if err != nil {
		return nil, ErrSignature
	}
	sig, err := schnorr.ParseSignature(inner[signedSize:])
	if err != nil {
		return nil, ErrSignature
	}
	h := signedHash(inner[:signedSize])
	if !sig.Verify(h[:], pub) {
		return nil, ErrSignature
	}

	k := Kind(inner[0])
	if !k.Valid() {
		return nil, ErrKind
	}
	ts := binary.BigEndian.Uint32(inner[1:5])
	created := time.Unix(int64(ts), 0).UTC()
	if ts == 0 || created.After(now.Add(MaxClockSkew)) {
		return nil, ErrTime
	}
	n := int(inner[9])
	if n > MaxBodySize {
		return nil, ErrBody
	}
	for _, b := range inner[10+n : 10+MaxBodySize] {
		if b != 0 {
			return nil, ErrBody
		}
	}

	e := &Event{
		Kind:      k,
		CreatedAt: created,
		Seq:       binary.BigEndian.Uint32(inner[5:9]),
		Signer:    signer,
	}
	if n > 0 {
		e.Body = append([]byte(nil), inner[10:10+n]...)
	}
	return e, nil
}

// EncodeText turns a wire message into the SMS-safe text form: unpadded
// base64url, whose alphabet is entirely in the GSM-7 basic character set.
func EncodeText(wire []byte) string {
	return base64.RawURLEncoding.EncodeToString(wire)
}

// DecodeText parses the SMS text form strictly. Whitespace that some phones
// add when joining segments is not accepted; the transport must strip it.
func DecodeText(s string) ([]byte, error) {
	if len(s) != TextSize {
		return nil, ErrSize
	}
	wire, err := base64.RawURLEncoding.Strict().DecodeString(s)
	if err != nil {
		return nil, ErrEncoding
	}
	if len(wire) != WireSize {
		return nil, ErrSize
	}
	return wire, nil
}

func conversationKey(priv *btcec.PrivateKey, peer [32]byte) ([32]byte, error) {
	ck, err := nip44.GenerateConversationKey(hex.EncodeToString(peer[:]), hex.EncodeToString(priv.Serialize()))
	if err != nil {
		return ck, fmt.Errorf("message: conversation key: %w", err)
	}
	return ck, nil
}

// signedHash is the BIP-340 tagged hash of the signed part of the plaintext.
func signedHash(data []byte) [32]byte {
	tag := sha256.Sum256([]byte(signTag))
	h := sha256.New()
	h.Write(tag[:])
	h.Write(tag[:])
	h.Write(data)
	var out [32]byte
	copy(out[:], h.Sum(nil))
	return out
}
