// Package bitchat is the plan and test stub for carrying Kengele messages
// over the bitchat Bluetooth mesh. It builds no mesh and speaks no BLE:
// it defines how the 228-byte wire message (docs/message-format.md)
// enters and leaves mesh carriage, so drills and the future Android
// transport share one contract.
//
// The mesh is an untrusted carrier (docs/threat-model.md, A5/A6): bytes
// travel unchanged and opaque, and only message.Open decides what they
// mean. See docs/mesh-bitchat.md for the protocol research, the payload
// options awaiting the maintainers' answer, and the prototype plan.
package bitchat

import (
	"crypto/sha256"
	"errors"
	"sync"

	"github.com/altradits/kengele/internal/message"
	"github.com/altradits/kengele/internal/transport"
)

// Mesh limits from the bitchat research (docs/mesh-bitchat.md). Values
// marked "confirm" there must be re-checked against the bitchat-android
// source before any BLE code relies on them.
const (
	// MaxPayload is the largest v1 packet payload in bytes: the 2-byte
	// length field allows at most 64 KiB.
	MaxPayload = 65535
	// FragmentSize is the cross-platform BLE fragment size in bytes
	// (bitchat-android 0.7 release notes). A Kengele wire message needs
	// two fragments.
	FragmentSize = 150
	// MaxHops is the TTL a packet starts with: at most 7 relay hops.
	MaxHops = 7
)

// FragmentsFor reports how many BLE fragments a wire message needs.
func FragmentsFor(wire []byte) int {
	if len(wire) == 0 {
		return 0
	}
	return (len(wire) + FragmentSize - 1) / FragmentSize
}

// Errors from Validate. Carriers drop rejected bytes silently; the
// errors differ only to help debugging and tests.
var (
	ErrSize    = errors.New("bitchat: not a Kengele wire message (wrong size)")
	ErrVersion = errors.New("bitchat: not a Kengele wire message (unknown version)")
)

// Validate checks that wire has the shape of a Kengele message: the
// fixed wire size with a known format version. It is the shape half of
// the "shape plus opens" recognition rule; the caller still opens the
// bytes with message.Open against watched sender keys. The mesh sender
// ID is never trusted.
func Validate(wire []byte) error {
	if len(wire) != message.WireSize {
		return ErrSize
	}
	if wire[0] != message.Version {
		return ErrVersion
	}
	return nil
}

// Delivery is one carried message, tagged with the route it arrived on
// so the watcher can tell mesh carriage apart from Nostr or SMS (the
// mesh proves nothing about any network).
type Delivery struct {
	Wire []byte
	Via  transport.Kind
}

// Carrier is the contract the future Android mesh transport will keep:
// broadcast sealed wire bytes to nearby phones, collect what arrives.
// Bytes are opaque; merging, ordering and opening stay with the watcher
// and the message package.
type Carrier interface {
	Broadcast(wire []byte) error
	Collect() []Delivery
}

// MemoryCarrier is an in-memory Carrier for drills and tests. It stands
// in for "nearby phones": Broadcast queues a copy, Collect returns each
// unique message once, tagged transport.Mesh. Repeats of an already
// seen message are dropped, like bitchat's packet-ID dedup. It is safe
// for concurrent use.
type MemoryCarrier struct {
	mu    sync.Mutex
	seen  map[[32]byte]bool
	queue [][]byte
}

// Broadcast queues wire for later collection. It rejects bytes that fail
// Validate and silently drops repeats.
func (m *MemoryCarrier) Broadcast(wire []byte) error {
	if err := Validate(wire); err != nil {
		return err
	}
	sum := sha256.Sum256(wire)
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.seen == nil {
		m.seen = make(map[[32]byte]bool)
	}
	if m.seen[sum] {
		return nil
	}
	m.seen[sum] = true
	m.queue = append(m.queue, append([]byte(nil), wire...))
	return nil
}

// Collect returns every queued message once, in broadcast order, and
// clears the queue. Every delivery is tagged transport.Mesh.
func (m *MemoryCarrier) Collect() []Delivery {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]Delivery, 0, len(m.queue))
	for _, w := range m.queue {
		out = append(out, Delivery{Wire: w, Via: transport.Mesh})
	}
	m.queue = nil
	return out
}
