package message

import (
	"encoding/binary"
	"errors"
	"fmt"
	"time"
	"unicode/utf8"
)

// Going-dark (kind 7) events are how an activist pre-arms the guardians
// before an expected shutdown: they carry the current watch plan, how long
// the activist expects to be dark, the check-in window they ask for once it
// ends, a commitment to the pre-written encrypted alert, and a reference to
// the written shutdown plan. See docs/shutdown-mode.md.
//
// The body layout travels inside the signed, encrypted event, so only
// guardians ever see it:
//
//	0:      version 0x01
//	1:5     dark seconds: how long the activist expects to be dark,
//	        60 to 604800 (1 minute to 7 days)
//	5:9     return-window seconds: check-in window after the dark
//	        period ends, 60 to 604800
//	9:13    plan start, Unix seconds
//	13:17   plan end, Unix seconds, after the start
//	17:21   plan check-in interval, seconds, positive
//	21:25   plan grace, seconds
//	25:29   plan reminder, seconds, shorter than the interval
//	29:61   alert hash: SHA-256 of the canonical encrypted alert blob,
//	        sent alongside over Nostr (#8) or already held in the
//	        guardian's confirmation kit (#11)
//	61      plan transports bitmask: bit 0 Nostr, bit 1 SMS, never 0
//	62      shutdown-plan reference length N, 0 to 23
//	63:63+N shutdown-plan reference: an opaque short code for the
//	        written plan (who checks on whom, where, when), for
//	        example a meeting-point code
//
// The full plan and the encrypted alert do not fit in 86 bytes, so the
// body carries the plan's window (which the guardian compares against the
// encrypted plan it already holds) and the alert's hash (which the guardian
// compares against the sealed alert in its kit). A mismatch means the
// guardian must fetch the newer plan or alert over a channel it trusts;
// the watcher never adopts them from this message alone.
//
// Threat model (docs/threat-model.md): a going-dark lasts at most 7 days
// because a longer silence without renewal must fail safe towards the
// alarm (§4.1, A6 "Shut down the internet"); it never downgrades a missed
// deadline or an alarm, so forcing one under coercion buys at most a
// bounded pause of a running clock (§4.1, A3). Like every kind it is the
// same size on the wire, so a carrier cannot tell it from a check-in,
// though the fact that a message was sent is still visible (§2, check-in
// timing metadata).
const GoingDarkVersion byte = 0x01

const (
	// goingDarkFixed is the body size with an empty shutdown reference.
	goingDarkFixed = 63

	// MaxShutdownRef is the longest shutdown-plan reference, in bytes.
	MaxShutdownRef = MaxBodySize - goingDarkFixed

	// MaxGoingDark bounds one going-dark: 7 days. A longer shutdown
	// needs a renewal, which needs the activist's signing key again.
	MaxGoingDark = 7 * 24 * time.Hour

	// minGoingDarkWindow is the shortest dark or return window: anything
	// shorter pauses nothing.
	minGoingDarkWindow = time.Minute
)

// Plan transports in a going-dark body, as a bitmask. The bits follow
// internal/transport.Kind names ("nostr", "sms"); the mesh bit is reserved
// for #20 and rejected until then.
const (
	GoingDarkNostr byte = 1 << 0
	GoingDarkSMS   byte = 1 << 1
)

// ErrGoingDark is returned for a going-dark event with a bad body. Like
// every message error it means "drop this message".
var ErrGoingDark = errors.New("message: invalid going-dark body")

// GoingDark is the decoded body of a kind 7 event.
type GoingDark struct {
	// Dark is how long the activist expects to be dark. The watcher's
	// shutdown mode for this activist ends at receipt plus Dark, at most
	// MaxGoingDark later, unless a newer going-dark renews it.
	Dark time.Duration
	// ReturnWindow is the check-in window the activist asks for once the
	// dark period ends.
	ReturnWindow time.Duration
	// PlanStart and PlanEnd bound the activist's current watch window.
	PlanStart, PlanEnd time.Time
	// Interval, Grace and Reminder are the plan's check-in schedule.
	Interval, Grace, Reminder time.Duration
	// Transports is the plan's routes as a GoingDarkNostr|GoingDarkSMS mask.
	Transports byte
	// AlertHash commits to the pre-written encrypted alert (see above).
	AlertHash [32]byte
	// ShutdownRef points at the written shutdown plan (see above).
	ShutdownRef string
}

// Check reports whether g is a valid going-dark payload.
func (g GoingDark) Check() error {
	if g.Dark < minGoingDarkWindow || g.Dark > MaxGoingDark || g.Dark%time.Second != 0 {
		return fmt.Errorf("%w: dark window must be 1 minute to 7 days in whole seconds", ErrGoingDark)
	}
	if g.ReturnWindow < minGoingDarkWindow || g.ReturnWindow > MaxGoingDark || g.ReturnWindow%time.Second != 0 {
		return fmt.Errorf("%w: return window must be 1 minute to 7 days in whole seconds", ErrGoingDark)
	}
	start, end := g.PlanStart.Unix(), g.PlanEnd.Unix()
	if start <= 0 || start > int64(^uint32(0)) || g.PlanStart.Nanosecond() != 0 {
		return fmt.Errorf("%w: plan start is not a whole Unix second", ErrGoingDark)
	}
	if end <= 0 || end > int64(^uint32(0)) || g.PlanEnd.Nanosecond() != 0 {
		return fmt.Errorf("%w: plan end is not a whole Unix second", ErrGoingDark)
	}
	if end <= start {
		return fmt.Errorf("%w: plan end must be after plan start", ErrGoingDark)
	}
	if g.Interval <= 0 || g.Interval%time.Second != 0 {
		return fmt.Errorf("%w: plan interval must be positive in whole seconds", ErrGoingDark)
	}
	if g.Grace < 0 || g.Grace%time.Second != 0 || g.Reminder < 0 || g.Reminder%time.Second != 0 {
		return fmt.Errorf("%w: plan grace and reminder must not be negative and must be whole seconds", ErrGoingDark)
	}
	if g.Reminder >= g.Interval {
		return fmt.Errorf("%w: plan reminder must be shorter than the interval", ErrGoingDark)
	}
	if g.Transports == 0 || g.Transports&^(GoingDarkNostr|GoingDarkSMS) != 0 {
		return fmt.Errorf("%w: plan transports must be nostr and/or sms", ErrGoingDark)
	}
	if len(g.ShutdownRef) > MaxShutdownRef || !utf8.ValidString(g.ShutdownRef) {
		return fmt.Errorf("%w: shutdown-plan reference must be valid text of at most %d bytes", ErrGoingDark, MaxShutdownRef)
	}
	return nil
}

// GoingDarkEvent builds the activist's going-dark event for seq at time now.
func GoingDarkEvent(g GoingDark, seq uint32, now time.Time) (Event, error) {
	if err := g.Check(); err != nil {
		return Event{}, err
	}
	if now.IsZero() {
		return Event{}, ErrTime
	}
	return Event{
		Kind:      KindGoingDark,
		CreatedAt: now.UTC().Truncate(time.Second),
		Seq:       seq,
		Body:      g.encode(),
	}, nil
}

// ParseGoingDark decodes the body of a going-dark event.
func ParseGoingDark(e Event) (GoingDark, error) {
	var g GoingDark
	if e.Kind != KindGoingDark {
		return g, fmt.Errorf("%w: kind is %s", ErrGoingDark, e.Kind)
	}
	b := e.Body
	if len(b) < goingDarkFixed || len(b) > MaxBodySize || b[0] != GoingDarkVersion {
		return g, fmt.Errorf("%w: bad version or size %d", ErrGoingDark, len(b))
	}
	n := int(b[62])
	if len(b) != goingDarkFixed+n {
		return g, fmt.Errorf("%w: reference length %d does not match size %d", ErrGoingDark, n, len(b))
	}
	g.Dark = time.Duration(binary.BigEndian.Uint32(b[1:5])) * time.Second
	g.ReturnWindow = time.Duration(binary.BigEndian.Uint32(b[5:9])) * time.Second
	g.PlanStart = time.Unix(int64(binary.BigEndian.Uint32(b[9:13])), 0).UTC()
	g.PlanEnd = time.Unix(int64(binary.BigEndian.Uint32(b[13:17])), 0).UTC()
	g.Interval = time.Duration(binary.BigEndian.Uint32(b[17:21])) * time.Second
	g.Grace = time.Duration(binary.BigEndian.Uint32(b[21:25])) * time.Second
	g.Reminder = time.Duration(binary.BigEndian.Uint32(b[25:29])) * time.Second
	copy(g.AlertHash[:], b[29:61])
	g.Transports = b[61]
	g.ShutdownRef = string(b[63 : 63+n])
	if err := g.Check(); err != nil {
		return GoingDark{}, err
	}
	return g, nil
}

func (g GoingDark) encode() []byte {
	b := make([]byte, goingDarkFixed+len(g.ShutdownRef))
	b[0] = GoingDarkVersion
	binary.BigEndian.PutUint32(b[1:5], uint32(g.Dark/time.Second))
	binary.BigEndian.PutUint32(b[5:9], uint32(g.ReturnWindow/time.Second))
	binary.BigEndian.PutUint32(b[9:13], uint32(g.PlanStart.Unix()))
	binary.BigEndian.PutUint32(b[13:17], uint32(g.PlanEnd.Unix()))
	binary.BigEndian.PutUint32(b[17:21], uint32(g.Interval/time.Second))
	binary.BigEndian.PutUint32(b[21:25], uint32(g.Grace/time.Second))
	binary.BigEndian.PutUint32(b[25:29], uint32(g.Reminder/time.Second))
	copy(b[29:61], g.AlertHash[:])
	b[61] = g.Transports
	b[62] = byte(len(g.ShutdownRef))
	copy(b[63:], g.ShutdownRef)
	return b
}
