package meshexit

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"sync"
	"time"
)

const (
	// DefaultRetryInterval is how often the exit retries queued datagrams.
	DefaultRetryInterval = 30 * time.Second
	// DefaultDedupSize is how many recent datagram hashes the exit
	// remembers, so mesh repeats are forwarded only once.
	DefaultDedupSize = 1024
)

// Config sets up an Exit. Only the mesh and publisher given to New are
// required.
type Config struct {
	// Queue keeps datagrams Nostr has not accepted yet. Default: a memory
	// queue. Organisations that must survive restarts pass a FileQueue.
	Queue Queue
	// ReassembleTimeout bounds how long fragments of one datagram are
	// kept. Default: DefaultReassembleTimeout.
	ReassembleTimeout time.Duration
	// RetryInterval is how often the queue is retried. Default:
	// DefaultRetryInterval.
	RetryInterval time.Duration
	// DedupSize is how many recent datagrams are remembered. Default:
	// DefaultDedupSize.
	DedupSize int
	// Now is for tests. Default: time.Now.
	Now func() time.Time
}

// Stats counts what the exit has seen. Dropped covers bad fragments,
// invalid datagrams and arrivals the queue could not take.
type Stats struct {
	Received  uint64
	Forwarded uint64
	Queued    uint64
	Dropped   uint64
}

// Exit listens on a mesh and forwards Kengele gift wraps to Nostr, queuing
// them while Nostr is unreachable. Run owns the receive loop; Flush and
// SendDatagram may be called alongside it.
type Exit struct {
	mesh  Mesh
	pub   Publisher
	q     Queue
	now   func() time.Time
	retry time.Duration
	re    *Reassembler
	dedup int

	mu    sync.Mutex
	seen  map[string]bool
	order []string // datagram ids, oldest first, for eviction
	stats Stats
}

// New returns an Exit listening on mesh and publishing through pub.
func New(mesh Mesh, pub Publisher, cfg Config) (*Exit, error) {
	if mesh == nil {
		return nil, errors.New("meshexit: no mesh")
	}
	if pub == nil {
		return nil, errors.New("meshexit: no publisher")
	}
	q := cfg.Queue
	if q == nil {
		q = NewMemoryQueue()
	}
	now := cfg.Now
	if now == nil {
		now = time.Now
	}
	retry := cfg.RetryInterval
	if retry <= 0 {
		retry = DefaultRetryInterval
	}
	dedup := cfg.DedupSize
	if dedup <= 0 {
		dedup = DefaultDedupSize
	}
	return &Exit{
		mesh:  mesh,
		pub:   pub,
		q:     q,
		now:   now,
		retry: retry,
		re:    NewReassembler(cfg.ReassembleTimeout, now),
		dedup: dedup,
		seen:  map[string]bool{},
	}, nil
}

// Stats returns a copy of the counters.
func (e *Exit) Stats() Stats {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.stats
}

// Run listens until ctx ends or the mesh closes, logging one bilingual
// line per event to out (which may be nil for silence).
func (e *Exit) Run(ctx context.Context, out io.Writer) error {
	ticker := time.NewTicker(e.retry)
	defer ticker.Stop()
	ch := e.mesh.Receive()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			before := e.Stats().Forwarded
			pending, err := e.Flush(ctx)
			if err != nil {
				e.logf(out, "queue retry failed / kujaribu foleni kumeshindikana (%v)", err)
			} else {
				e.logFlushed(out, before)
				if pending > 0 {
					e.logf(out, "queue: %d still waiting / foleni: %d bado zinasubiri", pending, pending)
				}
			}
		case p, ok := <-ch:
			if !ok {
				return nil
			}
			e.handlePacket(ctx, p, out)
		}
	}
}

// Flush retries every queued datagram once and returns how many are still
// waiting. Entries that are no longer valid gift wraps (a hand-edited queue
// file, or stricter validation than when they were queued) are dropped
// instead of retried forever.
func (e *Exit) Flush(ctx context.Context) (int, error) {
	items, err := e.q.List()
	if err != nil {
		return 0, err
	}
	pending := 0
	for i := range items {
		it := items[i]
		if err := e.pub.Publish(ctx, it.Data); err != nil {
			if errors.Is(err, ErrNotGiftWrap) {
				if rerr := e.q.Remove(it.ID); rerr != nil {
					return pending, rerr
				}
				e.bumpDropped()
				continue
			}
			pending++
			it.Attempts++
			if perr := e.q.Put(it); perr != nil {
				return pending, perr
			}
			continue
		}
		if err := e.q.Remove(it.ID); err != nil {
			return pending, err
		}
		e.mu.Lock()
		e.stats.Forwarded++
		e.mu.Unlock()
	}
	return pending, nil
}

// SendDatagram fragments datagram and transmits it on the mesh, for tests
// and for the future Nostr-to-mesh direction.
func (e *Exit) SendDatagram(ctx context.Context, datagram []byte, msgID uint32) error {
	parts, err := Split(datagram, msgID)
	if err != nil {
		return err
	}
	for _, p := range parts {
		if err := e.mesh.Send(ctx, p); err != nil {
			return err
		}
	}
	return nil
}

func (e *Exit) handlePacket(ctx context.Context, p Packet, out io.Writer) {
	datagram, _, complete, err := e.re.Add(p.Payload)
	if err != nil {
		// Log before counting: a watcher that sees the new counter must
		// also see the line that explains it.
		e.logf(out, "dropped bad fragment / kipande kibaya kimetupwa (%s: %v)", p.From, err)
		e.bumpDropped()
		return
	}
	if !complete {
		return
	}
	e.handleDatagram(ctx, datagram, p.From, out)
}

func (e *Exit) handleDatagram(ctx context.Context, datagram []byte, from string, out io.Writer) {
	sum := sha256.Sum256(datagram)
	id := hex.EncodeToString(sum[:])
	short := hex.EncodeToString(sum[:4])

	e.mu.Lock()
	dup := e.seen[id]
	if !dup {
		e.seen[id] = true
		e.order = append(e.order, id)
		for len(e.order) > e.dedup {
			delete(e.seen, e.order[0])
			e.order = e.order[1:]
		}
		e.stats.Received++
	}
	e.mu.Unlock()
	if dup {
		e.logf(out, "duplicate ignored / marudio yamepuuzwa (%s)", short)
		return
	}
	if _, err := ValidateDatagram(datagram); err != nil {
		e.logf(out, "dropped invalid message / ujumbe batili umetupwa (%s)", short)
		e.bumpDropped()
		return
	}
	if err := e.pub.Publish(ctx, datagram); err != nil {
		// Defensive: the datagram validated above, so a publisher that
		// still calls it invalid disagrees with the exit. Drop rather
		// than queue what can never send.
		if errors.Is(err, ErrNotGiftWrap) {
			e.logf(out, "dropped invalid message / ujumbe batili umetupwa (%s)", short)
			e.bumpDropped()
			return
		}
		entry := Entry{
			ID:         id,
			Data:       append([]byte(nil), datagram...),
			From:       from,
			ReceivedAt: e.now().UTC(),
			Attempts:   1,
		}
		if qerr := e.q.Put(entry); qerr != nil {
			e.logf(out, "dropped, queue failed / umetupwa, foleni imeshindikana (%s: %v)", short, qerr)
			e.bumpDropped()
			return
		}
		e.logf(out, "queued, Nostr unreachable / imewekwa foleni, Nostr haipatikani (%s)", short)
		e.mu.Lock()
		e.stats.Queued++
		e.mu.Unlock()
		return
	}
	e.logf(out, "forwarded to Nostr / imetumwa kwa Nostr (%s)", short)
	e.mu.Lock()
	e.stats.Forwarded++
	e.mu.Unlock()
	// A success means Nostr is back: take the queue with it.
	before := e.Stats().Forwarded
	pending, _ := e.Flush(ctx)
	e.logFlushed(out, before)
	if pending > 0 {
		e.logf(out, "queue: %d still waiting / foleni: %d bado zinasubiri", pending, pending)
	}
}

// logFlushed reports how many queued datagrams a Flush just forwarded, if
// any, so an uplink that returns is visible in the log.
func (e *Exit) logFlushed(out io.Writer, before uint64) {
	if after := e.Stats().Forwarded; after > before {
		n := after - before
		e.logf(out, "flushed %d to Nostr / %d zimetumwa kwa Nostr kutoka foleni", n, n)
	}
}

func (e *Exit) bumpDropped() {
	e.mu.Lock()
	e.stats.Dropped++
	e.mu.Unlock()
}

func (e *Exit) logf(out io.Writer, format string, args ...any) {
	if out == nil {
		return
	}
	fmt.Fprintf(out, "%s ", e.now().UTC().Format(time.RFC3339))
	fmt.Fprintf(out, format+"\n", args...)
}
