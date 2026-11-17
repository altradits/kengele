package schedule

import (
	"errors"
	"strings"
	"testing"
	"time"
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
	for name, js := range bad {
		if _, err := ParsePlan([]byte(js)); err == nil {
			t.Errorf("%s: accepted", name)
		}
		if _, err := SealPlan([]byte(js), pass); err == nil {
			t.Errorf("%s: sealed", name)
		}
	}
}
