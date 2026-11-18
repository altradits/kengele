// Package confirm turns guardian confirmations into a public alert, and
// all-clears into a stand-down or a "found safe" update.
//
// Nothing goes public until k distinct guardians (default 2 of 3) confirm
// the same alarm. This is enforced by cryptography, not only by code: the
// activist's pre-written alert and the key that signs it are sealed under a
// random key that is split among the guardians with Shamir's secret sharing.
// Each guardian-confirm carries that guardian's share, so one guardian alone,
// or a stolen guardian phone, cannot read or publish the alert.
//
// See docs/guardian-confirmation.md.
package confirm

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/btcsuite/btcd/btcec/v2"
	"github.com/openbao/openbao/sdk/v2/helper/shamir"
	"golang.org/x/crypto/chacha20poly1305"

	"github.com/altradits/kengele/internal/message"
)

// Limits on a kit. Guardian counts are small by design (3 to 5 is normal).
const (
	DefaultThreshold = 2
	MinThreshold     = 2 // Shamir needs at least 2; 1 would let one guardian publish
	MaxGuardians     = 10

	// DefaultConfirmTTL is how long a guardian's confirmation stays valid.
	// An old confirmation must not combine with a new one days later.
	DefaultConfirmTTL = 24 * time.Hour

	keySize   = chacha20poly1305.KeySize // 32
	shareSize = keySize + 1              // Shamir adds a 1-byte x coordinate

	maxField = 280 // characters per alert field
)

// KitVersion is the version of the kit format.
const KitVersion = 1

// Errors returned when building or opening kits.
var (
	ErrThreshold   = errors.New("confirm: threshold must be at least 2 and at most the number of guardians")
	ErrGuardians   = errors.New("confirm: guardians must be distinct, between 2 and 10, and not the activist")
	ErrCoordinates = errors.New("confirm: alert text looks like GPS coordinates or a map link; Kengele never publishes a location trail")
	ErrAlert       = errors.New("confirm: invalid alert")
	ErrKit         = errors.New("confirm: invalid kit")
)

// Alert is the activist's pre-written public alert. It deliberately has no
// coordinates: the place comes only from what the activist typed at their
// last check-in.
type Alert struct {
	Name   string `json:"name"`
	Photo  string `json:"photo,omitempty"` // a link or hash of a photo the activist chose
	Lawyer string `json:"lawyer"`          // who to call: name and number or handle
	Text   string `json:"text,omitempty"`  // any extra words, in the activist's language
}

// Check validates the alert fields.
func (a Alert) Check() error {
	if strings.TrimSpace(a.Name) == "" || strings.TrimSpace(a.Lawyer) == "" {
		return fmt.Errorf("%w: name and lawyer are required", ErrAlert)
	}
	for _, f := range []string{a.Name, a.Photo, a.Lawyer, a.Text} {
		if !utf8.ValidString(f) || utf8.RuneCountInString(f) > maxField {
			return fmt.Errorf("%w: fields must be valid text of at most %d characters", ErrAlert, maxField)
		}
		if LooksLikeLocation(f) {
			return ErrCoordinates
		}
	}
	return nil
}

var (
	coordPair = regexp.MustCompile(`-?\d{1,3}\.\d{3,}\s*[,;/ ]\s*-?\d{1,3}\.\d{3,}`)
	mapLink   = regexp.MustCompile(`(?i)(geo:|maps\.google|google\.[a-z.]+/maps|goo\.gl/maps|maps\.app\.goo\.gl|openstreetmap\.org|osm\.org/go|what3words|w3w\.co|plus\.codes)`)
)

// LooksLikeLocation reports whether s contains something that looks like a
// precise location: a decimal coordinate pair or a map link. Typed place
// names ("Kisumu law courts") are fine.
func LooksLikeLocation(s string) bool {
	return coordPair.MatchString(s) || mapLink.MatchString(s)
}

// sealed is what the alert key protects.
type sealed struct {
	Alert    Alert  `json:"alert"`
	AlertKey string `json:"alert_key"` // hex secret key that signs the public notes
}

// GuardianKit is what one guardian holds for one activist. It must be stored
// only inside the guardian's encrypted plan. On its own it reveals nothing
// about the alert: Ciphertext needs Threshold shares to open.
type GuardianKit struct {
	Version    int      `json:"version"`
	Activist   string   `json:"activist"`  // hex x-only key
	AlertPub   string   `json:"alert_pub"` // hex x-only key that will sign public notes; published in advance
	Guardians  []string `json:"guardians"` // hex x-only keys, in enrolment order
	Threshold  int      `json:"threshold"`
	Index      int      `json:"index"` // this guardian's position in Guardians
	Ciphertext []byte   `json:"ciphertext"`
	Share      []byte   `json:"share"`
}

// NewKits seals alert and alertKey and returns one kit per guardian, in the
// same order as guardians. It is run once on the activist's device during
// setup; the activist then gives each guardian their kit over a secure
// channel and publishes the alert key's public key on their own profile so
// journalists can check that a later alert is genuine.
//
// rnd is the randomness source; nil means crypto/rand.
func NewKits(alert Alert, activist [32]byte, alertKey *btcec.PrivateKey, guardians [][32]byte, threshold int, rnd io.Reader) ([]GuardianKit, error) {
	if rnd == nil {
		rnd = rand.Reader
	}
	if err := alert.Check(); err != nil {
		return nil, err
	}
	if alertKey == nil {
		return nil, fmt.Errorf("%w: no alert key", ErrKit)
	}
	alertPub := message.PublicKey(alertKey)
	if alertPub == activist {
		return nil, fmt.Errorf("%w: the alert key must be separate from the activist's key", ErrKit)
	}
	if err := checkGuardians(activist, guardians); err != nil {
		return nil, err
	}
	if threshold < MinThreshold || threshold > len(guardians) {
		return nil, ErrThreshold
	}

	k := make([]byte, keySize)
	if _, err := io.ReadFull(rnd, k); err != nil {
		return nil, fmt.Errorf("confirm: random key: %w", err)
	}
	defer wipe(k)

	plain, err := json.Marshal(sealed{Alert: alert, AlertKey: hex.EncodeToString(alertKey.Serialize())})
	if err != nil {
		return nil, err
	}
	defer wipe(plain)

	ghex := make([]string, len(guardians))
	for i, g := range guardians {
		ghex[i] = hex.EncodeToString(g[:])
	}
	tmpl := GuardianKit{
		Version:   KitVersion,
		Activist:  hex.EncodeToString(activist[:]),
		AlertPub:  hex.EncodeToString(alertPub[:]),
		Guardians: ghex,
		Threshold: threshold,
	}

	aead, err := chacha20poly1305.NewX(k)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, aead.NonceSize())
	if _, err := io.ReadFull(rnd, nonce); err != nil {
		return nil, fmt.Errorf("confirm: random nonce: %w", err)
	}
	ct := aead.Seal(append([]byte(nil), nonce...), nonce, plain, tmpl.associatedData())

	shares, err := shamir.Split(k, len(guardians), threshold)
	if err != nil {
		return nil, fmt.Errorf("confirm: split: %w", err)
	}
	kits := make([]GuardianKit, len(guardians))
	for i := range guardians {
		kit := tmpl
		kit.Guardians = append([]string(nil), ghex...)
		kit.Index = i
		kit.Ciphertext = append([]byte(nil), ct...)
		kit.Share = shares[i]
		kits[i] = kit
	}
	return kits, nil
}

// associatedData binds the ciphertext to everything else in the kit, so a
// kit cannot be mixed with another activist's or another guardian set's.
func (k GuardianKit) associatedData() []byte {
	h := sha256.New()
	h.Write([]byte("kengele/alert-kit/v1"))
	var b [8]byte
	binary.BigEndian.PutUint32(b[:4], uint32(k.Version))
	binary.BigEndian.PutUint32(b[4:], uint32(k.Threshold))
	h.Write(b[:])
	h.Write([]byte(k.Activist))
	h.Write([]byte(k.AlertPub))
	for _, g := range k.Guardians {
		h.Write([]byte(g))
	}
	return h.Sum(nil)
}

// parsed holds a kit's decoded keys.
type parsed struct {
	activist  [32]byte
	alertPub  [32]byte
	guardians [][32]byte
	self      [32]byte
}

// Check validates a kit and decodes its keys.
func (k GuardianKit) parse() (*parsed, error) {
	if k.Version != KitVersion {
		return nil, fmt.Errorf("%w: unknown version %d", ErrKit, k.Version)
	}
	p := &parsed{}
	var err error
	if p.activist, err = decodeKey(k.Activist); err != nil {
		return nil, err
	}
	if p.alertPub, err = decodeKey(k.AlertPub); err != nil {
		return nil, err
	}
	for _, g := range k.Guardians {
		gk, err := decodeKey(g)
		if err != nil {
			return nil, err
		}
		p.guardians = append(p.guardians, gk)
	}
	if err := checkGuardians(p.activist, p.guardians); err != nil {
		return nil, err
	}
	if k.Threshold < MinThreshold || k.Threshold > len(p.guardians) {
		return nil, ErrThreshold
	}
	if k.Index < 0 || k.Index >= len(p.guardians) {
		return nil, fmt.Errorf("%w: index out of range", ErrKit)
	}
	if len(k.Share) != shareSize {
		return nil, fmt.Errorf("%w: share size", ErrKit)
	}
	if len(k.Ciphertext) < chacha20poly1305.NonceSizeX+chacha20poly1305.Overhead {
		return nil, fmt.Errorf("%w: ciphertext too short", ErrKit)
	}
	p.self = p.guardians[k.Index]
	return p, nil
}

// open tries to open the kit's ciphertext with the key rebuilt from shares.
func (k GuardianKit) open(key []byte) (*sealed, *btcec.PrivateKey, error) {
	aead, err := chacha20poly1305.NewX(key)
	if err != nil {
		return nil, nil, err
	}
	ns := aead.NonceSize()
	plain, err := aead.Open(nil, k.Ciphertext[:ns], k.Ciphertext[ns:], k.associatedData())
	if err != nil {
		return nil, nil, err
	}
	defer wipe(plain)
	var s sealed
	if err := json.Unmarshal(plain, &s); err != nil {
		return nil, nil, fmt.Errorf("%w: sealed alert", ErrKit)
	}
	kb, err := hex.DecodeString(s.AlertKey)
	if err != nil || len(kb) != 32 {
		return nil, nil, fmt.Errorf("%w: alert key", ErrKit)
	}
	priv, _ := btcec.PrivKeyFromBytes(kb)
	wipe(kb)
	s.AlertKey = ""
	if pub := message.PublicKey(priv); hex.EncodeToString(pub[:]) != k.AlertPub {
		return nil, nil, fmt.Errorf("%w: alert key does not match", ErrKit)
	}
	return &s, priv, nil
}

func checkGuardians(activist [32]byte, gs [][32]byte) error {
	if len(gs) < MinThreshold || len(gs) > MaxGuardians {
		return ErrGuardians
	}
	seen := make(map[[32]byte]bool, len(gs))
	for _, g := range gs {
		if g == activist || seen[g] || g == ([32]byte{}) {
			return ErrGuardians
		}
		seen[g] = true
	}
	return nil
}

func decodeKey(s string) ([32]byte, error) {
	var k [32]byte
	b, err := hex.DecodeString(s)
	if err != nil || len(b) != 32 {
		return k, fmt.Errorf("%w: key must be 64 hex characters", ErrKit)
	}
	copy(k[:], b)
	return k, nil
}

func wipe(b []byte) {
	for i := range b {
		b[i] = 0
	}
}
