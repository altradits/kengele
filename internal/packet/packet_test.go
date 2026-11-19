package packet

import (
	"strings"
	"testing"
	"time"

	"github.com/altradits/kengele/internal/message"
)

// All names, IDs, keys, places, and times here are made up for tests.
// Never use them for anything real.

var (
	testSignerA = strings.Repeat("a1", 32)
	testSignerB = strings.Repeat("b2", 32)
	testSignerC = strings.Repeat("c3", 32)
)

func testRecord() Record {
	return Record{
		SubjectName:   "Amina Mfano",
		NationalID:    "MFANO-000001",
		LastSeenAt:    time.Date(2026, 3, 10, 14, 30, 0, 0, time.UTC),
		LastSeenPlace: "Mfano Market, Kisumu (made-up)",
		Statements: []GuardianStatement{
			{
				Guardian:  "Guardian One (made-up)",
				Statement: "I tried calling twice with no answer. / Nilijaribu kupiga simu mara mbili.",
				SignedAt:  time.Date(2026, 3, 10, 16, 5, 0, 0, time.UTC),
			},
			{
				Guardian:  "Guardian Two (made-up)",
				Statement: "I went to the meeting point; she was not there.",
				SignedAt:  time.Date(2026, 3, 10, 16, 20, 0, 0, time.UTC),
			},
		},
		Events: []LoggedEvent{
			{Kind: "check-in", At: time.Date(2026, 3, 10, 13, 0, 0, 0, time.UTC), Seq: 12, Signer: testSignerA, OTS: OTSVerified, Note: "last check-in before silence"},
			{Kind: "missed", At: time.Date(2026, 3, 10, 14, 30, 0, 0, time.UTC), Seq: 3, Signer: testSignerB, OTS: OTSPending, Note: "deadline passed"},
			{Kind: "guardian-confirm", At: time.Date(2026, 3, 10, 16, 20, 0, 0, time.UTC), Seq: 4, Signer: testSignerC, OTS: OTSMissing},
		},
		GeneratedAt: time.Date(2026, 3, 10, 17, 0, 0, 0, time.UTC),
	}
}

func TestRenderFillsEverySection(t *testing.T) {
	out, err := Render(testRecord())
	if err != nil {
		t.Fatal(err)
	}
	want := []string{
		"Amina Mfano",
		"MFANO-000001",
		"2026-03-10 14:30 UTC",
		"Mfano Market, Kisumu (made-up)",
		"Guardian One (made-up)",
		"I tried calling twice",
		"2026-03-10 16:05 UTC",
		"Guardian Two (made-up)",
		"2026-03-10 16:20 UTC",
		// Event log: original signed time + OTS status per row.
		"check-in",
		"2026-03-10 13:00 UTC",
		testSignerA,
		"verified / imethibitishwa",
		"pending / inasubiri",
		"missing / haipo",
		// Advocate gate, in both languages, and the timing caveat.
		"A Kenyan advocate must review this packet before any real use.",
		"Wakili wa Kenya lazima apitie jalada hili kabla ya matumizi yoyote halisi.",
		"claims until anchored",
		"threat model, section 6.10",
		// Bilingual headings.
		"Subject / Mtu",
		"Guardian statements / Maelezo ya walinzi",
		"Event log / Kumbukumbu za matukio",
		"National ID / Kitambulisho cha Taifa",
		"Time last seen / Muda alipoonekana mwisho",
		"Place last seen / Mahali alipoonekana mwisho",
	}
	for _, w := range want {
		if !strings.Contains(out, w) {
			t.Errorf("packet is missing %q\n%s", w, out)
		}
	}
}

func TestRenderWithoutID(t *testing.T) {
	r := testRecord()
	r.NationalID = "  "
	out, err := Render(r)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out, "not provided / haikutolewa") {
		t.Fatalf("missing ID should render as not provided:\n%s", out)
	}
}

func TestRenderEscapesTableBreaks(t *testing.T) {
	r := testRecord()
	r.Events[0].Note = "a|b\nc"
	out, err := Render(r)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out, `a\|b c`) {
		t.Fatalf("note should escape pipes and newlines:\n%s", out)
	}
}

func TestFromMessageEventKeepsSignedTime(t *testing.T) {
	var signer [32]byte
	for i := range signer {
		signer[i] = 0xa1
	}
	at := time.Date(2026, 3, 10, 13, 0, 0, 0, time.UTC)
	ev := message.Event{Kind: message.KindPanic, CreatedAt: at, Seq: 9, Signer: signer}
	got := FromMessageEvent(ev, OTSVerified, "made-up note")
	if got.Kind != "panic" || !got.At.Equal(at) || got.Seq != 9 || got.OTS != OTSVerified || got.Note != "made-up note" {
		t.Fatalf("FromMessageEvent mismatch: %+v", got)
	}
	if got.Signer != testSignerA {
		t.Fatalf("signer = %s, want %s", got.Signer, testSignerA)
	}
	r := testRecord()
	r.Events = []LoggedEvent{got}
	if _, err := Render(r); err != nil {
		t.Fatalf("converted event should render: %v", err)
	}
}

func TestRenderRejects(t *testing.T) {
	base := testRecord()
	tests := []struct {
		name string
		mut  func(*Record)
		want string
	}{
		{"missing name", func(r *Record) { r.SubjectName = " " }, "jina"},
		{"missing place", func(r *Record) { r.LastSeenPlace = "" }, "mahali"},
		{"zero last-seen time", func(r *Record) { r.LastSeenAt = time.Time{} }, "muda"},
		{"no statements", func(r *Record) { r.Statements = nil }, "mlinzi"},
		{"empty guardian", func(r *Record) { r.Statements[0].Guardian = " " }, "mlinzi"},
		{"empty statement text", func(r *Record) { r.Statements[1].Statement = "" }, "maelezo"},
		{"zero statement time", func(r *Record) { r.Statements[0].SignedAt = time.Time{} }, "kutia saini"},
		{"no events", func(r *Record) { r.Events = nil }, "tukio"},
		{"empty kind", func(r *Record) { r.Events[0].Kind = "" }, "aina"},
		{"zero event time", func(r *Record) { r.Events[1].At = time.Time{} }, "signed time"},
		{"bad signer", func(r *Record) { r.Events[0].Signer = "not-hex" }, "hex"},
		{"short signer", func(r *Record) { r.Events[0].Signer = strings.Repeat("ab", 16) }, "hex"},
		{"bad OTS status", func(r *Record) { r.Events[2].OTS = "sealed" }, "OpenTimestamps"},
		{"empty OTS status", func(r *Record) { r.Events[2].OTS = "" }, "OpenTimestamps"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			r := base
			r.Statements = append([]GuardianStatement(nil), base.Statements...)
			r.Events = append([]LoggedEvent(nil), base.Events...)
			tt.mut(&r)
			_, err := Render(r)
			if err == nil {
				t.Fatalf("Render accepted an invalid record (%s)", tt.name)
			}
			if !strings.Contains(strings.ToLower(err.Error()), strings.ToLower(tt.want)) {
				t.Fatalf("error %q should mention %q", err, tt.want)
			}
			// Errors are user-facing, so they must carry both languages.
			if !strings.Contains(err.Error(), "/") {
				t.Fatalf("error %q should be bilingual (English/Swahili)", err)
			}
		})
	}
}
