package message

import (
	"bytes"
	"crypto/sha256"
	"errors"
	"strings"
	"testing"
	"time"
)

// goodGoingDark is a valid going-dark payload with made-up data only.
func goodGoingDark() GoingDark {
	return GoingDark{
		Dark:         48 * time.Hour,
		ReturnWindow: 2 * time.Hour,
		PlanStart:    now.Add(-time.Hour).Truncate(time.Second),
		PlanEnd:      now.Add(7 * time.Hour).Truncate(time.Second),
		Interval:     90 * time.Minute,
		Grace:        15 * time.Minute,
		Reminder:     10 * time.Minute,
		Transports:   GoingDarkNostr | GoingDarkSMS,
		AlertHash:    sha256.Sum256([]byte("made-up alert KISUMU-C")),
		ShutdownRef:  "KISUMU-C",
	}
}

func checkGoingDarkEqual(t *testing.T, want, got GoingDark) {
	t.Helper()
	if got.Dark != want.Dark || got.ReturnWindow != want.ReturnWindow ||
		!got.PlanStart.Equal(want.PlanStart) || !got.PlanEnd.Equal(want.PlanEnd) ||
		got.Interval != want.Interval || got.Grace != want.Grace || got.Reminder != want.Reminder ||
		got.Transports != want.Transports || got.AlertHash != want.AlertHash ||
		got.ShutdownRef != want.ShutdownRef {
		t.Fatalf("going-dark mismatch:\n got %+v\nwant %+v", got, want)
	}
}

func TestGoingDarkRoundTrip(t *testing.T) {
	activist := key(t, activistHex)
	g1 := key(t, guardian1Hex)
	in := goodGoingDark()
	e, err := GoingDarkEvent(in, 9, now)
	if err != nil {
		t.Fatal(err)
	}
	if e.Kind != KindGoingDark || e.Seq != 9 || !e.CreatedAt.Equal(now) {
		t.Fatalf("event header %+v", e)
	}
	wire, err := Seal(e, activist, PublicKey(g1))
	if err != nil {
		t.Fatal(err)
	}
	text := EncodeText(wire)
	if len(text) != TextSize {
		t.Fatalf("going-dark text is %d chars, want %d: it must look like every other kind", len(text), TextSize)
	}
	back, err := DecodeText(text)
	if err != nil {
		t.Fatal(err)
	}
	opened, err := Open(back, g1, [][32]byte{PublicKey(activist)}, now)
	if err != nil {
		t.Fatal(err)
	}
	got, err := ParseGoingDark(*opened)
	if err != nil {
		t.Fatal(err)
	}
	checkGoingDarkEqual(t, in, got)
}

func TestGoingDarkSizes(t *testing.T) {
	short := goodGoingDark()
	short.ShutdownRef = ""
	e, err := GoingDarkEvent(short, 1, now)
	if err != nil {
		t.Fatal(err)
	}
	if len(e.Body) != 63 {
		t.Fatalf("empty ref body is %d bytes, want 63", len(e.Body))
	}
	long := goodGoingDark()
	long.ShutdownRef = strings.Repeat("s", MaxShutdownRef)
	e, err = GoingDarkEvent(long, 1, now)
	if err != nil {
		t.Fatal(err)
	}
	if len(e.Body) != MaxBodySize {
		t.Fatalf("longest body is %d bytes, want %d", len(e.Body), MaxBodySize)
	}
	tooLong := goodGoingDark()
	tooLong.ShutdownRef = strings.Repeat("s", MaxShutdownRef+1)
	if _, err := GoingDarkEvent(tooLong, 1, now); !errors.Is(err, ErrGoingDark) {
		t.Fatalf("ref of %d bytes accepted: %v", MaxShutdownRef+1, err)
	}
}

// TestGoingDarkVector pins the exact body bytes so the layout cannot drift
// by accident. Field values are made up.
func TestGoingDarkVector(t *testing.T) {
	g := GoingDark{
		Dark:         2 * time.Hour,
		ReturnWindow: 90 * time.Minute,
		PlanStart:    time.Date(2026, 1, 15, 10, 0, 0, 0, time.UTC),
		PlanEnd:      time.Date(2026, 1, 15, 18, 0, 0, 0, time.UTC),
		Interval:     90 * time.Minute,
		Grace:        15 * time.Minute,
		Reminder:     10 * time.Minute,
		Transports:   GoingDarkNostr,
		AlertHash:    sha256.Sum256([]byte("made-up alert KISUMU-C")),
		ShutdownRef:  "C",
	}
	e, err := GoingDarkEvent(g, 1, now)
	if err != nil {
		t.Fatal(err)
	}
	want := "0100001c20000015186968baa069692b20000015180000038400000258" +
		"e1608ac9d307b688d513a53cd7a1a2f73eb1f0a21808debe322b8b35c450995c" +
		"010143"
	if got := bytes.ToLower([]byte(hexString(e.Body))); string(got) != want {
		t.Fatalf("body layout changed:\n got %s\nwant %s", got, want)
	}
	if _, err := ParseGoingDark(e); err != nil {
		t.Fatal(err)
	}
}

func hexString(b []byte) string {
	const digits = "0123456789abcdef"
	out := make([]byte, 0, len(b)*2)
	for _, c := range b {
		out = append(out, digits[c>>4], digits[c&0xf])
	}
	return string(out)
}

func TestGoingDarkRejects(t *testing.T) {
	base := goodGoingDark()
	mutate := map[string]func(g *GoingDark){
		"dark is zero":           func(g *GoingDark) { g.Dark = 0 },
		"dark under a minute":    func(g *GoingDark) { g.Dark = 30 * time.Second },
		"dark over seven days":   func(g *GoingDark) { g.Dark = MaxGoingDark + time.Second },
		"dark not whole seconds": func(g *GoingDark) { g.Dark = 90*time.Second + 500*time.Millisecond },
		"return is zero":         func(g *GoingDark) { g.ReturnWindow = 0 },
		"return over seven days": func(g *GoingDark) { g.ReturnWindow = MaxGoingDark + time.Second },
		"plan end before start":  func(g *GoingDark) { g.PlanEnd = g.PlanStart.Add(-time.Hour) },
		"plan end equals start":  func(g *GoingDark) { g.PlanEnd = g.PlanStart },
		"plan start not seconds": func(g *GoingDark) { g.PlanStart = g.PlanStart.Add(500 * time.Millisecond) },
		"interval is zero":       func(g *GoingDark) { g.Interval = 0 },
		"reminder equals inter":  func(g *GoingDark) { g.Reminder = g.Interval },
		"reminder over interval": func(g *GoingDark) { g.Reminder = g.Interval + time.Minute },
		"no transports":          func(g *GoingDark) { g.Transports = 0 },
		"unknown transport bit":  func(g *GoingDark) { g.Transports |= 0x04 },
		"ref not utf-8":          func(g *GoingDark) { g.ShutdownRef = "C\xff" },
	}
	for name, f := range mutate {
		t.Run(name, func(t *testing.T) {
			g := base
			f(&g)
			if err := g.Check(); !errors.Is(err, ErrGoingDark) {
				t.Fatalf("Check: got %v, want %v", err, ErrGoingDark)
			}
			if _, err := GoingDarkEvent(g, 1, now); !errors.Is(err, ErrGoingDark) {
				t.Fatalf("GoingDarkEvent: got %v, want %v", err, ErrGoingDark)
			}
		})
	}

	good, err := GoingDarkEvent(base, 1, now)
	if err != nil {
		t.Fatal(err)
	}
	raw := map[string]func() Event{
		"wrong kind": func() Event {
			e := good
			e.Kind = KindCheckIn
			return e
		},
		"truncated": func() Event {
			e := good
			e.Body = e.Body[:len(e.Body)-1]
			return e
		},
		"too short": func() Event {
			e := good
			e.Body = e.Body[:10]
			return e
		},
		"trailing byte": func() Event {
			e := good
			e.Body = append(append([]byte(nil), e.Body...), 0x00)
			return e
		},
		"bad version": func() Event {
			e := good
			b := append([]byte(nil), e.Body...)
			b[0] = 0x02
			e.Body = b
			return e
		},
		"ref length lies": func() Event {
			e := good
			b := append([]byte(nil), e.Body...)
			b[62]++
			e.Body = b
			return e
		},
	}
	for name, f := range raw {
		t.Run(name, func(t *testing.T) {
			if _, err := ParseGoingDark(f()); !errors.Is(err, ErrGoingDark) {
				t.Fatalf("got %v, want %v", err, ErrGoingDark)
			}
		})
	}
}

func TestGoingDarkBounds(t *testing.T) {
	// The edges of the valid range are accepted: one minute and seven days.
	for _, d := range []time.Duration{time.Minute, MaxGoingDark} {
		g := goodGoingDark()
		g.Dark, g.ReturnWindow = d, d
		e, err := GoingDarkEvent(g, 1, now)
		if err != nil {
			t.Fatalf("dark %v: %v", d, err)
		}
		got, err := ParseGoingDark(e)
		if err != nil || got.Dark != d || got.ReturnWindow != d {
			t.Fatalf("dark %v: %+v %v", d, got, err)
		}
	}
}
