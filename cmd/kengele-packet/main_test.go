package main

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/altradits/kengele/internal/packet"
)

// Made-up record. Never real people, keys, places, or numbers.
func testRecord() packet.Record {
	return packet.Record{
		SubjectName:   "Juma Mfano",
		LastSeenAt:    time.Date(2026, 4, 2, 9, 0, 0, 0, time.UTC),
		LastSeenPlace: "Mfano Road (made-up)",
		Statements: []packet.GuardianStatement{
			{Guardian: "Guardian One (made-up)", Statement: "Made-up statement.", SignedAt: time.Date(2026, 4, 2, 10, 0, 0, 0, time.UTC)},
		},
		Events: []packet.LoggedEvent{
			{Kind: "panic", At: time.Date(2026, 4, 2, 9, 5, 0, 0, time.UTC), Seq: 1, Signer: strings.Repeat("a1", 32), OTS: packet.OTSPending},
		},
		GeneratedAt: time.Date(2026, 4, 2, 11, 0, 0, 0, time.UTC),
	}
}

func TestCLIWritesPacket(t *testing.T) {
	dir := t.TempDir()
	in := filepath.Join(dir, "record.json")
	out := filepath.Join(dir, "packet.md")
	raw, err := json.Marshal(testRecord())
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(in, raw, 0o600); err != nil {
		t.Fatal(err)
	}
	var buf bytes.Buffer
	if err := cli([]string{"-in", in, "-out", out}, &buf); err != nil {
		t.Fatal(err)
	}
	got, err := os.ReadFile(out)
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"Juma Mfano", "pending / inasubiri", "A Kenyan advocate must review"} {
		if !strings.Contains(string(got), want) {
			t.Errorf("packet is missing %q", want)
		}
	}
	fi, err := os.Stat(out)
	if err != nil {
		t.Fatal(err)
	}
	if fi.Mode().Perm() != 0o600 {
		t.Errorf("packet mode = %o, want 600", fi.Mode().Perm())
	}
}

func TestCLIFailuresAreBilingual(t *testing.T) {
	var buf bytes.Buffer
	if err := cli(nil, &buf); err == nil || !strings.Contains(err.Error(), "/") {
		t.Fatalf("missing flags should fail bilingually, got %v", err)
	}
	dir := t.TempDir()
	bad := filepath.Join(dir, "bad.json")
	if err := os.WriteFile(bad, []byte("{nope"), 0o600); err != nil {
		t.Fatal(err)
	}
	buf.Reset()
	err := cli([]string{"-in", bad, "-out", filepath.Join(dir, "o.md")}, &buf)
	if err == nil || !strings.Contains(err.Error(), "/") {
		t.Fatalf("bad JSON should fail bilingually, got %v", err)
	}
	// Valid JSON but invalid record (no name) must also fail.
	empty := filepath.Join(dir, "empty.json")
	if err := os.WriteFile(empty, []byte(`{}`), 0o600); err != nil {
		t.Fatal(err)
	}
	buf.Reset()
	if err := cli([]string{"-in", empty, "-out", filepath.Join(dir, "o2.md")}, &buf); err == nil {
		t.Fatal("invalid record should fail")
	}
}
