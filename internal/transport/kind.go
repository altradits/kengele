// Package transport names the routes a Kengele message can travel. Each
// delivery is tagged with the route it arrived on, so the watcher can tell
// "SMS works" apart from "Nostr works" (see docs/shutdown-mode.md).
package transport

import "fmt"

// Kind is a route a message can arrive on.
type Kind uint8

const (
	// Unknown proves nothing about any route. A message tagged Unknown is
	// still processed, but it never counts as evidence that a network works.
	Unknown Kind = iota
	// Nostr: gift-wrapped events through public relays (#8).
	Nostr
	// SMS: the encrypted two-part SMS fallback (#9).
	SMS
	// Mesh is reserved for the Bluetooth mesh (#20, v0.2). Plans cannot
	// enable it yet.
	Mesh
)

var names = [...]string{"unknown", "nostr", "sms", "mesh"}

func (k Kind) String() string {
	if int(k) < len(names) {
		return names[k]
	}
	return fmt.Sprintf("transport(%d)", int(k))
}

// Supported lists the routes a plan may enable today.
var Supported = []Kind{Nostr, SMS}

// IsSupported reports whether k is in Supported.
func IsSupported(k Kind) bool {
	for _, s := range Supported {
		if s == k {
			return true
		}
	}
	return false
}

// Parse turns a plan's transport name into a Kind. Only supported routes
// are accepted.
func Parse(s string) (Kind, error) {
	switch s {
	case "nostr":
		return Nostr, nil
	case "sms":
		return SMS, nil
	case "mesh":
		return Unknown, fmt.Errorf("transport: mesh is not supported yet (#20)")
	}
	return Unknown, fmt.Errorf("transport: unknown transport %q", s)
}
