package meshexit

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func testEntry(id string, at time.Time) Entry {
	return Entry{ID: id, Data: []byte("datagram-" + id), From: "node-1", ReceivedAt: at, Attempts: 1}
}

func listIDs(t *testing.T, q Queue) []string {
	t.Helper()
	list, err := q.List()
	if err != nil {
		t.Fatal(err)
	}
	var ids []string
	for _, e := range list {
		ids = append(ids, e.ID)
	}
	return ids
}

func TestMemoryQueueOrder(t *testing.T) {
	q := NewMemoryQueue()
	now := time.Now().UTC()
	// Newest first on purpose; List must return oldest first.
	for _, e := range []Entry{testEntry("c", now.Add(2*time.Second)), testEntry("a", now), testEntry("b", now.Add(time.Second))} {
		if err := q.Put(e); err != nil {
			t.Fatal(err)
		}
	}
	got := listIDs(t, q)
	if fmt.Sprint(got) != "[a b c]" {
		t.Fatalf("order=%v", got)
	}
	// Put replaces the same id.
	e := testEntry("b", now.Add(time.Hour))
	e.Attempts = 5
	if err := q.Put(e); err != nil {
		t.Fatal(err)
	}
	list, _ := q.List()
	for _, it := range list {
		if it.ID == "b" && it.Attempts != 5 {
			t.Fatal("Put did not replace")
		}
	}
	if err := q.Remove("a"); err != nil {
		t.Fatal(err)
	}
	if err := q.Remove("missing"); err != nil {
		t.Fatal(err) // removing nothing is fine
	}
	if got := listIDs(t, q); fmt.Sprint(got) != "[c b]" {
		t.Fatalf("after remove: %v", got)
	}
}

func TestMemoryQueueRejects(t *testing.T) {
	q := NewMemoryQueue()
	if err := q.Put(Entry{Data: []byte("x")}); err == nil {
		t.Error("empty id accepted")
	}
	if err := q.Put(Entry{ID: "x"}); err == nil {
		t.Error("empty data accepted")
	}
	big := Entry{ID: "x", Data: make([]byte, MaxDatagram+1)}
	if err := q.Put(big); !errors.Is(err, ErrDatagramSize) {
		t.Errorf("oversize: %v", err)
	}
}

func TestMemoryQueueFull(t *testing.T) {
	q := NewMemoryQueue()
	for i := 0; i < MaxQueuedEntries; i++ {
		if err := q.Put(testEntry(fmt.Sprintf("id-%04d", i), time.Now())); err != nil {
			t.Fatal(err)
		}
	}
	if err := q.Put(testEntry("one-more", time.Now())); !errors.Is(err, ErrQueueFull) {
		t.Fatalf("overflow: %v", err)
	}
	// Replacing an existing entry still works when full.
	e := testEntry("id-0000", time.Now())
	e.Attempts = 9
	if err := q.Put(e); err != nil {
		t.Fatal(err)
	}
}

func TestFileQueuePersists(t *testing.T) {
	path := filepath.Join(t.TempDir(), "queue.json")
	q, err := OpenFileQueue(path)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UTC().Truncate(time.Second)
	if err := q.Put(testEntry("a", now)); err != nil {
		t.Fatal(err)
	}
	if err := q.Put(testEntry("b", now.Add(time.Second))); err != nil {
		t.Fatal(err)
	}
	if st, err := os.Stat(path); err != nil || st.Mode().Perm() != 0o600 {
		t.Fatalf("queue file: %v", err)
	}
	again, err := OpenFileQueue(path)
	if err != nil {
		t.Fatal(err)
	}
	list, err := again.List()
	if err != nil {
		t.Fatal(err)
	}
	if len(list) != 2 || list[0].ID != "a" || list[1].ID != "b" || string(list[0].Data) != "datagram-a" {
		t.Fatalf("reopened=%+v", list)
	}
	if err := again.Remove("a"); err != nil {
		t.Fatal(err)
	}
	third, err := OpenFileQueue(path)
	if err != nil {
		t.Fatal(err)
	}
	if got := listIDs(t, third); fmt.Sprint(got) != "[b]" {
		t.Fatalf("after remove: %v", got)
	}
}

func TestFileQueueEmptyAndCorrupt(t *testing.T) {
	dir := t.TempDir()
	missing, err := OpenFileQueue(filepath.Join(dir, "nope.json"))
	if err != nil {
		t.Fatal(err)
	}
	if got := listIDs(t, missing); len(got) != 0 {
		t.Fatalf("missing file: %v", got)
	}
	empty := filepath.Join(dir, "empty.json")
	if err := os.WriteFile(empty, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := OpenFileQueue(empty); err != nil {
		t.Fatalf("empty file: %v", err)
	}
	bad := filepath.Join(dir, "bad.json")
	if err := os.WriteFile(bad, []byte("{nope"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := OpenFileQueue(bad); err == nil {
		t.Fatal("corrupt file accepted")
	}
}
