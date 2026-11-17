package nostr

import (
	"sort"
	"sync"
	"time"

	gn "github.com/nbd-wtf/go-nostr"
)

// Item is one gift wrap waiting for enough relays to accept it. The wrap is
// already encrypted and signed, so a stored Item reveals no more than what a
// relay would see.
type Item struct {
	// Event is the signed gift wrap. Retries publish this same event, so a
	// relay that already has it just answers "duplicate".
	Event gn.Event
	// Relays are the relays this item should reach.
	Relays []string
	// Accepted holds the relays that have acknowledged the event.
	Accepted map[string]bool
	// Queued is when the item first failed to reach enough relays.
	Queued time.Time
	// Attempts counts publish rounds, including the first.
	Attempts int
}

// Outbox keeps gift wraps that have not reached enough relays yet. Put must
// replace an item with the same Event.ID.
type Outbox interface {
	Put(Item) error
	List() ([]Item, error)
	Remove(id string) error
}

// MemoryOutbox is an Outbox that lives in memory only: queued messages are
// lost if the process stops. A persistent outbox is an open question; see
// docs/nostr-transport.md.
type MemoryOutbox struct {
	mu    sync.Mutex
	items map[string]Item
}

// NewMemoryOutbox returns an empty in-memory outbox.
func NewMemoryOutbox() *MemoryOutbox {
	return &MemoryOutbox{items: make(map[string]Item)}
}

// Put stores or replaces it.
func (o *MemoryOutbox) Put(it Item) error {
	o.mu.Lock()
	defer o.mu.Unlock()
	o.items[it.Event.ID] = cloneItem(it)
	return nil
}

// List returns the queued items, oldest first.
func (o *MemoryOutbox) List() ([]Item, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	out := make([]Item, 0, len(o.items))
	for _, it := range o.items {
		out = append(out, cloneItem(it))
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Queued.Before(out[j].Queued) })
	return out, nil
}

// Remove deletes the item with this event ID, if present.
func (o *MemoryOutbox) Remove(id string) error {
	o.mu.Lock()
	defer o.mu.Unlock()
	delete(o.items, id)
	return nil
}

func cloneItem(it Item) Item {
	c := it
	c.Relays = append([]string(nil), it.Relays...)
	c.Accepted = make(map[string]bool, len(it.Accepted))
	for k, v := range it.Accepted {
		c.Accepted[k] = v
	}
	return c
}
