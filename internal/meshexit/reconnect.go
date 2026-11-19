package meshexit

import (
	"context"
	"errors"
	"sync"
	"time"
)

// ErrMeshReconnecting means the mesh link is down and the exit is dialling
// it again. Receivers just see a gap in packets; Send callers get this
// error until the link is back.
var ErrMeshReconnecting = errors.New("meshexit: mesh reconnecting")

// ReconnectConfig tunes the automatic redial behind TCP and serial meshes.
// A dropped gateway link is routine (the shim restarts, USB re-enumerates),
// so the exit redials with exponential backoff instead of exiting.
type ReconnectConfig struct {
	// Initial is the wait before the first redial. Default 500ms.
	Initial time.Duration
	// Max caps the wait between redials. Default 30s.
	Max time.Duration
	// Factor multiplies the wait per attempt. Default 2.
	Factor float64
	// Log receives one bilingual line per redial event. Nil for silence.
	Log func(string)
}

func (c ReconnectConfig) initial() time.Duration {
	if c.Initial <= 0 {
		return 500 * time.Millisecond
	}
	return c.Initial
}

func (c ReconnectConfig) max() time.Duration {
	if c.Max <= 0 {
		return 30 * time.Second
	}
	return c.Max
}

func (c ReconnectConfig) factor() float64 {
	if c.Factor <= 0 {
		return 2
	}
	return c.Factor
}

// BackoffForAttempt returns how long to wait before redial attempt n
// (0-based): Initial * Factor^n, capped at Max.
func BackoffForAttempt(n int, cfg ReconnectConfig) time.Duration {
	d := float64(cfg.initial())
	for i := 0; i < n; i++ {
		d *= cfg.factor()
		if d >= float64(cfg.max()) {
			return cfg.max()
		}
	}
	if d >= float64(cfg.max()) {
		return cfg.max()
	}
	return time.Duration(d)
}

// ReconnectingMesh is a Mesh that survives gateway drops. It forwards
// packets from the current link over one stable Receive channel and redials
// with backoff when the link drops, so the exit keeps its reassembler,
// dedup and queue across outages. Use it for TCP and serial links; the
// simulator needs no redial.
type ReconnectingMesh struct {
	redial func() (Mesh, error)
	cfg    ReconnectConfig

	outgoing chan Packet
	done     chan struct{}
	once     sync.Once
	wg       sync.WaitGroup

	mu  sync.Mutex
	cur Mesh
}

// NewReconnectingMesh wraps initial, redialling through redial when it
// drops. initial may be nil to start disconnected (the first redial then
// happens after Initial). redial must not be nil.
func NewReconnectingMesh(initial Mesh, redial func() (Mesh, error), cfg ReconnectConfig) *ReconnectingMesh {
	m := &ReconnectingMesh{
		redial:   redial,
		cfg:      cfg,
		outgoing: make(chan Packet, 64),
		done:     make(chan struct{}),
		cur:      initial,
	}
	m.wg.Add(1)
	go m.loop()
	return m
}

// Receive implements Mesh. The channel stays open across redials and closes
// only when this mesh closes.
func (m *ReconnectingMesh) Receive() <-chan Packet { return m.outgoing }

// Send implements Mesh. While no link is up it returns ErrMeshReconnecting.
func (m *ReconnectingMesh) Send(ctx context.Context, payload []byte) error {
	m.mu.Lock()
	cur := m.cur
	m.mu.Unlock()
	if cur == nil {
		return ErrMeshReconnecting
	}
	return cur.Send(ctx, payload)
}

// Close implements Mesh.
func (m *ReconnectingMesh) Close() error {
	m.once.Do(func() { close(m.done) })
	m.wg.Wait()
	return nil
}

func (m *ReconnectingMesh) get() Mesh {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.cur
}

func (m *ReconnectingMesh) set(cur Mesh) {
	m.mu.Lock()
	m.cur = cur
	m.mu.Unlock()
}

func (m *ReconnectingMesh) log(s string) {
	if m.cfg.Log != nil {
		m.cfg.Log(s)
	}
}

func (m *ReconnectingMesh) loop() {
	defer m.wg.Done()
	defer close(m.outgoing)
	defer func() {
		if cur := m.get(); cur != nil {
			_ = cur.Close()
		}
	}()
	for {
		cur := m.get()
		if cur == nil {
			if !m.redialLoop() {
				return
			}
			continue
		}
		ch := cur.Receive()
		closed := false
		for !closed {
			select {
			case <-m.done:
				return
			case p, ok := <-ch:
				if !ok {
					closed = true
					break
				}
				select {
				case m.outgoing <- p:
				case <-m.done:
					return
				}
			}
		}
		// The link dropped. Close it before redialling so a half-open
		// connection (protocol violation leaves the fd open) is released.
		_ = cur.Close()
		m.set(nil)
		select {
		case <-m.done:
			return
		default:
		}
		m.log("mesh link dropped, redialling / muunganisho wa mesh umekatika, inaunganisha tena")
		if !m.redialLoop() {
			return
		}
	}
}

// redialLoop dials until one link comes up or the mesh closes. It reports
// whether a link is up.
func (m *ReconnectingMesh) redialLoop() bool {
	for attempt := 0; ; attempt++ {
		wait := BackoffForAttempt(attempt, m.cfg)
		select {
		case <-m.done:
			return false
		case <-time.After(wait):
		}
		next, err := m.redial()
		if err != nil || next == nil {
			if err == nil {
				err = errors.New("meshexit: redial returned no mesh")
			}
			m.log("mesh redial failed / kuunganisha mesh kumeshindikana (" + err.Error() + ")")
			continue
		}
		m.set(next)
		m.log("mesh link restored / muunganisho wa mesh umerudi")
		return true
	}
}
