package meshexit

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"sync"
	"sync/atomic"
	"time"
)

// Packet is one mesh packet as the exit saw it.
type Packet struct {
	// From names the packet's source: a simulator label, "tcp:host:port"
	// or "serial:/path". It is only a debugging label, never trusted.
	From string
	// Payload is one fragment, header included, of at most MeshMTU bytes.
	Payload []byte
	// At is when the exit received it.
	At time.Time
}

// Mesh is a Meshtastic radio the exit listens to: a real node over serial
// or TCP, or the simulator in tests. Receive returns the channel packets
// arrive on; it closes when the mesh is closed or the link drops.
type Mesh interface {
	Receive() <-chan Packet
	Send(ctx context.Context, payload []byte) error
	Close() error
}

var (
	// ErrPacketTooLarge means a packet exceeds the mesh MTU.
	ErrPacketTooLarge = errors.New("meshexit: mesh packet exceeds MTU")
	// ErrMeshClosed means the mesh is closed.
	ErrMeshClosed = errors.New("meshexit: mesh closed")
)

// SimConfig tunes the simulator.
type SimConfig struct {
	// MTU caps packet sizes. Non-positive selects MeshMTU.
	MTU int
	// LossEvery drops every Nth injected packet when positive, to stand
	// in for radio loss.
	LossEvery int
	// DupeEvery delivers every Nth injected packet twice when positive,
	// to stand in for mesh repeats.
	DupeEvery int
}

// Simulator is an in-memory Mesh for tests and demos. Inject pretends a
// radio packet arrived; Sent records what the exit transmitted.
type Simulator struct {
	mtu       int
	lossEvery int
	dupeEvery int

	incoming chan Packet

	mu     sync.RWMutex // RLock is held while sending on incoming
	sent   [][]byte
	n      atomic.Uint64
	closed bool
	once   sync.Once
}

// NewSimulator returns a Simulator.
func NewSimulator(cfg SimConfig) *Simulator {
	mtu := cfg.MTU
	if mtu <= 0 {
		mtu = MeshMTU
	}
	return &Simulator{
		mtu:       mtu,
		lossEvery: cfg.LossEvery,
		dupeEvery: cfg.DupeEvery,
		incoming:  make(chan Packet, 256),
	}
}

// Receive implements Mesh.
func (s *Simulator) Receive() <-chan Packet { return s.incoming }

// Inject delivers payload as if a radio node sent it. The payload is
// copied. Injections after Close are ignored.
func (s *Simulator) Inject(from string, payload []byte) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if s.closed {
		return
	}
	n := s.n.Add(1)
	if s.lossEvery > 0 && n%uint64(s.lossEvery) == 0 {
		return
	}
	s.incoming <- Packet{From: from, Payload: append([]byte(nil), payload...), At: time.Now().UTC()}
	if s.dupeEvery > 0 && n%uint64(s.dupeEvery) == 0 {
		s.incoming <- Packet{From: from, Payload: append([]byte(nil), payload...), At: time.Now().UTC()}
	}
}

// Send implements Mesh. Payloads over the MTU are rejected, as a real
// radio would.
func (s *Simulator) Send(ctx context.Context, payload []byte) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if len(payload) > s.mtu {
		return fmt.Errorf("%w: %d > %d", ErrPacketTooLarge, len(payload), s.mtu)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return ErrMeshClosed
	}
	s.sent = append(s.sent, append([]byte(nil), payload...))
	return nil
}

// Sent returns copies of every payload transmitted so far.
func (s *Simulator) Sent() [][]byte {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([][]byte, 0, len(s.sent))
	for _, p := range s.sent {
		out = append(out, append([]byte(nil), p...))
	}
	return out
}

// Close implements Mesh.
func (s *Simulator) Close() error {
	s.once.Do(func() {
		s.mu.Lock()
		s.closed = true
		close(s.incoming)
		s.mu.Unlock()
	})
	return nil
}

// framedMesh speaks the exit's TCP framing to a gateway: each mesh packet
// travels as a 2-byte big-endian length followed by the payload. A small
// gateway process on the radio host (see docs/meshtastic-exit.md) translates
// between this framing and the Meshtastic node, so the exit itself needs no
// Meshtastic protocol code. Serial links use the same framing over the
// device file.
type framedMesh struct {
	conn     io.ReadWriteCloser
	from     string
	mtu      int
	incoming chan Packet
	done     chan struct{} // closed by Close
	wg       sync.WaitGroup
	wmu      sync.Mutex
	once     sync.Once
}

func newFramedMesh(conn io.ReadWriteCloser, from string, mtu int) *framedMesh {
	if mtu <= 0 {
		mtu = MeshMTU
	}
	m := &framedMesh{
		conn:     conn,
		from:     from,
		mtu:      mtu,
		incoming: make(chan Packet, 64),
		done:     make(chan struct{}),
	}
	m.wg.Add(1)
	go m.readLoop()
	return m
}

// Receive implements Mesh.
func (m *framedMesh) Receive() <-chan Packet { return m.incoming }

// Send implements Mesh.
func (m *framedMesh) Send(ctx context.Context, payload []byte) error {
	if len(payload) == 0 {
		return errors.New("meshexit: empty mesh packet")
	}
	if len(payload) > m.mtu {
		return fmt.Errorf("%w: %d > %d", ErrPacketTooLarge, len(payload), m.mtu)
	}
	frame := make([]byte, 2+len(payload))
	binary.BigEndian.PutUint16(frame, uint16(len(payload)))
	copy(frame[2:], payload)
	m.wmu.Lock()
	defer m.wmu.Unlock()
	select {
	case <-m.done:
		return ErrMeshClosed
	default:
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	for len(frame) > 0 {
		n, err := m.conn.Write(frame)
		if err != nil {
			return err
		}
		frame = frame[n:]
	}
	return nil
}

// Close implements Mesh.
func (m *framedMesh) Close() error {
	m.once.Do(func() {
		close(m.done)
		_ = m.conn.Close()
	})
	m.wg.Wait()
	return nil
}

func (m *framedMesh) readLoop() {
	defer m.wg.Done()
	defer close(m.incoming)
	hdr := make([]byte, 2)
	for {
		if _, err := io.ReadFull(m.conn, hdr); err != nil {
			return
		}
		n := int(binary.BigEndian.Uint16(hdr))
		if n == 0 || n > m.mtu {
			return // protocol violation: drop the link
		}
		buf := make([]byte, n)
		if _, err := io.ReadFull(m.conn, buf); err != nil {
			return
		}
		select {
		case m.incoming <- Packet{From: m.from, Payload: buf, At: time.Now().UTC()}:
		case <-m.done:
			return
		}
	}
}

// DialTCP connects to a Meshtastic gateway at addr ("host:port").
func DialTCP(ctx context.Context, addr string) (Mesh, error) {
	d := &net.Dialer{Timeout: 10 * time.Second}
	c, err := d.DialContext(ctx, "tcp", addr)
	if err != nil {
		return nil, fmt.Errorf("meshexit: dial %s: %w", addr, err)
	}
	return newFramedMesh(c, "tcp:"+addr, MeshMTU), nil
}

// OpenSerial opens a gateway on a serial device path. The port must already
// run at the node's baud rate (115200 for the usual Meshtastic firmware);
// this only reads and writes frames.
func OpenSerial(path string) (Mesh, error) {
	f, err := os.OpenFile(path, os.O_RDWR, 0)
	if err != nil {
		return nil, fmt.Errorf("meshexit: open %s: %w", path, err)
	}
	return newFramedMesh(f, "serial:"+path, MeshMTU), nil
}
