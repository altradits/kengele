package meshexit

import (
	"errors"
	"fmt"
	"time"
)

// Radio limits and the Kengele fragment format. A datagram (one gift-wrapped
// Nostr event as JSON, about 2 KB) is split into fragments that each fit one
// Meshtastic packet, whose payload tops out at 237 bytes. Chunks are capped
// at 200 bytes of datagram each so firmware overhead never pushes a packet
// over the limit.
//
// Fragment layout (8-byte header + 1..200 bytes of datagram):
//
//	0..1  magic "KG": anything else on the mesh is ignored, not an error
//	2     format version (1)
//	3     total fragments (1..255)
//	4     this fragment's index (0..total-1)
//	5..7  message id, 24 bits, chosen by the sender (random per datagram)
//
// Every fragment except the last carries a full 200 bytes. See
// docs/meshtastic-exit.md.
const (
	// MeshMTU is the largest payload one mesh packet may carry.
	MeshMTU = 237
	// HeaderLen is the fragment header size in bytes.
	HeaderLen = 8
	// MaxChunkData is the largest datagram slice one fragment carries.
	MaxChunkData = 200
	// MaxDatagram is the largest reassembled datagram in bytes.
	MaxDatagram = 4096
	// MaxPendingGroups caps how many partial datagrams the reassembler
	// holds at once; the oldest is dropped past this.
	MaxPendingGroups = 64
	// FragmentVersion is the only fragment version this code speaks.
	FragmentVersion byte = 1
	// DefaultReassembleTimeout is how long fragments of one datagram are
	// kept while the rest are still on the air. LoRa is slow: ten
	// fragments can take minutes.
	DefaultReassembleTimeout = 10 * time.Minute
)

const (
	magic0 byte = 'K'
	magic1 byte = 'G'
	// maxMsgID is the largest 24-bit message id.
	maxMsgID = 1<<24 - 1
)

var (
	// ErrNotKengele means the packet is other mesh traffic. Callers must
	// ignore it silently, not report it.
	ErrNotKengele = errors.New("meshexit: not a Kengele fragment")
	// ErrFragment means a Kengele fragment is malformed.
	ErrFragment = errors.New("meshexit: bad fragment")
	// ErrDatagramSize means a datagram would exceed MaxDatagram.
	ErrDatagramSize = errors.New("meshexit: datagram too large")
)

// Split cuts datagram into mesh-sized fragments labelled with msgID, which
// must fit 24 bits. The sender should pick a fresh random msgID per
// datagram.
func Split(datagram []byte, msgID uint32) ([][]byte, error) {
	if len(datagram) == 0 {
		return nil, fmt.Errorf("%w: empty datagram", ErrFragment)
	}
	if len(datagram) > MaxDatagram {
		return nil, ErrDatagramSize
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
		p[3] = byte(total)
		p[4] = byte(i)
		p[5] = byte(msgID >> 16)
		p[6] = byte(msgID >> 8)
		p[7] = byte(msgID)
		copy(p[HeaderLen:], datagram[start:end])
		out = append(out, p)
	}
	return out, nil
}

// Chunk is one parsed fragment. Data aliases the packet it was parsed from.
type Chunk struct {
	MsgID uint32
	Total uint8
	Index uint8
	Data  []byte
}

// ParseChunk parses one mesh packet. Anything without the "KG" magic is
// other traffic and returns ErrNotKengele; a Kengele packet that fails any
// other check returns an error wrapping ErrFragment or ErrDatagramSize.
func ParseChunk(p []byte) (Chunk, error) {
	var c Chunk
	if len(p) < 2 || p[0] != magic0 || p[1] != magic1 {
		return c, ErrNotKengele
	}
	if len(p) < HeaderLen+1 || len(p) > HeaderLen+MaxChunkData {
		return c, fmt.Errorf("%w: size %d", ErrFragment, len(p))
	}
	if p[2] != FragmentVersion {
		return c, fmt.Errorf("%w: version %d", ErrFragment, p[2])
	}
	total, index := p[3], p[4]
	if total == 0 || index >= total {
		return c, fmt.Errorf("%w: part %d of %d", ErrFragment, index, total)
	}
	if int(total-1)*MaxChunkData >= MaxDatagram {
		return c, ErrDatagramSize
	}
	data := p[HeaderLen:]
	if index < total-1 && len(data) != MaxChunkData {
		return c, fmt.Errorf("%w: short non-last part", ErrFragment)
	}
	return Chunk{
		MsgID: uint32(p[5])<<16 | uint32(p[6])<<8 | uint32(p[7]),
		Total: total,
		Index: index,
		Data:  data,
	}, nil
}

// Reassembler joins fragments back into datagrams. Groups expire after the
// timeout so one lost fragment cannot pin memory forever; the sender must
// send the datagram again under a new message id. It is not safe for
// concurrent use; the exit feeds it from one loop.
type Reassembler struct {
	timeout time.Duration
	now     func() time.Time
	groups  map[uint32]*reasmGroup
	order   []uint32 // message ids, oldest first, for eviction
}

type reasmGroup struct {
	total uint8
	parts map[uint8][]byte
	first time.Time
}

// NewReassembler returns a Reassembler. A non-positive timeout selects
// DefaultReassembleTimeout; a nil clock selects time.Now.
func NewReassembler(timeout time.Duration, now func() time.Time) *Reassembler {
	if timeout <= 0 {
		timeout = DefaultReassembleTimeout
	}
	if now == nil {
		now = time.Now
	}
	return &Reassembler{timeout: timeout, now: now, groups: map[uint32]*reasmGroup{}}
}

// Pending reports how many partial datagrams are held.
func (r *Reassembler) Pending() int {
	r.purge()
	return len(r.groups)
}

// Add feeds one mesh packet in. Non-Kengele traffic is ignored silently and
// returns complete false with a nil error. A duplicate fragment is also
// ignored: the mesh repeats packets. When the last missing fragment of a
// datagram arrives, Add returns the reassembled bytes with complete true.
func (r *Reassembler) Add(packet []byte) (datagram []byte, msgID uint32, complete bool, err error) {
	r.purge()
	c, err := ParseChunk(packet)
	if errors.Is(err, ErrNotKengele) {
		return nil, 0, false, nil
	}
	if err != nil {
		return nil, 0, false, err
	}
	g := r.groups[c.MsgID]
	if g == nil {
		if len(r.groups) >= MaxPendingGroups {
			oldest := r.order[0]
			r.drop(oldest)
		}
		g = &reasmGroup{total: c.Total, parts: map[uint8][]byte{}, first: r.now()}
		r.groups[c.MsgID] = g
		r.order = append(r.order, c.MsgID)
	}
	if g.total != c.Total {
		// Two senders picked the same id, or a corrupt total slipped
		// past the radio checksum. Drop the group; the sender retries
		// under a new id.
		r.drop(c.MsgID)
		return nil, 0, false, fmt.Errorf("%w: conflicting part count", ErrFragment)
	}
	if _, dup := g.parts[c.Index]; dup {
		return nil, c.MsgID, false, nil
	}
	cp := make([]byte, len(c.Data))
	copy(cp, c.Data)
	g.parts[c.Index] = cp
	if len(g.parts) != int(g.total) {
		return nil, c.MsgID, false, nil
	}
	var out []byte
	for i := uint8(0); i < g.total; i++ {
		out = append(out, g.parts[i]...)
	}
	r.drop(c.MsgID)
	if len(out) > MaxDatagram {
		return nil, 0, false, ErrDatagramSize
	}
	return out, c.MsgID, true, nil
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

func (r *Reassembler) purge() {
	now := r.now()
	for id, g := range r.groups {
		if now.Sub(g.first) > r.timeout {
			r.drop(id)
		}
	}
}
