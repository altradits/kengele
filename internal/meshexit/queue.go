package meshexit

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"sort"
	"sync"
	"time"
)

// MaxQueuedEntries caps how many datagrams the queue holds. Past this the
// exit drops new arrivals rather than growing without bound; the sender's
// phone still has them and sends them again.
const MaxQueuedEntries = 1000

// ErrQueueFull means the queue holds MaxQueuedEntries datagrams already.
var ErrQueueFull = errors.New("meshexit: queue is full")

// Entry is one datagram waiting for Nostr to come back. Data is a signed
// gift wrap, so a stored entry reveals no more than what a relay would see.
type Entry struct {
	// ID identifies the datagram: the hex SHA-256 of Data.
	ID string `json:"id"`
	// Data is the gift wrap JSON.
	Data []byte `json:"data"`
	// From is the mesh label the datagram arrived from.
	From string `json:"from"`
	// ReceivedAt is when the exit first heard it.
	ReceivedAt time.Time `json:"received_at"`
	// Attempts counts publish rounds, including the first.
	Attempts int `json:"attempts"`
}

// Queue keeps datagrams that Nostr has not accepted yet. Put replaces an
// entry with the same ID.
type Queue interface {
	Put(Entry) error
	// List returns the queued entries, oldest first.
	List() ([]Entry, error)
	Remove(id string) error
}

func checkEntry(e Entry) (Entry, error) {
	if e.ID == "" {
		return e, errors.New("meshexit: queue entry needs an id")
	}
	if len(e.Data) == 0 {
		return e, errors.New("meshexit: queue entry is empty")
	}
	if len(e.Data) > MaxDatagram {
		return e, ErrDatagramSize
	}
	if e.ReceivedAt.IsZero() {
		e.ReceivedAt = time.Now().UTC()
	}
	return e, nil
}

func cloneEntry(e Entry) Entry {
	e.Data = append([]byte(nil), e.Data...)
	return e
}

// MemoryQueue is a Queue that lives in memory only: a restart loses what
// is queued. Organisations that must survive restarts use FileQueue.
type MemoryQueue struct {
	mu    sync.Mutex
	items map[string]Entry
}

// NewMemoryQueue returns an empty in-memory queue.
func NewMemoryQueue() *MemoryQueue {
	return &MemoryQueue{items: map[string]Entry{}}
}

// Put implements Queue.
func (q *MemoryQueue) Put(e Entry) error {
	e, err := checkEntry(e)
	if err != nil {
		return err
	}
	q.mu.Lock()
	defer q.mu.Unlock()
	if _, ok := q.items[e.ID]; !ok && len(q.items) >= MaxQueuedEntries {
		return ErrQueueFull
	}
	q.items[e.ID] = cloneEntry(e)
	return nil
}

// List implements Queue.
func (q *MemoryQueue) List() ([]Entry, error) {
	q.mu.Lock()
	defer q.mu.Unlock()
	out := make([]Entry, 0, len(q.items))
	for _, e := range q.items {
		out = append(out, cloneEntry(e))
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].ReceivedAt.Equal(out[j].ReceivedAt) {
			return out[i].ID < out[j].ID
		}
		return out[i].ReceivedAt.Before(out[j].ReceivedAt)
	})
	return out, nil
}

// Remove implements Queue.
func (q *MemoryQueue) Remove(id string) error {
	q.mu.Lock()
	defer q.mu.Unlock()
	delete(q.items, id)
	return nil
}

// FileQueue is a Queue persisted as JSON in one file, so queued datagrams
// survive a restart. The file holds gift wraps (relay-equivalent data) and
// is written with mode 0600. It rewrites the whole file per change, which
// is fine at exit-point volumes.
type FileQueue struct {
	path string
	mu   sync.Mutex
	mem  *MemoryQueue
}

// OpenFileQueue opens the queue at path. A missing or empty file opens as
// an empty queue; a corrupt one is an error.
func OpenFileQueue(path string) (*FileQueue, error) {
	q := &FileQueue{path: path, mem: NewMemoryQueue()}
	raw, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return q, nil
		}
		return nil, fmt.Errorf("meshexit: read queue %s: %w", path, err)
	}
	if len(raw) == 0 {
		return q, nil
	}
	var list []Entry
	if err := json.Unmarshal(raw, &list); err != nil {
		return nil, fmt.Errorf("meshexit: read queue %s: %w", path, err)
	}
	for _, e := range list {
		if err := q.mem.Put(e); err != nil {
			return nil, fmt.Errorf("meshexit: read queue %s: %w", path, err)
		}
	}
	return q, nil
}

// Put implements Queue.
func (q *FileQueue) Put(e Entry) error {
	q.mu.Lock()
	defer q.mu.Unlock()
	if err := q.mem.Put(e); err != nil {
		return err
	}
	return q.saveLocked()
}

// List implements Queue.
func (q *FileQueue) List() ([]Entry, error) {
	q.mu.Lock()
	defer q.mu.Unlock()
	return q.mem.List()
}

// Remove implements Queue.
func (q *FileQueue) Remove(id string) error {
	q.mu.Lock()
	defer q.mu.Unlock()
	if err := q.mem.Remove(id); err != nil {
		return err
	}
	return q.saveLocked()
}

func (q *FileQueue) saveLocked() error {
	list, err := q.mem.List()
	if err != nil {
		return err
	}
	if list == nil {
		list = []Entry{}
	}
	raw, err := json.Marshal(list)
	if err != nil {
		return err
	}
	if err := os.WriteFile(q.path, raw, 0o600); err != nil {
		return fmt.Errorf("meshexit: write queue %s: %w", q.path, err)
	}
	return nil
}
