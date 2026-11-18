//go:build otslive

// Live integration test against the real public calendars. It is never
// run by default (CI stays offline):
//
//	go test -tags otslive ./internal/ots/
//
// It stamps one made-up event, checks the pending proof parses, and tries
// one upgrade round. A fresh stamp is still pending — Bitcoin
// confirmations take an hour or more — so the test accepts pending and
// only fails on errors. It skips itself when the network is unreachable.
package ots

import (
	"context"
	"crypto/sha256"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestLiveCalendarRoundTrip(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	s, err := Open(filepath.Join(t.TempDir(), "ots-live"), DefaultCalendars)
	if err != nil {
		t.Fatal(err)
	}
	st, err := s.Anchor(ctx, testEvent())
	if err != nil {
		t.Fatal(err)
	}
	if st == StatusQueued {
		t.Skip("no usable network to the calendars; queued offline")
	}
	if st != StatusPending {
		t.Fatalf("fresh stamp status = %s, want pending", st)
	}
	digest := Digest(testEvent())
	rep, err := s.Verify(digest)
	if err != nil {
		t.Fatal(err)
	}
	if rep.Status != StatusPending || len(rep.PendingFrom) == 0 {
		t.Fatalf("report = %+v", rep)
	}
	// One upgrade round: still pending is the expected outcome.
	res, err := s.Sync(ctx)
	if err != nil {
		t.Fatal(err)
	}
	for _, r := range res {
		if r.Err != nil {
			t.Fatalf("sync: %+v", r)
		}
	}
	// The export pair is what `ots verify` would check: the data file
	// must hash to the digest recorded in the proof.
	dir := t.TempDir()
	dataPath, otsPath, err := s.Export(digest, dir)
	if err != nil {
		t.Fatal(err)
	}
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
		t.Fatal("proof digest mismatch")
	}
	t.Logf("pending from %v; export: %s %s", rep.PendingFrom, dataPath, otsPath)
}
