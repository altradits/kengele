// Wire codec for OpenTimestamps proofs.
//
// The byte layout here must match what the standard `ots` tool reads and
// writes, or Kengele's proofs are worthless in court. Every rule below
// follows the reference implementation (python-opentimestamps,
// opentimestamps/core/{serialize,op,notary,timestamp}.py):
//
//   - integers are unsigned LEB128 varuints, at most 9 bytes
//   - variable bytes are a varuint length followed by the bytes
//   - a timestamp node holds attestations, then operations, each but the
//     last prefixed with 0xff; a bare 0x00 introduces an attestation
//   - operations sort by (tag, argument), attestations by (tag, payload)
//   - a detached file is the magic header, version 1, the file-hash op,
//     the digest, and the timestamp, with nothing after
//
// Unknown attestation tags are kept verbatim so they survive a
// merge-and-upgrade round trip; unknown operation tags are rejected, as in
// the reference, because the result message cannot be computed.
package ots

import (
	"bytes"
	"crypto/sha1"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"sort"

	"golang.org/x/crypto/ripemd160"
	"golang.org/x/crypto/sha3"
)

// Magic header of every detached .ots file.
var magicHeader = []byte("\x00OpenTimestamps\x00\x00Proof\x00\xbf\x89\xe2\xe8\x84\xe8\x92\x94")

// Detached file version. Only 1 exists.
const fileVersion = 1

// Operation tags.
const (
	tagSHA1      = 0x02
	tagRIPEMD160 = 0x03
	tagSHA256    = 0x08
	tagKECCAK256 = 0x67
	tagAppend    = 0xf0
	tagPrepend   = 0xf1
	tagReverse   = 0xf2
	tagHexlify   = 0xf3
)

// Attestation tags.
var (
	tagPending = [8]byte{0x83, 0xdf, 0xe3, 0x0d, 0x2e, 0xf9, 0x0c, 0x8e}
	tagBitcoin = [8]byte{0x05, 0x88, 0x96, 0x0d, 0x73, 0xd7, 0x19, 0x01}
)

// Limits from the reference implementation.
const (
	maxMsgLength    = 4096
	maxResultLength = 4096
	maxVaruintBytes = 9
	maxPayloadSize  = 8192
	maxURILength    = 1000
	maxHeight       = 1<<32 - 1
	maxRecursion    = 256
)

var (
	// ErrTruncated is returned for inputs that end mid-value.
	ErrTruncated = errors.New("ots: truncated input")
	// ErrFormat is returned for inputs that break the format rules.
	ErrFormat = errors.New("ots: invalid timestamp format")
	// ErrTrailing is returned when bytes remain after a complete value.
	ErrTrailing = errors.New("ots: trailing garbage after timestamp")
)

// appendVaruint encodes v as unsigned LEB128.
func appendVaruint(b []byte, v uint64) []byte {
	if v == 0 {
		return append(b, 0x00)
	}
	for v != 0 {
		c := byte(v & 0x7f)
		v >>= 7
		if v != 0 {
			c |= 0x80
		}
		b = append(b, c)
	}
	return b
}

// readVaruint decodes one unsigned LEB128 value, returning the value and
// the bytes consumed.
func readVaruint(b []byte) (uint64, int, error) {
	var v uint64
	var shift uint
	for i := 0; i < maxVaruintBytes; i++ {
		if i >= len(b) {
			return 0, 0, ErrTruncated
		}
		c := b[i]
		// The 9th byte may only carry one bit (63 bits total).
		if i == maxVaruintBytes-1 && c&0xfe != 0 {
			return 0, 0, ErrFormat
		}
		v |= uint64(c&0x7f) << shift
		if c&0x80 == 0 {
			return v, i + 1, nil
		}
		shift += 7
	}
	return 0, 0, ErrFormat
}

func appendVarbytes(b, v []byte) []byte {
	b = appendVaruint(b, uint64(len(v)))
	return append(b, v...)
}

func readVarbytes(b []byte, maxLen, minLen int) ([]byte, int, error) {
	l, n, err := readVaruint(b)
	if err != nil {
		return nil, 0, err
	}
	if l > uint64(maxLen) || l < uint64(minLen) {
		return nil, 0, ErrFormat
	}
	if uint64(len(b)-n) < l {
		return nil, 0, ErrTruncated
	}
	return b[n : n+int(l)], n + int(l), nil
}

// Op is one edge in the timestamp tree: a tag plus, for append and
// prepend, a single argument.
type Op struct {
	Tag byte
	Arg []byte // only for append/prepend
}

// IsBinary reports whether the op carries an argument.
func (o Op) IsBinary() bool { return o.Tag == tagAppend || o.Tag == tagPrepend }

func (o Op) less(p Op) bool {
	if o.Tag != p.Tag {
		return o.Tag < p.Tag
	}
	return bytes.Compare(o.Arg, p.Arg) < 0
}

func (o Op) equal(p Op) bool { return o.Tag == p.Tag && bytes.Equal(o.Arg, p.Arg) }

// Apply runs the op on msg.
func (o Op) Apply(msg []byte) ([]byte, error) {
	limit := maxMsgLength
	if o.Tag == tagHexlify {
		limit = maxResultLength / 2
	}
	if len(msg) > limit {
		return nil, ErrFormat
	}
	var out []byte
	switch o.Tag {
	case tagAppend:
		out = append(append([]byte(nil), msg...), o.Arg...)
	case tagPrepend:
		out = append(append([]byte(nil), o.Arg...), msg...)
	case tagReverse:
		out = append([]byte(nil), msg...)
		for i, j := 0, len(out)-1; i < j; i, j = i+1, j-1 {
			out[i], out[j] = out[j], out[i]
		}
	case tagHexlify:
		out = []byte(hex.EncodeToString(msg))
	case tagSHA1:
		h := sha1.Sum(msg)
		out = h[:]
	case tagRIPEMD160:
		h := ripemd160.New()
		h.Write(msg)
		out = h.Sum(nil)
	case tagSHA256:
		h := sha256.Sum256(msg)
		out = h[:]
	case tagKECCAK256:
		h := sha3.NewLegacyKeccak256()
		h.Write(msg)
		out = h.Sum(nil)
	default:
		return nil, ErrFormat
	}
	if len(out) == 0 || len(out) > maxResultLength {
		return nil, ErrFormat
	}
	return out, nil
}

func (o Op) marshal(b []byte) []byte {
	b = append(b, o.Tag)
	if o.IsBinary() {
		b = appendVarbytes(b, o.Arg)
	}
	return b
}

// digestLength is the output size for the hash ops that may head a
// detached file.
func digestLength(tag byte) (int, bool) {
	switch tag {
	case tagSHA1, tagRIPEMD160:
		return 20, true
	case tagSHA256:
		return 32, true
	}
	return 0, false
}

// Attestation is one leaf of the timestamp tree: either a pending stamp
// from a calendar (URI), a Bitcoin block commitment (Height), or an
// unrecognized tag kept verbatim (Unknown).
type Attestation struct {
	Tag     [8]byte
	URI     string // pending attestations only
	Height  uint32 // bitcoin attestations only
	Unknown []byte // payload of any other tag, verbatim
}

// IsPending reports whether a is a calendar pending attestation.
func (a Attestation) IsPending() bool { return a.Tag == tagPending }

// IsBitcoin reports whether a is a Bitcoin block-header attestation.
func (a Attestation) IsBitcoin() bool { return a.Tag == tagBitcoin }

func (a Attestation) less(b Attestation) bool {
	if a.Tag != b.Tag {
		return bytes.Compare(a.Tag[:], b.Tag[:]) < 0
	}
	switch {
	case a.IsPending():
		return a.URI < b.URI
	case a.IsBitcoin():
		return a.Height < b.Height
	default:
		return bytes.Compare(a.Unknown, b.Unknown) < 0
	}
}

func (a Attestation) equal(b Attestation) bool {
	return a.Tag == b.Tag && a.URI == b.URI && a.Height == b.Height && bytes.Equal(a.Unknown, b.Unknown)
}

func (a Attestation) marshal(b []byte) []byte {
	var payload []byte
	switch {
	case a.IsPending():
		payload = appendVarbytes(nil, []byte(a.URI))
	case a.IsBitcoin():
		payload = appendVaruint(nil, uint64(a.Height))
	default:
		payload = a.Unknown
	}
	b = append(b, a.Tag[:]...)
	return appendVarbytes(b, payload)
}

var allowedURIBytes = func() [256]bool {
	var t [256]bool
	for _, c := range []byte("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._/:") {
		t[c] = true
	}
	return t
}()

// parseAttestation reads one attestation (tag plus payload) after the 0x00 marker.
func parseAttestation(b []byte) (Attestation, int, error) {
	var a Attestation
	if len(b) < 8 {
		return a, 0, ErrTruncated
	}
	copy(a.Tag[:], b[:8])
	payload, n, err := readVarbytes(b[8:], maxPayloadSize, 0)
	if err != nil {
		return a, 0, err
	}
	total := 8 + n
	switch a.Tag {
	case tagPending:
		uri, m, err := readVarbytes(payload, maxURILength, 0)
		if err != nil {
			return a, 0, ErrFormat
		}
		if m != len(payload) {
			return a, 0, ErrFormat
		}
		for _, c := range uri {
			if !allowedURIBytes[c] {
				return a, 0, ErrFormat
			}
		}
		a.URI = string(uri)
	case tagBitcoin:
		h, m, err := readVaruint(payload)
		if err != nil {
			return a, 0, ErrFormat
		}
		if m != len(payload) || h > maxHeight {
			return a, 0, ErrFormat
		}
		a.Height = uint32(h)
	default:
		a.Unknown = append([]byte(nil), payload...)
	}
	return a, total, nil
}

// opKey is the comparable form of an Op for use as a map key.
type opKey struct {
	tag byte
	arg string
}

func keyOf(o Op) opKey { return opKey{tag: o.Tag, arg: string(o.Arg)} }

func (k opKey) op() Op { return Op{Tag: k.tag, Arg: []byte(k.arg)} }

// Timestamp is one node of the proof tree: the message it commits to,
// the attestations on that message, and the operations leading onward.
type Timestamp struct {
	Msg          []byte
	Attestations []Attestation
	Ops          map[opKey]*Timestamp
}

// New returns an empty timestamp for msg.
func New(msg []byte) *Timestamp {
	return &Timestamp{Msg: append([]byte(nil), msg...)}
}

// child returns the node reached by op, creating it if needed.
func (t *Timestamp) child(op Op) *Timestamp {
	if t.Ops == nil {
		t.Ops = map[opKey]*Timestamp{}
	}
	k := keyOf(op)
	if c, ok := t.Ops[k]; ok {
		return c
	}
	res, err := op.Apply(t.Msg)
	if err != nil {
		// Callers only build with valid ops; a failure here is a bug.
		panic("ots: cannot apply op: " + err.Error())
	}
	c := &Timestamp{Msg: res}
	t.Ops[k] = c
	return c
}

func sortedOps(m map[opKey]*Timestamp) []Op {
	ops := make([]Op, 0, len(m))
	for k := range m {
		ops = append(ops, k.op())
	}
	sort.Slice(ops, func(i, j int) bool { return ops[i].less(ops[j]) })
	return ops
}

func sortedAttestations(a []Attestation) []Attestation {
	out := append([]Attestation(nil), a...)
	sort.Slice(out, func(i, j int) bool { return out[i].less(out[j]) })
	return out
}

// Marshal serializes the node. It returns nil for an empty node, which
// the format cannot express.
func (t *Timestamp) Marshal() []byte {
	if len(t.Attestations) == 0 && len(t.Ops) == 0 {
		return nil
	}
	var b []byte
	atts := sortedAttestations(t.Attestations)
	// Attestations come first. Without ops the node ends in a bare 0x00
	// attestation; with ops every attestation is flagged 0xff 0x00.
	for i, a := range atts {
		if len(t.Ops) == 0 && i == len(atts)-1 {
			b = append(b, 0x00)
		} else {
			b = append(b, 0xff, 0x00)
		}
		b = a.marshal(b)
	}
	if len(t.Ops) == 0 {
		return b
	}
	ops := sortedOps(t.Ops)
	for _, o := range ops[:len(ops)-1] {
		b = append(b, 0xff)
		b = o.marshal(b)
		b = append(b, t.Ops[keyOf(o)].Marshal()...)
	}
	last := ops[len(ops)-1]
	b = last.marshal(b)
	b = append(b, t.Ops[keyOf(last)].Marshal()...)
	return b
}

// ParseTimestamp parses one node for the known message msg. It rejects
// trailing garbage.
func ParseTimestamp(msg, b []byte) (*Timestamp, error) {
	t, n, err := parseNode(append([]byte(nil), msg...), b, maxRecursion)
	if err != nil {
		return nil, err
	}
	if n != len(b) {
		return nil, ErrTrailing
	}
	return t, nil
}

func parseNode(msg, b []byte, depth int) (*Timestamp, int, error) {
	if depth == 0 {
		return nil, 0, ErrFormat
	}
	t := &Timestamp{Msg: msg}
	i := 0
	// Each 0xff introduces another attestation or op; the node ends with
	// one unflagged attestation or op.
	for {
		if i >= len(b) {
			return nil, 0, ErrTruncated
		}
		if b[i] != 0xff {
			break
		}
		i++
		if i >= len(b) {
			return nil, 0, ErrTruncated
		}
		m, err := t.parseTagged(msg, b[i:], depth)
		if err != nil {
			return nil, 0, err
		}
		i += m
	}
	if i >= len(b) {
		return nil, 0, ErrTruncated
	}
	m, err := t.parseTagged(msg, b[i:], depth)
	if err != nil {
		return nil, 0, err
	}
	return t, i + m, nil
}

// parseTagged reads the value after a 0xff flag, or the final unflagged
// value: 0x00 plus an attestation, or an op plus its child node.
func (t *Timestamp) parseTagged(msg, b []byte, depth int) (int, error) {
	if len(b) == 0 {
		return 0, ErrTruncated
	}
	if b[0] == 0x00 {
		a, n, err := parseAttestation(b[1:])
		if err != nil {
			return 0, err
		}
		t.addAttestation(a)
		return 1 + n, nil
	}
	op, n, err := parseOp(b)
	if err != nil {
		return 0, err
	}
	if t.Ops == nil {
		t.Ops = map[opKey]*Timestamp{}
	}
	if _, dup := t.Ops[keyOf(op)]; dup {
		return 0, ErrFormat // repeated op
	}
	res, err := op.Apply(msg)
	if err != nil {
		return 0, ErrFormat
	}
	child, m, err := parseNode(res, b[n:], depth-1)
	if err != nil {
		return 0, err
	}
	t.Ops[keyOf(op)] = child
	return n + m, nil
}

func parseOp(b []byte) (Op, int, error) {
	var op Op
	if len(b) == 0 {
		return op, 0, ErrTruncated
	}
	op.Tag = b[0]
	switch op.Tag {
	case tagSHA1, tagRIPEMD160, tagSHA256, tagKECCAK256, tagReverse, tagHexlify:
		return op, 1, nil
	case tagAppend, tagPrepend:
		arg, n, err := readVarbytes(b[1:], maxResultLength, 1)
		if err != nil {
			return op, 0, err
		}
		op.Arg = append([]byte(nil), arg...)
		return op, 1 + n, nil
	default:
		return op, 0, ErrFormat
	}
}

func (t *Timestamp) addAttestation(a Attestation) {
	for _, have := range t.Attestations {
		if have.equal(a) {
			return
		}
	}
	t.Attestations = append(t.Attestations, a)
}

// Merge folds every attestation and operation from other into t. Both
// must commit to the same message.
func (t *Timestamp) Merge(other *Timestamp) error {
	if !bytes.Equal(t.Msg, other.Msg) {
		return errors.New("ots: cannot merge timestamps for different messages")
	}
	for _, a := range other.Attestations {
		t.addAttestation(a)
	}
	if t.Ops == nil && len(other.Ops) > 0 {
		t.Ops = map[opKey]*Timestamp{}
	}
	for k, oc := range other.Ops {
		if hc, ok := t.Ops[k]; ok {
			if err := hc.Merge(oc); err != nil {
				return err
			}
			continue
		}
		t.Ops[k] = oc.clone()
	}
	return nil
}

func (t *Timestamp) clone() *Timestamp {
	c := &Timestamp{Msg: append([]byte(nil), t.Msg...), Attestations: append([]Attestation(nil), t.Attestations...)}
	if len(t.Ops) > 0 {
		c.Ops = map[opKey]*Timestamp{}
		for k, oc := range t.Ops {
			c.Ops[k] = oc.clone()
		}
	}
	return c
}

// Equal reports whether t and other hold the same tree.
func (t *Timestamp) Equal(other *Timestamp) bool {
	if !bytes.Equal(t.Msg, other.Msg) || len(t.Attestations) != len(other.Attestations) || len(t.Ops) != len(other.Ops) {
		return false
	}
	a, b := sortedAttestations(t.Attestations), sortedAttestations(other.Attestations)
	for i := range a {
		if !a[i].equal(b[i]) {
			return false
		}
	}
	for k, c := range t.Ops {
		d, ok := other.Ops[k]
		if !ok || !c.Equal(d) {
			return false
		}
	}
	return true
}

// Walk calls f for every node in the tree, depth first.
func (t *Timestamp) Walk(f func(*Timestamp)) {
	f(t)
	for _, o := range sortedOps(t.Ops) {
		t.Ops[keyOf(o)].Walk(f)
	}
}

// Find returns the node committing to msg, or nil.
func (t *Timestamp) Find(msg []byte) *Timestamp {
	var found *Timestamp
	t.Walk(func(n *Timestamp) {
		if found == nil && bytes.Equal(n.Msg, msg) {
			found = n
		}
	})
	return found
}

// File is a detached .ots proof: the hash op and digest of the file plus
// the timestamp tree for that digest.
type File struct {
	HashOp Op
	Digest []byte
	Stamp  *Timestamp
}

// Marshal serializes the file. It returns nil when the tree is empty.
func (f *File) Marshal() []byte {
	body := f.Stamp.Marshal()
	if body == nil || len(f.Digest) == 0 {
		return nil
	}
	b := append([]byte(nil), magicHeader...)
	b = append(b, fileVersion)
	b = append(b, f.HashOp.Tag)
	b = append(b, f.Digest...)
	return append(b, body...)
}

// ParseFile parses a detached .ots file.
func ParseFile(b []byte) (*File, error) {
	if len(b) < len(magicHeader)+1+1 {
		return nil, ErrTruncated
	}
	if !bytes.Equal(b[:len(magicHeader)], magicHeader) {
		return nil, ErrFormat
	}
	rest := b[len(magicHeader):]
	if rest[0] != fileVersion {
		return nil, ErrFormat
	}
	n, ok := digestLength(rest[1])
	if !ok {
		return nil, ErrFormat
	}
	if len(rest) < 2+n {
		return nil, ErrTruncated
	}
	digest := append([]byte(nil), rest[2:2+n]...)
	stamp, err := ParseTimestamp(digest, rest[2+n:])
	if err != nil {
		return nil, err
	}
	return &File{HashOp: Op{Tag: rest[1]}, Digest: digest, Stamp: stamp}, nil
}
