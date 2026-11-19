# DRAFT: message to the bitchat maintainers (never sent from this repo)

Status: **draft for review.** Do not send, email or publish. It is kept
here so reviewers can agree the wording first; see the TODO in
[mesh-bitchat.md](mesh-bitchat.md). No real names, keys or contacts
below.

---

Subject: Request: a relay-only payload type for activist safety alarms (Kengele)

Hello bitchat team,

We are building Kengele, a free and open-source activist safety tool:
an activist checks in on a schedule, and if they go silent their
guardians are alerted. Design, threat model and code are public
([repo link to add]). We deliberately build no mesh of our own: our
offline layer is meant to ride bitchat.

What we would like to carry is one small, fixed-size packet: 228 bytes,
signed and encrypted by us, delay-tolerant (our watcher deduplicates by
sequence number, so late delivery is normal). At a protest this would
hop across nearby bitchat phones until one of them reaches a network.

What we are asking:

1. A payload type or app tag of your choosing that bitchat clients
   **relay like any other packet but never display** as a chat message.
2. Your rules for it: size caps, TTL, caching behavior, anything you
   want us never to do (volume limits, no-go payload shapes).
3. Whether you would rather we talk to a different channel first (an
   issue on bitchat-android, a discussion, or email).

What we promise in return:

- No fork, no competing mesh, no changes to your protocol without your
  agreement. If you say no, we stay Kengele-to-Kengele only.
- Tiny footprint: our traffic is rare (alarms first, check-ins only in
  shutdowns) and always the same 228 bytes.
- Full credit in our docs, and drill results (delivery rate, hops,
  battery observations) shared back with you.

Thank you for bitchat, and for considering this. Happy to answer any
questions about our format or threat model first.

— [name], Kengele ([contact to add])

---

Reviewer checklist before anyone sends this:

- [ ] Threat-model review is in progress or done, so our claims hold.
- [ ] Repo link, sender name and contact are filled in.
- [ ] The team agrees on fallback (option 2 or 3 in mesh-bitchat.md) if
  the answer is no.
