package meshexit

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
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

// get returns a copy of the entry for id, for FileQueue rollback.
func (q *MemoryQueue) get(id string) (Entry, bool) {
	q.mu.Lock()
	defer q.mu.Unlock()
	e, ok := q.items[id]
	if !ok {
		return Entry{}, false
	}
	return cloneEntry(e), true
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
// an empty queue; a corrupt one is an error. If the file exists its mode is
// tightened to 0600 on a best-effort basis; the next save rewrites it 0600
// regardless.
func OpenFileQueue(path string) (*FileQueue, error) {
	q := &FileQueue{path: path, mem: NewMemoryQueue()}
	raw, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return q, nil
		}
		return nil, fmt.Errorf("meshexit: read queue %s: %w", path, err)
	}
	_ = os.Chmod(path, 0o600)
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
	prev, hadPrev := q.mem.get(e.ID)
	if err := q.mem.Put(e); err != nil {
		return err
	}
	if err := q.saveLocked(); err != nil {
		// Roll back the in-memory change so a failed save never looks
		// queued: the next restart would otherwise lose it silently.
		if hadPrev {
			_ = q.mem.Put(prev)
		} else {
			_ = q.mem.Remove(e.ID)
		}
		return err
	}
	return nil
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
	prev, hadPrev := q.mem.get(id)
	if err := q.mem.Remove(id); err != nil {
		return err
	}
	if err := q.saveLocked(); err != nil {
		if hadPrev {
			_ = q.mem.Put(prev)
		}
		return err
	}
	return nil
}

// saveLocked rewrites the queue file atomically: the parent directory is
// created, the new contents are fsynced to a temp file in the same
// directory, then renamed over the old file. A crash either keeps the old
// queue or installs the new one, never a half-written file. The file is
// always 0600: it holds gift wraps, which reveal recipient keys.
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
	dir := filepath.Dir(q.path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return fmt.Errorf("meshexit: write queue %s: %w", q.path, err)
	}
	tmp, err := os.CreateTemp(dir, ".exit-queue-*.tmp")
	if err != nil {
		return fmt.Errorf("meshexit: write queue %s: %w", q.path, err)
	}
	tmpName := tmp.Name()
	done := false
	defer func() {
		if !done {
			_ = os.Remove(tmpName)
		}
	}()
	if _, err := tmp.Write(raw); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("meshexit: write queue %s: %w", q.path, err)
	}
	// CreateTemp already makes 0600; enforce it explicitly in case the
	// platform widened it.
	if err := tmp.Chmod(0o600); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("meshexit: write queue %s: %w", q.path, err)
	}
	if err := tmp.Sync(); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("meshexit: write queue %s: %w", q.path, err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("meshexit: write queue %s: %w", q.path, err)
	}
	if err := os.Rename(tmpName, q.path); err != nil {
		return fmt.Errorf("meshexit: write queue %s: %w", q.path, err)
	}
	// Fsync the directory so the rename survives a power cut. Best
	// effort: some filesystems refuse it, and the queue is still correct
	// without it.
	if dh, err := os.Open(dir); err == nil {
		_ = dh.Sync()
		_ = dh.Close()
	}
	done = true
	return nil
}
