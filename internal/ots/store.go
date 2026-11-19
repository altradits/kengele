package ots

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"time"

	"github.com/altradits/kengele/internal/message"
)

// Store keeps pending proofs and the offline queue on disk:
//
//	<dir>/meta/<digest-hex>.json    the event, nonce and submission record
//	<dir>/proofs/<digest-hex>.ots   the current proof bytes
//	<dir>/queue/<digest-hex>        present while submission is outstanding
//
// Anchor writes meta and the queue marker before touching the network,
// so an event is never lost to a crash or a dead network. The proof is
// written before the queue marker is removed, so a crash can only cause
// a harmless resubmission.
type Store struct {
	dir       string
	calendars []*Calendar
}

// Open creates (or opens) a store rooted at dir with the given calendar
// servers. An empty calendar list is valid: everything queues until
// calendars are configured.
func Open(dir string, calendars []string) (*Store, error) {
	for _, u := range calendars {
		if err := checkURL(u); err != nil {
			return nil, err
		}
	}
	for _, sub := range []string{"", "meta", "proofs", "queue"} {
		if err := os.MkdirAll(filepath.Join(dir, sub), 0o700); err != nil {
			return nil, fmt.Errorf("ots: create store: %w", err)
		}
	}
	s := &Store{dir: dir}
	for _, u := range calendars {
		s.calendars = append(s.calendars, &Calendar{URL: u})
	}
	return s, nil
}

// meta is the durable record for one anchored event.
type meta struct {
	Kind       uint8    `json:"kind"`
	CreatedAt  int64    `json:"created_at"`
	Seq        uint32   `json:"seq"`
	Signer     string   `json:"signer"` // hex
	Body       string   `json:"body"`   // base64
	Nonce      string   `json:"nonce"`  // hex
	Commitment string   `json:"commitment"`
	Calendars  []string `json:"calendars"`
	UpdatedAt  int64    `json:"updated_at"`
}

func (s *Store) metaPath(key string) string  { return filepath.Join(s.dir, "meta", key+".json") }
func (s *Store) proofPath(key string) string { return filepath.Join(s.dir, "proofs", key+".ots") }
func (s *Store) queuePath(key string) string { return filepath.Join(s.dir, "queue", key) }

func writeAtomic(path string, data []byte, perm os.FileMode) error {
	tmp, err := os.CreateTemp(filepath.Dir(path), ".tmp-*")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName) // no-op after a successful rename
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Chmod(perm); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmpName, path)
}

func (s *Store) loadMeta(key string) (*meta, error) {
	raw, err := os.ReadFile(s.metaPath(key))
	if err != nil {
		if os.IsNotExist(err) {
			return nil, ErrUnknown
		}
		return nil, err
	}
	var m meta
	if err := json.Unmarshal(raw, &m); err != nil {
		return nil, fmt.Errorf("ots: corrupt record for %s: %w", key, err)
	}
	return &m, nil
}

func (s *Store) saveMeta(key string, m *meta) error {
	m.UpdatedAt = time.Now().Unix()
	raw, err := json.Marshal(m)
	if err != nil {
		return err
	}
	if err := writeAtomic(s.metaPath(key), raw, 0o600); err != nil {
		return fmt.Errorf("ots: save record: %w", err)
	}
	return nil
}

// eventOf rebuilds the canonical event bytes from a record.
func eventOf(m *meta) (*message.Event, error) {
	signer, err := hex.DecodeString(m.Signer)
	if err != nil || len(signer) != 32 {
		return nil, fmt.Errorf("ots: bad signer in record: %w", err)
	}
	body, err := base64.StdEncoding.DecodeString(m.Body)
	if err != nil {
		return nil, fmt.Errorf("ots: bad body in record: %w", err)
	}
	k := message.Kind(m.Kind)
	if !k.Valid() {
		return nil, fmt.Errorf("ots: bad kind in record")
	}
	e := &message.Event{Kind: k, CreatedAt: time.Unix(m.CreatedAt, 0).UTC(), Seq: m.Seq, Body: body}
	copy(e.Signer[:], signer)
	return e, nil
}

// Anchor records an alarm-level event and submits its salted hash to the
// calendars. It returns StatusSkipped for routine events. When the
// network is down (or no calendar answers) the event stays queued and
// Anchor still returns nil: Sync retries later. Only local failures — a
// bad event or a disk error — return an error.
func (s *Store) Anchor(ctx context.Context, e *message.Event) (Status, error) {
	if e == nil {
		return StatusSkipped, fmt.Errorf("ots: nil event")
	}
	if !e.Kind.Valid() {
		return StatusSkipped, fmt.Errorf("ots: unknown event kind")
	}
	if !AlarmLevel(e.Kind) {
		return StatusSkipped, nil
	}
	if e.CreatedAt.Unix() <= 0 {
		return StatusSkipped, fmt.Errorf("ots: event has no time")
	}
	if len(e.Body) > message.MaxBodySize {
		return StatusSkipped, fmt.Errorf("ots: event body too large")
	}
	digest := Digest(e)
	key := hex.EncodeToString(digest[:])

	// Anchoring is idempotent: a known digest keeps its proof.
	if _, err := os.Stat(s.metaPath(key)); err == nil {
		rep, err := s.Verify(digest)
		if err != nil {
			return StatusSkipped, err
		}
		return rep.Status, nil
	}

	nonce := make([]byte, NonceSize)
	if _, err := rand.Read(nonce); err != nil {
		return StatusSkipped, fmt.Errorf("ots: nonce: %w", err)
	}
	commitment := Commitment(digest, nonce)
	m := &meta{
		Kind:       uint8(e.Kind),
		CreatedAt:  e.CreatedAt.Unix(),
		Seq:        e.Seq,
		Signer:     hex.EncodeToString(e.Signer[:]),
		Body:       base64.StdEncoding.EncodeToString(e.Body),
		Nonce:      hex.EncodeToString(nonce),
		Commitment: hex.EncodeToString(commitment[:]),
	}
	if err := s.saveMeta(key, m); err != nil {
		return StatusSkipped, err
	}
	// The queue marker goes down before any network use.
	if err := writeAtomic(s.queuePath(key), nil, 0o600); err != nil {
		return StatusSkipped, fmt.Errorf("ots: queue: %w", err)
	}

	root := New(digest[:])
	node := root.child(Op{Tag: tagAppend, Arg: nonce}).child(Op{Tag: tagSHA256})
	submitted := s.submitToMissing(ctx, m, commitment[:], node)
	if len(submitted) == 0 {
		return StatusQueued, nil
	}
	m.Calendars = submitted
	if err := s.saveMeta(key, m); err != nil {
		return StatusSkipped, err
	}
	f := &File{HashOp: Op{Tag: tagSHA256}, Digest: digest[:], Stamp: root}
	if err := writeAtomic(s.proofPath(key), f.Marshal(), 0o644); err != nil {
		return StatusSkipped, fmt.Errorf("ots: save proof: %w", err)
	}
	// Proof first, then unqueue: a crash between the two only resubmits.
	if err := os.Remove(s.queuePath(key)); err != nil && !os.IsNotExist(err) {
		return StatusSkipped, fmt.Errorf("ots: unqueue: %w", err)
	}
	return proofStatus(root), nil
}

// submitToMissing submits the commitment to every configured calendar
// not already recorded in m, merging each answer into node. It returns
// the updated calendar list.
func (s *Store) submitToMissing(ctx context.Context, m *meta, commitment []byte, node *Timestamp) []string {
	have := map[string]bool{}
	for _, u := range m.Calendars {
		have[u] = true
	}
	out := append([]string(nil), m.Calendars...)
	for _, c := range s.calendars {
		if have[c.URL] {
			continue
		}
		ts, err := c.Submit(ctx, commitment)
		if err != nil {
			continue
		}
		if err := node.Merge(ts); err != nil {
			continue
		}
		out = append(out, c.URL)
	}
	return out
}

// Result is what Sync did for one digest.
type Result struct {
	Digest    [32]byte
	Kind      message.Kind
	Status    Status
	Submitted bool // newly submitted in this Sync
	Upgraded  bool // proof bytes changed in this Sync
	Err       error
}

// Sync submits everything queued and refreshes every pending proof. Call
// it when the network returns and then periodically: calendars usually
// confirm within an hour or two. Upgrades are fetched only from the
// store's own calendars, never from calendar URLs carried inside proofs,
// so a hostile calendar cannot steer requests elsewhere.
func (s *Store) Sync(ctx context.Context) ([]Result, error) {
	queued, err := s.listDir("queue")
	if err != nil {
		return nil, err
	}
	proofs, err := s.listDir("proofs")
	if err != nil {
		return nil, err
	}
	var out []Result
	submittedNow := map[string]bool{}
	for _, key := range queued {
		r := s.submitQueued(ctx, key)
		out = append(out, r)
		if r.Submitted {
			submittedNow[key] = true
		}
	}
	for _, key := range proofs {
		if submittedNow[key] {
			continue // submitted this round; upgrade next time
		}
		out = append(out, s.upgradeProof(ctx, key))
	}
	sort.Slice(out, func(i, j int) bool {
		return bytes.Compare(out[i].Digest[:], out[j].Digest[:]) < 0
	})
	return out, nil
}

func (s *Store) listDir(sub string) ([]string, error) {
	ents, err := os.ReadDir(filepath.Join(s.dir, sub))
	if err != nil {
		return nil, fmt.Errorf("ots: list %s: %w", sub, err)
	}
	var out []string
	for _, e := range ents {
		if e.IsDir() {
			continue
		}
		name := e.Name()
		if sub == "proofs" {
			if len(name) <= len(".ots") {
				continue
			}
			name = name[:len(name)-len(".ots")]
		}
		out = append(out, name)
	}
	sort.Strings(out)
	return out, nil
}

func digestFromKey(key string) ([32]byte, error) {
	var d [32]byte
	raw, err := hex.DecodeString(key)
	if err != nil || len(raw) != 32 {
		return d, fmt.Errorf("ots: bad digest %q", key)
	}
	copy(d[:], raw)
	return d, nil
}

func (s *Store) submitQueued(ctx context.Context, key string) Result {
	var r Result
	digest, err := digestFromKey(key)
	if err != nil {
		return Result{Err: err}
	}
	r.Digest = digest
	m, err := s.loadMeta(key)
	if err != nil {
		r.Err = err
		r.Status = StatusQueued
		return r
	}
	r.Kind = message.Kind(m.Kind)
	root, node, err := s.proofNode(key, digest, m)
	if err != nil {
		r.Err = err
		r.Status = StatusQueued
		return r
	}
	before := len(m.Calendars)
	commitment, err := hex.DecodeString(m.Commitment)
	if err != nil {
		r.Err = fmt.Errorf("ots: bad commitment in record: %w", err)
		r.Status = StatusQueued
		return r
	}
	m.Calendars = s.submitToMissing(ctx, m, commitment, node)
	if len(m.Calendars) == before {
		r.Err = fmt.Errorf("ots: no calendar answered for %s", key)
		r.Status = StatusQueued
		return r
	}
	if err := s.saveMeta(key, m); err != nil {
		r.Err = err
		r.Status = StatusQueued
		return r
	}
	f := &File{HashOp: Op{Tag: tagSHA256}, Digest: digest[:], Stamp: root}
	if err := writeAtomic(s.proofPath(key), f.Marshal(), 0o644); err != nil {
		r.Err = fmt.Errorf("ots: save proof: %w", err)
		r.Status = StatusQueued
		return r
	}
	if err := os.Remove(s.queuePath(key)); err != nil && !os.IsNotExist(err) {
		r.Err = fmt.Errorf("ots: unqueue: %w", err)
		r.Status = StatusQueued
		return r
	}
	r.Submitted = true
	r.Upgraded = true
	r.Status = proofStatus(root)
	return r
}

// proofNode loads the stored proof for key, or builds a fresh tree when
// none exists yet, and returns the root plus the commitment node answers
// merge into.
func (s *Store) proofNode(key string, digest [32]byte, m *meta) (*Timestamp, *Timestamp, error) {
	nonce, err := hex.DecodeString(m.Nonce)
	if err != nil {
		return nil, nil, fmt.Errorf("ots: bad nonce in record: %w", err)
	}
	commitment, err := hex.DecodeString(m.Commitment)
	if err != nil {
		return nil, nil, fmt.Errorf("ots: bad commitment in record: %w", err)
	}
	raw, err := os.ReadFile(s.proofPath(key))
	if err != nil && !os.IsNotExist(err) {
		return nil, nil, fmt.Errorf("ots: read proof: %w", err)
	}
	if err == nil {
		f, err := ParseFile(raw)
		if err != nil {
			return nil, nil, fmt.Errorf("ots: stored proof for %s is corrupt: %w", key, err)
		}
		if !bytes.Equal(f.Digest, digest[:]) {
			return nil, nil, fmt.Errorf("ots: stored proof for %s: %w", key, ErrMismatch)
		}
		node := f.Stamp.Find(commitment)
		if node == nil {
			return nil, nil, fmt.Errorf("ots: stored proof for %s is missing its commitment: %w", key, ErrMismatch)
		}
		return f.Stamp, node, nil
	}
	root := New(digest[:])
	return root, root.child(Op{Tag: tagAppend, Arg: nonce}).child(Op{Tag: tagSHA256}), nil
}

func (s *Store) upgradeProof(ctx context.Context, key string) Result {
	var r Result
	digest, err := digestFromKey(key)
	if err != nil {
		return Result{Err: err}
	}
	r.Digest = digest
	m, err := s.loadMeta(key)
	if err != nil {
		r.Err = err
		r.Status = StatusPending
		return r
	}
	r.Kind = message.Kind(m.Kind)
	raw, err := os.ReadFile(s.proofPath(key))
	if err != nil {
		r.Err = fmt.Errorf("ots: read proof: %w", err)
		r.Status = StatusPending
		return r
	}
	f, err := ParseFile(raw)
	if err != nil {
		r.Err = fmt.Errorf("ots: stored proof for %s is corrupt: %w", key, err)
		r.Status = StatusPending
		return r
	}
	commitment, err := hex.DecodeString(m.Commitment)
	if err != nil {
		r.Err = fmt.Errorf("ots: bad commitment in record: %w", err)
		r.Status = proofStatus(f.Stamp)
		return r
	}
	node := f.Stamp.Find(commitment)
	if node == nil {
		r.Err = fmt.Errorf("ots: stored proof for %s is missing its commitment: %w", key, ErrMismatch)
		r.Status = proofStatus(f.Stamp)
		return r
	}
	for _, c := range s.calendars {
		ts, err := c.Get(ctx, commitment)
		if err != nil {
			if err == ErrNotFound {
				continue // not ready there yet
			}
			r.Err = err // keep the last problem, keep trying others
			continue
		}
		if err := node.Merge(ts); err != nil {
			r.Err = fmt.Errorf("ots: bad upgrade from %s: %w", c.URL, err)
			continue
		}
	}
	merged := (&File{HashOp: f.HashOp, Digest: f.Digest, Stamp: f.Stamp}).Marshal()
	if !bytes.Equal(merged, raw) {
		if err := writeAtomic(s.proofPath(key), merged, 0o644); err != nil {
			r.Err = fmt.Errorf("ots: save proof: %w", err)
		} else {
			r.Upgraded = true
		}
	}
	r.Status = proofStatus(f.Stamp)
	return r
}

// proofStatus walks a tree: anchored when any Bitcoin attestation is
// present anywhere, else pending.
func proofStatus(t *Timestamp) Status {
	anchored := false
	t.Walk(func(n *Timestamp) {
		for _, a := range n.Attestations {
			anchored = anchored || a.IsBitcoin()
		}
	})
	if anchored {
		return StatusAnchored
	}
	return StatusPending
}

// Verify re-parses the stored proof for digest, checks it against the
// recorded event, and reports every attestation. A pending proof is not
// an error, but callers must treat it as unconfirmed (threat model,
// residual risk 10). An anchored report means a calendar claims a Bitcoin
// attestation; checking that claim against the chain needs a Bitcoin node
// and is the job of the standard `ots verify` on the Export output, not
// of this package.
func (s *Store) Verify(digest [32]byte) (Report, error) {
	key := hex.EncodeToString(digest[:])
	m, err := s.loadMeta(key)
	if err != nil {
		return Report{}, err
	}
	e, err := eventOf(m)
	if err != nil {
		return Report{}, err
	}
	if got := Digest(e); got != digest {
		return Report{}, ErrMismatch
	}
	rep := Report{Digest: digest, Kind: e.Kind, CreatedAt: e.CreatedAt, Calendars: append([]string(nil), m.Calendars...)}
	raw, err := os.ReadFile(s.proofPath(key))
	if err != nil {
		if os.IsNotExist(err) {
			rep.Status = StatusQueued
			rep.ProofMissing = true
			return rep, nil
		}
		return Report{}, fmt.Errorf("ots: read proof: %w", err)
	}
	f, err := ParseFile(raw)
	if err != nil {
		return Report{}, fmt.Errorf("ots: stored proof for %s is corrupt: %w", key, err)
	}
	if f.HashOp.Tag != tagSHA256 || !bytes.Equal(f.Digest, digest[:]) {
		return Report{}, ErrMismatch
	}
	heights := map[uint32]bool{}
	uris := map[string]bool{}
	f.Stamp.Walk(func(n *Timestamp) {
		for _, a := range n.Attestations {
			switch {
			case a.IsBitcoin():
				heights[a.Height] = true
			case a.IsPending():
				uris[a.URI] = true
			}
		}
	})
	for h := range heights {
		rep.Bitcoin = append(rep.Bitcoin, h)
	}
	sort.Slice(rep.Bitcoin, func(i, j int) bool { return rep.Bitcoin[i] < rep.Bitcoin[j] })
	for u := range uris {
		rep.PendingFrom = append(rep.PendingFrom, u)
	}
	sort.Strings(rep.PendingFrom)
	rep.Status = proofStatus(f.Stamp)
	return rep, nil
}

// Export writes the canonical event bytes and the .ots proof into dir so
// the standard tool can check them (`ots verify proof.ots`) and the
// habeas packet (#12) can carry them. It returns both paths.
func (s *Store) Export(digest [32]byte, dir string) (dataPath, otsPath string, err error) {
	key := hex.EncodeToString(digest[:])
	m, err := s.loadMeta(key)
	if err != nil {
		return "", "", err
	}
	raw, err := os.ReadFile(s.proofPath(key))
	if err != nil {
		if os.IsNotExist(err) {
			return "", "", fmt.Errorf("ots: nothing to export yet for %s: still queued", key)
		}
		return "", "", fmt.Errorf("ots: read proof: %w", err)
	}
	e, err := eventOf(m)
	if err != nil {
		return "", "", err
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", "", fmt.Errorf("ots: create export dir: %w", err)
	}
	// The data file may carry a place name the activist typed; keep it
	// owner-only. The proof holds hashes alone and is safe to share.
	base := fmt.Sprintf("kengele-%s-%d.bin", e.Kind, e.CreatedAt.Unix())
	dataPath, otsPath = filepath.Join(dir, base), filepath.Join(dir, base+".ots")
	if err := writeAtomic(dataPath, Canonical(e), 0o600); err != nil {
		return "", "", fmt.Errorf("ots: export event: %w", err)
	}
	if err := writeAtomic(otsPath, raw, 0o644); err != nil {
		return "", "", fmt.Errorf("ots: export proof: %w", err)
	}
	return dataPath, otsPath, nil
}
