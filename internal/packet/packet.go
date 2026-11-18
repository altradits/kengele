// Package packet builds the Kenya habeas corpus packet (Level 3, legal):
// a Markdown file that names the missing person, when and where they were
// last seen, what the guardians say, and the signed event log with each
// event's original signed timestamp and OpenTimestamps proof status.
//
// The blank template lives in docs/habeas-kenya.md. Render fills the same
// sections from a Record so a lawyer can compare them side by side.
//
// Threat model (docs/threat-model.md): section 2 (Timestamps and evidence),
// section 4.2 (false public alerts: facts, not accusations), section 6.10
// (signed times are claims until anchored), section 9 (evidence in court).
// A Kenyan advocate must review any real packet before use; the output says
// so twice, in English and Swahili. Not for real use until the security
// review in SECURITY.md is complete.
package packet

import (
	"encoding/hex"
	"fmt"
	"strings"
	"time"

	"github.com/altradits/kengele/internal/message"
)

// OTSStatus is the OpenTimestamps proof status of one event.
type OTSStatus string

// OpenTimestamps proof states.
const (
	// OTSVerified means a proof exists and checks out.
	OTSVerified OTSStatus = "verified"
	// OTSPending means the event was submitted and the proof is not ready yet.
	OTSPending OTSStatus = "pending"
	// OTSMissing means there is no proof for this event.
	OTSMissing OTSStatus = "missing"
)

// label renders the status in English and Swahili for the packet table.
func (s OTSStatus) label() string {
	switch s {
	case OTSVerified:
		return "verified / imethibitishwa"
	case OTSPending:
		return "pending / inasubiri"
	case OTSMissing:
		return "missing / haipo"
	default:
		return string(s)
	}
}

// valid reports whether s is a known status.
func (s OTSStatus) valid() bool {
	return s == OTSVerified || s == OTSPending || s == OTSMissing
}

// LoggedEvent is one row of the packet's event log.
type LoggedEvent struct {
	// Kind is the event kind, for example "check-in" or "panic".
	Kind string `json:"kind"`
	// At is the sender's original signed timestamp. It survives
	// store-and-forward unchanged; see docs/message-format.md.
	At time.Time `json:"at"`
	// Seq is the sender's sequence number.
	Seq uint32 `json:"seq"`
	// Signer is the sender's x-only public key, 64 hex characters.
	Signer string `json:"signer"`
	// OTS is the OpenTimestamps proof status.
	OTS OTSStatus `json:"ots"`
	// Note is an optional human remark, for example "deadline passed".
	Note string `json:"note,omitempty"`
}

// FromMessageEvent converts an opened message.Event into a log row. ots is
// the OpenTimestamps proof status from the anchoring step (#13) and note is
// an optional human remark.
func FromMessageEvent(ev message.Event, ots OTSStatus, note string) LoggedEvent {
	return LoggedEvent{
		Kind:   ev.Kind.String(),
		At:     ev.CreatedAt,
		Seq:    ev.Seq,
		Signer: hex.EncodeToString(ev.Signer[:]),
		OTS:    ots,
		Note:   note,
	}
}

// GuardianStatement is one guardian's signed account in their own words.
type GuardianStatement struct {
	// Guardian names who speaks: a name, or a label such as "Guardian 1".
	Guardian string `json:"guardian"`
	// Statement is what they say, in their own words.
	Statement string `json:"statement"`
	// SignedAt is when they signed it.
	SignedAt time.Time `json:"signed_at"`
}

// Record is the alarm record the packet is filled from.
type Record struct {
	// SubjectName is the missing person's full name as it should appear
	// in court.
	SubjectName string `json:"subject_name"`
	// NationalID is the Kenyan ID number, if known. Empty renders as
	// "not provided / haikutolewa".
	NationalID string `json:"national_id,omitempty"`
	// LastSeenAt is the time they were last seen: the last signed
	// check-in time.
	LastSeenAt time.Time `json:"last_seen_at"`
	// LastSeenPlace is the place last seen: only what the activist typed.
	LastSeenPlace string `json:"last_seen_place"`
	// Statements holds at least one guardian statement.
	Statements []GuardianStatement `json:"statements"`
	// Events holds at least one signed event.
	Events []LoggedEvent `json:"events"`
	// GeneratedAt is when the packet was rendered. Zero means now (UTC).
	GeneratedAt time.Time `json:"generated_at,omitempty"`
}

// Limits keep one bad field from producing an unbounded packet.
const (
	maxName       = 200
	maxID         = 64
	maxPlace      = 280
	maxKind       = 64
	maxNote       = 280
	maxGuardian   = 200
	maxStatement  = 4000
	maxStatements = 10
	maxEvents     = 500
)

// check validates r and reports the first problem. All messages are in
// English and Swahili because the packet tool is user-facing.
func (r Record) check() error {
	if strings.TrimSpace(r.SubjectName) == "" {
		return fmt.Errorf("packet: subject name is required / jina la mtu linahitajika")
	}
	if len(r.SubjectName) > maxName {
		return fmt.Errorf("packet: subject name too long / jina la mtu ni refu mno")
	}
	if len(r.NationalID) > maxID {
		return fmt.Errorf("packet: national ID too long / kitambulisho ni kirefu mno")
	}
	if r.LastSeenAt.IsZero() {
		return fmt.Errorf("packet: time last seen is required / muda alipoonekana mwisho unahitajika")
	}
	if strings.TrimSpace(r.LastSeenPlace) == "" {
		return fmt.Errorf("packet: place last seen is required / mahali alipoonekana mwisho panahitajika")
	}
	if len(r.LastSeenPlace) > maxPlace {
		return fmt.Errorf("packet: place last seen too long / mahali ni marefu mno")
	}
	if len(r.Statements) == 0 {
		return fmt.Errorf("packet: at least one guardian statement is required / angalau maelezo moja ya mlinzi yanahitajika")
	}
	if len(r.Statements) > maxStatements {
		return fmt.Errorf("packet: too many guardian statements / maelezo ya walinzi ni mengi mno")
	}
	for i, s := range r.Statements {
		if strings.TrimSpace(s.Guardian) == "" {
			return fmt.Errorf("packet: statement %d: guardian is required / mlinzi anahitajika", i+1)
		}
		if len(s.Guardian) > maxGuardian {
			return fmt.Errorf("packet: statement %d: guardian too long / jina la mlinzi ni refu mno", i+1)
		}
		if strings.TrimSpace(s.Statement) == "" {
			return fmt.Errorf("packet: statement %d: text is required / maelezo yanahitajika", i+1)
		}
		if len(s.Statement) > maxStatement {
			return fmt.Errorf("packet: statement %d: text too long / maelezo ni marefu mno", i+1)
		}
		if s.SignedAt.IsZero() {
			return fmt.Errorf("packet: statement %d: signed time is required / muda wa kutia saini unahitajika", i+1)
		}
	}
	if len(r.Events) == 0 {
		return fmt.Errorf("packet: at least one event is required / angalau tukio moja linahitajika")
	}
	if len(r.Events) > maxEvents {
		return fmt.Errorf("packet: too many events / matukio ni mengi mno")
	}
	for i, e := range r.Events {
		if strings.TrimSpace(e.Kind) == "" {
			return fmt.Errorf("packet: event %d: kind is required / aina inahitajika", i+1)
		}
		if len(e.Kind) > maxKind {
			return fmt.Errorf("packet: event %d: kind too long / aina ni ndefu mno", i+1)
		}
		if e.At.IsZero() {
			return fmt.Errorf("packet: event %d: signed time is required / muda uliotiwa saini unahitajika", i+1)
		}
		if !validSigner(e.Signer) {
			return fmt.Errorf("packet: event %d: signer must be 64 hex characters / mtia saini lazima awe herufi 64 za heksi", i+1)
		}
		if !e.OTS.valid() {
			return fmt.Errorf("packet: event %d: OpenTimestamps status must be verified, pending or missing / hali ya OpenTimestamps lazima iwe verified, pending au missing", i+1)
		}
		if len(e.Note) > maxNote {
			return fmt.Errorf("packet: event %d: note too long / maelezo ni marefu mno", i+1)
		}
	}
	return nil
}

func validSigner(s string) bool {
	b, err := hex.DecodeString(s)
	return err == nil && len(b) == 32
}

// Render fills the Kenya habeas template from r and returns the Markdown.
// It refuses invalid records; see Record.check for the rules.
func Render(r Record) (string, error) {
	if err := r.check(); err != nil {
		return "", err
	}
	gen := r.GeneratedAt
	if gen.IsZero() {
		gen = time.Now().UTC()
	}
	var b strings.Builder
	b.WriteString("# Habeas corpus packet (Kenya) / Jalada la habeas corpus (Kenya)\n\n")
	b.WriteString("> A Kenyan advocate must review this packet before any real use.\n")
	b.WriteString("> Wakili wa Kenya lazima apitie jalada hili kabla ya matumizi yoyote halisi.\n\n")
	b.WriteString("Generated / Imetengenezwa: " + gen.UTC().Format("2006-01-02 15:04 UTC") + "\n\n")
	b.WriteString("## Subject / Mtu\n\n")
	fmt.Fprintf(&b, "- Name / Jina: %s\n", strings.TrimSpace(r.SubjectName))
	id := "not provided / haikutolewa"
	if strings.TrimSpace(r.NationalID) != "" {
		id = strings.TrimSpace(r.NationalID)
	}
	fmt.Fprintf(&b, "- National ID / Kitambulisho cha Taifa: %s\n", id)
	fmt.Fprintf(&b, "- Time last seen / Muda alipoonekana mwisho: %s\n", r.LastSeenAt.UTC().Format("2006-01-02 15:04 UTC"))
	fmt.Fprintf(&b, "- Place last seen / Mahali alipoonekana mwisho: %s\n", strings.TrimSpace(r.LastSeenPlace))
	b.WriteString("\n## Guardian statements / Maelezo ya walinzi\n\n")
	for i, s := range r.Statements {
		fmt.Fprintf(&b, "### %d. %s\n\n", i+1, strings.TrimSpace(s.Guardian))
		b.WriteString(strings.TrimSpace(s.Statement) + "\n\n")
		fmt.Fprintf(&b, "Signed / Imesainiwa: %s\n\n", s.SignedAt.UTC().Format("2006-01-02 15:04 UTC"))
	}
	b.WriteString("## Event log / Kumbukumbu za matukio\n\n")
	b.WriteString("Each row shows the sender's original signed time and its OpenTimestamps proof status.\n")
	b.WriteString("Kila mstari unaonyesha muda asili wa mtumaji uliotiwa saini na hali ya uthibitisho wa OpenTimestamps.\n\n")
	b.WriteString("| # | Kind / Aina | Signed time / Muda uliotiwa saini (UTC) | Seq | Signer / Mtia saini | OTS proof / Uthibitisho | Note / Maelezo |\n")
	b.WriteString("|---|-------------|------------------------------------------|-----|---------------------|-------------------------|----------------|\n")
	for i, e := range r.Events {
		fmt.Fprintf(&b, "| %d | %s | %s | %d | %s | %s | %s |\n",
			i+1,
			cell(e.Kind),
			e.At.UTC().Format("2006-01-02 15:04 UTC"),
			e.Seq,
			e.Signer,
			e.OTS.label(),
			cell(e.Note),
		)
	}
	b.WriteString("\n## Timing note / Ufafanuzi wa muda\n\n")
	b.WriteString("Signed times are the sender's claims until anchored: a wrong phone clock gives a wrong time.\n")
	b.WriteString("OpenTimestamps proves only that an event existed by a certain block.\n")
	b.WriteString("See the threat model, section 6.10.\n")
	b.WriteString("Muda uliotiwa saini ni madai ya mtumaji hadi uthibitishwe: saa ya simu isiyo sahihi hutoa muda usio sahihi.\n")
	b.WriteString("OpenTimestamps inathibitisha tu kwamba tukio lilikuwepo kufikia block fulani.\n")
	b.WriteString("\n## Advocate review / Uhakiki wa wakili\n\n")
	b.WriteString("- [ ] A Kenyan advocate has reviewed this packet. / Wakili wa Kenya amepitia jalada hili.\n")
	b.WriteString("- Reviewer / Aliyehakiki: [NAME, DATE]\n")
	return b.String(), nil
}

// cell escapes one Markdown table cell so a "|" or newline in user data
// cannot break the event log layout.
func cell(s string) string {
	s = strings.TrimSpace(s)
	s = strings.ReplaceAll(s, "|", "\\|")
	s = strings.ReplaceAll(s, "\r", " ")
	return strings.ReplaceAll(s, "\n", " ")
}
