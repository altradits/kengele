package confirm

import (
	"context"
	"encoding/hex"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/btcsuite/btcd/btcec/v2"
	gn "github.com/nbd-wtf/go-nostr"

	"github.com/altradits/kengele/internal/message"
)

var t0 = time.Date(2026, 1, 15, 14, 0, 0, 0, time.UTC)

type party struct {
	priv *btcec.PrivateKey
	pub  [32]byte
}

func newParty(t *testing.T) party {
	t.Helper()
	k, err := btcec.NewPrivateKey()
	if err != nil {
		t.Fatal(err)
	}
	return party{priv: k, pub: message.PublicKey(k)}
}

type fixture struct {
	activist  party
	alertKey  *btcec.PrivateKey
	guardians []party
	kits      []GuardianKit
	alert     Alert
	seq       uint32
}

func newFixture(t *testing.T, n, k int) *fixture {
	t.Helper()
	f := &fixture{activist: newParty(t), alert: Alert{
		Name: "Amina W.", Lawyer: "Wakili J. Otieno +254 700 000 000",
		Photo: "https://example.org/amina.jpg", Text: "Please share widely.",
	}}
	var err error
	if f.alertKey, err = btcec.NewPrivateKey(); err != nil {
		t.Fatal(err)
	}
	var pubs [][32]byte
	for i := 0; i < n; i++ {
		g := newParty(t)
		f.guardians = append(f.guardians, g)
		pubs = append(pubs, g.pub)
	}
	f.kits, err = NewKits(f.alert, f.activist.pub, f.alertKey, pubs, k, nil)
	if err != nil {
		t.Fatal(err)
	}
	return f
}

func (f *fixture) tally(t *testing.T, i int) *Tally {
	t.Helper()
	tl, err := NewTally(f.kits[i], 0)
	if err != nil {
		t.Fatal(err)
	}
	return tl
}

// over sends ev from sender to recipient through the real wire format, so
// the tests exercise signatures and encryption too.
func (f *fixture) over(t *testing.T, ev message.Event, from *btcec.PrivateKey, to party, senders [][32]byte, now time.Time) *message.Event {
	t.Helper()
	f.seq++
	ev.Seq = f.seq
	wire, err := message.Seal(ev, from, to.pub)
	if err != nil {
		t.Fatal(err)
	}
	got, err := message.Open(wire, to.priv, senders, now)
	if err != nil {
		t.Fatal(err)
	}
	return got
}

func (f *fixture) confirm(t *testing.T, tl *Tally, from int, epoch uint32, at, now time.Time) (Outcome, error) {
	t.Helper()
	ev, err := ConfirmEvent(f.kits[from], epoch, 0, at)
	if err != nil {
		t.Fatal(err)
	}
	return tl.Handle(f.over(t, ev, f.guardians[from].priv, f.guardians[0], tl.Senders(), now), now)
}

func (f *fixture) clearFrom(t *testing.T, tl *Tally, priv *btcec.PrivateKey, epoch uint32, at, now time.Time) (Outcome, error) {
	t.Helper()
	ev, err := AllClearEvent(f.activist.pub, epoch, 0, at)
	if err != nil {
		t.Fatal(err)
	}
	return tl.Handle(f.over(t, ev, priv, f.guardians[0], tl.Senders(), now), now)
}

func mustAction(t *testing.T, out Outcome, err error, want Action) Outcome {
	t.Helper()
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if out.Action != want {
		t.Fatalf("action = %v, want %v", out.Action, want)
	}
	return out
}

func mustErr(t *testing.T, out Outcome, err, want error) {
	t.Helper()
	if !errors.Is(err, want) {
		t.Fatalf("err = %v, want %v", err, want)
	}
	if out.Action != None {
		t.Fatalf("action = %v on error", out.Action)
	}
}

func TestOneOfThreeDoesNothingPublic(t *testing.T) {
	f := newFixture(t, 3, 2)
	tl := f.tally(t, 0)
	out, err := f.confirm(t, tl, 0, 1, t0, t0)
	mustAction(t, out, err, None)
	if tl.Released() {
		t.Fatal("released after 1 of 3")
	}
}

func TestTwoOfThreeReleasesOnce(t *testing.T) {
	f := newFixture(t, 3, 2)
	tl := f.tally(t, 0)
	expect(t, None)(f.confirm(t, tl, 0, 1, t0, t0))
	out := expect(t, Release)(f.confirm(t, tl, 1, 1, t0.Add(time.Minute), t0.Add(time.Minute)))
	if out.Alert == nil || out.Alert.Name != f.alert.Name || out.AlertKey == nil {
		t.Fatal("release did not carry the alert")
	}
	if message.PublicKey(out.AlertKey) != message.PublicKey(f.alertKey) {
		t.Fatal("wrong alert key")
	}
	if out.Confirmed != 2 || out.Epoch != 1 {
		t.Fatalf("confirmed=%d epoch=%d", out.Confirmed, out.Epoch)
	}
	// The third guardian's late confirmation must not publish again.
	o3, err := f.confirm(t, tl, 2, 1, t0.Add(2*time.Minute), t0.Add(2*time.Minute))
	mustErr(t, o3, err, ErrAlreadyReleased)
}

func TestThresholdEdges(t *testing.T) {
	for _, tc := range []struct{ n, k int }{{2, 2}, {3, 3}, {5, 3}, {10, 10}} {
		f := newFixture(t, tc.n, tc.k)
		tl := f.tally(t, 0)
		for i := 0; i < tc.k-1; i++ {
			out, err := f.confirm(t, tl, i, 1, t0, t0)
			mustAction(t, out, err, None)
		}
		out, err := f.confirm(t, tl, tc.k-1, 1, t0, t0)
		mustAction(t, out, err, Release)
	}

	// Invalid thresholds are refused when the kits are made.
	a := newParty(t)
	key, _ := btcec.NewPrivateKey()
	g1, g2, g3 := newParty(t), newParty(t), newParty(t)
	al := Alert{Name: "A", Lawyer: "L"}
	for _, k := range []int{0, 1, 4} {
		if _, err := NewKits(al, a.pub, key, [][32]byte{g1.pub, g2.pub, g3.pub}, k, nil); !errors.Is(err, ErrThreshold) {
			t.Fatalf("k=%d: err = %v", k, err)
		}
	}
	if _, err := NewKits(al, a.pub, key, [][32]byte{g1.pub, g1.pub, g3.pub}, 2, nil); !errors.Is(err, ErrGuardians) {
		t.Fatalf("duplicate guardian: err = %v", err)
	}
	if _, err := NewKits(al, a.pub, key, [][32]byte{a.pub, g2.pub}, 2, nil); !errors.Is(err, ErrGuardians) {
		t.Fatalf("activist as guardian: err = %v", err)
	}
	if _, err := NewKits(al, a.pub, a.priv, [][32]byte{g1.pub, g2.pub}, 2, nil); !errors.Is(err, ErrKit) {
		t.Fatalf("alert key = activist key: err = %v", err)
	}
}

func TestDuplicateConfirmationsIgnored(t *testing.T) {
	f := newFixture(t, 3, 2)
	tl := f.tally(t, 0)
	expect(t, None)(f.confirm(t, tl, 1, 1, t0, t0))
	// The same guardian again, even with a new message, does not reach 2.
	o, err := f.confirm(t, tl, 1, 1, t0.Add(time.Minute), t0.Add(time.Minute))
	mustErr(t, o, err, ErrDuplicate)
	if tl.Released() {
		t.Fatal("one guardian released alone")
	}
}

func TestExpiredConfirmationsIgnored(t *testing.T) {
	f := newFixture(t, 3, 2)
	tl := f.tally(t, 0)

	// Arrives already too old.
	late := t0.Add(DefaultConfirmTTL + time.Hour)
	o, err := f.confirm(t, tl, 1, 1, t0, late)
	mustErr(t, o, err, ErrExpired)

	// Valid when it arrives, but expired by the time a second one comes.
	expect(t, None)(f.confirm(t, tl, 0, 1, t0, t0))
	o, err = f.confirm(t, tl, 2, 1, late, late)
	mustAction(t, o, err, None)
	if tl.Released() {
		t.Fatal("expired confirmation counted")
	}
}

func TestForgedConfirmationRejected(t *testing.T) {
	f := newFixture(t, 3, 2)
	tl := f.tally(t, 0)
	stranger := newParty(t)

	// A stranger's key is not a known sender: message.Open refuses it.
	ev, _ := ConfirmEvent(f.kits[1], 1, 1, t0)
	wire, _ := message.Seal(ev, stranger.priv, f.guardians[0].pub)
	if _, err := message.Open(wire, f.guardians[0].priv, tl.Senders(), t0); !errors.Is(err, message.ErrNoSender) {
		t.Fatalf("stranger accepted: %v", err)
	}

	// Even if handed to the tally directly, a non-guardian signer is refused.
	ev.Signer = stranger.pub
	o, err := tl.Handle(&ev, t0)
	mustErr(t, o, err, ErrNotGuardian)

	// The activist cannot confirm their own alarm (their phone may be seized).
	o, err = tl.Handle(f.over(t, ev, f.activist.priv, f.guardians[0], tl.Senders(), t0), t0)
	mustErr(t, o, err, ErrNotGuardian)

	// A guardian's confirmation altered in transit fails the signature.
	ev2, _ := ConfirmEvent(f.kits[1], 1, 2, t0)
	wire, _ = message.Seal(ev2, f.guardians[1].priv, f.guardians[0].pub)
	wire[len(wire)-40] ^= 0x01
	if _, err := message.Open(wire, f.guardians[0].priv, tl.Senders(), t0); err == nil {
		t.Fatal("altered confirmation opened")
	}
}

func TestBadShareCannotForgeOrBlock(t *testing.T) {
	f := newFixture(t, 3, 2)
	tl := f.tally(t, 0)
	// Guardian 1 sends a garbage share: no release with only it and one other...
	bad := message.Event{Kind: message.KindGuardianConfirm, CreatedAt: t0,
		Body: encodeConfirm(f.activist.pub, 1, make([]byte, shareSize))}
	bad.Body[len(bad.Body)-1] = 7
	o, err := tl.Handle(f.over(t, bad, f.guardians[1].priv, f.guardians[0], tl.Senders(), t0), t0)
	mustAction(t, o, err, None)
	o, err = f.confirm(t, tl, 0, 1, t0, t0)
	mustAction(t, o, err, None)
	// ...but two honest guardians still release.
	o, err = f.confirm(t, tl, 2, 1, t0, t0)
	mustAction(t, o, err, Release)
}

func TestReplayFromEarlierAlarm(t *testing.T) {
	f := newFixture(t, 3, 2)
	tl := f.tally(t, 0)

	// Alarm 1: guardian 1 confirms, then two guardians clear it.
	ev1, _ := ConfirmEvent(f.kits[1], 1, 0, t0)
	opened1 := f.over(t, ev1, f.guardians[1].priv, f.guardians[0], tl.Senders(), t0)
	expect(t, None)(tl.Handle(opened1, t0))
	expect(t, None)(f.clearFrom(t, tl, f.guardians[0].priv, 1, t0.Add(time.Minute), t0.Add(time.Minute)))
	expect(t, StandDown)(f.clearFrom(t, tl, f.guardians[2].priv, 1, t0.Add(time.Minute), t0.Add(time.Minute)))
	if tl.Epoch() != 2 {
		t.Fatalf("epoch = %d", tl.Epoch())
	}

	// Alarm 2: replaying guardian 1's old confirmation does not count.
	now := t0.Add(2 * time.Hour)
	o, err := tl.Handle(opened1, now)
	mustErr(t, o, err, ErrStale)
	// One fresh confirmation for alarm 2 is still only one.
	o, err = f.confirm(t, tl, 2, 2, now, now)
	mustAction(t, o, err, None)
	if tl.Released() {
		t.Fatal("replay helped release")
	}

	// A confirmation far in the future is refused.
	o, err = f.confirm(t, tl, 1, 2+maxAhead+1, now, now)
	mustErr(t, o, err, ErrTooFarAhead)

	// A confirmation for another activist is refused.
	other := newFixture(t, 3, 2)
	evo, _ := ConfirmEvent(other.kits[1], 2, 0, now)
	evo.Signer = f.guardians[1].pub
	o, err = tl.Handle(&evo, now)
	mustErr(t, o, err, ErrNotActivist)
}

func TestSingleCoercedGuardianCannotPublish(t *testing.T) {
	f := newFixture(t, 3, 2)
	// The coerced guardian holds only their own kit. Their share alone does
	// not open the alert, whatever they try.
	kit := f.kits[1]
	for _, guess := range [][]byte{kit.Share[:keySize], make([]byte, keySize)} {
		if _, _, err := kit.open(guess); err == nil {
			t.Fatal("one share opened the alert")
		}
	}
	// And their own tally never releases on their vote alone.
	tl := f.tally(t, 1)
	o, err := f.confirm(t, tl, 1, 1, t0, t0)
	mustAction(t, o, err, None)
	if _, err := Note(o, Status{}, "", t0); !errors.Is(err, ErrNoRelease) {
		t.Fatalf("note built without release: %v", err)
	}
	// Publish refuses an empty outcome.
	if _, err := Publish(context.Background(), &recPub{}, o, Status{}, "", t0); !errors.Is(err, ErrNoRelease) {
		t.Fatalf("published without release: %v", err)
	}
}

func TestActivistAllClearAloneEndsNothing(t *testing.T) {
	// After a panic, captors force the activist to unlock with the real
	// PIN and send an all-clear. The alarm must stay, and the release must
	// still happen when a second guardian confirms.
	f := newFixture(t, 3, 2)
	tl := f.tally(t, 0)
	panicEv := message.Event{Kind: message.KindPanic, CreatedAt: t0}
	o, err := tl.Handle(f.over(t, panicEv, f.activist.priv, f.guardians[0], tl.Senders(), t0), t0)
	mustErr(t, o, err, ErrIgnoredKind)
	expect(t, None)(f.confirm(t, tl, 0, 1, t0, t0))

	o = expect(t, None)(f.clearFrom(t, tl, f.activist.priv, 1, t0, t0))
	if !o.ActivistSaysSafe || o.By != NotCleared || tl.Epoch() != 1 {
		t.Fatalf("activist all-clear ended the alarm: %+v epoch=%d", o, tl.Epoch())
	}
	o, err = f.clearFrom(t, tl, f.activist.priv, 1, t0, t0)
	mustErr(t, o, err, ErrDuplicate)

	cs := tl.ClearStatus(t0)
	if !cs.ActivistSaysSafe || !cs.VerifyFirst || cs.GuardianClears != 0 || cs.Needed != 2 || cs.Epoch != 1 {
		t.Fatalf("clear status = %+v", cs)
	}
	// The pending confirmation survived, and the second one releases.
	expect(t, Release)(f.confirm(t, tl, 2, 1, t0.Add(time.Minute), t0.Add(time.Minute)))
}

func TestActivistPlusOneGuardianIsNotEnough(t *testing.T) {
	// The activist's vote never counts towards k: with k=2 the activist and
	// one guardian do not stand down; a second guardian does.
	f := newFixture(t, 3, 2)
	tl := f.tally(t, 0)
	expect(t, None)(f.clearFrom(t, tl, f.activist.priv, 1, t0, t0))
	expect(t, None)(f.clearFrom(t, tl, f.guardians[1].priv, 1, t0, t0))
	if cs := tl.ClearStatus(t0); cs.GuardianClears != 1 || !cs.ActivistSaysSafe {
		t.Fatalf("clear status = %+v", cs)
	}
	o := expect(t, StandDown)(f.clearFrom(t, tl, f.guardians[2].priv, 1, t0, t0))
	if o.By != ByGuardians || tl.Epoch() != 2 {
		t.Fatalf("by=%v epoch=%d", o.By, tl.Epoch())
	}
	// The next alarm starts with a clean slate.
	if cs := tl.ClearStatus(t0); cs.ActivistSaysSafe || cs.GuardianClears != 0 || cs.Epoch != 2 {
		t.Fatalf("clear status after stand-down = %+v", cs)
	}
}

func TestGuardianAllClearNeedsVerification(t *testing.T) {
	f := newFixture(t, 3, 2)
	for _, v := range []Verification{
		{},
		{Method: "sms", At: t0},
		{Method: "voice"},
		{Method: "video", At: t0.Add(time.Hour)}, // in the future
	} {
		if _, err := GuardianAllClearEvent(f.activist.pub, 1, 0, t0, v); !errors.Is(err, ErrNotVerified) {
			t.Fatalf("%+v: err = %v", v, err)
		}
	}
	ev, err := GuardianAllClearEvent(f.activist.pub, 1, 0, t0, Verification{Method: "video", At: t0.Add(-5 * time.Minute)})
	if err != nil || ev.Kind != message.KindAllClear {
		t.Fatalf("verified all-clear: %v %v", ev.Kind, err)
	}
}

func TestAllClearByTwoGuardians(t *testing.T) {
	f := newFixture(t, 3, 2)
	tl := f.tally(t, 0)
	o, err := f.clearFrom(t, tl, f.guardians[1].priv, 1, t0, t0)
	mustAction(t, o, err, None) // one guardian is not enough
	o, err = f.clearFrom(t, tl, f.guardians[1].priv, 1, t0, t0)
	mustErr(t, o, err, ErrDuplicate)
	o = expect(t, StandDown)(f.clearFrom(t, tl, f.guardians[2].priv, 1, t0, t0))
	if o.By != ByGuardians || tl.Epoch() != 2 {
		t.Fatalf("by=%v epoch=%d", o.By, tl.Epoch())
	}
	// A stranger's all-clear never counts.
	s := newParty(t)
	ev, _ := AllClearEvent(f.activist.pub, 2, 1, t0)
	ev.Signer = s.pub
	o, err = tl.Handle(&ev, t0)
	mustErr(t, o, err, ErrNotGuardian)
}

func TestFoundSafeAfterRelease(t *testing.T) {
	f := newFixture(t, 3, 2)
	tl := f.tally(t, 0)
	expect(t, None)(f.confirm(t, tl, 0, 1, t0, t0))
	rel := expect(t, Release)(f.confirm(t, tl, 2, 1, t0, t0))

	pub := &recPub{}
	st := Status{LastCheckIn: t0.Add(-3 * time.Hour), Place: "Kisumu law courts"}
	alertEv, err := Publish(context.Background(), pub, rel, st, "", t0)
	if err != nil {
		t.Fatal(err)
	}
	checkNote(t, alertEv, f.alertKey, "Amina W.", "Kisumu law courts", "Wakili J. Otieno")

	// One guardian's all-clear is not enough to say "found safe" in public.
	o, err := f.clearFrom(t, tl, f.guardians[1].priv, 1, t0, t0)
	mustAction(t, o, err, None)
	safe := expect(t, FoundSafe)(f.clearFrom(t, tl, f.guardians[2].priv, 1, t0, t0))
	safeEv, err := Publish(context.Background(), pub, safe, st, alertEv.ID, t0.Add(time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	checkNote(t, safeEv, f.alertKey, "found safe")
	if safeEv.Tags.FindWithValue("e", alertEv.ID) == nil {
		t.Fatal("found-safe note does not reference the alert")
	}
	if len(pub.got) != 2 {
		t.Fatalf("published %d notes", len(pub.got))
	}

	// The kit is spent: a later alarm can never release with this key.
	if !tl.Spent() {
		t.Fatal("kit not spent")
	}
	o, err = f.confirm(t, tl, 0, 2, t0.Add(2*time.Hour), t0.Add(2*time.Hour))
	mustErr(t, o, err, ErrSpent)
}

func TestAllClearRaces(t *testing.T) {
	// k guardian all-clears first, confirmation after: the confirmation is
	// stale and nothing goes public.
	f := newFixture(t, 3, 2)
	tl := f.tally(t, 0)
	expect(t, None)(f.confirm(t, tl, 0, 1, t0, t0))
	ev, _ := ConfirmEvent(f.kits[1], 1, 0, t0)                                     // signed before the all-clears...
	opened := f.over(t, ev, f.guardians[1].priv, f.guardians[0], tl.Senders(), t0) // ...delivered after them
	expect(t, None)(f.clearFrom(t, tl, f.guardians[0].priv, 1, t0, t0))
	expect(t, StandDown)(f.clearFrom(t, tl, f.guardians[2].priv, 1, t0, t0))
	o, err := tl.Handle(opened, t0)
	mustErr(t, o, err, ErrStale)
	if tl.Released() {
		t.Fatal("released after all-clear")
	}

	// An activist all-clear racing confirmations neither blocks nor delays
	// the release.
	f1 := newFixture(t, 3, 2)
	tl1 := f1.tally(t, 0)
	expect(t, None)(f1.confirm(t, tl1, 0, 1, t0, t0))
	expect(t, None)(f1.clearFrom(t, tl1, f1.activist.priv, 1, t0, t0))
	expect(t, Release)(f1.confirm(t, tl1, 1, 1, t0, t0))

	// Release first, all-clears after: the activist alone gives no found
	// safe; k guardians do.
	f2 := newFixture(t, 3, 2)
	tl2 := f2.tally(t, 0)
	expect(t, None)(f2.confirm(t, tl2, 0, 1, t0, t0))
	expect(t, Release)(f2.confirm(t, tl2, 1, 1, t0, t0))
	o = expect(t, None)(f2.clearFrom(t, tl2, f2.activist.priv, 1, t0, t0))
	if !o.ActivistSaysSafe || o.Alert != nil || o.AlertKey != nil {
		t.Fatalf("activist all-clear after release: %+v", o)
	}
	expect(t, None)(f2.clearFrom(t, tl2, f2.guardians[1].priv, 1, t0, t0))
	expect(t, FoundSafe)(f2.clearFrom(t, tl2, f2.guardians[2].priv, 1, t0, t0))

	// A stale all-clear (from alarm 1) cannot touch alarm 2.
	o, err = f2.clearFrom(t, tl2, f2.activist.priv, 1, t0, t0)
	mustErr(t, o, err, ErrStale)
}

func TestConcurrentActivistAllClearAndConfirms(t *testing.T) {
	f := newFixture(t, 5, 3)
	tl := f.tally(t, 0)
	var evs []*message.Event
	for i := 0; i < 5; i++ {
		ev, _ := ConfirmEvent(f.kits[i], 1, 0, t0)
		evs = append(evs, f.over(t, ev, f.guardians[i].priv, f.guardians[0], tl.Senders(), t0))
	}
	ac, _ := AllClearEvent(f.activist.pub, 1, 0, t0)
	evs = append(evs, f.over(t, ac, f.activist.priv, f.guardians[0], tl.Senders(), t0))
	done := make(chan Outcome, len(evs))
	for _, e := range evs {
		go func(e *message.Event) {
			o, _ := tl.Handle(e, t0)
			done <- o
		}(e)
	}
	releases := 0
	for range evs {
		o := <-done
		if o.Action == Release {
			releases++
		}
		if o.Action == StandDown || o.Action == FoundSafe {
			t.Fatalf("alarm ended without guardians: %+v", o)
		}
	}
	if releases != 1 {
		t.Fatalf("released %d times", releases)
	}
}

func TestConcurrentHandle(t *testing.T) {
	f := newFixture(t, 5, 3)
	tl := f.tally(t, 0)
	evs := make([]*message.Event, 5)
	for i := range evs {
		ev, _ := ConfirmEvent(f.kits[i], 1, 0, t0)
		evs[i] = f.over(t, ev, f.guardians[i].priv, f.guardians[0], tl.Senders(), t0)
	}
	done := make(chan Action, 5)
	for _, e := range evs {
		go func(e *message.Event) {
			o, _ := tl.Handle(e, t0)
			done <- o.Action
		}(e)
	}
	releases := 0
	for range evs {
		if <-done == Release {
			releases++
		}
	}
	if releases != 1 {
		t.Fatalf("released %d times", releases)
	}
}

func TestDuressPath(t *testing.T) {
	f := newFixture(t, 3, 2)
	tl := f.tally(t, 0)
	expect(t, None)(f.confirm(t, tl, 0, 1, t0, t0))

	// Captors force a check-in: the duress PIN sends a duress check-in. It
	// must not stand the alarm down, and neither may an ordinary check-in
	// or a panic.
	for _, k := range []message.Kind{message.KindDuressCheckIn, message.KindCheckIn, message.KindPanic, message.KindGoingDark} {
		ev := message.Event{Kind: k, CreatedAt: t0}
		o, err := tl.Handle(f.over(t, ev, f.activist.priv, f.guardians[0], tl.Senders(), t0), t0)
		mustErr(t, o, err, ErrIgnoredKind)
	}
	if tl.Epoch() != 1 {
		t.Fatal("duress check-in changed the alarm")
	}
	// The second guardian's confirmation still releases.
	expect(t, Release)(f.confirm(t, tl, 2, 1, t0, t0))
}

func TestNoLocationTrail(t *testing.T) {
	for _, s := range []string{
		"-0.0917, 34.7680", "-1.28333,36.81667", "geo:-0.09,34.76",
		"https://maps.google.com/?q=-0.09,34.76", "https://maps.app.goo.gl/abc",
		"https://www.openstreetmap.org/#map=18/-0.09/34.76",
	} {
		if !LooksLikeLocation(s) {
			t.Errorf("not caught: %q", s)
		}
	}
	for _, s := range []string{"Kisumu law courts", "Nairobi CBD, Kenyatta Ave", "+254 700 000 000", "2026-01-15 14:00"} {
		if LooksLikeLocation(s) {
			t.Errorf("false positive: %q", s)
		}
	}

	a := newParty(t)
	key, _ := btcec.NewPrivateKey()
	g1, g2 := newParty(t), newParty(t)
	_, err := NewKits(Alert{Name: "A", Lawyer: "L", Text: "I am at -0.0917, 34.7680"}, a.pub, key, [][32]byte{g1.pub, g2.pub}, 2, nil)
	if !errors.Is(err, ErrCoordinates) {
		t.Fatalf("coordinates in alert accepted: %v", err)
	}

	// A typed place that is really coordinates is refused at release.
	f := newFixture(t, 3, 2)
	tl := f.tally(t, 0)
	expect(t, None)(f.confirm(t, tl, 0, 1, t0, t0))
	rel := expect(t, Release)(f.confirm(t, tl, 1, 1, t0, t0))
	if _, err := Note(rel, Status{Place: "-0.0917,34.7680"}, "", t0); !errors.Is(err, ErrCoordinates) {
		t.Fatalf("coordinates in place accepted: %v", err)
	}
}

func TestKitTampering(t *testing.T) {
	f := newFixture(t, 3, 2)
	// Swapping the guardian list breaks the ciphertext binding.
	kits := []GuardianKit{f.kits[0], f.kits[1]}
	for i := range kits {
		kits[i].Guardians = append([]string(nil), kits[i].Guardians...)
		kits[i].Guardians[2] = hex.EncodeToString(func() []byte { p := newParty(t).pub; return p[:] }())
	}
	tl, err := NewTally(kits[0], 0)
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 2; i++ {
		ev, _ := ConfirmEvent(kits[i], 1, 0, t0)
		ev.Signer = f.guardians[i].pub
		o, err := tl.Handle(&ev, t0)
		mustAction(t, o, err, None)
	}
	if tl.Released() {
		t.Fatal("tampered kit released")
	}

	bad := f.kits[0]
	bad.Share = bad.Share[:10]
	if _, err := NewTally(bad, 0); !errors.Is(err, ErrKit) {
		t.Fatalf("short share accepted: %v", err)
	}
}

func TestBodySizesFitMessage(t *testing.T) {
	if confirmBodySize > message.MaxBodySize || allClearBodySize > message.MaxBodySize {
		t.Fatalf("bodies %d/%d exceed %d", confirmBodySize, allClearBodySize, message.MaxBodySize)
	}
	if _, err := decodeConfirm(encodeConfirm([32]byte{1}, 0, make([]byte, shareSize))); !errors.Is(err, ErrBody) {
		t.Fatal("epoch 0 accepted")
	}
	if _, err := decodeAllClear([]byte{bodyAllClear}); !errors.Is(err, ErrBody) {
		t.Fatal("short all-clear accepted")
	}
}

func TestRecipientsExcludeActivist(t *testing.T) {
	f := newFixture(t, 3, 2)
	tl := f.tally(t, 1)
	rs := tl.Recipients()
	if len(rs) != 2 {
		t.Fatalf("recipients = %d", len(rs))
	}
	for _, r := range rs {
		if r == f.activist.pub || r == f.guardians[1].pub {
			t.Fatal("confirmation would be sent to the activist or to self")
		}
	}
}

type recPub struct{ got []gn.Event }

func (r *recPub) Publish(_ context.Context, ev gn.Event) error {
	r.got = append(r.got, ev)
	return nil
}

func checkNote(t *testing.T, ev gn.Event, key *btcec.PrivateKey, want ...string) {
	t.Helper()
	if ev.Kind != gn.KindTextNote {
		t.Fatalf("kind = %d", ev.Kind)
	}
	pk := message.PublicKey(key)
	if ev.PubKey != hex.EncodeToString(pk[:]) {
		t.Fatal("note not signed by the alert key")
	}
	if ok, err := ev.CheckSignature(); !ok || err != nil {
		t.Fatalf("bad note signature: %v", err)
	}
	low := strings.ToLower(ev.Content)
	for _, w := range want {
		if !strings.Contains(low, strings.ToLower(w)) {
			t.Fatalf("note missing %q:\n%s", w, ev.Content)
		}
	}
}

// expect returns a checker for a (Outcome, error) pair, so calls read
// expect(t, Release)(f.confirm(...)).
func expect(t *testing.T, want Action) func(Outcome, error) Outcome {
	t.Helper()
	return func(o Outcome, err error) Outcome {
		t.Helper()
		return mustAction(t, o, err, want)
	}
}
