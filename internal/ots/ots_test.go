package ots

import (
	"strings"
	"testing"
	"time"

	"github.com/altradits/kengele/internal/message"
)

// Made-up test event. Never a real person, key, place or number.
func testEvent() *message.Event {
	var signer [32]byte
	for i := range signer {
		signer[i] = 0xA1
	}
	return &message.Event{
		Kind:      message.KindPanic,
		CreatedAt: time.Date(2026, 1, 15, 14, 7, 0, 0, time.UTC),
		Seq:       7,
		Body:      []byte("Kijiji cha Mfano"),
		Signer:    signer,
	}
}

func TestAlarmLevel(t *testing.T) {
	alarm := []message.Kind{message.KindDuressCheckIn, message.KindPanic, message.KindMissed, message.KindGuardianConfirm, message.KindAllClear}
	routine := []message.Kind{message.KindCheckIn, message.KindGoingDark, message.Kind(0), message.Kind(99)}
	for _, k := range alarm {
		if !AlarmLevel(k) {
			t.Errorf("%s: not alarm-level", k)
		}
	}
	for _, k := range routine {
		if AlarmLevel(k) {
			t.Errorf("%s: alarm-level, want routine", k)
		}
	}
}

func TestDigestDeterministic(t *testing.T) {
	a, b := Digest(testEvent()), Digest(testEvent())
	if a != b {
		t.Fatal("same event gave different digests")
	}
	other := testEvent()
	other.Seq++
	if Digest(other) == a {
		t.Fatal("different events gave the same digest")
	}
	other = testEvent()
	other.Kind = message.KindCheckIn
	if Digest(other) == a {
		t.Fatal("kind change did not change the digest")
	}
	// The digest is over the canonical bytes.
	if got := Canonical(testEvent()); len(got) == 0 {
		t.Fatal("empty canonical encoding")
	}
}

func TestStatusBilingual(t *testing.T) {
	for _, s := range []Status{StatusQueued, StatusPending, StatusAnchored, StatusSkipped} {
		if StatusLine(s) == "" {
			t.Errorf("%s: empty status line", s)
		}
	}
	// Every user-facing line carries both languages.
	for _, s := range []Status{StatusQueued, StatusPending, StatusAnchored} {
		line := StatusLine(s)
		hasSwahili, hasEnglish := false, false
		for _, word := range []string{"Muhuri", "unasubiri", "umethibitishwa", "umetumwa", "umepangwa", "mtandao"} {
			if strings.Contains(line, word) {
				hasSwahili = true
			}
		}
		for _, word := range []string{"Timestamp", "timestamp", "Bitcoin", "network", "confirmed", "queued", "waiting"} {
			if strings.Contains(line, word) {
				hasEnglish = true
			}
		}
		if !hasSwahili || !hasEnglish {
			t.Errorf("%s: not bilingual: %q", s, line)
		}
	}
}
