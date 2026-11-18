// Package meshexit is the organisation-run Meshtastic exit point: it hears
// Kengele gift wraps on a LoRa mesh, reassembles them, and publishes them
// to Nostr relays. When no relay can be reached it queues them and retries.
//
// The activist's phone wraps each Kengele wire message in a NIP-59 gift wrap
// before it ever touches the radio, so the exit holds no private keys and
// learns nothing a relay would not already see: the recipient key, the size
// and the time of arrival. See docs/meshtastic-exit.md.
//
// Threat model (docs/threat-model.md): organisation exit points are the
// mitigation for total blackouts in section 5 ("Organisation exit points",
// issue #21). The exit is exposed to relay-equivalent observers (A5) and to
// an insider at the organisation running it (A7); section 9 notes that radio
// exits may be restricted in some places, so organisations must check local
// law before transmitting.
//
// Status: design stage. Not for real use until the threat-model review and
// an independent security review are done.
package meshexit
