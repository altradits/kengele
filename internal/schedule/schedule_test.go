package schedule

import (
	"crypto/sha256"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/altradits/kengele/internal/message"
	"github.com/altradits/kengele/internal/transport"
)

// t0 is the start of every test window: 10:00 on election day.
var t0 = time.Date(2026, 1, 15, 10, 0, 0, 0, time.UTC)

func at(min int) time.Time { return t0.Add(time.Duration(min) * time.Minute) }

func key(i int) [32]byte { return [32]byte{byte(i + 1)} }

// Default test window: 8 hours, check in every 90 min, 15 min grace,
// reminder 10 min before due. First due 11:30 (+90), fires 11:45 (+105).
func window() Window {
	return Window{Start: t0, End: at(480), Interval: 90 * time.Minute, Grace: 15 * time.Minute, Reminder: 10 * time.Minute}
}

type op int

const (
	opTick op = iota
	opOffline
	opOnline
	opEvent
	opDown // one transport down
	opUp   // one transport up
)

type step struct {
	op   op
	at   int // engine time, minutes after t0
	who  int // activist index
	kind message.Kind
	sig  int // signed CreatedAt, minutes after t0
	seq  uint32
	via  transport.Kind // transport for opEvent (default Nostr), opDown, opUp
	body []byte         // opEvent body; nil except for going-dark steps
}

func tick(m int) step    { return step{op: opTick, at: m} }
func offline(m int) step { return step{op: opOffline, at: m} }
func online(m int) step  { return step{op: opOnline, at: m} }
func ev(m int, who int, k message.Kind, sig int, seq uint32) step {
	return step{op: opEvent, at: m, who: who, kind: k, sig: sig, seq: seq, via: transport.Nostr}
}

// evVia is ev with an explicit arrival transport.
func evVia(m int, via transport.Kind, who int, k message.Kind, sig int, seq uint32) step {
	return step{op: opEvent, at: m, who: who, kind: k, sig: sig, seq: seq, via: via}
}

func down(m int, k transport.Kind) step { return step{op: opDown, at: m, via: k} }
func up(m int, k transport.Kind) step   { return step{op: opUp, at: m, via: k} }

// gdark builds a going-dark step (#10) with the given dark and return
// windows. The plan, alert hash and shutdown reference are fixed made-up
// data; only the timing varies between cases.
func gdark(m, who, sig int, seq uint32, dark, ret time.Duration) step {
	return step{op: opEvent, at: m, who: who, kind: message.KindGoingDark, sig: sig, seq: seq, via: transport.Nostr, body: mustGoingDarkBody(dark, ret)}
}

// gdarkBad builds a going-dark step with a raw body the watcher cannot parse.
func gdarkBad(m, who, sig int, seq uint32, body []byte) step {
	return step{op: opEvent, at: m, who: who, kind: message.KindGoingDark, sig: sig, seq: seq, via: transport.Nostr, body: body}
}

func mustGoingDarkBody(dark, ret time.Duration) []byte {
	e, err := message.GoingDarkEvent(message.GoingDark{
		Dark:         dark,
		ReturnWindow: ret,
		PlanStart:    t0,
		PlanEnd:      at(480),
		Interval:     90 * time.Minute,
		Grace:        15 * time.Minute,
		Reminder:     10 * time.Minute,
		Transports:   message.GoingDarkNostr,
		AlertHash:    sha256.Sum256([]byte("made-up alert KAMUKUNJI-1")),
		ShutdownRef:  "KAMUKUNJI-1",
	}, 0, t0)
	if err != nil {
		panic("test going-dark: " + err.Error())
	}
	return e.Body
}

type want struct {
	who      int
	state    State
	deadline int // minutes after t0; -1 to skip
}

type tcase struct {
	name     string
	n        int // activists, default 1
	cfg      Config
	win      func() Window
	steps    []step
	notices  []string // "kind/who" in order; nil to skip
	want     []want
	contains []string // substrings that must appear in some notice reason
	// via gives an activist's transports; activists not listed are
	// Nostr-only, the plan default.
	via map[int][]transport.Kind
	// dark lists the activists expected to hold a going-dark at the end.
	dark []int
}

func run(t *testing.T, c tcase) {
	t.Helper()
	n := c.n
	if n == 0 {
		n = 1
	}
	win := window
	if c.win != nil {
		win = c.win
	}
	e := New(c.cfg, t0.Add(-time.Hour))
	for i := 0; i < n; i++ {
		if err := e.Watch(key(i), win(), c.via[i]...); err != nil {
			t.Fatal(err)
		}
	}
	var got []Notice
	for _, s := range c.steps {
		now := at(s.at)
		switch s.op {
		case opTick:
			got = append(got, e.Tick(now)...)
		case opOffline:
			got = append(got, e.SetOnline(now, false)...)
		case opOnline:
			got = append(got, e.SetOnline(now, true)...)
		case opEvent:
			got = append(got, e.Observe(now, s.via, message.Event{Kind: s.kind, CreatedAt: at(s.sig), Seq: s.seq, Signer: key(s.who), Body: s.body})...)
		case opDown:
			got = append(got, e.SetTransport(now, s.via, false)...)
		case opUp:
			got = append(got, e.SetTransport(now, s.via, true)...)
		}
	}
	if c.notices != nil {
		var names []string
		for _, nt := range got {
			names = append(names, nt.Kind.String()+"/"+string(rune('0'+int(nt.Activist[0])-1)))
		}
		if strings.Join(names, " ") != strings.Join(c.notices, " ") {
			t.Errorf("notices:\n got  %v\n want %v", names, c.notices)
		}
	}
	for _, w := range c.want {
		s, _ := e.State(key(w.who))
		if s != w.state {
			t.Errorf("activist %d: state %v, want %v", w.who, s, w.state)
		}
		if w.deadline >= 0 {
			d, _ := e.Deadline(key(w.who))
			if !d.Equal(at(w.deadline)) {
				t.Errorf("activist %d: deadline +%v, want +%dm", w.who, d.Sub(t0), w.deadline)
			}
		}
	}
	expectDark := map[int]bool{}
	for _, i := range c.dark {
		expectDark[i] = true
	}
	for i := 0; i < n; i++ {
		if _, isDark := e.GoingDark(key(i)); isDark != expectDark[i] {
			t.Errorf("activist %d: going-dark %v, want %v", i, isDark, expectDark[i])
		}
	}
	for _, sub := range c.contains {
		found := false
		for _, nt := range got {
			if strings.Contains(nt.Reason, sub) {
				found = true
			}
		}
		if !found {
			t.Errorf("no notice reason contains %q", sub)
		}
	}
}

func TestSchedule(t *testing.T) {
	cases := []tcase{
		{
			name:    "on-time check-in",
			steps:   []step{tick(0), tick(80), ev(85, 0, message.KindCheckIn, 85, 1), tick(150)},
			notices: []string{"started/0", "reminder/0"},
			want:    []want{{0, StateWaiting, 190}},
		},
		{
			name:    "late check-in inside grace",
			steps:   []step{tick(0), tick(95), ev(100, 0, message.KindCheckIn, 100, 1), tick(104)},
			notices: []string{"started/0", "reminder/0"},
			want:    []want{{0, StateWaiting, 205}},
		},
		{
			name:    "missed",
			steps:   []step{tick(0), tick(104), tick(105)},
			notices: []string{"started/0", "reminder/0", "missed/0"},
			want:    []want{{0, StateMissed, 105}},
		},
		{
			name:     "check-in after missed is recovered but must be verified",
			steps:    []step{tick(0), tick(106), ev(120, 0, message.KindCheckIn, 119, 1)},
			notices:  []string{"started/0", "reminder/0", "missed/0", "recovered/0"},
			want:     []want{{0, StateWaiting, 224}},
			contains: []string{"verify by voice"},
		},
		{
			name:     "duress check-in raises the alarm and a later check-in cannot cancel it",
			steps:    []step{tick(0), ev(30, 0, message.KindDuressCheckIn, 30, 1), ev(40, 0, message.KindCheckIn, 40, 2), tick(300)},
			notices:  []string{"started/0", "alarm/0"},
			want:     []want{{0, StateAlarm, -1}},
			contains: []string{"duress"},
		},
		{
			name:     "panic raises the alarm",
			steps:    []step{tick(0), ev(10, 0, message.KindPanic, 10, 1)},
			notices:  []string{"started/0", "alarm/0"},
			want:     []want{{0, StateAlarm, -1}},
			contains: []string{"panic"},
		},
		{
			name:     "duress during a shutdown still raises the alarm",
			steps:    []step{tick(0), offline(30), ev(60, 0, message.KindDuressCheckIn, 58, 1), online(200), tick(400)},
			notices:  []string{"started/0", "paused/0", "alarm/0"},
			want:     []want{{0, StateAlarm, -1}},
			contains: []string{"forced"},
		},
		{
			name:    "deadline inside the outage gets the return window",
			steps:   []step{tick(0), offline(60), tick(200), online(120 + 0)},
			notices: []string{"started/0", "paused/0", "resumed/0"},
			want:    []want{{0, StateWaiting, 320}}, // engine time is already +200 when it comes back
		},
		{
			name:    "shutdown pause and resume, deadline passed in the outage",
			steps:   []step{tick(0), offline(60), online(120), tick(239)},
			notices: []string{"started/0", "paused/0", "resumed/0", "reminder/0"},
			want:    []want{{0, StateReminder, 240}},
		},
		{
			name:    "short outage before the deadline moves it by the outage",
			steps:   []step{tick(0), offline(30), online(50), tick(124), tick(125)},
			notices: []string{"started/0", "paused/0", "resumed/0", "reminder/0", "missed/0"},
			want:    []want{{0, StateMissed, 125}},
		},
		{
			name:    "shutdown starts mid-window during the reminder",
			steps:   []step{tick(0), tick(82), offline(85), tick(300), online(310), tick(429)},
			notices: []string{"started/0", "reminder/0", "paused/0", "resumed/0", "reminder/0"},
			want:    []want{{0, StateReminder, 430}},
		},
		{
			name:    "reconnect then check-in within the return window",
			steps:   []step{tick(0), offline(60), online(180), ev(200, 0, message.KindCheckIn, 200, 1), tick(279)},
			notices: []string{"started/0", "paused/0", "resumed/0"},
			want:    []want{{0, StateWaiting, 305}},
		},
		{
			name:    "reconnect then silence",
			steps:   []step{tick(0), offline(60), online(180), tick(299), tick(300)},
			notices: []string{"started/0", "paused/0", "resumed/0", "reminder/0", "missed/0"},
			want:    []want{{0, StateMissed, 300}},
		},
		{
			name: "flapping network cannot stretch one deadline again and again",
			steps: []step{
				tick(0), offline(60), online(120), // deadline +105 in outage -> +240, return window used
				offline(130), online(135), // 5 min outage -> +245
				offline(200), online(260), // +245 passed in outage, but window already used -> +305
				tick(304), tick(305),
			},
			want: []want{{0, StateMissed, 305}},
		},
		{
			name:     "check-in signed in time but delivered late (store and forward)",
			steps:    []step{tick(0), tick(106), ev(130, 0, message.KindCheckIn, 100, 1)},
			notices:  []string{"started/0", "reminder/0", "missed/0", "recovered/0"},
			want:     []want{{0, StateWaiting, 205}},
			contains: []string{"signed in time but delivered late"},
		},
		{
			name: "check-in carried through the outage uses its signed time",
			steps: []step{
				tick(0), offline(60),
				ev(200, 0, message.KindCheckIn, 80, 1), // carried through, delivered at +200, signed at +80
				online(210),                            // +80 deadline is +185: fell in outage -> +330
				tick(329),
			},
			want: []want{{0, StateReminder, 330}},
		},
		{
			name:     "a very old delayed check-in cannot cover now",
			steps:    []step{tick(0), tick(300), ev(300, 0, message.KindCheckIn, 50, 1)},
			notices:  []string{"started/0", "reminder/0", "missed/0", "recovered/0", "missed/0"},
			want:     []want{{0, StateMissed, 155}},
			contains: []string{"too old"},
		},
		{
			name:  "stale check-in signed before the last one is ignored",
			steps: []step{tick(0), ev(85, 0, message.KindCheckIn, 85, 2), ev(90, 0, message.KindCheckIn, 60, 1)},
			want:  []want{{0, StateWaiting, 190}},
		},
		{
			name:  "replayed sequence number is ignored",
			steps: []step{tick(0), ev(85, 0, message.KindCheckIn, 85, 7), ev(100, 0, message.KindCheckIn, 99, 7)},
			want:  []want{{0, StateWaiting, 190}},
		},
		{
			name:    "message from an unknown key is ignored",
			steps:   []step{tick(0), ev(10, 5, message.KindPanic, 10, 1)},
			notices: []string{"started/0"},
			want:    []want{{0, StateWaiting, 105}},
		},
		{
			name:  "before the window starts nothing runs and check-ins are ignored",
			steps: []step{tick(-30), ev(-20, 0, message.KindCheckIn, -20, 1), tick(-1)},
			want:  []want{{0, StateInactive, -1}},
		},
		{
			name: "final check-in closes the window",
			win: func() Window {
				return Window{Start: t0, End: at(120), Interval: 90 * time.Minute, Grace: 15 * time.Minute, Reminder: 10 * time.Minute}
			},
			steps:   []step{tick(0), ev(85, 0, message.KindCheckIn, 85, 1), ev(108, 0, message.KindCheckIn, 108, 2), tick(500)},
			notices: []string{"started/0", "reminder/0", "cleared/0"},
			want:    []want{{0, StateCleared, -1}},
		},
		{
			// #30: after a missed deadline (Level 1) the activist's own
			// all-clear is shown but ends nothing; guardians stand down.
			name:     "all clear after a missed deadline is recorded but does not stand down",
			steps:    []step{tick(0), tick(106), ev(120, 0, message.KindAllClear, 120, 1), tick(500)},
			notices:  []string{"started/0", "reminder/0", "missed/0", "activist-says-safe/0"},
			want:     []want{{0, StateMissed, -1}},
			contains: []string{"does not end the alarm"},
		},
		{
			name:    "all clear during the grace period still stands down",
			steps:   []step{tick(0), tick(95), ev(100, 0, message.KindAllClear, 100, 1), tick(500)},
			notices: []string{"started/0", "reminder/0", "cleared/0"},
			want:    []want{{0, StateCleared, -1}},
		},
		{
			name:     "all clear after panic keeps the alarm",
			steps:    []step{tick(0), ev(10, 0, message.KindPanic, 10, 1), ev(20, 0, message.KindAllClear, 20, 2), tick(500)},
			notices:  []string{"started/0", "alarm/0", "activist-says-safe/0"},
			want:     []want{{0, StateAlarm, -1}},
			contains: []string{"guardians must check by voice or video"},
		},
		{
			name:    "all clear after duress keeps the alarm",
			steps:   []step{tick(0), ev(30, 0, message.KindDuressCheckIn, 30, 1), ev(45, 0, message.KindAllClear, 45, 2), tick(500)},
			notices: []string{"started/0", "alarm/0", "activist-says-safe/0"},
			want:    []want{{0, StateAlarm, -1}},
		},
		{
			name: "all clear during a mass-silence hold is recorded and the hold still ends in missed",
			n:    3,
			steps: []step{
				tick(0), tick(105), // held until +225
				ev(130, 0, message.KindAllClear, 130, 1),
			},
			want: []want{{0, StatePaused, 225}, {1, StateMissed, -1}, {2, StateMissed, -1}},
		},
		{
			name:    "window that starts during a shutdown starts paused",
			steps:   []step{offline(-10), tick(0), tick(200), online(220)},
			notices: []string{"started/0", "paused/0", "resumed/0"},
			want:    []want{{0, StateWaiting, 340}},
		},
		{
			name: "many silent at once with nobody heard is held, then fires",
			n:    3,
			steps: []step{
				tick(0), tick(105), tick(224), tick(225),
			},
			notices: []string{
				"started/0", "started/1", "started/2",
				"reminder/0", "reminder/1", "reminder/2",
				"mass-silence/0", "mass-silence/1", "mass-silence/2",
				"missed/0", "missed/1", "missed/2",
			},
			want:     []want{{0, StateMissed, 225}, {1, StateMissed, 225}, {2, StateMissed, 225}},
			contains: []string{"possible shutdown or mass arrest"},
		},
		{
			name: "a hold ends at once when anyone gets through",
			n:    3,
			steps: []step{
				tick(0), tick(105),
				ev(130, 0, message.KindCheckIn, 130, 1), // activist 0 gets through
			},
			notices: []string{
				"started/0", "started/1", "started/2",
				"reminder/0", "reminder/1", "reminder/2",
				"mass-silence/0", "mass-silence/1", "mass-silence/2",
				"missed/1", "missed/2", "recovered/0",
			},
			want:     []want{{0, StateWaiting, 235}, {1, StateMissed, -1}, {2, StateMissed, -1}},
			contains: []string{"others are getting through"},
		},
		{
			name:  "mass-silence check can be turned off",
			n:     3,
			cfg:   Config{MassSilence: -1},
			steps: []step{tick(0), tick(105)},
			want:  []want{{0, StateMissed, 105}, {1, StateMissed, 105}, {2, StateMissed, 105}},
		},
		{
			name:  "two silent is below the threshold and fires",
			n:     2,
			steps: []step{tick(0), tick(105)},
			want:  []want{{0, StateMissed, 105}, {1, StateMissed, 105}},
		},
		{
			name: "a hold the probe then confirms as a shutdown becomes a normal pause",
			n:    3,
			steps: []step{
				tick(0), tick(105), // held until +225
				offline(110), online(170), // 60 min outage, return window already used
				tick(284), tick(285),
			},
			want: []want{{0, StateMissed, 285}, {1, StateMissed, 285}, {2, StateMissed, 285}},
		},
		{
			name:  "custom return window",
			cfg:   Config{ReturnWindow: 30 * time.Minute},
			steps: []step{tick(0), offline(60), online(120)},
			want:  []want{{0, StateWaiting, 150}},
		},
		{
			name:  "time running backwards is ignored",
			steps: []step{tick(0), tick(106), tick(50)},
			want:  []want{{0, StateMissed, 105}},
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) { run(t, c) })
	}
}

// Going dark (#10): the activist pre-arms a shutdown, the clock pauses at
// once for that activist only, and resumes when the hold ends.
func TestGoingDark(t *testing.T) {
	week := 7 * 24 * time.Hour
	cases := []tcase{
		{
			name:     "going-dark pauses the clock at once",
			steps:    []step{tick(0), gdark(30, 0, 30, 1, 120*time.Minute, 60*time.Minute), tick(60)},
			notices:  []string{"started/0", "going-dark/0"},
			want:     []want{{0, StatePaused, 105}},
			dark:     []int{0},
			contains: []string{"anaenda gizani / going dark", "clock paused until"},
		},
		{
			name:     "going-dark then silence raises the alarm after the return window",
			steps:    []step{tick(0), gdark(30, 0, 30, 1, 120*time.Minute, 60*time.Minute), tick(149), tick(150), tick(200), tick(210)},
			notices:  []string{"started/0", "going-dark/0", "dark-expired/0", "reminder/0", "missed/0"},
			want:     []want{{0, StateMissed, 210}},
			contains: []string{"giza kimekwisha / dark period ended"},
		},
		{
			name:    "a short dark moves an unpassed deadline by the hold",
			steps:   []step{tick(0), gdark(30, 0, 30, 1, 30*time.Minute, 60*time.Minute), tick(60), tick(110), tick(135)},
			notices: []string{"started/0", "going-dark/0", "dark-expired/0", "reminder/0", "missed/0"},
			want:    []want{{0, StateMissed, 135}},
		},
		{
			name:    "going-dark then check-in resumes the clock",
			steps:   []step{tick(0), gdark(30, 0, 30, 1, 120*time.Minute, 60*time.Minute), ev(60, 0, message.KindCheckIn, 60, 2), tick(150)},
			notices: []string{"started/0", "going-dark/0", "reminder/0"},
			want:    []want{{0, StateReminder, 165}},
		},
		{
			name:    "a newer going-dark replaces the hold",
			steps:   []step{tick(0), gdark(30, 0, 30, 1, 200*time.Minute, 60*time.Minute), gdark(60, 0, 60, 2, 30*time.Minute, 60*time.Minute), tick(90)},
			notices: []string{"started/0", "going-dark/0", "going-dark/0", "dark-expired/0"},
			want:    []want{{0, StateWaiting, 135}},
		},
		{
			name: "renewal extends past the first expiry, up to seven days from the renewal",
			steps: []step{
				tick(0), gdark(30, 0, 30, 1, week, 2*time.Hour),
				gdark(9000, 0, 9000, 2, week, 2*time.Hour),
				tick(10110), tick(19080),
			},
			notices: []string{"started/0", "going-dark/0", "going-dark/0", "dark-expired/0"},
			want:    []want{{0, StateWaiting, 19200}},
		},
		{
			name:     "panic during going-dark still raises the alarm",
			steps:    []step{tick(0), gdark(30, 0, 30, 1, 120*time.Minute, 60*time.Minute), ev(40, 0, message.KindPanic, 40, 2), tick(500)},
			notices:  []string{"started/0", "going-dark/0", "alarm/0"},
			want:     []want{{0, StateAlarm, -1}},
			contains: []string{"panic"},
		},
		{
			name:    "duress during going-dark still raises the alarm",
			steps:   []step{tick(0), gdark(30, 0, 30, 1, 120*time.Minute, 60*time.Minute), ev(40, 0, message.KindDuressCheckIn, 40, 2)},
			notices: []string{"started/0", "going-dark/0", "alarm/0"},
			want:    []want{{0, StateAlarm, -1}},
		},
		{
			name:    "an unreadable going-dark is dropped",
			steps:   []step{tick(0), gdarkBad(30, 0, 30, 1, []byte("junk")), tick(95), tick(105)},
			notices: []string{"started/0", "reminder/0", "missed/0"},
			want:    []want{{0, StateMissed, 105}},
		},
		{
			name:    "a replayed going-dark is ignored",
			steps:   []step{tick(0), gdark(30, 0, 30, 5, 60*time.Minute, 60*time.Minute), gdark(60, 0, 60, 5, 200*time.Minute, 60*time.Minute), tick(90)},
			notices: []string{"started/0", "going-dark/0", "dark-expired/0"},
			want:    []want{{0, StateWaiting, 165}},
		},
		{
			name:    "going-dark after a missed deadline is recorded but changes nothing",
			steps:   []step{tick(0), tick(106), gdark(120, 0, 120, 1, 60*time.Minute, 60*time.Minute), tick(500)},
			notices: []string{"started/0", "reminder/0", "missed/0", "going-dark/0"},
			want:    []want{{0, StateMissed, 105}},
		},
		{
			name:    "going-dark while offline resumes only after both end",
			steps:   []step{tick(0), gdark(30, 0, 30, 1, 60*time.Minute, 60*time.Minute), offline(40), online(70), tick(90)},
			notices: []string{"started/0", "going-dark/0", "dark-expired/0"},
			want:    []want{{0, StateWaiting, 165}},
		},
		{
			name:    "final check-in during going-dark clears",
			steps:   []step{tick(0), gdark(30, 0, 30, 1, 500*time.Minute, 60*time.Minute), ev(470, 0, message.KindCheckIn, 470, 2)},
			notices: []string{"started/0", "going-dark/0", "cleared/0"},
			want:    []want{{0, StateCleared, -1}},
		},
		{
			// The going-dark arrives over Nostr, which proves Nostr
			// works: the other two are no longer explained by a
			// shutdown and miss at once, while its sender converts
			// the hold into a dark pause.
			name: "going-dark during a mass-silence hold explains that activist",
			n:    3,
			steps: []step{
				tick(0), tick(105), // all held until +225
				gdark(130, 0, 130, 1, 60*time.Minute, 60*time.Minute),
				tick(190), tick(225),
			},
			notices: []string{
				"started/0", "started/1", "started/2",
				"reminder/0", "reminder/1", "reminder/2",
				"mass-silence/0", "mass-silence/1", "mass-silence/2",
				"missed/1", "missed/2", "going-dark/0", "dark-expired/0",
			},
			want:     []want{{0, StateWaiting, 285}, {1, StateMissed, 225}, {2, StateMissed, 225}},
			contains: []string{"others are getting through on nostr"},
		},
		{
			name:    "going-dark only pauses its own activist",
			n:       2,
			steps:   []step{tick(0), gdark(30, 0, 30, 1, 120*time.Minute, 60*time.Minute), tick(105)},
			notices: []string{"started/0", "started/1", "going-dark/0", "reminder/1", "missed/1"},
			want:    []want{{0, StatePaused, 105}, {1, StateMissed, 105}},
			dark:    []int{0},
		},
		{
			name:    "a window that opens during going-dark starts paused",
			steps:   []step{gdark(-30, 0, -30, 1, 120*time.Minute, 60*time.Minute), tick(0), tick(90)},
			notices: []string{"going-dark/0", "started/0", "dark-expired/0"},
			want:    []want{{0, StateWaiting, 225}},
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) { run(t, c) })
	}
}

// Mixed-transport shutdowns (#28). Unless a case says otherwise, activist 0
// uses Nostr and SMS and activist 1 is Nostr-only (the plan default).
func TestMixedTransports(t *testing.T) {
	both := []transport.Kind{transport.Nostr, transport.SMS}
	smsAnd1 := map[int][]transport.Kind{0: both}
	cases := []tcase{
		{
			name:     "Nostr down, SMS up: the SMS activist's clock runs, the Nostr-only one pauses",
			n:        2,
			via:      smsAnd1,
			steps:    []step{tick(0), down(60, transport.Nostr), tick(105)},
			notices:  []string{"started/0", "started/1", "paused/1", "reminder/0", "missed/0"},
			want:     []want{{0, StateMissed, 105}, {1, StatePaused, 105}},
			contains: []string{"all of this activist's transports are down"},
		},
		{
			name: "an SMS check-in during a Nostr outage keeps the SMS activist covered",
			n:    2,
			via:  smsAnd1,
			steps: []step{
				tick(0), down(60, transport.Nostr),
				evVia(90, transport.SMS, 0, message.KindCheckIn, 90, 1),
				tick(150),
			},
			want: []want{{0, StateWaiting, 195}, {1, StatePaused, 105}},
		},
		{
			name: "an SMS check-in during a Nostr outage does not lift the hold for Nostr-only activists",
			n:    4,
			via:  map[int][]transport.Kind{3: both},
			steps: []step{
				tick(0), tick(105), // all four silent, nobody heard: held until +225
				evVia(130, transport.SMS, 3, message.KindCheckIn, 130, 1), // proves SMS only
				tick(224),
			},
			want: []want{
				{0, StatePaused, 225}, {1, StatePaused, 225}, {2, StatePaused, 225},
				{3, StateReminder, 235}, // due +220, reminder from +210
			},
		},
		{
			name: "the Nostr-only holds still end on time after an SMS check-in",
			n:    4,
			via:  map[int][]transport.Kind{3: both},
			steps: []step{
				tick(0), tick(105),
				evVia(130, transport.SMS, 3, message.KindCheckIn, 130, 1),
				tick(225),
			},
			want:     []want{{0, StateMissed, 225}, {1, StateMissed, 225}, {2, StateMissed, 225}, {3, StateReminder, 235}},
			contains: []string{"still silent after the mass-silence hold ended"},
		},
		{
			name: "an SMS message lifts the hold for other SMS activists",
			n:    3,
			via:  map[int][]transport.Kind{0: both, 1: both, 2: both},
			steps: []step{
				tick(0), tick(105),
				evVia(130, transport.SMS, 0, message.KindCheckIn, 130, 1),
			},
			want:     []want{{0, StateWaiting, 235}, {1, StateMissed, -1}, {2, StateMissed, -1}},
			contains: []string{"others are getting through on sms"},
		},
		{
			name: "a Nostr message lifts the hold for everyone on Nostr",
			n:    4,
			via:  map[int][]transport.Kind{3: both},
			steps: []step{
				tick(0), tick(105),
				ev(130, 3, message.KindCheckIn, 130, 1),
			},
			want:     []want{{0, StateMissed, -1}, {1, StateMissed, -1}, {2, StateMissed, -1}, {3, StateWaiting, 235}},
			contains: []string{"others are getting through on nostr"},
		},
		{
			name: "a message on an unknown transport proves nothing but still counts as a check-in",
			n:    3,
			steps: []step{
				tick(0), tick(105),
				evVia(130, transport.Unknown, 0, message.KindCheckIn, 130, 1),
			},
			want: []want{{0, StateWaiting, 235}, {1, StatePaused, 225}, {2, StatePaused, 225}},
		},
		{
			name: "both down, SMS back first: only the SMS activist resumes",
			n:    2,
			via:  smsAnd1,
			steps: []step{
				tick(0), down(60, transport.Nostr), down(70, transport.SMS),
				up(120, transport.SMS), // +105 fell in the outage: return window -> +240
				tick(170),
			},
			notices:  []string{"started/0", "started/1", "paused/1", "paused/0", "resumed/0"},
			want:     []want{{0, StateWaiting, 240}, {1, StatePaused, 105}},
			contains: []string{"sms is back"},
		},
		{
			name: "both down, SMS back first, then Nostr: each resumes when its own transport returns",
			n:    2,
			via:  smsAnd1,
			steps: []step{
				tick(0), down(60, transport.Nostr), down(70, transport.SMS),
				up(120, transport.SMS), up(180, transport.Nostr),
			},
			notices: []string{"started/0", "started/1", "paused/1", "paused/0", "resumed/0", "resumed/1"},
			want:    []want{{0, StateWaiting, 240}, {1, StateWaiting, 300}},
		},
		{
			name: "both down, Nostr back first: both resume and SMS returning changes nothing",
			n:    2,
			via:  smsAnd1,
			steps: []step{
				tick(0), offline(60),
				up(120, transport.Nostr), up(130, transport.SMS),
			},
			notices: []string{"started/0", "started/1", "paused/1", "paused/0", "resumed/0", "resumed/1"},
			want:    []want{{0, StateWaiting, 240}, {1, StateWaiting, 240}},
		},
		{
			name:  "an SMS outage does not pause a Nostr-only activist",
			n:     2,
			via:   smsAnd1,
			steps: []step{tick(0), down(30, transport.SMS), tick(105)},
			want:  []want{{0, StateMissed, 105}, {1, StateMissed, 105}},
		},
		{
			name: "short Nostr outage while SMS stays up: the SMS activist's deadline does not move",
			n:    2,
			via:  smsAnd1,
			steps: []step{
				tick(0), down(30, transport.Nostr), up(50, transport.Nostr), tick(105),
			},
			want: []want{{0, StateMissed, 105}, {1, StateReminder, 125}}, // Nostr-only: moved by the 20 min outage
		},
		{
			name:  "a window that starts while only Nostr is down starts running for the SMS activist",
			n:     2,
			via:   smsAnd1,
			steps: []step{down(-10, transport.Nostr), tick(0), tick(105)},
			want:  []want{{0, StateMissed, 105}, {1, StatePaused, 105}},
		},
		{
			name: "duress by SMS during a Nostr outage raises the alarm",
			n:    2,
			via:  smsAnd1,
			steps: []step{
				tick(0), down(30, transport.Nostr),
				evVia(40, transport.SMS, 0, message.KindDuressCheckIn, 40, 1),
			},
			want:     []want{{0, StateAlarm, -1}, {1, StatePaused, 105}},
			contains: []string{"forced"},
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) { run(t, c) })
	}
}

// Still-paused re-alerts: an offline pause never ends on its own, so Tick
// re-alerts every StillPausedEvery (default 24h) while one holds. Made-up
// data only, like the rest of this file.
func TestStillPaused(t *testing.T) {
	both := []transport.Kind{transport.Nostr, transport.SMS}
	cases := []tcase{
		{
			name:     "offline pause re-alerts every 24 hours by default",
			steps:    []step{tick(0), offline(60), tick(1000), tick(1500), tick(2940)},
			notices:  []string{"started/0", "paused/0", "still-paused/0", "still-paused/0"},
			want:     []want{{0, StatePaused, 105}},
			contains: []string{"bado imesimama / still paused", "2026-01-15T11:00:00Z", "2026-01-15T11:45:00Z"},
		},
		{
			name:    "short interval re-alerts on cadence from the last notice",
			cfg:     Config{StillPausedEvery: time.Hour},
			steps:   []step{tick(0), offline(60), tick(119), tick(120), tick(179), tick(180)},
			notices: []string{"started/0", "paused/0", "still-paused/0", "still-paused/0"},
			want:    []want{{0, StatePaused, 105}},
		},
		{
			name: "each activist re-alerts on their own pause time",
			n:    2,
			cfg:  Config{StillPausedEvery: time.Hour},
			via:  map[int][]transport.Kind{0: both},
			steps: []step{
				tick(0), down(60, transport.Nostr), down(70, transport.SMS),
				tick(120), tick(130),
			},
			notices: []string{"started/0", "started/1", "paused/1", "paused/0", "still-paused/1", "still-paused/0"},
			want:    []want{{0, StatePaused, 105}, {1, StatePaused, 105}},
		},
		{
			name:    "the cadence resets when the pause lifts",
			cfg:     Config{StillPausedEvery: time.Hour},
			steps:   []step{tick(0), offline(60), tick(120), online(130), offline(140), tick(199), tick(200)},
			notices: []string{"started/0", "paused/0", "still-paused/0", "resumed/0", "paused/0", "still-paused/0"},
			want:    []want{{0, StatePaused, 250}},
		},
		{
			name:    "negative interval disables the re-alert",
			cfg:     Config{StillPausedEvery: -1},
			steps:   []step{tick(0), offline(60), tick(1500), tick(3000)},
			notices: []string{"started/0", "paused/0"},
			want:    []want{{0, StatePaused, 105}},
		},
		{
			name:    "a going-dark hold alone never re-alerts",
			steps:   []step{tick(0), gdark(30, 0, 30, 1, 48*time.Hour, 60*time.Minute), tick(1470), tick(2000), tick(2910)},
			notices: []string{"started/0", "going-dark/0", "dark-expired/0"},
			want:    []want{{0, StateWaiting, 2970}},
		},
		{
			name:  "a mass-silence hold never re-alerts",
			n:     3,
			cfg:   Config{StillPausedEvery: 30 * time.Minute},
			steps: []step{tick(0), tick(105), tick(135), tick(200)},
			notices: []string{
				"started/0", "started/1", "started/2",
				"reminder/0", "reminder/1", "reminder/2",
				"mass-silence/0", "mass-silence/1", "mass-silence/2",
			},
			want: []want{{0, StatePaused, 225}, {1, StatePaused, 225}, {2, StatePaused, 225}},
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) { run(t, c) })
	}
}

func TestStillPausedNotice(t *testing.T) {
	e := New(Config{}, t0)
	if e.cfg.StillPausedEvery != 24*time.Hour {
		t.Fatalf("default StillPausedEvery %v, want 24h", e.cfg.StillPausedEvery)
	}
	if d := New(Config{StillPausedEvery: -1}, t0).cfg.StillPausedEvery; d != -1 {
		t.Fatalf("negative StillPausedEvery became %v, want -1 (disabled)", d)
	}
	if err := e.Watch(key(0), window()); err != nil {
		t.Fatal(err)
	}
	e.Tick(t0)
	e.SetOnline(at(60), false)
	ns := e.Tick(at(1500))
	if len(ns) != 1 || ns[0].Kind != NoticeStillPaused {
		t.Fatalf("notices %+v, want one still-paused", ns)
	}
	n := ns[0]
	if n.Kind.String() != "still-paused" {
		t.Errorf("kind %q, want still-paused", n.Kind)
	}
	if !n.At.Equal(at(1500)) {
		t.Errorf("at %v, want +1500m", n.At.Sub(t0))
	}
	if !n.Deadline.Equal(at(105)) {
		t.Errorf("deadline +%v, want +105m", n.Deadline.Sub(t0))
	}
	if n.Activist != key(0) {
		t.Errorf("activist %v, want key(0)", n.Activist)
	}
}

func TestStandDown(t *testing.T) {
	e := New(Config{}, t0)
	if err := e.Watch(key(0), window()); err != nil {
		t.Fatal(err)
	}
	e.Tick(t0)
	e.Observe(at(10), transport.Nostr, message.Event{Kind: message.KindPanic, CreatedAt: at(10), Seq: 1, Signer: key(0)})
	e.Observe(at(20), transport.Nostr, message.Event{Kind: message.KindAllClear, CreatedAt: at(20), Seq: 2, Signer: key(0)})
	if s, _ := e.State(key(0)); s != StateAlarm || !e.ActivistSaysSafe(key(0)) {
		t.Fatalf("after activist all-clear: state %v, says safe %v", s, e.ActivistSaysSafe(key(0)))
	}
	ns := e.StandDown(at(40), key(0))
	if s, _ := e.State(key(0)); s != StateCleared || e.ActivistSaysSafe(key(0)) {
		t.Fatalf("after stand-down: state %v", s)
	}
	if len(ns) != 1 || ns[0].Kind != NoticeCleared || !strings.Contains(ns[0].Reason, "guardians stood down") {
		t.Errorf("stand-down notices %+v", ns)
	}
	if ns := e.StandDown(at(50), key(0)); len(ns) != 0 {
		t.Errorf("second stand-down raised %+v", ns)
	}
	if ns := e.StandDown(at(50), key(9)); len(ns) != 0 {
		t.Errorf("stand-down for an unknown activist raised %+v", ns)
	}
}

func TestEngineTransports(t *testing.T) {
	e := New(Config{}, t0)
	if err := e.Watch(key(0), window(), transport.Mesh); err == nil {
		t.Error("mesh accepted before it is supported")
	}
	if err := e.Watch(key(0), window(), transport.Unknown); err == nil {
		t.Error("unknown transport accepted")
	}
	if err := e.Watch(key(0), window(), transport.SMS, transport.SMS, transport.Nostr); err != nil {
		t.Fatal(err)
	}
	if got := e.acts[key(0)].via; len(got) != 2 {
		t.Errorf("duplicate transports not folded: %v", got)
	}
	if !e.Online() || !e.Up(transport.Nostr) || !e.Up(transport.SMS) {
		t.Error("a new engine should start with every transport up")
	}
	e.SetTransport(t0, transport.Mesh, false) // unsupported: ignored
	if !e.Online() {
		t.Error("an unsupported transport changed connectivity")
	}
	e.SetTransport(t0, transport.SMS, false)
	if e.Online() || e.Up(transport.SMS) || !e.Up(transport.Nostr) {
		t.Error("SMS down should leave Nostr up and Online false")
	}
}

func TestWindowValidate(t *testing.T) {
	ok := window()
	bad := map[string]func(w *Window){
		"no start":          func(w *Window) { w.Start = time.Time{} },
		"end before start":  func(w *Window) { w.End = w.Start },
		"zero interval":     func(w *Window) { w.Interval = 0 },
		"negative grace":    func(w *Window) { w.Grace = -time.Minute },
		"reminder too long": func(w *Window) { w.Reminder = w.Interval },
	}
	if err := ok.Validate(); err != nil {
		t.Fatal(err)
	}
	for name, f := range bad {
		w := window()
		f(&w)
		if w.Validate() == nil {
			t.Errorf("%s: want error", name)
		}
	}
	e := New(Config{}, t0)
	if e.Watch(key(0), Window{}) == nil {
		t.Error("Watch accepted a bad window")
	}
}

func TestNoGoroutines(t *testing.T) {
	before := runtime.NumGoroutine()
	e := New(Config{}, t0)
	for i := 0; i < 50; i++ {
		_ = e.Watch(key(i), window())
	}
	for m := 0; m < 600; m += 5 {
		e.Tick(at(m))
		if m == 200 {
			e.SetOnline(at(m), false)
		}
		if m == 260 {
			e.SetOnline(at(m), true)
		}
	}
	if after := runtime.NumGoroutine(); after > before {
		t.Errorf("goroutines: %d before, %d after", before, after)
	}
}

func TestSenders(t *testing.T) {
	e := New(Config{}, t0)
	_ = e.Watch(key(2), window())
	_ = e.Watch(key(0), window())
	_ = e.Watch(key(0), window()) // replace, not duplicate
	s := e.Senders()
	if len(s) != 2 || s[0] != key(0) || s[1] != key(2) {
		t.Errorf("senders %v", s)
	}
}
