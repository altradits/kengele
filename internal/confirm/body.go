package confirm

import (
	"encoding/binary"
	"errors"
	"time"

	"github.com/altradits/kengele/internal/message"
)

// Body layouts. They travel inside a signed, encrypted message.Event, so
// only guardians ever see them.
//
//	guardian-confirm: 0x01 | activist (32) | epoch (4) | share (33)  = 70 bytes
//	all-clear:        0x02 | activist (32) | epoch (4)               = 37 bytes
//
// The epoch numbers alarms for one activist. It starts at 1 and goes up by
// one each time an alarm ends with an all-clear, so a confirmation or an
// all-clear from an earlier alarm can never count towards a later one.
const (
	bodyConfirm  byte = 0x01
	bodyAllClear byte = 0x02

	confirmBodySize  = 1 + 32 + 4 + shareSize
	allClearBodySize = 1 + 32 + 4
)

// ErrBody is returned for a guardian-confirm or all-clear with a bad body.
var ErrBody = errors.New("confirm: invalid confirmation body")

type confirmBody struct {
	activist [32]byte
	epoch    uint32
	share    []byte
}

type allClearBody struct {
	activist [32]byte
	epoch    uint32
}

func encodeConfirm(activist [32]byte, epoch uint32, share []byte) []byte {
	b := make([]byte, 0, confirmBodySize)
	b = append(b, bodyConfirm)
	b = append(b, activist[:]...)
	b = binary.BigEndian.AppendUint32(b, epoch)
	return append(b, share...)
}

func encodeAllClear(activist [32]byte, epoch uint32) []byte {
	b := make([]byte, 0, allClearBodySize)
	b = append(b, bodyAllClear)
	b = append(b, activist[:]...)
	return binary.BigEndian.AppendUint32(b, epoch)
}

func decodeConfirm(b []byte) (confirmBody, error) {
	var c confirmBody
	if len(b) != confirmBodySize || b[0] != bodyConfirm {
		return c, ErrBody
	}
	copy(c.activist[:], b[1:33])
	c.epoch = binary.BigEndian.Uint32(b[33:37])
	c.share = append([]byte(nil), b[37:]...)
	if c.epoch == 0 {
		return c, ErrBody
	}
	return c, nil
}

func decodeAllClear(b []byte) (allClearBody, error) {
	var c allClearBody
	if len(b) != allClearBodySize || b[0] != bodyAllClear {
		return c, ErrBody
	}
	copy(c.activist[:], b[1:33])
	c.epoch = binary.BigEndian.Uint32(b[33:37])
	if c.epoch == 0 {
		return c, ErrBody
	}
	return c, nil
}

// ConfirmEvent builds this guardian's confirmation of the given alarm. It
// carries the guardian's share, so it must be sealed only to the other
// guardians (Tally.Recipients), never to the activist: the activist's phone
// may already be in the captors' hands.
func ConfirmEvent(kit GuardianKit, epoch uint32, seq uint32, now time.Time) (message.Event, error) {
	p, err := kit.parse()
	if err != nil {
		return message.Event{}, err
	}
	if epoch == 0 {
		return message.Event{}, ErrBody
	}
	return message.Event{
		Kind:      message.KindGuardianConfirm,
		CreatedAt: now.UTC().Truncate(time.Second),
		Seq:       seq,
		Body:      encodeConfirm(p.activist, epoch, kit.Share),
	}, nil
}

// AllClearEvent builds the activist's own all-clear for the given alarm.
// Guardians must use GuardianAllClearEvent, which requires a verification.
func AllClearEvent(activist [32]byte, epoch uint32, seq uint32, now time.Time) (message.Event, error) {
	if epoch == 0 {
		return message.Event{}, ErrBody
	}
	return message.Event{
		Kind:      message.KindAllClear,
		CreatedAt: now.UTC().Truncate(time.Second),
		Seq:       seq,
		Body:      encodeAllClear(activist, epoch),
	}, nil
}

// Verification records how a guardian checked that the activist is free
// before sending an all-clear. It stays on the guardian's device and is
// not sent on the wire.
type Verification struct {
	// Method is "voice" or "video".
	Method string
	// At is when the call happened.
	At time.Time
}

// ErrNotVerified means a guardian tried to build an all-clear without
// first speaking to the activist by voice or video.
var ErrNotVerified = errors.New("confirm: speak to the activist by voice or video before sending an all-clear")

// GuardianAllClearEvent builds a guardian's all-clear for the given alarm.
// It refuses unless v records a voice or video call with the activist: the
// guardian app's hook for the check required by ClearStatus.VerifyFirst.
func GuardianAllClearEvent(activist [32]byte, epoch uint32, seq uint32, now time.Time, v Verification) (message.Event, error) {
	if v.Method != "voice" && v.Method != "video" {
		return message.Event{}, ErrNotVerified
	}
	if v.At.IsZero() || v.At.After(now.Add(message.MaxClockSkew)) {
		return message.Event{}, ErrNotVerified
	}
	return AllClearEvent(activist, epoch, seq, now)
}
