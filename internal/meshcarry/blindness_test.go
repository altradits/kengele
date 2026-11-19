package meshcarry

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"go/parser"
	"go/token"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/altradits/kengele/internal/message"
	"github.com/btcsuite/btcd/btcec/v2"
)

// Made-up test keys. Never use these for anything real.
const (
	blindActivistHex = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"
	blindGuardianHex = "b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2"
)

func blindKey(t *testing.T, h string) *btcec.PrivateKey {
	t.Helper()
	b, err := hex.DecodeString(h)
	if err != nil {
		t.Fatal(err)
	}
	k, _ := btcec.PrivKeyFromBytes(b)
	return k
}

// packageImports parses every non-test Go file in this package and
// returns its import paths.
func packageImports(t *testing.T) map[string][]string {
	t.Helper()
	_, self, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("cannot locate test file")
	}
	files, err := filepath.Glob(filepath.Join(filepath.Dir(self), "*.go"))
	if err != nil {
		t.Fatal(err)
	}
	out := map[string][]string{}
	for _, f := range files {
		if strings.HasSuffix(f, "_test.go") {
			continue
		}
		fset := token.NewFileSet()
		pf, err := parser.ParseFile(fset, f, nil, parser.ImportsOnly)
		if err != nil {
			t.Fatal(err)
		}
		for _, imp := range pf.Imports {
			out[filepath.Base(f)] = append(out[filepath.Base(f)], strings.Trim(imp.Path.Value, `"`))
		}
	}
	return out
}

// T9a: the relay path cannot decrypt because it cannot even name the
// decryptor: no non-test file imports internal/message.
func TestNoMessageImport(t *testing.T) {
	for file, imports := range packageImports(t) {
		for _, imp := range imports {
			if strings.Contains(imp, "internal/message") {
				t.Errorf("%s imports %s: the relay path must stay blind", file, imp)
			}
		}
	}
}

// T9a: relaying works on random 228-byte blobs with no message layer
// involved at all.
func TestRelayWorksOnRandomBlobs(t *testing.T) {
	dg := make([]byte, MaxDatagram)
	if _, err := rand.Read(dg); err != nil {
		t.Fatal(err)
	}
	frags, err := Split(dg, 0xC0FFEE, DefaultTTL)
	if err != nil {
		t.Fatal(err)
	}
	r := NewReassembler(0, 0)
	var out []byte
	var complete bool
	for _, f := range frags {
		if out, complete, err = r.Add(f); err != nil {
			t.Fatal(err)
		}
	}
	if !complete || !bytes.Equal(out, dg) {
		t.Fatal("random blob did not round-trip byte-identical")
	}
	s := lineSim()
	s.SeedMsgID(42)
	if err := s.Inject("A", dg); err != nil {
		t.Fatal(err)
	}
	s.Run(10)
	got := s.Deliveries("C")
	if len(got) != 1 || !bytes.Equal(got[0].Datagram, dg) {
		t.Fatalf("C deliveries = %d, want 1 byte-identical blob", len(got))
	}
}

// traceRun runs one datagram through a seeded A->B->C chain and returns
// the per-wave relay counts plus every trace event.
func traceRun(datagram []byte) ([]int, []TraceEvent) {
	s := lineSim()
	s.SeedMsgID(0x777001)
	var events []TraceEvent
	s.OnTrace = func(ev TraceEvent) { events = append(events, ev) }
	if err := s.Inject("A", datagram); err != nil {
		panic(err)
	}
	var counts []int
	for i := 0; i < 10; i++ {
		n := s.Step()
		counts = append(counts, n)
		if n == 0 {
			break
		}
	}
	return counts, events
}

// T9b: a sealed check-in and a sealed panic are indistinguishable on
// the carrier: identical fragment counts, sizes and headers apart from
// the message id, and identical simulator relay traces.
func TestKindIndistinguishable(t *testing.T) {
	activist := blindKey(t, blindActivistHex)
	guardian := blindKey(t, blindGuardianHex)
	guardianPub := message.PublicKey(guardian)
	now := time.Date(2026, 1, 15, 14, 7, 0, 0, time.UTC)

	checkIn, err := message.Seal(message.Event{
		Kind: message.KindCheckIn, CreatedAt: now, Seq: 7, Body: []byte("Kisumu"),
	}, activist, guardianPub)
	if err != nil {
		t.Fatal(err)
	}
	panicEv, err := message.Seal(message.Event{
		Kind: message.KindPanic, CreatedAt: now, Seq: 8, Body: []byte("Mtihani"),
	}, activist, guardianPub)
	if err != nil {
		t.Fatal(err)
	}

	fragsA, err := Split(checkIn, 0xAAAAAA, DefaultTTL)
	if err != nil {
		t.Fatal(err)
	}
	fragsB, err := Split(panicEv, 0xBBBBBB, DefaultTTL)
	if err != nil {
		t.Fatal(err)
	}
	if len(fragsA) != len(fragsB) {
		t.Fatalf("frag counts %d vs %d", len(fragsA), len(fragsB))
	}
	for i := range fragsA {
		if len(fragsA[i]) != len(fragsB[i]) {
			t.Fatalf("frag %d sizes %d vs %d", i, len(fragsA[i]), len(fragsB[i]))
		}
		ha, _, err := ParseHeader(fragsA[i])
		if err != nil {
			t.Fatal(err)
		}
		hb, _, err := ParseHeader(fragsB[i])
		if err != nil {
			t.Fatal(err)
		}
		ha.MsgID, hb.MsgID = 0, 0
		if ha != hb {
			t.Fatalf("frag %d headers differ: %+v vs %+v", i, ha, hb)
		}
	}

	countsA, traceA := traceRun(checkIn)
	countsB, traceB := traceRun(panicEv)
	if fmt.Sprint(countsA) != fmt.Sprint(countsB) {
		t.Fatalf("relay counts %v vs %v", countsA, countsB)
	}
	if len(traceA) != len(traceB) {
		t.Fatalf("trace lengths %d vs %d", len(traceA), len(traceB))
	}
	for i := range traceA {
		if traceA[i] != traceB[i] {
			t.Fatalf("trace %d differs:\n%+v\n%+v", i, traceA[i], traceB[i])
		}
	}
}

// T10: the carry path is memory-only. No non-test file imports os, and
// a full simulate-and-reassemble cycle leaves no new files behind.
func TestMemoryOnly(t *testing.T) {
	for file, imports := range packageImports(t) {
		for _, imp := range imports {
			if imp == "os" {
				t.Errorf("%s imports %q: carry state must stay in memory", file, imp)
			}
		}
	}
	_, self, _, _ := runtime.Caller(0)
	dir := filepath.Dir(self)
	before, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	s := lineSim()
	if err := s.Inject("A", testDatagram(0xEE)); err != nil {
		t.Fatal(err)
	}
	s.Run(10)
	after, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(before) != len(after) {
		t.Errorf("package dir changed from %d to %d entries during a sim run", len(before), len(after))
	}
}
