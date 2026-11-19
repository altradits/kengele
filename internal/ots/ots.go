// Package ots anchors alarm-level Kengele events to Bitcoin with
// OpenTimestamps, so the habeas corpus packet (#12) can show a court when
// an alarm event existed.
//
// A guardian's device hashes each alarm-level event (see AlarmLevel) and
// submits only the hash to public calendar servers. The calendars return
// pending proofs, which the store keeps and later upgrades to Bitcoin
// block attestations. Proofs are standard detached .ots files: they open
// in the `ots` tool (`ots verify`) and need no Kengele software to check.
//
// Privacy: only 32 opaque bytes per event — a salted hash, never the
// event — leave the device (threat model A5, network observers). The salt
// (a random nonce, recorded inside the proof) stops a curious calendar
// from testing guesses like "did this activist panic at this hour?". A
// submission still reveals timing metadata: that someone timestamped
// something at that moment.
//
// Offline use: Anchor always records the event locally first and queues
// it; Sync submits the queue and refreshes pending proofs when the
// network returns. This package runs no calendar server (out of scope).
//
// Security notes (docs/threat-model.md): this package implements the
// "OpenTimestamps | Disputed timing in court" row of section 5. Timestamps
// before anchoring are claims (assumption 6, residual risk 10): Verify
// reports whether a proof is still pending or is anchored in a block, and
// callers must treat pending proofs as unconfirmed.
package ots

import (
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"fmt"
	"time"

	"github.com/altradits/kengele/internal/message"
)

// DefaultCalendars are the public aggregation endpoints the reference
// client submits to.
var DefaultCalendars = []string{
	"https://a.pool.opentimestamps.org",
	"https://b.pool.opentimestamps.org",
	"https://a.pool.eternitywall.com",
	"https://ots.btc.catallaxy.com",
}

// NonceSize is the random salt prepended to each digest before it leaves
// the device, as in the reference client.
const NonceSize = 16

var (
	// ErrUnknown is returned for a digest the store has never seen.
	ErrUnknown = errors.New("ots: unknown digest")
	// ErrMismatch is returned when a stored proof does not match its event.
	ErrMismatch = errors.New("ots: proof does not match its event")
	// ErrNotFound is returned when a calendar has no such commitment (yet).
	ErrNotFound = errors.New("ots: calendar has no such commitment")
)

// AlarmLevel reports whether events of this kind are anchored. Routine
// check-ins and going-dark notices are high-volume and rarely disputed,
// so they are skipped; everything in the alarm chain — raising it,
// confirming it, standing it down — is anchored for the habeas packet.
func AlarmLevel(k message.Kind) bool {
	switch k {
	case message.KindDuressCheckIn,
		message.KindPanic,
		message.KindMissed,
		message.KindGuardianConfirm,
		message.KindAllClear:
		return true
	default:
		return false
	}
}

// canonicalDomain versions the canonical encoding below.
const canonicalDomain = "kengele/ots/v1"

// Canonical encodes an opened event into the bytes whose SHA-256 is
// anchored. The encoding is recipient-independent (unlike the wire
// ciphertext, which differs per guardian), so every guardian anchors the
// same digest for the same event, and Export writes these bytes next to
// the .ots file for `ots verify`.
func Canonical(e *message.Event) []byte {
	b := make([]byte, 0, len(canonicalDomain)+1+4+4+32+len(e.Body))
	b = append(b, canonicalDomain...)
	b = append(b, byte(e.Kind))
	var tmp [4]byte
	binary.BigEndian.PutUint32(tmp[:], uint32(e.CreatedAt.Unix()))
	b = append(b, tmp[:]...)
	binary.BigEndian.PutUint32(tmp[:], e.Seq)
	b = append(b, tmp[:]...)
	b = append(b, e.Signer[:]...)
	return append(b, e.Body...)
}

// Digest returns the SHA-256 of the canonical event encoding.
func Digest(e *message.Event) [32]byte {
	return sha256.Sum256(Canonical(e))
}

// Commitment salts a digest with a fresh nonce. The commitment is what
// is submitted to calendars; the nonce stays inside the proof, so a
// calendar that guesses event contents cannot match them to submissions.
func Commitment(digest [32]byte, nonce []byte) [32]byte {
	h := sha256.New()
	h.Write(digest[:])
	h.Write(nonce)
	var out [32]byte
	copy(out[:], h.Sum(nil))
	return out
}

// Status is where one event's proof stands.
type Status int

const (
	// StatusSkipped: a routine event Anchor recorded nothing for.
	StatusSkipped Status = iota
	// StatusQueued: recorded locally, still waiting for a network.
	StatusQueued
	// StatusPending: submitted; waiting for a Bitcoin confirmation.
	StatusPending
	// StatusAnchored: a Bitcoin block attests to the event.
	StatusAnchored
)

var statusNames = [...]string{"skipped", "queued", "pending", "anchored"}

func (s Status) String() string {
	if int(s) < len(statusNames) {
		return statusNames[s]
	}
	return fmt.Sprintf("status(%d)", int(s))
}

// StatusLine is the guardian-facing line for a status, in Swahili and
// English.
func StatusLine(s Status) string {
	switch s {
	case StatusQueued:
		return "Muhuri wa muda umepangwa, unasubiri mtandao / Timestamp queued, waiting for network"
	case StatusPending:
		return "Muhuri wa muda umetumwa, unasubiri Bitcoin / Timestamp submitted, waiting for Bitcoin"
	case StatusAnchored:
		return "Muhuri wa muda umethibitishwa na Bitcoin / Timestamp confirmed by Bitcoin"
	default:
		return "Haijatiwa muhuri / Not timestamped"
	}
}

// Report describes one event's proof.
type Report struct {
	Digest       [32]byte
	Kind         message.Kind
	CreatedAt    time.Time
	Status       Status
	Bitcoin      []uint32 // block heights attesting, ascending
	PendingFrom  []string // calendar URLs with outstanding pending stamps
	Calendars    []string // calendars the commitment was submitted to
	ProofMissing bool     // queued with no proof bytes yet
}

// Summary is the guardian-facing one-liner, in Swahili and English.
func (r Report) Summary() string {
	switch r.Status {
	case StatusAnchored:
		return fmt.Sprintf("Muhuri wa muda umethibitishwa na Bitcoin (kizuizi %d) / Timestamp confirmed by Bitcoin (block %d)", r.Bitcoin[0], r.Bitcoin[0])
	default:
		return StatusLine(r.Status)
	}
}
