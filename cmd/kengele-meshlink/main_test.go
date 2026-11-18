package main

import (
	"bytes"
	"errors"
	"regexp"
	"strings"
	"testing"

	"github.com/altradits/kengele/internal/message"
)

func run(t *testing.T, args ...string) string {
	t.Helper()
	var buf bytes.Buffer
	if err := cli(args, &buf); err != nil {
		t.Fatalf("cli(%v) err = %v", args, err)
	}
	return buf.String()
}

func TestDemoEN(t *testing.T) {
	out := run(t, "demo")
	for _, want := range []string{
		"Broadcast over Bluetooth",
		"Mesh: 2 phones nearby",
		"Relaying blindly",
		"A broadcast msg 0x", "frag 0/2 ttl 7", "frag 1/2 ttl 7",
		"B relay msg 0x", "frag 0/2 ttl 6 (blind: no decrypt)",
		"B reassembled 228 opaque bytes (blind: no decrypt)",
		"C reassembled 228B",
		"C delivered: kind=check-in seq=1 created=",
		"hops=2",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("demo output lacks %q\n%s", want, out)
		}
	}
	// The middle node logs hop control only: no kind, body or key.
	for _, leak := range []string{"Mtihani-trill", "panic", "a1a1", "b2b2"} {
		if strings.Contains(out, leak) {
			t.Errorf("demo output leaks %q\n%s", leak, out)
		}
	}
	if !regexp.MustCompile(`created=\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z`).MatchString(out) {
		t.Errorf("delivered line lacks an RFC3339 timestamp\n%s", out)
	}
}

func TestDemoSwahili(t *testing.T) {
	out := run(t, "demo", "--sw")
	for _, want := range []string{
		"Tangaza kwa Bluetooth",
		"Mesh: simu 2 karibu",
		"Inasambaza bila kusoma",
		"A imetangaza msg 0x", "kipande 0/2 ttl 7",
		"B inasambaza msg 0x", "(bila kusoma)",
		"C imeunganisha 228B",
		"C imepokea: kind=check-in seq=1 created=",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("sw demo output lacks %q\n%s", want, out)
		}
	}
}

func TestDemoDropMiddle(t *testing.T) {
	out := run(t, "demo", "--drop", "B")
	if !strings.Contains(out, "B drops everything (--drop B)") {
		t.Errorf("no drop notice\n%s", out)
	}
	if !strings.Contains(out, "C received 0 (no path); silence fails safe") {
		t.Errorf("no honest-silence line\n%s", out)
	}
	if strings.Contains(out, "delivered") {
		t.Errorf("delivery past a dropping middle node\n%s", out)
	}
	outSw := run(t, "demo", "--drop", "B", "--sw")
	if !strings.Contains(outSw, "C imepokea 0 (hakuna njia); kimya ni salama") {
		t.Errorf("no Swahili silence line\n%s", outSw)
	}
}

func TestDemoShortTTL(t *testing.T) {
	out := run(t, "demo", "--ttl", "1")
	if !strings.Contains(out, "C received 0 (no path); silence fails safe") {
		t.Errorf("TTL 1 should strand C\n%s", out)
	}
	if !strings.Contains(out, "B reassembled 228 opaque bytes") {
		t.Errorf("TTL 1 should still reach B\n%s", out)
	}
}

func TestQR(t *testing.T) {
	out := run(t, "qr")
	lines := strings.Split(strings.TrimSpace(out), "\n")
	if len(lines) != 2 {
		t.Fatalf("qr printed %d lines, want 2\n%s", len(lines), out)
	}
	if len(lines[0]) != message.TextSize {
		t.Fatalf("qr text is %d chars, want %d", len(lines[0]), message.TextSize)
	}
	wire, err := message.DecodeText(lines[0])
	if err != nil {
		t.Fatalf("qr text does not decode: %v", err)
	}
	if len(wire) != message.WireSize {
		t.Fatalf("qr wire is %d bytes, want %d", len(wire), message.WireSize)
	}
	if lines[1] != "Show this QR / Onyesha QR hii" {
		t.Errorf("qr second line = %q", lines[1])
	}
}

func TestVersionAndHelp(t *testing.T) {
	if out := run(t, "version"); !strings.Contains(out, "kengele-meshlink") {
		t.Errorf("version = %q", out)
	}
	if out := run(t, "help"); !strings.Contains(out, "kengele-meshlink demo") {
		t.Errorf("help lacks usage\n%s", out)
	}
}

func TestMisuse(t *testing.T) {
	for _, args := range [][]string{
		{},
		{"demos"},
		{"demo", "--ttl", "abc"},
		{"demo", "--ttl", "-1"},
		{"demo", "--ttl", "256"},
		{"demo", "--drop", "Z"},
		{"demo", "extra"},
		{"demo", "--unknown"},
		{"qr", "extra"},
	} {
		var buf bytes.Buffer
		err := cli(args, &buf)
		if !errors.Is(err, errMisuse) {
			t.Errorf("cli(%v) err = %v, want misuse", args, err)
		}
	}
}
