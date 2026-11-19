// Package meshcarry is the transport-agnostic core of Kengele's Bluetooth
// crowd carry: it splits the opaque 228-byte sealed wire message
// (docs/message-format.md) into radio-sized fragments, relays them
// without ever decrypting, and reassembles them at the far end.
//
// The relay path is cryptographically blind. Fragments carry no sender
// key, no event kind, no guardian identity and no body: only a magic,
// a version, a TTL, part numbers and a random message id. A relaying
// phone cannot tell a panic from a check-in, because every kind seals
// to the same 228 bytes. This package does not import internal/message
// on purpose (see TestNoMessageImport): if the relay code cannot even
// name the decryptor, it cannot call it.
//
// Threat model (docs/threat-model.md): carriers are consenting app
// users only; recipients are guardians, never authorities (A1 state
// agents may be abductors, so no authority routing exists anywhere).
// Everything is memory-only so a seized phone (A2) yields no carry
// queue from disk. Nearby observers (A5) and shutdown adversaries (A6)
// are assumed to read, store and replay every byte, which is why the
// bytes stay sealed end to end and carry no metadata beyond hop control.
//
// What this package never promises: delivery. A destroyed or isolated
// phone with no peers nearby sends nothing, and every simulation runs
// silent in that case rather than erroring. Silence fails safe: the
// guardians' clocks, not the mesh, raise the alarm.
//
// Integration layers tag meshcarry deliveries as transport.Mesh so the
// watcher can tell mesh carriage apart from Nostr or SMS; the core
// itself takes no dependency, so the relay path stays blind.
package meshcarry
