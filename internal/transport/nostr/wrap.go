// Package nostr carries Kengele wire messages (internal/message) over Nostr
// relays as NIP-17 private messages inside NIP-59 gift wraps.
//
// A relay, or anyone watching it, sees only a kind 1059 event signed by a
// one-time key, addressed to the guardian's inbox key, with a created_at
// pushed up to two days into the past. The sender's key, the real time and
// the message all stay inside two layers of NIP-44 encryption.
//
// Status: design stage. Not for real use until the threat model and an
// independent security review are done. See docs/nostr-transport.md.
package nostr

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	"time"

	"github.com/altradits/kengele/internal/message"
	"github.com/btcsuite/btcd/btcec/v2"
	gn "github.com/nbd-wtf/go-nostr"
	"github.com/nbd-wtf/go-nostr/nip44"
)

// MaxJitter is how far into the past a seal's and a gift wrap's created_at
// can be moved. NIP-59 suggests up to two days.
const MaxJitter = 48 * time.Hour

var (
	// ErrNotForUs means the gift wrap is addressed to another key.
	ErrNotForUs = errors.New("nostr: gift wrap is not addressed to this key")
	// ErrMalformed covers a bad signature, a wrong kind, a sender mismatch
	// between seal and rumor, or content that is not a Kengele message.
	ErrMalformed = errors.New("nostr: malformed gift wrap")
)

// Wrap puts a Kengele wire message into a NIP-17 rumor (kind 14), seals it
// with the sender's key (kind 13) and gift-wraps it with a fresh one-time key
// (kind 1059) addressed to recipient.
//
// now is the real time; it only appears inside the encrypted rumor. rnd
// supplies the timestamp jitter; nil means crypto/rand.
func Wrap(wire []byte, sender *btcec.PrivateKey, recipient [32]byte, now time.Time, rnd io.Reader) (gn.Event, error) {
	if sender == nil {
		return gn.Event{}, errors.New("nostr: no sender key")
	}
	if rnd == nil {
		rnd = rand.Reader
	}
	// Refuse to wrap anything that is not a well-formed Kengele message, so
	// this transport can never be used to leak other content.
	if len(wire) != message.WireSize || wire[0] != message.Version {
		return gn.Event{}, fmt.Errorf("nostr: wrap: %w", message.ErrSize)
	}

	senderSec := hex.EncodeToString(sender.Serialize())
	senderPub := message.PublicKey(sender)
	senderPubHex := hex.EncodeToString(senderPub[:])
	recipientHex := hex.EncodeToString(recipient[:])

	rumor := gn.Event{
		Kind:      gn.KindDirectMessage,
		PubKey:    senderPubHex,
		CreatedAt: gn.Timestamp(now.Unix()),
		Tags:      gn.Tags{{"p", recipientHex}},
		Content:   message.EncodeText(wire),
	}
	rumor.ID = rumor.GetID()

	ck, err := nip44.GenerateConversationKey(recipientHex, senderSec)
	if err != nil {
		return gn.Event{}, fmt.Errorf("nostr: wrap: %w", err)
	}
	sealContent, err := nip44.Encrypt(rumor.String(), ck)
	if err != nil {
		return gn.Event{}, fmt.Errorf("nostr: wrap: %w", err)
	}
	sealAt, err := jitter(now, rnd)
	if err != nil {
		return gn.Event{}, err
	}
	seal := gn.Event{
		Kind:      gn.KindSeal,
		CreatedAt: sealAt,
		Tags:      gn.Tags{},
		Content:   sealContent,
	}
	if err := seal.Sign(senderSec); err != nil {
		return gn.Event{}, fmt.Errorf("nostr: wrap: %w", err)
	}

	oneTime := gn.GeneratePrivateKey()
	ck2, err := nip44.GenerateConversationKey(recipientHex, oneTime)
	if err != nil {
		return gn.Event{}, fmt.Errorf("nostr: wrap: %w", err)
	}
	wrapContent, err := nip44.Encrypt(seal.String(), ck2)
	if err != nil {
		return gn.Event{}, fmt.Errorf("nostr: wrap: %w", err)
	}
	wrapAt, err := jitter(now, rnd)
	if err != nil {
		return gn.Event{}, err
	}
	gw := gn.Event{
		Kind:      gn.KindGiftWrap,
		CreatedAt: wrapAt,
		Tags:      gn.Tags{{"p", recipientHex}},
		Content:   wrapContent,
	}
	if err := gw.Sign(oneTime); err != nil {
		return gn.Event{}, fmt.Errorf("nostr: wrap: %w", err)
	}
	return gw, nil
}

// Unwrap opens a gift wrap addressed to recipient and returns the Kengele
// wire message and the key that signed the seal. It does not check the
// Kengele signature inside the wire message: call message.Open for that.
func Unwrap(gw *gn.Event, recipient *btcec.PrivateKey) (wire []byte, sealer [32]byte, err error) {
	if gw == nil || recipient == nil {
		return nil, sealer, ErrMalformed
	}
	own := message.PublicKey(recipient)
	ownHex := hex.EncodeToString(own[:])
	if gw.Kind != gn.KindGiftWrap {
		return nil, sealer, ErrMalformed
	}
	if !addressedTo(gw.Tags, ownHex) {
		return nil, sealer, ErrNotForUs
	}
	if ok, _ := gw.CheckSignature(); !ok {
		return nil, sealer, ErrMalformed
	}
	sec := hex.EncodeToString(recipient.Serialize())

	sealJSON, err := decrypt(gw.Content, gw.PubKey, sec)
	if err != nil {
		return nil, sealer, ErrMalformed
	}
	var seal gn.Event
	if err := json.Unmarshal([]byte(sealJSON), &seal); err != nil {
		return nil, sealer, ErrMalformed
	}
	if seal.Kind != gn.KindSeal {
		return nil, sealer, ErrMalformed
	}
	if ok, _ := seal.CheckSignature(); !ok {
		return nil, sealer, ErrMalformed
	}

	rumorJSON, err := decrypt(seal.Content, seal.PubKey, sec)
	if err != nil {
		return nil, sealer, ErrMalformed
	}
	var rumor gn.Event
	if err := json.Unmarshal([]byte(rumorJSON), &rumor); err != nil {
		return nil, sealer, ErrMalformed
	}
	// NIP-17: the rumor's author must be the seal's signer, or anyone could
	// seal a rumor that claims to come from someone else.
	if rumor.Kind != gn.KindDirectMessage || rumor.PubKey != seal.PubKey || rumor.Sig != "" {
		return nil, sealer, ErrMalformed
	}
	if !addressedTo(rumor.Tags, ownHex) {
		return nil, sealer, ErrNotForUs
	}
	wire, err = message.DecodeText(rumor.Content)
	if err != nil {
		return nil, sealer, ErrMalformed
	}
	b, err := hex.DecodeString(seal.PubKey)
	if err != nil || len(b) != 32 {
		return nil, sealer, ErrMalformed
	}
	copy(sealer[:], b)
	return wire, sealer, nil
}

func decrypt(content, peerHex, sec string) (string, error) {
	ck, err := nip44.GenerateConversationKey(peerHex, sec)
	if err != nil {
		return "", err
	}
	return nip44.Decrypt(content, ck)
}

func addressedTo(tags gn.Tags, pubHex string) bool {
	for _, t := range tags {
		if len(t) >= 2 && t[0] == "p" && t[1] == pubHex {
			return true
		}
	}
	return false
}

// jitter returns now moved back by a uniformly random amount in [0, MaxJitter).
func jitter(now time.Time, rnd io.Reader) (gn.Timestamp, error) {
	n, err := rand.Int(rnd, big.NewInt(int64(MaxJitter/time.Second)))
	if err != nil {
		return 0, fmt.Errorf("nostr: jitter: %w", err)
	}
	return gn.Timestamp(now.Unix() - n.Int64()), nil
}
