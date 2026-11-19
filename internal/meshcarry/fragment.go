package meshcarry

import (
	"fmt"
	"time"
)

// Radio limits and the Kengele crowd-carry fragment format. A datagram
// is one sealed wire message (message.Seal output, always 228 bytes),
// carried byte-identical and never parsed on the relay path. Fragments
// are capped at the bitchat BLE fragment size so a datagram always
// travels as exactly 2 fragments.
//
// Fragment layout (9-byte header + 1..141 bytes of datagram):
//
//	0..1  magic "KC": anything else on the air is ignored, not an error
//	2     format version (1)
//	3     TTL, hops left (sender sets DefaultTTL; each relay sends TTL-1)
//	4     total fragments (1..255; a 228-byte datagram always makes 2)
//	5     this fragment's index (0..total-1)
//	6..8  message id, 24 bits, sender-fresh random per datagram
//
// For a 228-byte datagram fragment 0 carries 141 bytes and fragment 1
// carries 87 bytes. Every fragment except the last carries a full 141
// bytes. See docs/offgrid-comms.md.
const (
	// FragmentMTU is the largest on-air fragment in bytes.
	FragmentMTU = 150
	// HeaderLen is the fragment header size in bytes.
	HeaderLen = 9
	// MaxChunkData is the largest datagram slice one fragment carries.
	MaxChunkData = 141
	// MaxDatagram is the only datagram size this layer carries: one
	// sealed 228-byte wire message.
	MaxDatagram = 228
	// MaxPendingGroups caps how many partial datagrams the reassembler
	// holds at once; the oldest is dropped past this.
	MaxPendingGroups = 64
	// FragmentVersion is the only fragment version this code speaks.
	FragmentVersion byte = 1
	// DefaultTTL is the hop budget a sender puts on a fresh datagram.
	DefaultTTL uint8 = 7
	// DenseCrowdTTL caps the broadcast TTL once a node holds this many
	// links or more (bitchat dense-crowd rule).
	DenseCrowdLinks = 6
	// DenseCrowdTTL is the broadcast TTL cap in a dense crowd.
	DenseCrowdTTL uint8 = 5
	// DefaultReassembleTimeout is how long fragments of one datagram are
	// kept while the rest are still on the air.
	DefaultReassembleTimeout = 10 * time.Minute
)

const (
	magic0 byte = 'K'
	magic1 byte = 'C'
	// maxMsgID is the largest 24-bit message id.
	maxMsgID = 1<<24 - 1
)

// Split cuts datagram, which must be exactly one 228-byte sealed wire
// message, into fragments labelled with msgID (24 bits, sender-fresh
// random per datagram) and ttl. Anything else is not carried: Split
// rejects it instead of guessing.
func Split(datagram []byte, msgID uint32, ttl uint8) ([][]byte, error) {
	if len(datagram) != MaxDatagram {
		return nil, fmt.Errorf("%w: got %d bytes", ErrDatagramSize, len(datagram))
	}
	if msgID > maxMsgID {
		return nil, fmt.Errorf("%w: message id out of range", ErrFragment)
	}
	total := (len(datagram) + MaxChunkData - 1) / MaxChunkData
	out := make([][]byte, 0, total)
	for i := 0; i < total; i++ {
		start := i * MaxChunkData
		end := start + MaxChunkData
		if end > len(datagram) {
			end = len(datagram)
		}
		p := make([]byte, HeaderLen+(end-start))
		p[0] = magic0
		p[1] = magic1
		p[2] = FragmentVersion
		p[3] = ttl
		p[4] = byte(total)
		p[5] = byte(i)
		p[6] = byte(msgID >> 16)
		p[7] = byte(msgID >> 8)
		p[8] = byte(msgID)
		copy(p[HeaderLen:], datagram[start:end])
		out = append(out, p)
	}
	return out, nil
}

// Header is one parsed fragment header. It carries hop control only:
// no sender, no kind, no body.
type Header struct {
	TTL   uint8
	Total uint8
	Index uint8
	MsgID uint32
}

// ParseHeader parses one on-air fragment. Anything without the "KC"
// magic is other traffic and returns ErrNotKengele; a Kengele fragment
// that fails any other check returns an error wrapping ErrFragment.
// The returned chunk aliases f.
func ParseHeader(f []byte) (Header, []byte, error) {
	var h Header
	if len(f) < 2 || f[0] != magic0 || f[1] != magic1 {
		return h, nil, ErrNotKengele
	}
	if len(f) < HeaderLen+1 || len(f) > HeaderLen+MaxChunkData {
		return h, nil, fmt.Errorf("%w: size %d", ErrFragment, len(f))
	}
	if f[2] != FragmentVersion {
		return h, nil, fmt.Errorf("%w: version %d", ErrFragment, f[2])
	}
	total, index := f[4], f[5]
	if total == 0 || index >= total {
		return h, nil, fmt.Errorf("%w: part %d of %d", ErrFragment, index, total)
	}
	chunk := f[HeaderLen:]
	if index < total-1 && len(chunk) != MaxChunkData {
		return h, nil, fmt.Errorf("%w: short non-last part", ErrFragment)
	}
	h = Header{
		TTL:   f[3],
		Total: total,
		Index: index,
		MsgID: uint32(f[6])<<16 | uint32(f[7])<<8 | uint32(f[8]),
	}
	return h, chunk, nil
}

// Reassembler joins fragments back into datagrams. Groups expire after
// the timeout so one lost fragment cannot pin memory forever; the
// sender must send the datagram again under a new message id. Two
// senders that pick the same id collide: a conflicting part count
// drops the group, and completed datagrams are deduplicated by hash at
// the Node layer. It is not safe for concurrent use.
type Reassembler struct {
	timeout   time.Duration
	maxGroups int
	groups    map[uint32]*reasmGroup
	order     []uint32 // message ids, oldest first, for eviction
}

type reasmGroup struct {
	total uint8
	parts map[uint8][]byte
	first time.Time
}

// NewReassembler returns a Reassembler. A non-positive timeout selects
// DefaultReassembleTimeout; a non-positive maxGroups selects
// MaxPendingGroups.
func NewReassembler(timeout time.Duration, maxGroups int) *Reassembler {
	if timeout <= 0 {
		timeout = DefaultReassembleTimeout
	}
	if maxGroups <= 0 {
		maxGroups = MaxPendingGroups
	}
	return &Reassembler{timeout: timeout, maxGroups: maxGroups, groups: map[uint32]*reasmGroup{}}
}

// Pending reports how many partial datagrams are held.
func (r *Reassembler) Pending() int {
	return len(r.groups)
}

// Add feeds one on-air fragment in. Non-Kengele traffic is ignored
// silently and returns complete false with a nil error. A duplicate
// fragment is also ignored: the mesh repeats packets. When the last
// missing fragment of a datagram arrives, Add returns the reassembled
// bytes with complete true. The reassembled datagram must be exactly
// 228 bytes, or Add reports ErrDatagramSize.
func (r *Reassembler) Add(f []byte) (datagram []byte, complete bool, err error) {
	h, chunk, err := ParseHeader(f)
	if err == ErrNotKengele {
		return nil, false, nil
	}
	if err != nil {
		return nil, false, err
	}
	// A group whose smallest possible completion already exceeds 228
	// bytes can never become a datagram.
	if int(h.Total-1)*MaxChunkData+1 > MaxDatagram {
		return nil, false, fmt.Errorf("%w: %d parts cannot make 228 bytes", ErrDatagramSize, h.Total)
	}
	g := r.groups[h.MsgID]
	if g == nil {
		if len(r.groups) >= r.maxGroups {
			r.drop(r.order[0])
		}
		g = &reasmGroup{total: h.Total, parts: map[uint8][]byte{}, first: time.Now()}
		r.groups[h.MsgID] = g
		r.order = append(r.order, h.MsgID)
	}
	if g.total != h.Total {
		// Two senders picked the same id, or a corrupt total slipped
		// past the radio checksum. Drop the group; the sender retries
		// under a new id.
		r.drop(h.MsgID)
		return nil, false, fmt.Errorf("%w: conflicting part count", ErrFragment)
	}
	if _, dup := g.parts[h.Index]; dup {
		return nil, false, nil
	}
	cp := make([]byte, len(chunk))
	copy(cp, chunk)
	g.parts[h.Index] = cp
	if len(g.parts) != int(g.total) {
		return nil, false, nil
	}
	var out []byte
	for i := uint8(0); i < g.total; i++ {
		out = append(out, g.parts[i]...)
	}
	r.drop(h.MsgID)
	if len(out) != MaxDatagram {
		return nil, false, fmt.Errorf("%w: got %d bytes", ErrDatagramSize, len(out))
	}
	return out, true, nil
}

// EvictExpired drops every partial datagram older than the timeout as
// of now.
func (r *Reassembler) EvictExpired(now time.Time) {
	for id, g := range r.groups {
		if now.Sub(g.first) > r.timeout {
			r.drop(id)
		}
	}
}

func (r *Reassembler) drop(id uint32) {
	delete(r.groups, id)
	for i, v := range r.order {
		if v == id {
			r.order = append(r.order[:i], r.order[i+1:]...)
			return
		}
	}
}
