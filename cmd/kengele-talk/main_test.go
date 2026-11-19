package main

import (
	"bytes"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/altradits/kengele/internal/message"
	"github.com/altradits/kengele/internal/transport/nostr"
	"github.com/btcsuite/btcd/btcec/v2"
)

// Made-up keys for tests. Never real keys.
const (
	testPrivHex = "2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b"
	testPeerHex = "3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c"
	testPubHex  = "1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d1d"
)

func mustPriv(t *testing.T, hexKey string) *btcec.PrivateKey {
	t.Helper()
	p := filepath.Join(t.TempDir(), "k.hex")
	if err := os.WriteFile(p, []byte(hexKey+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	key, err := loadKey(io.Discard, p)
	if err != nil {
		t.Fatal(err)
	}
	return key
}

func TestSplitCSV(t *testing.T) {
	cases := map[string]int{
		"":                                     0,
		" , ":                                  0,
		"wss://relay.example.org":              1,
		"wss://a.example,wss://b.example":      2,
		" wss://a.example ,, wss://b.example ": 2,
	}
	for in, want := range cases {
		if got := splitCSV(in); len(got) != want {
			t.Errorf("splitCSV(%q) = %d parts, want %d", in, len(got), want)
		}
	}
}

func TestParsePubkey(t *testing.T) {
	k, err := parsePubkey("-to", testPubHex)
	if err != nil {
		t.Fatalf("valid pubkey rejected: %v", err)
	}
	if k[0] != 0x1d || k[31] != 0x1d {
		t.Errorf("pubkey bytes wrong: %x", k)
	}
	if _, err := parsePubkey("-to", "  "+testPubHex+"\n"); err != nil {
		t.Errorf("padded pubkey rejected: %v", err)
	}
	for name, in := range map[string]string{
		"bad hex": "zzz-not-hex",
		"short":   "1d1d",
		"long":    testPubHex + "00",
		"empty":   "",
		"blanks":  "   ",
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := parsePubkey("-to", in); err == nil {
				t.Errorf("accepted %q", in)
			}
		})
	}
}

func TestParseSenders(t *testing.T) {
	if _, err := parseSenders(""); err == nil {
		t.Error("accepted empty -senders")
	}
	if _, err := parseSenders(" , "); err == nil {
		t.Error("accepted blank -senders")
	}
	got, err := parseSenders(testPubHex + "," + testPeerHex)
	if err != nil {
		t.Fatalf("valid senders rejected: %v", err)
	}
	if len(got) != 2 {
		t.Fatalf("got %d senders, want 2", len(got))
	}
	if _, err := parseSenders(testPubHex + ",zzz"); err == nil {
		t.Error("accepted a bad entry in -senders")
	}
}

func TestLoadKey(t *testing.T) {
	dir := t.TempDir()
	good := filepath.Join(dir, "good.hex")
	if err := os.WriteFile(good, []byte(testPrivHex+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	var warn bytes.Buffer
	key, err := loadKey(&warn, good)
	if err != nil {
		t.Fatalf("good key rejected: %v", err)
	}
	if key == nil || warn.Len() != 0 {
		t.Errorf("good key: key=%v warn=%q", key != nil, warn.String())
	}
	world := filepath.Join(dir, "world.hex")
	if err := os.WriteFile(world, []byte(testPrivHex), 0o644); err != nil {
		t.Fatal(err)
	}
	warn.Reset()
	if _, err := loadKey(&warn, world); err != nil {
		t.Fatalf("world-readable key rejected: %v", err)
	}
	if !strings.Contains(warn.String(), "world-readable") {
		t.Errorf("no world-readable warning: %q", warn.String())
	}
	for name, content := range map[string]string{
		"bad hex":  "zzz-not-hex",
		"short":    "2b2b",
		"empty":    "",
		"zero key": strings.Repeat("00", 32),
	} {
		t.Run(name, func(t *testing.T) {
			p := filepath.Join(dir, "bad.hex")
			if err := os.WriteFile(p, []byte(content), 0o600); err != nil {
				t.Fatal(err)
			}
			if _, err := loadKey(&warn, p); err == nil {
				t.Errorf("accepted %q", content)
			}
		})
	}
	if _, err := loadKey(&warn, filepath.Join(dir, "missing.hex")); err == nil {
		t.Error("accepted a missing key file")
	}
}

func TestKeygen(t *testing.T) {
	dir := t.TempDir()
	dst := filepath.Join(dir, "talk.hex")
	var out bytes.Buffer
	if err := cli([]string{"keygen", "-out", dst}, &out, io.Discard); err != nil {
		t.Fatal(err)
	}
	st, err := os.Stat(dst)
	if err != nil {
		t.Fatal(err)
	}
	if st.Mode().Perm() != 0o600 {
		t.Errorf("key file mode %o, want 600", st.Mode().Perm())
	}
	raw, err := os.ReadFile(dst)
	if err != nil {
		t.Fatal(err)
	}
	key, err := loadKey(io.Discard, dst)
	if err != nil {
		t.Fatalf("keygen wrote an unreadable key: %v (raw %q)", err, raw)
	}
	pub := message.PublicKey(key)
	if !strings.Contains(out.String(), fingerprint(pub)) {
		t.Errorf("output lacks the fingerprint %s:\n%s", fingerprint(pub), out.String())
	}
	// A pre-existing wide-open file is tightened to 0600.
	open := filepath.Join(dir, "open.hex")
	if err := os.WriteFile(open, []byte("old"), 0o644); err != nil {
		t.Fatal(err)
	}
	out.Reset()
	if err := cli([]string{"keygen", "-out", open}, &out, io.Discard); err != nil {
		t.Fatal(err)
	}
	if st, _ := os.Stat(open); st.Mode().Perm() != 0o600 {
		t.Errorf("overwritten key file mode %o, want 600", st.Mode().Perm())
	}
	if err := cli([]string{"keygen"}, &out, io.Discard); err == nil {
		t.Error("keygen accepted without -out")
	}
}

func TestFingerprint(t *testing.T) {
	k, _ := parsePubkey("-to", testPubHex)
	if got := fingerprint(k); got != "1d1d1d1d" {
		t.Errorf("fingerprint %q, want 1d1d1d1d", got)
	}
}

func TestBuildCheckIn(t *testing.T) {
	now := time.Date(2026, 11, 18, 12, 0, 0, 0, time.UTC)
	ev, err := buildCheckIn("habari", now)
	if err != nil {
		t.Fatal(err)
	}
	if ev.Kind != message.KindCheckIn {
		t.Errorf("kind %s, want check-in", ev.Kind)
	}
	if string(ev.Body) != "habari" {
		t.Errorf("body %q, want habari", ev.Body)
	}
	if !ev.CreatedAt.Equal(now) {
		t.Errorf("time %s, want %s", ev.CreatedAt, now)
	}
	if ev.Seq == 0 {
		t.Error("seq is zero")
	}
	// Empty note is a plain check-in.
	if _, err := buildCheckIn("", now); err != nil {
		t.Errorf("empty note rejected: %v", err)
	}
	// Exactly 86 bytes fits; 87 does not.
	if _, err := buildCheckIn(strings.Repeat("a", message.MaxBodySize), now); err != nil {
		t.Errorf("86-byte note rejected: %v", err)
	}
	if _, err := buildCheckIn(strings.Repeat("a", message.MaxBodySize+1), now); err == nil {
		t.Error("87-byte note accepted")
	} else if !strings.Contains(err.Error(), "86") {
		t.Errorf("error should name the limit: %v", err)
	}
	// Multibyte notes are measured in bytes.
	if _, err := buildCheckIn(strings.Repeat("ü", 44), now); err == nil {
		t.Error("accepted an 88-byte note")
	}
	// Two events in the same second get distinct rising seqs, so the
	// second is never dropped as a replay of the first.
	a, _ := buildCheckIn("one", now)
	b, _ := buildCheckIn("two", now)
	if a.Seq == 0 || b.Seq <= a.Seq {
		t.Errorf("seqs %d then %d, want distinct and rising", a.Seq, b.Seq)
	}
}

// TestSealOpenRoundtrip seals a built check-in and opens it as the
// recipient, like send and listen do over the wire.
func TestSealOpenRoundtrip(t *testing.T) {
	sender := mustPriv(t, testPrivHex)
	peer := mustPriv(t, testPeerHex)
	peerPub := message.PublicKey(peer)
	senderPub := message.PublicKey(sender)
	now := time.Now().Truncate(time.Second)
	ev, err := buildCheckIn("tuko salama", now)
	if err != nil {
		t.Fatal(err)
	}
	wire, err := message.Seal(ev, sender, peerPub)
	if err != nil {
		t.Fatal(err)
	}
	opened, err := message.Open(wire, peer, [][32]byte{senderPub}, now)
	if err != nil {
		t.Fatal(err)
	}
	if opened.Kind != message.KindCheckIn || string(opened.Body) != "tuko salama" {
		t.Errorf("opened %+v", opened)
	}
	if opened.Signer != senderPub {
		t.Error("signer mismatch after roundtrip")
	}
	if !opened.CreatedAt.Equal(now) {
		t.Errorf("time %s, want %s", opened.CreatedAt, now)
	}
	// Unknown senders stay unreadable.
	if _, err := message.Open(wire, peer, [][32]byte{}, now); err == nil {
		t.Error("opened with no known senders")
	}
}

func TestFormatDelivery(t *testing.T) {
	k, _ := parsePubkey("-to", testPubHex)
	ts := time.Date(2026, 11, 18, 12, 34, 56, 0, time.UTC)
	line := formatDelivery(nostr.Delivery{Event: &message.Event{
		Kind: message.KindCheckIn, CreatedAt: ts, Seq: 1, Body: []byte("habari"), Signer: k,
	}})
	for _, want := range []string{"2026-11-18T12:34:56Z", "1d1d1d1d", "habari"} {
		if !strings.Contains(line, want) {
			t.Errorf("line lacks %q: %q", want, line)
		}
	}
}

// TestSendValidation fails fast on bad input without touching the network:
// every case below must error before any relay is dialled.
func TestSendValidation(t *testing.T) {
	dir := t.TempDir()
	keyFile := filepath.Join(dir, "k.hex")
	if err := os.WriteFile(keyFile, []byte(testPrivHex), 0o600); err != nil {
		t.Fatal(err)
	}
	cases := map[string][]string{
		"no key":    {"send", "-to", testPubHex, "-relays", "wss://relay.example.org", "-note", "x"},
		"no to":     {"send", "-key", keyFile, "-relays", "wss://relay.example.org", "-note", "x"},
		"no relays": {"send", "-key", keyFile, "-to", testPubHex, "-note", "x"},
		"blank relays": {"send", "-key", keyFile, "-to", testPubHex, "-relays", " , ",
			"-note", "x"},
		"bad to":        {"send", "-key", keyFile, "-to", "zzz", "-relays", "wss://relay.example.org", "-note", "x"},
		"bad key":       {"send", "-key", filepath.Join(dir, "missing.hex"), "-to", testPubHex, "-relays", "wss://relay.example.org", "-note", "x"},
		"note too long": {"send", "-key", keyFile, "-to", testPubHex, "-relays", "wss://relay.example.org", "-note", strings.Repeat("a", 87)},
	}
	for name, args := range cases {
		t.Run(name, func(t *testing.T) {
			var out bytes.Buffer
			if err := cli(args, &out, io.Discard); err == nil {
				t.Errorf("accepted %v", args)
			}
		})
	}
}

func TestListenValidation(t *testing.T) {
	dir := t.TempDir()
	keyFile := filepath.Join(dir, "k.hex")
	if err := os.WriteFile(keyFile, []byte(testPrivHex), 0o600); err != nil {
		t.Fatal(err)
	}
	cases := map[string][]string{
		"no key":     {"listen", "-relays", "wss://relay.example.org", "-senders", testPubHex},
		"no relays":  {"listen", "-key", keyFile, "-senders", testPubHex},
		"no senders": {"listen", "-key", keyFile, "-relays", "wss://relay.example.org"},
		"bad senders": {"listen", "-key", keyFile, "-relays", "wss://relay.example.org",
			"-senders", "zzz"},
		"bad key": {"listen", "-key", filepath.Join(dir, "missing.hex"), "-relays",
			"wss://relay.example.org", "-senders", testPubHex},
	}
	for name, args := range cases {
		t.Run(name, func(t *testing.T) {
			var out bytes.Buffer
			if err := cli(args, &out, io.Discard); err == nil {
				t.Errorf("accepted %v", args)
			}
		})
	}
}

func TestCLIHelp(t *testing.T) {
	var out bytes.Buffer
	if err := cli([]string{"help"}, &out, io.Discard); err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{
		"keygen", "send", "listen", "-relays", "-senders", "-note",
		"no company server", "has no", "default, and that is the proof", "matumizi", "Hakuna seva",
		"chaguo-msingi", "Si kwa matumizi halisi",
	} {
		if !strings.Contains(out.String(), want) {
			t.Errorf("usage lacks %q:\n%s", want, out.String())
		}
	}
	if err := cli(nil, &out, io.Discard); err == nil {
		t.Error("accepted no command")
	}
	if err := cli([]string{"nope"}, &out, io.Discard); err == nil {
		t.Error("accepted an unknown command")
	}
	out.Reset()
	if err := cli([]string{"version"}, &out, io.Discard); err != nil || !strings.Contains(out.String(), version) {
		t.Errorf("version: %v %q", err, out.String())
	}
}

func TestQueuedLineIsHonest(t *testing.T) {
	// A queued send reached no relay and this process exits without
	// retrying, so the line must not promise a retry.
	line := queuedLine(message.PublicKey(mustPriv(t, testPrivHex)), message.PublicKey(mustPriv(t, testPeerHex)), 0, 2)
	for _, want := range []string{"not delivered", "does not retry", "haikutumwa", "haijaribu tena", "0 of 2"} {
		if !strings.Contains(line, want) {
			t.Errorf("queued line lacks %q: %q", want, line)
		}
	}
	if strings.Contains(line, "queued for retry") || strings.Contains(line, "imepangwa kutumwa tena") {
		t.Errorf("queued line still promises a retry: %q", line)
	}
}
