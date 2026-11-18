package schedule

import (
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/altradits/kengele/internal/transport"
)

const pk = "0101010101010101010101010101010101010101010101010101010101010101"

const goodPlan = `{
  "return_window": "90m",
  "mass_silence": 4,
  "activists": [
    {"label": "A", "pubkey": "` + pk + `",
     "start": "2026-01-15T10:00:00Z", "end": "2026-01-15T18:00:00Z",
     "interval": "90m", "grace": "15m", "reminder": "10m"}
  ]
}`

var pass = []byte("correct horse battery")

func TestPlanRoundTrip(t *testing.T) {
	sealed, err := SealPlan([]byte(goodPlan), pass)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(sealed), pk) || strings.Contains(string(sealed), "activists") {
		t.Fatal("plan file is not encrypted")
	}
	p, err := OpenPlan(sealed, pass)
	if err != nil {
		t.Fatal(err)
	}
	cfg := p.Config()
	if cfg.ReturnWindow != 90*time.Minute || cfg.MassSilence != 4 {
		t.Errorf("config %+v", cfg)
	}
	e := New(cfg, t0)
	if err := p.Load(e); err != nil {
		t.Fatal(err)
	}
	e.Tick(t0)
	k, _ := p.Activists[0].Key()
	if d, _ := e.Deadline(k); !d.Equal(at(105)) {
		t.Errorf("deadline %v", d)
	}
	// Two seals of the same plan differ (fresh salt and nonce).
	again, _ := SealPlan([]byte(goodPlan), pass)
	if string(again) == string(sealed) {
		t.Error("same ciphertext twice")
	}
}

func TestPlanRejects(t *testing.T) {
	sealed, err := SealPlan([]byte(goodPlan), pass)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := OpenPlan(sealed, []byte("wrong passphrase!")); !errors.Is(err, ErrPlanKey) {
		t.Errorf("wrong passphrase: %v", err)
	}
	tampered := append([]byte(nil), sealed...)
	tampered[len(tampered)-1] ^= 1
	if _, err := OpenPlan(tampered, pass); !errors.Is(err, ErrPlanKey) {
		t.Errorf("tampered: %v", err)
	}
	if _, err := OpenPlan([]byte("not a plan"), pass); err == nil {
		t.Error("garbage accepted")
	}
	if _, err := SealPlan([]byte(goodPlan), []byte("short")); err == nil {
		t.Error("short passphrase accepted")
	}

	bad := map[string]string{
		"unknown field":  strings.Replace(goodPlan, `"mass_silence"`, `"surprise"`, 1),
		"bad key":        strings.Replace(goodPlan, pk, "abcd", 1),
		"bad duration":   strings.Replace(goodPlan, `"interval": "90m"`, `"interval": "soon"`, 1),
		"bad window":     strings.Replace(goodPlan, `"end": "2026-01-15T18:00:00Z"`, `"end": "2026-01-15T09:00:00Z"`, 1),
		"nobody watched": `{"activists": []}`,
		"listed twice": strings.Replace(goodPlan, `]
}`, `, {"label": "B", "pubkey": "`+pk+`", "start": "2026-01-15T10:00:00Z", "end": "2026-01-15T18:00:00Z", "interval": "90m", "grace": "15m"}]
}`, 1),
	}
	withTransports := func(list string) string {
		return strings.Replace(goodPlan, `"reminder": "10m"}`, `"reminder": "10m", "transports": `+list+`}`, 1)
	}
	bad["mesh not supported yet"] = withTransports(`["nostr", "mesh"]`)
	bad["unknown transport"] = withTransports(`["carrier pigeon"]`)
	bad["transport listed twice"] = withTransports(`["sms", "sms"]`)
	bad["newer plan version"] = strings.Replace(goodPlan, `{
  "return_window"`, `{
  "version": 2,
  "return_window"`, 1)
	bad["negative plan version"] = strings.Replace(goodPlan, `{
  "return_window"`, `{
  "version": -1,
  "return_window"`, 1)
	for name, js := range bad {
		if _, err := ParsePlan([]byte(js)); err == nil {
			t.Errorf("%s: accepted", name)
		}
		if _, err := SealPlan([]byte(js), pass); err == nil {
			t.Errorf("%s: sealed", name)
		}
	}
}

func TestPlanTransports(t *testing.T) {
	// An old plan with no version and no transports is Nostr-only.
	p, err := ParsePlan([]byte(goodPlan))
	if err != nil {
		t.Fatal(err)
	}
	via, err := p.Activists[0].Via()
	if err != nil || len(via) != 1 || via[0] != transport.Nostr {
		t.Fatalf("default transports %v %v, want [nostr]", via, err)
	}

	v1 := strings.Replace(goodPlan, `{
  "return_window"`, `{
  "version": 1,
  "return_window"`, 1)
	v1 = strings.Replace(v1, `"reminder": "10m"}`, `"reminder": "10m", "transports": ["sms", "nostr"]}`, 1)
	p, err = ParsePlan([]byte(v1))
	if err != nil {
		t.Fatal(err)
	}
	via, _ = p.Activists[0].Via()
	if len(via) != 2 || via[0] != transport.SMS || via[1] != transport.Nostr {
		t.Fatalf("transports %v", via)
	}

	// The transports reach the engine: with Nostr down the activist keeps
	// running on SMS, and pauses only when SMS goes down too.
	e := New(p.Config(), t0)
	if err := p.Load(e); err != nil {
		t.Fatal(err)
	}
	k, _ := p.Activists[0].Key()
	e.Tick(t0)
	e.SetTransport(at(10), transport.Nostr, false)
	if s, _ := e.State(k); s != StateWaiting {
		t.Errorf("Nostr down: state %v, want waiting", s)
	}
	e.SetTransport(at(20), transport.SMS, false)
	if s, _ := e.State(k); s != StatePaused {
		t.Errorf("both down: state %v, want paused", s)
	}

	// Sealing keeps the field.
	sealed, err := SealPlan([]byte(v1), pass)
	if err != nil {
		t.Fatal(err)
	}
	q, err := OpenPlan(sealed, pass)
	if err != nil || len(q.Activists[0].Transports) != 2 || q.Version != 1 {
		t.Fatalf("round trip: %+v %v", q, err)
	}
}
