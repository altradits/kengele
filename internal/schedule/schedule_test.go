package schedule

import (
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/altradits/kengele/internal/message"
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
)

type step struct {
	op   op
	at   int // engine time, minutes after t0
	who  int // activist index
	kind message.Kind
	sig  int // signed CreatedAt, minutes after t0
	seq  uint32
}

func tick(m int) step    { return step{op: opTick, at: m} }
func offline(m int) step { return step{op: opOffline, at: m} }
func online(m int) step  { return step{op: opOnline, at: m} }
func ev(m int, who int, k message.Kind, sig int, seq uint32) step {
	return step{op: opEvent, at: m, who: who, kind: k, sig: sig, seq: seq}
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
		if err := e.Watch(key(i), win()); err != nil {
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
			got = append(got, e.Observe(now, message.Event{Kind: s.kind, CreatedAt: at(s.sig), Seq: s.seq, Signer: key(s.who)})...)
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
				ev(200, 0, message.KindCheckIn, 80, 1), // arrives by SMS at +200, signed at +80
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
			name:    "all clear stands down",
			steps:   []step{tick(0), tick(106), ev(120, 0, message.KindAllClear, 120, 1), tick(500)},
			notices: []string{"started/0", "reminder/0", "missed/0", "cleared/0"},
			want:    []want{{0, StateCleared, -1}},
		},
		{
			name:    "window that starts during a shutdown starts paused",
			steps:   []step{offline(-10), tick(0), tick(200), online(220)},
			notices: []string{"started/0", "resumed/0"},
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
