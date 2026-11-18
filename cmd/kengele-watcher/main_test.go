package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/altradits/kengele/internal/message"
	"github.com/altradits/kengele/internal/schedule"
	"github.com/altradits/kengele/internal/transport"
	nnostr "github.com/altradits/kengele/internal/transport/nostr"
)

const planJSON = `{"activists": [{"label": "A", "pubkey": "0101010101010101010101010101010101010101010101010101010101010101",
 "start": "2026-01-15T10:00:00Z", "end": "2026-01-15T18:00:00Z", "interval": "90m", "grace": "15m", "reminder": "10m"}]}`

const pass = "correct horse battery"

func TestCLI(t *testing.T) {
	dir := t.TempDir()
	in := filepath.Join(dir, "plan.json")
	dst := filepath.Join(dir, "plan.kgp")
	if err := os.WriteFile(in, []byte(planJSON), 0o600); err != nil {
		t.Fatal(err)
	}
	var out bytes.Buffer
	if err := cli([]string{"version"}, &out, ""); err != nil || !strings.Contains(out.String(), version) {
		t.Fatalf("version: %v %q", err, out.String())
	}
	if err := cli([]string{"seal-plan", "-in", in, "-out", dst}, &out, ""); err == nil {
		t.Error("sealed without a passphrase")
	}
	if err := cli([]string{"seal-plan", "-in", in, "-out", dst}, &out, pass); err != nil {
		t.Fatal(err)
	}
	if st, err := os.Stat(dst); err != nil || st.Mode().Perm() != 0o600 {
		t.Fatalf("plan file: %v", err)
	}
	if err := cli([]string{"run", "-plan", dst}, &out, "wrong passphrase!"); err == nil {
		t.Error("ran with the wrong passphrase")
	}
	if err := cli([]string{"run", "-plan", dst, "-down", "mesh"}, &out, pass); err == nil {
		t.Error("ran with an unsupported transport in -down")
	}
	if err := cli([]string{"nope"}, &out, ""); err == nil {
		t.Error("unknown command accepted")
	}
}

type fakeTransport struct {
	mu  sync.Mutex
	evs []delivery
}

func (f *fakeTransport) Receive() []delivery {
	f.mu.Lock()
	defer f.mu.Unlock()
	e := f.evs
	f.evs = nil
	return e
}

func (f *fakeTransport) send(via transport.Kind, e message.Event) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.evs = append(f.evs, delivery{via: via, ev: e})
}

func TestWatchLoop(t *testing.T) {
	plan, err := schedule.ParsePlan([]byte(planJSON))
	if err != nil {
		t.Fatal(err)
	}
	start := time.Date(2026, 1, 15, 10, 0, 0, 0, time.UTC)
	now := func() time.Time { return start }
	ticks := make(chan time.Time)
	var out bytes.Buffer
	done := make(chan error)
	k, _ := plan.Activists[0].Key()
	tr := &fakeTransport{}
	go func() {
		done <- watch(context.Background(), plan, ticks, now, staticProbe{}, tr, &out)
	}()
	for _, m := range []int{30, 60, 106} {
		clock := start.Add(time.Duration(m) * time.Minute)
		if m == 60 {
			tr.send(transport.Nostr, message.Event{Kind: message.KindCheckIn, CreatedAt: clock.Add(-time.Minute), Seq: 1, Signer: k})
		}
		ticks <- clock
	}
	close(ticks)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	s := out.String()
	for _, want := range []string{"started", "not for real use"} {
		if !strings.Contains(s, want) {
			t.Errorf("output lacks %q:\n%s", want, s)
		}
	}
	if strings.Contains(s, "missed") {
		t.Errorf("check-in at +59 should cover +106:\n%s", s)
	}
}

// With Nostr down, an SMS activist checking in by SMS stays covered while a
// Nostr-only activist pauses (#28).
func TestWatchLoopNostrDown(t *testing.T) {
	js := `{"version": 1, "activists": [
 {"label": "SMS", "pubkey": "0101010101010101010101010101010101010101010101010101010101010101",
  "start": "2026-01-15T10:00:00Z", "end": "2026-01-15T18:00:00Z", "interval": "90m", "grace": "15m", "reminder": "10m",
  "transports": ["nostr", "sms"]},
 {"label": "NOSTRONLY", "pubkey": "0202020202020202020202020202020202020202020202020202020202020202",
  "start": "2026-01-15T10:00:00Z", "end": "2026-01-15T18:00:00Z", "interval": "90m", "grace": "15m", "reminder": "10m"}]}`
	plan, err := schedule.ParsePlan([]byte(js))
	if err != nil {
		t.Fatal(err)
	}
	start := time.Date(2026, 1, 15, 10, 0, 0, 0, time.UTC)
	ticks := make(chan time.Time)
	var out bytes.Buffer
	done := make(chan error)
	k, _ := plan.Activists[0].Key()
	tr := &fakeTransport{}
	go func() {
		done <- watch(context.Background(), plan, ticks, func() time.Time { return start }, staticProbe{transport.Nostr: true}, tr, &out)
	}()
	for _, m := range []int{30, 60, 106} {
		clock := start.Add(time.Duration(m) * time.Minute)
		if m == 60 {
			tr.send(transport.SMS, message.Event{Kind: message.KindCheckIn, CreatedAt: clock.Add(-time.Minute), Seq: 1, Signer: k})
		}
		ticks <- clock
	}
	close(ticks)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	s := out.String()
	if !strings.Contains(s, "paused") || !strings.Contains(s, "NOSTRONLY") {
		t.Errorf("Nostr-only activist should pause:\n%s", s)
	}
	if strings.Contains(s, "missed") {
		t.Errorf("nobody should miss: SMS check-in covers one, the other is paused:\n%s", s)
	}
}

// A going-dark (#10) pauses the activist at once: the 11:45 deadline passes
// quietly, the clock resumes when the hold ends at 12:00, and silence past
// the 1-hour return window misses at 13:01.
func TestWatchLoopGoingDark(t *testing.T) {
	plan, err := schedule.ParsePlan([]byte(planJSON))
	if err != nil {
		t.Fatal(err)
	}
	start := time.Date(2026, 1, 15, 10, 0, 0, 0, time.UTC)
	k, _ := plan.Activists[0].Key()
	gd, err := message.GoingDarkEvent(message.GoingDark{
		Dark:         2 * time.Hour,
		ReturnWindow: time.Hour,
		PlanStart:    start,
		PlanEnd:      start.Add(8 * time.Hour),
		Interval:     90 * time.Minute,
		Grace:        15 * time.Minute,
		Reminder:     10 * time.Minute,
		Transports:   message.GoingDarkNostr,
		AlertHash:    sha256.Sum256([]byte("made-up alert KISUMU-C")),
		ShutdownRef:  "KISUMU-C",
	}, 1, start)
	if err != nil {
		t.Fatal(err)
	}
	gd.Signer = k
	ticks := make(chan time.Time)
	var out bytes.Buffer
	done := make(chan error)
	tr := &fakeTransport{}
	// Queued before the loop starts, so the initial step at 10:00 picks it
	// up deterministically and the hold runs 10:00 to 12:00.
	tr.send(transport.Nostr, gd)
	go func() {
		done <- watch(context.Background(), plan, ticks, func() time.Time { return start }, staticProbe{}, tr, &out)
	}()
	for _, m := range []int{121, 181} {
		ticks <- start.Add(time.Duration(m) * time.Minute)
	}
	close(ticks)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	s := out.String()
	for _, want := range []string{
		"going-dark",
		"deadline 2026-01-15T12:00:00Z", // the pause ends here
		"dark-expired",
		"missed",
		"deadline 2026-01-15T13:01:00Z", // return window over, alarm
	} {
		if !strings.Contains(s, want) {
			t.Errorf("output lacks %q:\n%s", want, s)
		}
	}
}

// Made-up guardian key for key-file tests. Never a real key.
const guardianKeyHex = "0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f"

func TestSplitRelays(t *testing.T) {
	cases := map[string]int{
		"":                                     0,
		" , ":                                  0,
		"wss://relay.example.org":              1,
		"wss://a.example,wss://b.example":      2,
		" wss://a.example ,, wss://b.example ": 2,
	}
	for in, want := range cases {
		if got := splitRelays(in); len(got) != want {
			t.Errorf("splitRelays(%q) = %d relays, want %d", in, len(got), want)
		}
	}
}

func TestLoadGuardianKey(t *testing.T) {
	dir := t.TempDir()
	good := filepath.Join(dir, "guardian.key")
	if err := os.WriteFile(good, []byte(guardianKeyHex+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	var warn bytes.Buffer
	key, err := loadGuardianKey(&warn, good)
	if err != nil {
		t.Fatalf("good key rejected: %v", err)
	}
	if key == nil || warn.Len() != 0 {
		t.Errorf("good key: key=%v warn=%q", key != nil, warn.String())
	}
	world := filepath.Join(dir, "world.key")
	if err := os.WriteFile(world, []byte(guardianKeyHex), 0o644); err != nil {
		t.Fatal(err)
	}
	warn.Reset()
	if _, err := loadGuardianKey(&warn, world); err != nil {
		t.Fatalf("world-readable key rejected: %v", err)
	}
	if !strings.Contains(warn.String(), "world-readable") {
		t.Errorf("no world-readable warning: %q", warn.String())
	}
	for name, content := range map[string]string{
		"bad hex":  "zzz-not-hex",
		"short":    "0f0f",
		"empty":    "",
		"zero key": strings.Repeat("00", 32),
	} {
		t.Run(name, func(t *testing.T) {
			p := filepath.Join(dir, "bad.key")
			if err := os.WriteFile(p, []byte(content), 0o600); err != nil {
				t.Fatal(err)
			}
			if _, err := loadGuardianKey(&warn, p); err == nil {
				t.Errorf("accepted %q", content)
			}
		})
	}
	if _, err := loadGuardianKey(&warn, filepath.Join(dir, "missing.key")); err == nil {
		t.Error("accepted a missing key file")
	}
}

func TestLiveProbeFollowsRelayStatus(t *testing.T) {
	conn := &connectivity{}
	p := liveProbe{conn: conn}
	if !p.Up(transport.Nostr) {
		t.Error("Nostr should be up until the first report")
	}
	conn.RelayStatus(nnostr.Status{Reachable: 0, Total: 2})
	if p.Up(transport.Nostr) {
		t.Error("Nostr should be down with 0 reachable relays")
	}
	conn.RelayStatus(nnostr.Status{Reachable: 1, Total: 2})
	if !p.Up(transport.Nostr) {
		t.Error("Nostr should be up with 1 reachable relay")
	}
	if !p.Up(transport.SMS) {
		t.Error("SMS should stay up on the static flags")
	}
	forced := liveProbe{down: staticProbe{transport.Nostr: true}, conn: &connectivity{}}
	if forced.Up(transport.Nostr) {
		t.Error("-down nostr should force Nostr down in live mode")
	}
	smsDown := liveProbe{down: staticProbe{transport.SMS: true}, conn: &connectivity{}}
	if smsDown.Up(transport.SMS) || !smsDown.Up(transport.Nostr) {
		t.Error("SMS should follow only the static flags in live mode")
	}
}

func TestNostrReceiverDrains(t *testing.T) {
	// An empty channel must return at once, not block.
	empty := make(chan nnostr.Delivery)
	done := make(chan []delivery, 1)
	go func() { done <- nostrReceiver{ch: empty}.Receive() }()
	select {
	case got := <-done:
		if len(got) != 0 {
			t.Errorf("empty channel gave %d deliveries", len(got))
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Receive blocked on an empty channel")
	}
	// Queued deliveries drain in order; empty ones are skipped.
	ch := make(chan nnostr.Delivery, 4)
	ev := message.Event{Kind: message.KindCheckIn, Seq: 7}
	ch <- nnostr.Delivery{Event: nil, Via: transport.Nostr}
	ch <- nnostr.Delivery{Event: &ev, Via: transport.Nostr}
	got := nostrReceiver{ch: ch}.Receive()
	if len(got) != 1 || got[0].via != transport.Nostr || got[0].ev.Seq != 7 {
		t.Fatalf("drained %+v", got)
	}
	rest := nostrReceiver{ch: ch}.Receive()
	if len(rest) != 0 {
		t.Errorf("second drain gave %d deliveries", len(rest))
	}
	// A closed channel ends the drain.
	closed := make(chan nnostr.Delivery, 1)
	closed <- nnostr.Delivery{Event: &ev, Via: transport.Nostr}
	close(closed)
	got = nostrReceiver{ch: closed}.Receive()
	if len(got) != 1 {
		t.Errorf("closed channel gave %d deliveries", len(got))
	}
	got = nostrReceiver{ch: closed}.Receive()
	if len(got) != 0 {
		t.Errorf("drained-closed channel gave %d deliveries", len(got))
	}
}

func TestAlarmingKind(t *testing.T) {
	for _, k := range []schedule.NoticeKind{schedule.NoticeMissed, schedule.NoticeAlarm, schedule.NoticeMassSilence} {
		if !alarmingKind(k.String()) {
			t.Errorf("%q should trigger the hook", k)
		}
	}
	if !alarmingKind("still-paused") {
		t.Error(`"still-paused" should trigger the hook`)
	}
	for _, k := range []schedule.NoticeKind{
		schedule.NoticeStarted, schedule.NoticeReminder, schedule.NoticeRecovered,
		schedule.NoticeCleared, schedule.NoticePaused, schedule.NoticeResumed,
		schedule.NoticeActivistSafe, schedule.NoticeGoingDark, schedule.NoticeDarkExpired,
	} {
		if alarmingKind(k.String()) {
			t.Errorf("%q should not trigger the hook", k)
		}
	}
}

// writeHook writes an executable shell hook that appends one line per call.
func writeHook(t *testing.T, body string) string {
	t.Helper()
	p := filepath.Join(t.TempDir(), "hook.sh")
	if err := os.WriteFile(p, []byte("#!/bin/sh\n"+body+"\n"), 0o700); err != nil {
		t.Fatal(err)
	}
	return p
}

// driveMissed runs the loop until the first deadline passes with no
// check-in, so exactly one missed notice fires.
func driveMissed(t *testing.T, hook string) string {
	t.Helper()
	plan, err := schedule.ParsePlan([]byte(planJSON))
	if err != nil {
		t.Fatal(err)
	}
	start := time.Date(2026, 1, 15, 10, 0, 0, 0, time.UTC)
	ticks := make(chan time.Time)
	var buf bytes.Buffer
	done := make(chan error)
	go func() {
		done <- runLoop(context.Background(), plan, ticks, func() time.Time { return start }, staticProbe{}, &fakeTransport{}, &buf, runOpts{hook: hook})
	}()
	for _, m := range []int{30, 60, 106} {
		ticks <- start.Add(time.Duration(m) * time.Minute)
	}
	close(ticks)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	return buf.String()
}

func TestHookInvokedOnMissed(t *testing.T) {
	calls := filepath.Join(t.TempDir(), "calls.log")
	hook := writeHook(t, fmt.Sprintf(`echo "KIND=$KENGELE_KIND LABEL=$KENGELE_LABEL TIME=$KENGELE_TIME REASON=$KENGELE_REASON DEADLINE=$KENGELE_DEADLINE" >> %q`, calls))
	s := driveMissed(t, hook)
	if !strings.Contains(s, "missed") {
		t.Fatalf("no missed notice:\n%s", s)
	}
	raw, err := os.ReadFile(calls)
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimSpace(string(raw)), "\n")
	if len(lines) != 1 {
		t.Fatalf("hook ran %d times, want 1:\n%s", len(lines), raw)
	}
	for _, want := range []string{
		"KIND=missed",
		"LABEL=A",
		"TIME=2026-01-15T11:46:00Z",
		"REASON=no check-in by 2026-01-15T11:45:00Z",
		"DEADLINE=2026-01-15T11:45:00Z",
	} {
		if !strings.Contains(lines[0], want) {
			t.Errorf("hook line lacks %q:\n%s", want, lines[0])
		}
	}
}

func TestHookFailureTolerated(t *testing.T) {
	// A missing hook binary must not stop the loop.
	s := driveMissed(t, filepath.Join(t.TempDir(), "no-such-hook"))
	if !strings.Contains(s, "missed") {
		t.Fatalf("missing hook stopped the loop:\n%s", s)
	}
	// Neither must a hook that exits 1.
	hook := writeHook(t, "exit 1")
	s = driveMissed(t, hook)
	if !strings.Contains(s, "missed") {
		t.Fatalf("failing hook stopped the loop:\n%s", s)
	}
}

func TestRunLiveNeedsKeyFile(t *testing.T) {
	dir := t.TempDir()
	in := filepath.Join(dir, "plan.json")
	dst := filepath.Join(dir, "plan.kgp")
	if err := os.WriteFile(in, []byte(planJSON), 0o600); err != nil {
		t.Fatal(err)
	}
	var out bytes.Buffer
	if err := cli([]string{"seal-plan", "-in", in, "-out", dst}, &out, pass); err != nil {
		t.Fatal(err)
	}
	if err := cli([]string{"run", "-plan", dst, "-relays", "wss://relay.example.org"}, &out, pass); err == nil {
		t.Error("live mode accepted without -key-file")
	} else if !strings.Contains(err.Error(), "key-file") {
		t.Errorf("error should name -key-file: %v", err)
	}
	bad := filepath.Join(dir, "bad.key")
	if err := os.WriteFile(bad, []byte("not a key"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := cli([]string{"run", "-plan", dst, "-relays", "wss://relay.example.org", "-key-file", bad}, &out, pass); err == nil {
		t.Error("live mode accepted with a bad -key-file")
	}
	var help bytes.Buffer
	if err := cli([]string{"help"}, &help, ""); err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"-relays", "-key-file", "-alert-hook", "SMS", "matumizi", "Hakuna mtumaji wa SMS"} {
		if !strings.Contains(help.String(), want) {
			t.Errorf("usage lacks %q:\n%s", want, help.String())
		}
	}
}
