package confirm

import (
	"errors"
	"sync"
	"time"

	"github.com/btcsuite/btcd/btcec/v2"
	"github.com/openbao/openbao/sdk/v2/helper/shamir"

	"github.com/altradits/kengele/internal/message"
)

// Action is what the guardian's app must do after handling an event.
type Action int

const (
	// None: nothing changes in public.
	None Action = iota
	// Release: k guardians confirmed. Publish the public alert once.
	Release
	// StandDown: the alarm ended before anything went public. Tell the
	// guardians privately; publish nothing.
	StandDown
	// FoundSafe: the alarm ended after the public alert. Publish a "found
	// safe" update signed by the same alert key.
	FoundSafe
)

func (a Action) String() string {
	switch a {
	case Release:
		return "release"
	case StandDown:
		return "stand-down"
	case FoundSafe:
		return "found-safe"
	default:
		return "none"
	}
}

// ClearedBy says who ended an alarm.
type ClearedBy int

const (
	NotCleared ClearedBy = iota
	// ByGuardians: k distinct guardians signed an all-clear. It is the only
	// way an alarm ends here; see Tally.allClear.
	ByGuardians
)

// Outcome is the result of handling one event.
type Outcome struct {
	Action Action
	Epoch  uint32
	By     ClearedBy
	// Alert and AlertKey are set for Release and FoundSafe. The key signs
	// the public notes; never log or store it.
	Alert    *Alert
	AlertKey *btcec.PrivateKey
	// Confirmed is how many distinct guardians the release counted.
	Confirmed int
	// ActivistSaysSafe is set when this event was the activist's own
	// all-clear. It ends nothing: the app must tell guardians to verify by
	// voice or video, because captors may have forced it with the real PIN.
	ActivistSaysSafe bool
}

// Errors from Tally.Handle. All of them mean "ignore this event".
var (
	ErrNotGuardian     = errors.New("confirm: signer is not an enrolled guardian")
	ErrNotActivist     = errors.New("confirm: event is about a different activist")
	ErrStale           = errors.New("confirm: event belongs to an earlier alarm")
	ErrTooFarAhead     = errors.New("confirm: event is for an alarm too far ahead")
	ErrExpired         = errors.New("confirm: confirmation is too old")
	ErrDuplicate       = errors.New("confirm: this guardian already counted for this alarm")
	ErrAlreadyReleased = errors.New("confirm: alert already released for this alarm")
	ErrSpent           = errors.New("confirm: this kit's alert key has already been used; the activist must make a new kit")
	ErrIgnoredKind     = errors.New("confirm: event kind is not a confirmation or all-clear")
)

// maxAhead limits how far ahead of the current alarm a confirmation may be
// kept, so a misbehaving guardian cannot fill memory.
const maxAhead = 3

type vote struct {
	at    time.Time
	share []byte
}

type epochState struct {
	confirms map[[32]byte]vote
	clears   map[[32]byte]time.Time
	// activistClear is when the activist signed an all-clear for this
	// alarm, zero if never. It is shown to guardians but never counted.
	activistClear time.Time
}

// ClearStatus is what a guardian's app shows before its user may send an
// all-clear for the current alarm.
type ClearStatus struct {
	Epoch uint32
	// ActivistSaysSafe: the activist signed an all-clear for this alarm. On
	// its own it proves nothing; captors can force the real PIN.
	ActivistSaysSafe bool
	// GuardianClears is how many distinct, fresh guardian all-clears count
	// so far; Needed is the threshold k.
	GuardianClears int
	Needed         int
	// VerifyFirst is true for every open alarm. The app must ask its user
	// to speak to the activist by voice or video call, and to be satisfied
	// they are free and not being coerced, before it builds a guardian
	// all-clear with GuardianAllClearEvent.
	VerifyFirst bool
}

// ClearStatus reports the all-clear state of the current alarm.
func (t *Tally) ClearStatus(now time.Time) ClearStatus {
	t.mu.Lock()
	defer t.mu.Unlock()
	cs := ClearStatus{Epoch: t.cur, Needed: t.kit.Threshold, VerifyFirst: true}
	if st, ok := t.byEp[t.cur]; ok {
		cs.ActivistSaysSafe = !st.activistClear.IsZero()
		for _, at := range st.clears {
			if fresh(at, now, t.ttl) {
				cs.GuardianClears++
			}
		}
	}
	return cs
}

// Tally runs on each guardian's device, one per watched activist. It counts
// guardian confirmations and all-clears and says when to publish.
//
// It is safe for concurrent use.
type Tally struct {
	mu   sync.Mutex
	kit  GuardianKit
	p    *parsed
	ttl  time.Duration
	enr  map[[32]byte]bool
	cur  uint32
	byEp map[uint32]*epochState

	released uint32 // epoch whose alert went public, 0 if none
	spent    bool
	alert    *Alert
	alertKey *btcec.PrivateKey
}

// NewTally starts counting for the activist in kit. ttl is how long a
// confirmation stays valid; 0 means DefaultConfirmTTL.
func NewTally(kit GuardianKit, ttl time.Duration) (*Tally, error) {
	p, err := kit.parse()
	if err != nil {
		return nil, err
	}
	if ttl <= 0 {
		ttl = DefaultConfirmTTL
	}
	t := &Tally{kit: kit, p: p, ttl: ttl, cur: 1, enr: map[[32]byte]bool{}, byEp: map[uint32]*epochState{}}
	for _, g := range p.guardians {
		t.enr[g] = true
	}
	return t, nil
}

// Epoch is the number of the current alarm for this activist.
func (t *Tally) Epoch() uint32 {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.cur
}

// Released reports whether the public alert has gone out.
func (t *Tally) Released() bool {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.released != 0
}

// Spent reports whether the kit's alert key has been used. A spent kit can
// sign a "found safe" update but never a new alert.
func (t *Tally) Spent() bool {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.spent
}

// Recipients returns the guardians a confirmation must be sealed to: every
// guardian except this one. The activist is deliberately not included.
func (t *Tally) Recipients() [][32]byte {
	out := make([][32]byte, 0, len(t.p.guardians)-1)
	for _, g := range t.p.guardians {
		if g != t.p.self {
			out = append(out, g)
		}
	}
	return out
}

// Senders returns the keys whose events this tally accepts: the activist
// and every guardian. Pass it to message.Open.
func (t *Tally) Senders() [][32]byte {
	return append([][32]byte{t.p.activist}, t.p.guardians...)
}

// Handle counts one opened event. e must come from message.Open, which has
// already checked its signature and Signer. now is the guardian's clock.
//
// The guardian's own confirmation and all-clear must be passed in too.
func (t *Tally) Handle(e *message.Event, now time.Time) (Outcome, error) {
	if e == nil {
		return Outcome{}, ErrBody
	}
	t.mu.Lock()
	defer t.mu.Unlock()

	switch e.Kind {
	case message.KindGuardianConfirm:
		return t.confirm(e, now)
	case message.KindAllClear:
		return t.allClear(e, now)
	default:
		// Check-ins, duress check-ins and panics are the watcher's job
		// (internal/schedule). None of them can stand an alarm down here:
		// only a signed all-clear can.
		return Outcome{}, ErrIgnoredKind
	}
}

func (t *Tally) confirm(e *message.Event, now time.Time) (Outcome, error) {
	if !t.enr[e.Signer] {
		return Outcome{}, ErrNotGuardian
	}
	c, err := decodeConfirm(e.Body)
	if err != nil {
		return Outcome{}, err
	}
	if c.activist != t.p.activist {
		return Outcome{}, ErrNotActivist
	}
	if err := t.checkEpoch(c.epoch); err != nil {
		return Outcome{}, err
	}
	if t.released == c.epoch {
		return Outcome{}, ErrAlreadyReleased
	}
	if t.spent {
		return Outcome{}, ErrSpent
	}
	if !fresh(e.CreatedAt, now, t.ttl) {
		return Outcome{}, ErrExpired
	}
	st := t.state(c.epoch)
	if _, ok := st.confirms[e.Signer]; ok {
		return Outcome{}, ErrDuplicate
	}
	st.confirms[e.Signer] = vote{at: e.CreatedAt, share: c.share}

	return t.tryRelease(c.epoch, now), nil
}

// tryRelease rebuilds the alert key from k fresh shares. A bad share from a
// misbehaving guardian makes the authenticated decryption fail, so every
// k-sized group is tried before giving up and waiting for more.
func (t *Tally) tryRelease(epoch uint32, now time.Time) Outcome {
	st := t.state(epoch)
	var shares [][]byte
	for g, v := range st.confirms {
		if !fresh(v.at, now, t.ttl) {
			delete(st.confirms, g) // expired: no longer counts
			continue
		}
		shares = append(shares, v.share)
	}
	k := t.kit.Threshold
	if len(shares) < k {
		return Outcome{Epoch: epoch}
	}
	var out Outcome
	combinations(len(shares), k, func(idx []int) bool {
		parts := make([][]byte, k)
		for i, j := range idx {
			parts[i] = shares[j]
		}
		key, err := shamir.Combine(parts)
		if err != nil {
			return true
		}
		s, priv, err := t.kit.open(key)
		wipe(key)
		if err != nil {
			return true
		}
		t.released = epoch
		t.spent = true
		t.alert = &s.Alert
		t.alertKey = priv
		if epoch > t.cur {
			t.cur = epoch
		}
		out = Outcome{Action: Release, Epoch: epoch, Alert: t.alert, AlertKey: priv, Confirmed: len(shares)}
		return false
	})
	if out.Action == None {
		out.Epoch = epoch
	}
	return out
}

func (t *Tally) allClear(e *message.Event, now time.Time) (Outcome, error) {
	c, err := decodeAllClear(e.Body)
	if err != nil {
		return Outcome{}, err
	}
	if c.activist != t.p.activist {
		return Outcome{}, ErrNotActivist
	}
	if c.epoch < t.cur {
		return Outcome{}, ErrStale
	}
	if c.epoch > t.cur+maxAhead {
		return Outcome{}, ErrTooFarAhead
	}

	switch {
	case e.Signer == t.p.activist:
		// Every alarm the tally sees is at Level 1 or above (missed,
		// panic or duress). Captors holding the activist can force the
		// real PIN, so the activist's own all-clear never ends an alarm
		// and is not counted towards k. It is recorded and shown to
		// guardians, who must verify by voice or video and then send
		// their own all-clears. It never blocks or delays a release.
		st := t.state(c.epoch)
		if !st.activistClear.IsZero() {
			return Outcome{}, ErrDuplicate
		}
		st.activistClear = e.CreatedAt
		return Outcome{Epoch: c.epoch, ActivistSaysSafe: true}, nil
	case t.enr[e.Signer]:
		if !fresh(e.CreatedAt, now, t.ttl) {
			return Outcome{}, ErrExpired
		}
		st := t.state(c.epoch)
		if _, ok := st.clears[e.Signer]; ok {
			return Outcome{}, ErrDuplicate
		}
		st.clears[e.Signer] = e.CreatedAt
		n := 0
		for g, at := range st.clears {
			if fresh(at, now, t.ttl) {
				n++
			} else {
				delete(st.clears, g)
			}
		}
		if n < t.kit.Threshold {
			return Outcome{Epoch: c.epoch}, nil
		}
		return t.end(c.epoch, ByGuardians), nil
	default:
		return Outcome{}, ErrNotGuardian
	}
}

// end closes alarm epoch and moves to the next one.
func (t *Tally) end(epoch uint32, by ClearedBy) Outcome {
	out := Outcome{Action: StandDown, Epoch: epoch, By: by}
	if t.released != 0 && t.released == epoch {
		out.Action = FoundSafe
		out.Alert = t.alert
		out.AlertKey = t.alertKey
	}
	t.cur = epoch + 1
	for ep := range t.byEp {
		if ep < t.cur {
			delete(t.byEp, ep)
		}
	}
	return out
}

func (t *Tally) checkEpoch(ep uint32) error {
	if ep < t.cur {
		return ErrStale
	}
	if ep > t.cur+maxAhead {
		return ErrTooFarAhead
	}
	return nil
}

func (t *Tally) state(ep uint32) *epochState {
	st, ok := t.byEp[ep]
	if !ok {
		st = &epochState{confirms: map[[32]byte]vote{}, clears: map[[32]byte]time.Time{}}
		t.byEp[ep] = st
	}
	return st
}

// fresh reports whether a confirmation signed at "at" still counts at now.
func fresh(at, now time.Time, ttl time.Duration) bool {
	if at.After(now.Add(message.MaxClockSkew)) {
		return false
	}
	return now.Sub(at) <= ttl
}

// combinations calls f with every k-sized subset of 0..n-1 until f returns
// false. n is at most MaxGuardians, so this is small.
func combinations(n, k int, f func([]int) bool) {
	idx := make([]int, k)
	var rec func(start, depth int) bool
	rec = func(start, depth int) bool {
		if depth == k {
			return f(idx)
		}
		for i := start; i <= n-(k-depth); i++ {
			idx[depth] = i
			if !rec(i+1, depth+1) {
				return false
			}
		}
		return true
	}
	rec(0, 0)
}
