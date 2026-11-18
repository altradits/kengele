package schedule

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/altradits/kengele/internal/transport"
	"golang.org/x/crypto/argon2"
	"golang.org/x/crypto/chacha20poly1305"
)

// PlanVersion is the newest plan format this build understands. Version 1
// added per-activist transports; a plan with no version is version 1 too,
// because the field only adds an optional key and its default (Nostr only)
// is what older plans meant.
const PlanVersion = 1

// Plan is a guardian's watch plan: whom to watch and when. On disk it is
// always encrypted (see SealPlan); it never holds names, only a label the
// guardian chooses and the activist's public key.
type Plan struct {
	// Version is the plan format. 0 or missing reads as 1. A plan from a
	// newer build is refused rather than half understood.
	Version      int            `json:"version,omitempty"`
	ReturnWindow Duration       `json:"return_window,omitempty"`
	MassSilence  int            `json:"mass_silence,omitempty"`
	Activists    []PlanActivist `json:"activists"`
}

// PlanActivist is one watched activist.
type PlanActivist struct {
	Label    string    `json:"label"`
	PubKey   string    `json:"pubkey"` // 64 hex characters, x-only
	Start    time.Time `json:"start"`
	End      time.Time `json:"end"`
	Interval Duration  `json:"interval"`
	Grace    Duration  `json:"grace"`
	Reminder Duration  `json:"reminder,omitempty"`
	// Transports the activist uses: "nostr", "sms". Missing means
	// ["nostr"], the safe default: SMS is off unless the activist turns it
	// on (docs/sms-transport.md).
	Transports []string `json:"transports,omitempty"`
}

// Duration is a time.Duration written as "90m", "2h" in JSON.
type Duration time.Duration

func (d Duration) MarshalJSON() ([]byte, error) { return json.Marshal(time.Duration(d).String()) }

func (d *Duration) UnmarshalJSON(b []byte) error {
	var s string
	if err := json.Unmarshal(b, &s); err != nil {
		return fmt.Errorf("schedule: duration must be a string like \"90m\": %w", err)
	}
	v, err := time.ParseDuration(s)
	if err != nil {
		return fmt.Errorf("schedule: %w", err)
	}
	*d = Duration(v)
	return nil
}

// Key decodes the activist's public key.
func (a PlanActivist) Key() ([32]byte, error) {
	var k [32]byte
	b, err := hex.DecodeString(a.PubKey)
	if err != nil || len(b) != 32 {
		return k, fmt.Errorf("schedule: %q: pubkey must be 64 hex characters", a.Label)
	}
	copy(k[:], b)
	return k, nil
}

// Via decodes the activist's transports, defaulting to Nostr only.
func (a PlanActivist) Via() ([]transport.Kind, error) {
	if len(a.Transports) == 0 {
		return []transport.Kind{transport.Nostr}, nil
	}
	var out []transport.Kind
	seen := map[transport.Kind]bool{}
	for _, name := range a.Transports {
		k, err := transport.Parse(name)
		if err != nil {
			return nil, fmt.Errorf("schedule: %q: %w", a.Label, err)
		}
		if seen[k] {
			return nil, fmt.Errorf("schedule: %q: transport %q listed twice", a.Label, name)
		}
		seen[k] = true
		out = append(out, k)
	}
	return out, nil
}

// Window returns the activist's watch window.
func (a PlanActivist) Window() Window {
	return Window{Start: a.Start, End: a.End, Interval: time.Duration(a.Interval),
		Grace: time.Duration(a.Grace), Reminder: time.Duration(a.Reminder)}
}

// ParsePlan decodes and checks a plan in JSON.
func ParsePlan(b []byte) (*Plan, error) {
	dec := json.NewDecoder(bytes.NewReader(b))
	dec.DisallowUnknownFields()
	var p Plan
	if err := dec.Decode(&p); err != nil {
		return nil, fmt.Errorf("schedule: plan: %w", err)
	}
	if p.Version < 0 || p.Version > PlanVersion {
		return nil, fmt.Errorf("schedule: plan version %d is not supported by this build (newest %d)", p.Version, PlanVersion)
	}
	if len(p.Activists) == 0 {
		return nil, errors.New("schedule: plan watches nobody")
	}
	seen := map[[32]byte]bool{}
	for _, a := range p.Activists {
		k, err := a.Key()
		if err != nil {
			return nil, err
		}
		if seen[k] {
			return nil, fmt.Errorf("schedule: %q is listed twice", a.Label)
		}
		seen[k] = true
		if _, err := a.Via(); err != nil {
			return nil, err
		}
		if err := a.Window().Validate(); err != nil {
			return nil, fmt.Errorf("%w (%q)", err, a.Label)
		}
	}
	return &p, nil
}

// Config returns the engine config the plan asks for.
func (p *Plan) Config() Config {
	return Config{ReturnWindow: time.Duration(p.ReturnWindow), MassSilence: p.MassSilence}
}

// Load adds every activist in the plan to the engine.
func (p *Plan) Load(e *Engine) error {
	for _, a := range p.Activists {
		k, err := a.Key()
		if err != nil {
			return err
		}
		via, err := a.Via()
		if err != nil {
			return err
		}
		if err := e.Watch(k, a.Window(), via...); err != nil {
			return fmt.Errorf("%w (%q)", err, a.Label)
		}
	}
	return nil
}

// Encrypted plan file:
//
//	magic "KGPLAN01" (8) | salt (16) | nonce (24) | XChaCha20-Poly1305 ciphertext
//
// The key is Argon2id(passphrase, salt, t=3, m=64 MiB, p=4). The magic is
// authenticated as additional data.
var planMagic = []byte("KGPLAN01")

const (
	planSalt   = 16
	argonTime  = 3
	argonMem   = 64 * 1024
	argonLanes = 4
)

// ErrPlanKey means the passphrase is wrong or the file was changed.
var ErrPlanKey = errors.New("schedule: wrong passphrase or damaged plan file")

func planKey(pass, salt []byte) []byte {
	return argon2.IDKey(pass, salt, argonTime, argonMem, argonLanes, chacha20poly1305.KeySize)
}

// SealPlan encrypts a JSON plan with a passphrase. It checks the plan first.
func SealPlan(planJSON, pass []byte) ([]byte, error) {
	if len(pass) < 12 {
		return nil, errors.New("schedule: passphrase must be at least 12 characters")
	}
	if _, err := ParsePlan(planJSON); err != nil {
		return nil, err
	}
	head := make([]byte, len(planMagic)+planSalt+chacha20poly1305.NonceSizeX)
	copy(head, planMagic)
	if _, err := rand.Read(head[len(planMagic):]); err != nil {
		return nil, err
	}
	salt := head[len(planMagic) : len(planMagic)+planSalt]
	nonce := head[len(planMagic)+planSalt:]
	aead, err := chacha20poly1305.NewX(planKey(pass, salt))
	if err != nil {
		return nil, err
	}
	return aead.Seal(head, nonce, planJSON, planMagic), nil
}

// OpenPlan decrypts and parses an encrypted plan file.
func OpenPlan(data, pass []byte) (*Plan, error) {
	n := len(planMagic) + planSalt + chacha20poly1305.NonceSizeX
	if len(data) < n+chacha20poly1305.Overhead || !bytes.Equal(data[:len(planMagic)], planMagic) {
		return nil, errors.New("schedule: not a Kengele plan file")
	}
	salt := data[len(planMagic) : len(planMagic)+planSalt]
	nonce := data[len(planMagic)+planSalt : n]
	aead, err := chacha20poly1305.NewX(planKey(pass, salt))
	if err != nil {
		return nil, err
	}
	plain, err := aead.Open(nil, nonce, data[n:], planMagic)
	if err != nil {
		return nil, ErrPlanKey
	}
	return ParsePlan(plain)
}
