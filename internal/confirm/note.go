package confirm

import (
	"context"
	"encoding/hex"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/btcsuite/btcd/btcec/v2"
	gn "github.com/nbd-wtf/go-nostr"
)

// Publisher sends a public Nostr event to relays. The Nostr transport (#8)
// or any relay client can implement it.
type Publisher interface {
	Publish(ctx context.Context, ev gn.Event) error
}

// Status is what the watcher knows at the moment of release.
type Status struct {
	// LastCheckIn is the signed time of the activist's last check-in.
	LastCheckIn time.Time
	// Place is what the activist typed at that check-in, if anything.
	Place string
}

// ErrNoRelease is returned when an Outcome carries nothing to publish.
var ErrNoRelease = errors.New("confirm: outcome has nothing to publish")

// Note builds the public kind 1 note for a Release or FoundSafe outcome,
// signed by the activist's alert key. alertID is the id of the earlier
// alert note, used only for FoundSafe to link the update to it; it may be
// empty.
func Note(out Outcome, st Status, alertID string, now time.Time) (gn.Event, error) {
	if out.Alert == nil || out.AlertKey == nil {
		return gn.Event{}, ErrNoRelease
	}
	var content string
	tags := gn.Tags{{"t", "kengele"}}
	switch out.Action {
	case Release:
		if LooksLikeLocation(st.Place) {
			return gn.Event{}, ErrCoordinates
		}
		content = alertText(*out.Alert, st, out.Confirmed)
		tags = append(tags, gn.Tag{"kengele", "alert", strconv.FormatUint(uint64(out.Epoch), 10)})
	case FoundSafe:
		content = foundSafeText(*out.Alert)
		tags = append(tags, gn.Tag{"kengele", "found-safe", strconv.FormatUint(uint64(out.Epoch), 10)})
		if alertID != "" {
			if b, err := hex.DecodeString(alertID); err != nil || len(b) != 32 {
				return gn.Event{}, fmt.Errorf("confirm: alert id must be 64 hex characters")
			}
			tags = append(tags, gn.Tag{"e", alertID})
		}
	default:
		return gn.Event{}, ErrNoRelease
	}
	return sign(gn.Event{Kind: gn.KindTextNote, CreatedAt: gn.Timestamp(now.Unix()), Tags: tags, Content: content}, out.AlertKey)
}

// Publish builds the note for out and sends it. It refuses anything but a
// Release or FoundSafe outcome from a Tally, which only exists after k
// guardians confirmed (or the alarm was cleared after that).
func Publish(ctx context.Context, p Publisher, out Outcome, st Status, alertID string, now time.Time) (gn.Event, error) {
	ev, err := Note(out, st, alertID, now)
	if err != nil {
		return gn.Event{}, err
	}
	if err := p.Publish(ctx, ev); err != nil {
		return gn.Event{}, fmt.Errorf("confirm: publish: %w", err)
	}
	return ev, nil
}

func alertText(a Alert, st Status, confirmed int) string {
	var b strings.Builder
	b.WriteString("KENGELE: TAHADHARI / ALERT\n\n")
	fmt.Fprintf(&b, "%s hajasikika tangu / has not been heard from since %s.\n", a.Name, when(st.LastCheckIn))
	if p := strings.TrimSpace(st.Place); p != "" {
		fmt.Fprintf(&b, "Mahali pa mwisho (aliandika) / Last place (typed by them): %s\n", p)
	}
	fmt.Fprintf(&b, "Wakili / Lawyer: %s\n", a.Lawyer)
	if a.Photo != "" {
		fmt.Fprintf(&b, "Picha / Photo: %s\n", a.Photo)
	}
	if t := strings.TrimSpace(a.Text); t != "" {
		fmt.Fprintf(&b, "\n%s\n", t)
	}
	fmt.Fprintf(&b, "\nImethibitishwa na walinzi %d / Confirmed by %d of the guardians they chose. ", confirmed, confirmed)
	b.WriteString("Check this note is signed by the alert key on their profile. #kengele")
	return b.String()
}

func foundSafeText(a Alert) string {
	return fmt.Sprintf("KENGELE: YUKO SALAMA / FOUND SAFE\n\n%s amepatikana salama / has been found safe. Asanteni. Thank you to everyone who shared the alert. #kengele", a.Name)
}

func when(t time.Time) string {
	if t.IsZero() {
		return "their watch window began"
	}
	return t.UTC().Format("2006-01-02 15:04 UTC")
}

func sign(ev gn.Event, key *btcec.PrivateKey) (gn.Event, error) {
	if err := ev.Sign(hex.EncodeToString(key.Serialize())); err != nil {
		return gn.Event{}, fmt.Errorf("confirm: sign note: %w", err)
	}
	return ev, nil
}
