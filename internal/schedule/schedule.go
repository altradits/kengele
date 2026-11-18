// Package schedule is the guardian-side deadline engine. It follows each
// watched activist's watch window, decides when a check-in is late, and
// pauses the clock while the guardian is offline, so that a network
// shutdown never turns into a wave of false alarms.
//
// The engine is passive and has no goroutines: the caller feeds it the
// time (Tick), connectivity changes (SetOnline) and opened messages
// (Observe), and acts on the Notices it returns. That keeps it
// deterministic under a fake clock. See docs/shutdown-mode.md, principle 1.
package schedule

import (
	"errors"
	"fmt"
	"sort"
	"time"

	"github.com/altradits/kengele/internal/message"
)

// State is where one activist's watch stands.
type State int

const (
	// StateInactive: the watch window has not started yet.
	StateInactive State = iota
	// StateWaiting: the next check-in is not yet due.
	StateWaiting
	// StateReminder: the check-in is close to due or inside its grace period.
	StateReminder
	// StateMissed: a deadline passed while online. Level 1, private: the
	// guardians are told and the alarm awaits guardian confirmation (#11).
	StateMissed
	// StateAlarm: a duress check-in or panic arrived. Top level.
	StateAlarm
	// StatePaused: the guardian is offline (or many activists went silent
	// at once), so deadlines are frozen.
	StatePaused
	// StateCleared: the window ended with a final check-in, or all clear.
	StateCleared
)

var stateNames = [...]string{"inactive", "waiting", "reminder", "missed", "alarm", "paused", "cleared"}

func (s State) String() string {
	if int(s) < len(stateNames) {
		return stateNames[s]
	}
	return fmt.Sprintf("state(%d)", int(s))
}

// NoticeKind says what a Notice is about.
type NoticeKind int

const (
	NoticeStarted     NoticeKind = iota // window opened
	NoticeReminder                      // check-in is close to due
	NoticeMissed                        // level 1: tell guardians privately
	NoticeAlarm                         // duress or panic: top level
	NoticeRecovered                     // a check-in arrived after a missed deadline
	NoticeCleared                       // window finished or all clear
	NoticePaused                        // guardian offline: clock paused
	NoticeResumed                       // guardian back online: clock running
	NoticeMassSilence                   // several activists silent at once
)

var noticeNames = [...]string{"started", "reminder", "missed", "alarm", "recovered", "cleared", "paused", "resumed", "mass-silence"}

func (k NoticeKind) String() string {
	if int(k) < len(noticeNames) {
		return noticeNames[k]
	}
	return fmt.Sprintf("notice(%d)", int(k))
}

// Notice is something the guardian (or the next stage) must act on.
type Notice struct {
	Kind     NoticeKind
	Activist [32]byte
	// At is the engine time the notice was raised.
	At time.Time
	// EventTime is the activist's signed original time, when the notice
	// comes from a message. It survives store-and-forward and is the time
	// that goes into the habeas corpus packet.
	EventTime time.Time
	// Deadline is the deadline in force after this notice, if any.
	Deadline time.Time
	Reason   string
}

// Window is one watch: from Start to End, a check-in every Interval.
// A check-in is late Grace after it was due, and the watcher shows a
// reminder Reminder before it is due. The window closes with a final
// check-in due at End; any check-in signed from End-Grace onwards counts
// as the final one.
type Window struct {
	Start, End time.Time
	Interval   time.Duration
	Grace      time.Duration
	Reminder   time.Duration
}

// Validate reports whether the window makes sense.
func (w Window) Validate() error {
	switch {
	case w.Start.IsZero() || w.End.IsZero():
		return errors.New("schedule: window needs a start and an end")
	case !w.End.After(w.Start):
		return errors.New("schedule: window end must be after start")
	case w.Interval <= 0:
		return errors.New("schedule: interval must be positive")
	case w.Grace < 0 || w.Reminder < 0:
		return errors.New("schedule: grace and reminder must not be negative")
	case w.Reminder >= w.Interval:
		return errors.New("schedule: reminder must be shorter than the interval")
	}
	return nil
}

// Config tunes the engine. Zero values take the defaults below.
type Config struct {
	// ReturnWindow is how long an activist gets to check in after the
	// network returns, when their deadline fell inside the outage.
	ReturnWindow time.Duration
	// MassSilence is how many activists must miss a deadline within
	// MassSpan, with no message from anyone, before the engine treats it
	// as a possible shutdown. Negative disables the check.
	MassSilence int
	// MassSpan is the span for MassSilence.
	MassSpan time.Duration
}

// Defaults.
const (
	DefaultReturnWindow = 2 * time.Hour
	DefaultMassSilence  = 3
	DefaultMassSpan     = 15 * time.Minute
)

func (c Config) withDefaults() Config {
	if c.ReturnWindow <= 0 {
		c.ReturnWindow = DefaultReturnWindow
	}
	if c.MassSilence == 0 {
		c.MassSilence = DefaultMassSilence
	}
	if c.MassSpan <= 0 {
		c.MassSpan = DefaultMassSpan
	}
	return c
}

type tracker struct {
	key   [32]byte
	w     Window
	state State
	// resume is the state to return to after a pause.
	resume State
	// last is the signed time of the last accepted check-in (or Start).
	last time.Time
	// missAt is when the current deadline fires (due + grace).
	missAt time.Time
	// reminded is true once the reminder for this cycle was raised.
	reminded bool
	// returnGranted is true once this cycle has used its return window,
	// so a flapping network cannot stretch one deadline again and again.
	returnGranted bool
	// massHold is set while a mass-silence hold is in force.
	massHold bool
	seen     map[uint32]struct{}
}

func (t *tracker) due() time.Time { return t.missAt.Add(-t.w.Grace) }

// Engine follows every activist one guardian watches.
type Engine struct {
	cfg       Config
	acts      map[[32]byte]*tracker
	order     [][32]byte
	online    bool
	offlineAt time.Time
	lastAny   time.Time
	now       time.Time
}

// New returns an engine that starts online at time now.
func New(cfg Config, now time.Time) *Engine {
	return &Engine{cfg: cfg.withDefaults(), acts: map[[32]byte]*tracker{}, online: true, now: now, lastAny: now}
}

// Watch adds (or replaces) an activist's window.
func (e *Engine) Watch(activist [32]byte, w Window) error {
	if err := w.Validate(); err != nil {
		return err
	}
	if _, ok := e.acts[activist]; !ok {
		e.order = append(e.order, activist)
		sort.Slice(e.order, func(i, j int) bool { return string(e.order[i][:]) < string(e.order[j][:]) })
	}
	e.acts[activist] = &tracker{key: activist, w: w, state: StateInactive, seen: map[uint32]struct{}{}}
	return nil
}

// State returns an activist's current state.
func (e *Engine) State(activist [32]byte) (State, bool) {
	t, ok := e.acts[activist]
	if !ok {
		return 0, false
	}
	return t.state, true
}

// Deadline returns when the activist's current deadline fires.
func (e *Engine) Deadline(activist [32]byte) (time.Time, bool) {
	t, ok := e.acts[activist]
	if !ok || t.missAt.IsZero() {
		return time.Time{}, false
	}
	return t.missAt, true
}

// Online reports whether the engine believes the guardian is online.
func (e *Engine) Online() bool { return e.online }

// Senders returns the keys of every watched activist, for message.Open.
func (e *Engine) Senders() [][32]byte {
	out := make([][32]byte, len(e.order))
	copy(out, e.order)
	return out
}

// Tick advances the engine to now and returns what happened.
func (e *Engine) Tick(now time.Time) []Notice {
	if now.Before(e.now) {
		now = e.now // time never runs backwards inside the engine
	}
	e.now = now
	var out []Notice

	// Open windows that have started.
	for _, k := range e.order {
		t := e.acts[k]
		if t.state == StateInactive && !now.Before(t.w.Start) {
			t.last = t.w.Start
			e.setDeadline(t, t.w.Start)
			t.state = StateWaiting
			if !e.online {
				t.resume, t.state = StateWaiting, StatePaused
			}
			out = append(out, Notice{Kind: NoticeStarted, Activist: k, At: now, Deadline: t.missAt})
		}
	}
	if !e.online {
		return out // clock paused
	}

	// Release mass-silence holds that ran out.
	for _, k := range e.order {
		t := e.acts[k]
		if t.massHold && !now.Before(t.missAt) {
			t.massHold = false
			t.state = StateMissed
			out = append(out, Notice{Kind: NoticeMissed, Activist: k, At: now, Deadline: t.missAt,
				Reason: "still silent after the mass-silence hold ended"})
		}
	}

	// Reminders, and collect deadlines that would fire now.
	var firing []*tracker
	for _, k := range e.order {
		t := e.acts[k]
		if t.state != StateWaiting && t.state != StateReminder {
			continue
		}
		if !t.reminded && t.w.Reminder > 0 && !now.Before(t.due().Add(-t.w.Reminder)) {
			t.reminded = true
			t.state = StateReminder
			out = append(out, Notice{Kind: NoticeReminder, Activist: k, At: now, Deadline: t.missAt})
		}
		if !now.Before(t.missAt) {
			firing = append(firing, t)
		}
	}

	// Many activists silent at once, and nobody heard from at all: this
	// may be a shutdown the probe has not seen, or a mass arrest. Hold
	// for at most ReturnWindow and tell the guardians to check by hand.
	if e.cfg.MassSilence > 0 && len(firing) >= e.cfg.MassSilence && now.Sub(e.lastAny) >= e.cfg.MassSpan {
		var recent []*tracker
		for _, t := range firing {
			// One delay per check-in cycle: a hold uses the return window.
			if now.Sub(t.missAt) < e.cfg.MassSpan && !t.returnGranted {
				recent = append(recent, t)
			}
		}
		if len(recent) >= e.cfg.MassSilence {
			for _, t := range recent {
				t.massHold, t.returnGranted = true, true
				t.resume, t.state = t.state, StatePaused
				t.missAt = now.Add(e.cfg.ReturnWindow)
				out = append(out, Notice{Kind: NoticeMassSilence, Activist: t.key, At: now, Deadline: t.missAt,
					Reason: fmt.Sprintf("%d activists silent at once: possible shutdown or mass arrest, check by hand now", len(recent))})
			}
			firing = firing[:0]
			for _, t := range e.trackersIn(StateWaiting, StateReminder) {
				if !now.Before(t.missAt) {
					firing = append(firing, t)
				}
			}
		}
	}

	for _, t := range firing {
		t.state = StateMissed
		out = append(out, Notice{Kind: NoticeMissed, Activist: t.key, At: now, Deadline: t.missAt,
			Reason: fmt.Sprintf("no check-in by %s", t.missAt.UTC().Format(time.RFC3339))})
	}
	return out
}

// SetOnline records a connectivity change seen by the probe at time now.
// Going offline pauses every running deadline. Coming back resumes them:
// a deadline that fell inside the outage gets ReturnWindow from now; any
// other deadline moves on by the length of the outage. Each check-in
// cycle gets the return window at most once.
func (e *Engine) SetOnline(now time.Time, online bool) []Notice {
	if online == e.online {
		return e.Tick(now)
	}
	var out []Notice
	if !online {
		out = e.Tick(now) // settle anything already due before pausing
		e.online = false
		e.offlineAt = e.now
		for _, k := range e.order {
			t := e.acts[k]
			if t.massHold {
				// The probe now confirms a shutdown: the hold becomes an
				// ordinary pause that has already used its return window.
				t.massHold, t.returnGranted = false, true
				continue
			}
			if t.state == StateWaiting || t.state == StateReminder {
				t.resume, t.state = t.state, StatePaused
				out = append(out, Notice{Kind: NoticePaused, Activist: k, At: e.now, Deadline: t.missAt})
			}
		}
		return out
	}

	if now.Before(e.now) {
		now = e.now
	}
	e.now = now
	e.online = true
	outage := now.Sub(e.offlineAt)
	for _, k := range e.order {
		t := e.acts[k]
		if t.state != StatePaused || t.massHold {
			continue
		}
		if !t.missAt.After(now) && !t.returnGranted {
			t.missAt = now.Add(e.cfg.ReturnWindow)
			t.returnGranted = true
		} else {
			t.missAt = t.missAt.Add(outage)
		}
		t.state = t.resume
		if t.state == StateReminder && now.Before(t.due().Add(-t.w.Reminder)) {
			t.state, t.reminded = StateWaiting, false
		}
		out = append(out, Notice{Kind: NoticeResumed, Activist: k, At: now, Deadline: t.missAt})
	}
	return append(out, e.Tick(now)...)
}

// Observe takes an opened, verified message (message.Open has already
// checked the signature, sender and time) and returns what it changes.
// Messages from keys not being watched are ignored, as are replays of a
// (signer, seq) pair already seen. Guardian kinds (missed, confirm) and
// going-dark are handled by later stages (#10, #11).
func (e *Engine) Observe(now time.Time, ev message.Event) []Notice {
	out := e.Tick(now)
	t, ok := e.acts[ev.Signer]
	if !ok {
		return out
	}
	if _, dup := t.seen[ev.Seq]; dup {
		return out
	}
	t.seen[ev.Seq] = struct{}{}
	e.lastAny = e.now

	// A message from anyone proves the network works: release holds.
	for _, k := range e.order {
		h := e.acts[k]
		if h.massHold && k != ev.Signer {
			h.massHold = false
			h.state = StateMissed
			out = append(out, Notice{Kind: NoticeMissed, Activist: k, At: e.now, Deadline: h.missAt,
				Reason: "others are getting through, this activist is still silent"})
		}
	}

	switch ev.Kind {
	case message.KindDuressCheckIn, message.KindPanic:
		reason := "panic trigger"
		if ev.Kind == message.KindDuressCheckIn {
			reason = "duress check-in: the activist is being forced"
		}
		t.state, t.massHold = StateAlarm, false
		out = append(out, Notice{Kind: NoticeAlarm, Activist: t.key, At: e.now, EventTime: ev.CreatedAt, Reason: reason})

	case message.KindCheckIn:
		out = append(out, e.checkIn(t, ev)...)

	case message.KindAllClear:
		if t.state != StateInactive {
			t.state, t.massHold = StateCleared, false
			out = append(out, Notice{Kind: NoticeCleared, Activist: t.key, At: e.now, EventTime: ev.CreatedAt,
				Reason: "all clear from the activist"})
		}
	}
	return out
}

func (e *Engine) checkIn(t *tracker, ev message.Event) []Notice {
	at := ev.CreatedAt
	switch {
	case t.state == StateInactive, t.state == StateAlarm, t.state == StateCleared:
		return nil // a check-in cannot cancel an alarm; only all clear can
	case at.Before(t.w.Start), !at.After(t.last):
		return nil // stale: signed before the window or before the last check-in
	}
	prev := t.state
	held := t.massHold
	prevMiss := t.missAt
	t.last = at
	t.massHold = false

	if !at.Before(t.w.End.Add(-t.w.Grace)) {
		t.state = StateCleared
		return []Notice{{Kind: NoticeCleared, Activist: t.key, At: e.now, EventTime: at, Reason: "final check-in"}}
	}

	e.setDeadline(t, at)
	switch {
	case prev == StatePaused && !held:
		t.resume = StateWaiting // still offline; stays paused with the new deadline
	default:
		t.state = StateWaiting
	}
	var out []Notice
	if prev == StateMissed || held {
		reason := "late check-in: verify by voice before standing down"
		if prev == StateMissed && !at.After(prevMiss) {
			reason = "check-in was signed in time but delivered late"
		}
		out = append(out, Notice{Kind: NoticeRecovered, Activist: t.key, At: e.now, EventTime: at, Deadline: t.missAt, Reason: reason})
	}
	if e.online && t.state != StatePaused && !e.now.Before(t.missAt) {
		// A very old check-in can leave the next deadline already passed.
		t.state = StateMissed
		out = append(out, Notice{Kind: NoticeMissed, Activist: t.key, At: e.now, Deadline: t.missAt,
			Reason: "the latest check-in was too old to cover now"})
	}
	return out
}

// setDeadline sets the next deadline after a check-in signed at "from".
func (e *Engine) setDeadline(t *tracker, from time.Time) {
	due := from.Add(t.w.Interval)
	if due.After(t.w.End) {
		due = t.w.End
	}
	t.missAt = due.Add(t.w.Grace)
	t.reminded = false
	t.returnGranted = false
}

func (e *Engine) trackersIn(states ...State) []*tracker {
	var out []*tracker
	for _, k := range e.order {
		t := e.acts[k]
		for _, s := range states {
			if t.state == s {
				out = append(out, t)
				break
			}
		}
	}
	return out
}
