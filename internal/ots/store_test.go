package ots

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"github.com/altradits/kengele/internal/message"
)

// mockCalendar pretends to be a public OTS calendar. It answers in
// process through httptest's recorder, so no socket is needed: POST
// /digest returns a pending stamp, GET /timestamp/<hex> follows mode
// ("pending" repeats the pending stamp, "bitcoin" returns a nested
// Bitcoin attestation, "missing" answers 404), and offline fails every
// request like a dead network.
type mockCalendar struct {
	t       *testing.T
	mu      sync.Mutex
	mode    string
	offline bool
	bodies  [][]byte
	submits int
	gets    int
	status  int // override for POST /digest; 0 means 200
	reply   []byte
}

func newMock(t *testing.T) *mockCalendar {
	t.Helper()
	return &mockCalendar{t: t, mode: "pending"}
}

func (m *mockCalendar) url() string { return "http://calendar.test" }

func (m *mockCalendar) client() *http.Client { return &http.Client{Transport: m} }

// RoundTrip implements http.RoundTripper.
func (m *mockCalendar) RoundTrip(req *http.Request) (*http.Response, error) {
	if m.isOffline() {
		return nil, fmt.Errorf("mock: network down")
	}
	rec := httptest.NewRecorder()
	m.serve(rec, req)
	return rec.Result(), nil
}

func (m *mockCalendar) isOffline() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.offline
}

func (m *mockCalendar) serve(w http.ResponseWriter, r *http.Request) {
	m.mu.Lock()
	defer m.mu.Unlock()
	switch {
	case r.Method == http.MethodPost && r.URL.Path == "/digest":
		body, _ := io.ReadAll(r.Body)
		m.bodies = append(m.bodies, body)
		m.submits++
		if m.status != 0 {
			w.WriteHeader(m.status)
			return
		}
		if m.reply != nil {
			w.Write(m.reply)
			return
		}
		ts := &Timestamp{Msg: body, Attestations: []Attestation{{Tag: tagPending, URI: "http://" + r.Host}}}
		w.Write(ts.Marshal())
	case r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/timestamp/"):
		m.gets++
		commitment, err := hex.DecodeString(strings.TrimPrefix(r.URL.Path, "/timestamp/"))
		if err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		switch m.mode {
		case "missing":
			w.WriteHeader(http.StatusNotFound)
		case "bitcoin":
			// A nested upgrade: the commitment sits one append deep
			// under a Bitcoin attestation, as real calendars return.
			root := New(commitment)
			root.Attestations = []Attestation{{Tag: tagPending, URI: "http://" + r.Host}}
			root.child(Op{Tag: tagAppend, Arg: []byte{0x11, 0x22}}).child(Op{Tag: tagSHA256}).Attestations =
				[]Attestation{{Tag: tagBitcoin, Height: 800000}}
			w.Write(root.Marshal())
		default:
			ts := &Timestamp{Msg: commitment, Attestations: []Attestation{{Tag: tagPending, URI: "http://" + r.Host}}}
			w.Write(ts.Marshal())
		}
	default:
		w.WriteHeader(http.StatusNotFound)
	}
}

func openStore(t *testing.T, mocks ...*mockCalendar) *Store {
	t.Helper()
	urls := make([]string, len(mocks))
	for i, m := range mocks {
		urls[i] = m.url()
	}
	s, err := Open(filepath.Join(t.TempDir(), "ots"), urls)
	if err != nil {
		t.Fatal(err)
	}
	for i, m := range mocks {
		s.calendars[i].HTTP = m.client()
	}
	return s
}

func TestAnchorOnlinePending(t *testing.T) {
	m := newMock(t)
	s := openStore(t, m)
	st, err := s.Anchor(context.Background(), testEvent())
	if err != nil {
		t.Fatal(err)
	}
	if st != StatusPending {
		t.Fatalf("status = %s, want pending", st)
	}
	// Only the salted 32-byte commitment left the device.
	if m.submits != 1 || len(m.bodies) != 1 || len(m.bodies[0]) != 32 {
		t.Fatalf("submits = %d bodies %v", m.submits, m.bodies)
	}
	digest := Digest(testEvent())
	if bytes.Equal(m.bodies[0], digest[:]) {
		t.Fatal("raw digest submitted; must be salted")
	}
	// The commitment in the proof matches what was submitted.
	rep, err := s.Verify(digest)
	if err != nil {
		t.Fatal(err)
	}
	if rep.Status != StatusPending || len(rep.PendingFrom) != 1 {
		t.Fatalf("report = %+v", rep)
	}
	raw, _ := os.ReadFile(s.proofPath(hex.EncodeToString(digest[:])))
	f, err := ParseFile(raw)
	if err != nil {
		t.Fatal(err)
	}
	var commitment []byte
	f.Stamp.Walk(func(n *Timestamp) {
		for _, a := range n.Attestations {
			if a.IsPending() {
				commitment = n.Msg
			}
		}
	})
	if !bytes.Equal(commitment, m.bodies[0]) {
		t.Fatal("proof commitment differs from submitted bytes")
	}
	// And the commitment is sha256(digest || nonce from the proof).
	var nonce []byte
	for _, o := range sortedOps(f.Stamp.Ops) {
		if o.Tag == tagAppend {
			nonce = o.Arg
		}
	}
	if len(nonce) != NonceSize {
		t.Fatalf("nonce length %d", len(nonce))
	}
	h := sha256.Sum256(append(append([]byte(nil), digest[:]...), nonce...))
	if !bytes.Equal(h[:], m.bodies[0]) {
		t.Fatal("commitment is not sha256(digest || nonce)")
	}
}

func TestAnchorOfflineThenSync(t *testing.T) {
	m := newMock(t)
	m.offline = true
	s := openStore(t, m)
	// The network is down: Anchor queues without an error.
	st, err := s.Anchor(context.Background(), testEvent())
	if err != nil {
		t.Fatal(err)
	}
	if st != StatusQueued {
		t.Fatalf("offline status = %s, want queued", st)
	}
	digest := Digest(testEvent())
	if rep, _ := s.Verify(digest); rep.Status != StatusQueued || !rep.ProofMissing {
		t.Fatalf("queued report = %+v", rep)
	}
	// The network returns. A new store handle on the same directory —
	// as after a restart — submits the queue.
	m.offline = false
	s2, err := Open(s.dir, []string{m.url()})
	if err != nil {
		t.Fatal(err)
	}
	s2.calendars[0].HTTP = m.client()
	res, err := s2.Sync(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(res) != 1 || !res[0].Submitted || res[0].Status != StatusPending || res[0].Err != nil {
		t.Fatalf("sync results = %+v", res)
	}
	if rep, _ := s2.Verify(digest); rep.Status != StatusPending {
		t.Fatalf("after sync: %+v", rep)
	}
}

func TestAnchorSkipsRoutineEvents(t *testing.T) {
	m := newMock(t)
	s := openStore(t, m)
	e := testEvent()
	e.Kind = message.KindCheckIn
	st, err := s.Anchor(context.Background(), e)
	if err != nil || st != StatusSkipped {
		t.Fatalf("check-in: %s, %v", st, err)
	}
	if _, err := s.Verify(Digest(e)); err != ErrUnknown {
		t.Fatalf("routine event stored: %v", err)
	}
}

func TestAnchorIdempotent(t *testing.T) {
	m := newMock(t)
	s := openStore(t, m)
	if _, err := s.Anchor(context.Background(), testEvent()); err != nil {
		t.Fatal(err)
	}
	st, err := s.Anchor(context.Background(), testEvent())
	if err != nil || st != StatusPending {
		t.Fatalf("re-anchor: %s, %v", st, err)
	}
	if m.submits != 1 {
		t.Fatalf("submits = %d, want 1", m.submits)
	}
}

func TestUpgradeToBitcoin(t *testing.T) {
	m := newMock(t)
	s := openStore(t, m)
	if _, err := s.Anchor(context.Background(), testEvent()); err != nil {
		t.Fatal(err)
	}
	digest := Digest(testEvent())
	// Still pending: no Bitcoin attestation yet, no error, no rewrite.
	m.mode = "pending"
	res, err := s.Sync(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(res) != 1 || res[0].Upgraded || res[0].Status != StatusPending || res[0].Err != nil {
		t.Fatalf("pending sync = %+v", res)
	}
	// The calendar confirms: the proof gains a Bitcoin attestation.
	m.mode = "bitcoin"
	res, err = s.Sync(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(res) != 1 || !res[0].Upgraded || res[0].Status != StatusAnchored || res[0].Err != nil {
		t.Fatalf("upgrade sync = %+v", res)
	}
	rep, err := s.Verify(digest)
	if err != nil {
		t.Fatal(err)
	}
	if rep.Status != StatusAnchored || len(rep.Bitcoin) != 1 || rep.Bitcoin[0] != 800000 {
		t.Fatalf("report = %+v", rep)
	}
	if !strings.Contains(rep.Summary(), "800000") {
		t.Fatalf("summary = %q", rep.Summary())
	}
}

func TestUpgradeMissingIsBenign(t *testing.T) {
	m := newMock(t)
	s := openStore(t, m)
	if _, err := s.Anchor(context.Background(), testEvent()); err != nil {
		t.Fatal(err)
	}
	m.mode = "missing" // calendar forgot us; stay pending, no error
	res, err := s.Sync(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(res) != 1 || res[0].Err != nil || res[0].Status != StatusPending {
		t.Fatalf("results = %+v", res)
	}
}

func TestCalendarFailures(t *testing.T) {
	ctx := context.Background()
	t.Run("500 keeps queued", func(t *testing.T) {
		m := newMock(t)
		m.status = http.StatusInternalServerError
		s := openStore(t, m)
		if st, err := s.Anchor(ctx, testEvent()); err != nil || st != StatusQueued {
			t.Fatalf("anchor: %s, %v", st, err)
		}
		res, err := s.Sync(ctx)
		if err != nil {
			t.Fatal(err)
		}
		if len(res) != 1 || res[0].Err == nil || res[0].Status != StatusQueued {
			t.Fatalf("results = %+v", res)
		}
	})
	t.Run("garbage rejected", func(t *testing.T) {
		m := newMock(t)
		m.reply = []byte("not a timestamp")
		s := openStore(t, m)
		if st, err := s.Anchor(ctx, testEvent()); err != nil || st != StatusQueued {
			t.Fatalf("anchor: %s, %v", st, err)
		}
	})
	t.Run("oversized rejected", func(t *testing.T) {
		m := newMock(t)
		m.reply = bytes.Repeat([]byte{0x00}, maxResponseLength+100)
		s := openStore(t, m)
		if st, err := s.Anchor(ctx, testEvent()); err != nil || st != StatusQueued {
			t.Fatalf("anchor: %s, %v", st, err)
		}
	})
	t.Run("unknown op rejected", func(t *testing.T) {
		m := newMock(t)
		m.reply = []byte{0x09} // no such op tag
		s := openStore(t, m)
		st, err := s.Anchor(ctx, testEvent())
		if err != nil || st != StatusQueued {
			t.Fatalf("anchor: %s, %v", st, err)
		}
	})
}

func TestVerifyFailures(t *testing.T) {
	m := newMock(t)
	s := openStore(t, m)
	var missing [32]byte
	if _, err := s.Verify(missing); err != ErrUnknown {
		t.Fatalf("unknown digest: %v", err)
	}
	if _, err := s.Anchor(context.Background(), testEvent()); err != nil {
		t.Fatal(err)
	}
	digest := Digest(testEvent())
	// Tamper with the stored proof.
	p := s.proofPath(hex.EncodeToString(digest[:]))
	raw, _ := os.ReadFile(p)
	raw[len(raw)-1] ^= 0xff
	if err := os.WriteFile(p, raw, 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Verify(digest); err == nil {
		t.Fatal("tampered proof verified")
	}
}

func TestExport(t *testing.T) {
	m := newMock(t)
	s := openStore(t, m)
	digest := Digest(testEvent())
	if _, _, err := s.Export(digest, t.TempDir()); err != ErrUnknown {
		t.Fatalf("export unknown: %v", err)
	}
	// Queued proofs cannot be exported.
	sQ := openStore(t) // no calendars: stays queued
	if _, err := sQ.Anchor(context.Background(), testEvent()); err != nil {
		t.Fatal(err)
	}
	if _, _, err := sQ.Export(digest, t.TempDir()); err == nil {
		t.Fatal("exported a queued proof")
	}
	if _, err := s.Anchor(context.Background(), testEvent()); err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	dataPath, otsPath, err := s.Export(digest, dir)
	if err != nil {
		t.Fatal(err)
	}
	// The data file hashes to the digest with the file-hash op, exactly
	// what `ots verify` checks first.
	data, _ := os.ReadFile(dataPath)
	if h := sha256.Sum256(data); h != digest {
		t.Fatal("exported data does not hash to the digest")
	}
	proof, _ := os.ReadFile(otsPath)
	f, err := ParseFile(proof)
	if err != nil {
		t.Fatal(err)
	}
	if f.HashOp.Tag != tagSHA256 || string(f.Digest) != string(digest[:]) {
		t.Fatal("exported proof is for another digest")
	}
	if !strings.HasSuffix(otsPath, ".ots") {
		t.Fatalf("proof path %q has no .ots suffix", otsPath)
	}
	// The data file is owner-only: it may carry a typed place name.
	if fi, _ := os.Stat(dataPath); fi.Mode().Perm() != 0o600 {
		t.Fatalf("data file mode %o", fi.Mode().Perm())
	}
}

func TestOpenRejectsBadCalendars(t *testing.T) {
	if _, err := Open(filepath.Join(t.TempDir(), "x"), []string{"ftp://x.example"}); err == nil {
		t.Error("ftp calendar accepted")
	}
	if _, err := Open(filepath.Join(t.TempDir(), "x"), []string{"http://x.example", "notaurl"}); err == nil {
		t.Error("bad calendar URL accepted")
	}
}

func TestAnchorRejectsBadEvents(t *testing.T) {
	s := openStore(t)
	if _, err := s.Anchor(context.Background(), nil); err == nil {
		t.Error("nil event accepted")
	}
	e := testEvent()
	e.Kind = message.Kind(99)
	if _, err := s.Anchor(context.Background(), e); err == nil {
		t.Error("unknown kind accepted")
	}
}
