package meshcarry

import (
	"crypto/rand"
	"crypto/sha256"
	"errors"
	"fmt"
	"sort"
	"time"
)

// Seen-set limits: a node remembers recent datagrams and forwarded
// fragments so floods terminate instead of looping forever. Memory-only
// (A2 seizure): nothing here touches disk.
const (
	// maxSeenDatagrams caps completed-datagram hashes remembered.
	maxSeenDatagrams = 1000
	// maxRelayedFrags caps forwarded (message id, index) pairs remembered.
	maxRelayedFrags = 1000
	// seenTTL is how long a completed datagram stays deduplicated.
	seenTTL = 5 * time.Minute
)

// Delivery is one datagram this node reassembled. Datagram is the
// opaque 228 bytes; From names the previous-hop node (set by the
// Simulator; empty for a bare Node that never learned its peer); Hops
// counts links traversed from the source.
type Delivery struct {
	Datagram []byte
	From     string
	Hops     uint8
}

// Node is one phone in the crowd mesh. The zero value is usable once ID
// is set; all state is created lazily and lives in memory only. It is
// not safe for concurrent use; the Simulator feeds it from one loop.
type Node struct {
	// ID names the node in traces and deliveries.
	ID string
	// Drop, when true, discards every received fragment. It is the
	// drill hook that proves the honest limit: with the middle node
	// dropping, nothing arrives and silence fails safe.
	Drop bool

	reasm   *Reassembler
	seen    map[[32]byte]time.Time
	seenOrd [][32]byte
	relayed map[uint64]bool
	relOrd  []uint64
}

func (n *Node) init() {
	if n.reasm == nil {
		n.reasm = NewReassembler(0, 0)
	}
	if n.seen == nil {
		n.seen = map[[32]byte]time.Time{}
	}
	if n.relayed == nil {
		n.relayed = map[uint64]bool{}
	}
}

// fragKey joins a message id and part index for duplicate suppression.
func fragKey(msgID uint32, index uint8) uint64 {
	return uint64(msgID)<<8 | uint64(index)
}

// Receive feeds one on-air fragment in and returns what leaves the
// node: relay is the fragment to rebroadcast (a copy with TTL-1, or
// nil when nothing forwards), and deliver is set when this fragment
// completes a datagram the node had not seen before.
//
// The relay decision uses the header only, never the bytes: the node
// forwards what it cannot read. Non-Kengele traffic and TTL-expired
// fragments are dropped silently with nil outputs and a nil error. A
// malformed Kengele fragment returns an error wrapping ErrFragment (or
// ErrDatagramSize) so direct callers can observe it; the Simulator
// drops such fragments silently, as a carrier should.
//
// Hops on a direct-call delivery is a best effort assuming the sender
// used DefaultTTL; the Simulator overwrites it and From with exact
// values. From is always empty here: a bare node never learns its peer.
func (n *Node) Receive(frag []byte) (relay []byte, deliver *Delivery, err error) {
	n.init()
	if n.Drop {
		return nil, nil, nil
	}
	h, _, err := ParseHeader(frag)
	if errors.Is(err, ErrNotKengele) {
		return nil, nil, nil
	}
	if err != nil {
		return nil, nil, err
	}
	if h.TTL == 0 {
		return nil, nil, nil
	}
	key := fragKey(h.MsgID, h.Index)
	if n.relayed[key] {
		return nil, nil, nil
	}
	out := make([]byte, len(frag))
	copy(out, frag)
	out[3] = h.TTL - 1
	n.markRelayed(key)

	dg, complete, err := n.reasm.Add(frag)
	if err != nil {
		// The bytes still travel: a blind carrier relays first and
		// lets endpoints argue about reassembly.
		return out, nil, err
	}
	if !complete {
		return out, nil, nil
	}
	sum := sha256.Sum256(dg)
	if _, dup := n.seen[sum]; dup {
		return out, nil, nil
	}
	n.markSeen(sum)
	hops := uint8(1)
	if h.TTL <= DefaultTTL {
		hops = DefaultTTL - h.TTL + 1
	}
	return out, &Delivery{Datagram: dg, Hops: hops}, nil
}

// markSeen records a completed datagram hash given as raw bytes.
func (n *Node) markSeenDatagram(dg []byte) {
	n.init()
	n.markSeen(sha256.Sum256(dg))
}

func (n *Node) markSeen(sum [32]byte) {
	n.purgeSeen(time.Now())
	if _, ok := n.seen[sum]; ok {
		return
	}
	if len(n.seenOrd) >= maxSeenDatagrams {
		oldest := n.seenOrd[0]
		n.seenOrd = n.seenOrd[1:]
		delete(n.seen, oldest)
	}
	n.seen[sum] = time.Now()
	n.seenOrd = append(n.seenOrd, sum)
}

func (n *Node) purgeSeen(now time.Time) {
	kept := n.seenOrd[:0]
	for _, sum := range n.seenOrd {
		if now.Sub(n.seen[sum]) > seenTTL {
			delete(n.seen, sum)
			continue
		}
		kept = append(kept, sum)
	}
	n.seenOrd = kept
}

func (n *Node) markRelayed(key uint64) {
	if n.relayed[key] {
		return
	}
	if len(n.relOrd) >= maxRelayedFrags {
		oldest := n.relOrd[0]
		n.relOrd = n.relOrd[1:]
		delete(n.relayed, oldest)
	}
	n.relayed[key] = true
	n.relOrd = append(n.relOrd, key)
}

// TraceKind names the observable steps of a simulation wave, for demos
// and drills. Traces log hop control only (message id, part, TTL),
// never datagram bytes: the log of a blind carrier must itself be blind.
type TraceKind uint8

const (
	// TraceBroadcast is one fragment leaving its source node.
	TraceBroadcast TraceKind = iota + 1
	// TraceRelay is one fragment forwarded by a middle node.
	TraceRelay
	// TraceReassembled is one datagram joined at a node.
	TraceReassembled
)

// TraceEvent is one observable step. Node is the acting node; Hops and
// Bytes apply to TraceReassembled only.
type TraceEvent struct {
	Kind  TraceKind
	Node  string
	MsgID uint32
	Index uint8
	Total uint8
	TTL   uint8
	Hops  uint8
	Bytes int
}

// flight is one fragment travelling one link.
type flight struct {
	to   string
	from string
	frag []byte
	hops uint8
}

// Simulator is a synchronous, deterministic, in-memory crowd mesh for
// demos, drills and tests. Time moves in waves: Step delivers every
// in-flight fragment to its next hop and queues the relays as the next
// wave. It is not safe for concurrent use.
type Simulator struct {
	// SendTTL overrides the TTL fresh broadcasts carry. Zero means
	// DefaultTTL.
	SendTTL uint8
	// OnTrace, when non-nil, receives every broadcast, relay and
	// reassembly in wave order.
	OnTrace func(TraceEvent)

	nodes      map[string]*Node
	links      map[string]map[string]bool
	inbox      []flight
	deliveries map[string][]Delivery
	nextID     uint32
	lastID     uint32
	injected   bool
}

// NewSimulator returns an empty Simulator with a random starting
// message id. Tests that need exact ids call SeedMsgID.
func NewSimulator() *Simulator {
	return &Simulator{
		nodes:      map[string]*Node{},
		links:      map[string]map[string]bool{},
		deliveries: map[string][]Delivery{},
		nextID:     freshMsgID(),
	}
}

func freshMsgID() uint32 {
	var b [3]byte
	if _, err := rand.Read(b[:]); err != nil {
		return 1
	}
	return uint32(b[0])<<16 | uint32(b[1])<<8 | uint32(b[2])
}

// SeedMsgID fixes the next broadcast message id for deterministic tests.
func (s *Simulator) SeedMsgID(v uint32) {
	s.nextID = v & maxMsgID
}

// LastMsgID reports the message id of the most recent Inject.
func (s *Simulator) LastMsgID() (uint32, bool) {
	return s.lastID, s.injected
}

// AddNode adds a node, keeping the existing one under a repeated id.
func (s *Simulator) AddNode(id string) {
	if id == "" {
		return
	}
	if _, ok := s.nodes[id]; !ok {
		s.nodes[id] = &Node{ID: id}
	}
	if _, ok := s.links[id]; !ok {
		s.links[id] = map[string]bool{}
	}
}

// Node returns the node under id, or nil.
func (s *Simulator) Node(id string) *Node {
	return s.nodes[id]
}

// Link connects two nodes both ways, creating missing endpoints.
func (s *Simulator) Link(a, b string) {
	s.AddNode(a)
	s.AddNode(b)
	if a == "" || b == "" || a == b {
		return
	}
	s.links[a][b] = true
	s.links[b][a] = true
}

// Unlink disconnects two nodes both ways.
func (s *Simulator) Unlink(a, b string) {
	delete(s.links[a], b)
	delete(s.links[b], a)
}

// Peers returns the ids of the nodes linked to id, sorted. A missing
// node has no peers.
func (s *Simulator) Peers(id string) []string {
	peers := make([]string, 0, len(s.links[id]))
	for p := range s.links[id] {
		peers = append(peers, p)
	}
	sort.Strings(peers)
	return peers
}

// Inject broadcasts datagram from nodeID to its linked peers. The
// source records the datagram as seen and its fragments as forwarded,
// so it never delivers its own broadcast and its neighbours' echoes
// die on arrival. Broadcasts use SendTTL (or DefaultTTL), capped at
// DenseCrowdTTL once the source holds DenseCrowdLinks or more links.
func (s *Simulator) Inject(nodeID string, datagram []byte) error {
	n := s.nodes[nodeID]
	if n == nil {
		return fmt.Errorf("meshcarry: unknown node %q", nodeID)
	}
	ttl := s.SendTTL
	if ttl == 0 {
		ttl = DefaultTTL
	}
	if len(s.links[nodeID]) >= DenseCrowdLinks && ttl > DenseCrowdTTL {
		ttl = DenseCrowdTTL
	}
	msgID := s.nextID
	frags, err := Split(datagram, msgID, ttl)
	if err != nil {
		return err
	}
	s.nextID = (s.nextID + 1) & maxMsgID
	s.lastID, s.injected = msgID, true
	n.markSeenDatagram(datagram)
	for i := range frags {
		n.markRelayed(fragKey(msgID, uint8(i)))
	}
	for _, to := range s.Peers(nodeID) {
		for i, f := range frags {
			s.inbox = append(s.inbox, flight{to: to, from: nodeID, frag: f, hops: 1})
			s.trace(TraceEvent{
				Kind: TraceBroadcast, Node: nodeID,
				MsgID: msgID, Index: uint8(i), Total: uint8(len(frags)), TTL: ttl,
			})
		}
	}
	return nil
}

// Step delivers one wave: every in-flight fragment reaches its next
// hop, relays are queued for the next wave, and completed datagrams
// are recorded with their exact previous hop and hop count. It returns
// how many fragments were relayed. Malformed fragments are dropped
// silently; carriers log hop control, never bytes.
func (s *Simulator) Step() int {
	wave := s.inbox
	s.inbox = nil
	relays := 0
	for _, fl := range wave {
		n := s.nodes[fl.to]
		if n == nil {
			continue
		}
		relay, deliver, _ := n.Receive(fl.frag)
		if relay != nil {
			relays++
			if rh, _, err := ParseHeader(relay); err == nil {
				s.trace(TraceEvent{
					Kind: TraceRelay, Node: fl.to,
					MsgID: rh.MsgID, Index: rh.Index, Total: rh.Total, TTL: rh.TTL,
				})
			}
			for _, to := range s.Peers(fl.to) {
				s.inbox = append(s.inbox, flight{to: to, from: fl.to, frag: relay, hops: fl.hops + 1})
			}
		}
		if deliver != nil {
			deliver.From = fl.from
			deliver.Hops = fl.hops
			s.deliveries[fl.to] = append(s.deliveries[fl.to], *deliver)
			if fh, _, err := ParseHeader(fl.frag); err == nil {
				s.trace(TraceEvent{
					Kind: TraceReassembled, Node: fl.to,
					MsgID: fh.MsgID, Index: fh.Index, Total: fh.Total, TTL: fh.TTL,
					Hops: fl.hops, Bytes: len(deliver.Datagram),
				})
			}
		}
	}
	return relays
}

// Run steps until a wave relays nothing or maxSteps waves have run.
func (s *Simulator) Run(maxSteps int) {
	for i := 0; i < maxSteps; i++ {
		if s.Step() == 0 {
			return
		}
	}
}

// Deliveries returns the datagrams reassembled at id, oldest first.
func (s *Simulator) Deliveries(id string) []Delivery {
	return append([]Delivery(nil), s.deliveries[id]...)
}

func (s *Simulator) trace(ev TraceEvent) {
	if s.OnTrace != nil {
		s.OnTrace(ev)
	}
}
