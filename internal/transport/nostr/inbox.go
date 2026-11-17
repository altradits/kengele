package nostr

import (
	"context"
	"encoding/hex"
	"errors"
	"time"

	"github.com/btcsuite/btcd/btcec/v2"
	gn "github.com/nbd-wtf/go-nostr"
)

// InboxRelayList builds a signed NIP-17 kind 10050 event that tells senders
// which relays to deliver gift wraps to. Publishing it links this key to
// these relays in public; see docs/nostr-transport.md.
func InboxRelayList(key *btcec.PrivateKey, relays []string, now time.Time) (gn.Event, error) {
	if key == nil {
		return gn.Event{}, errors.New("nostr: no key")
	}
	urls := normalizeAll(relays)
	if len(urls) == 0 {
		return gn.Event{}, ErrNoRelays
	}
	ev := gn.Event{
		Kind:      gn.KindDMRelayList,
		CreatedAt: gn.Timestamp(now.Unix()),
		Tags:      make(gn.Tags, 0, len(urls)),
	}
	for _, u := range urls {
		ev.Tags = append(ev.Tags, gn.Tag{"relay", u})
	}
	if err := ev.Sign(hex.EncodeToString(key.Serialize())); err != nil {
		return gn.Event{}, err
	}
	return ev, nil
}

// ParseInboxRelays checks that ev is a validly signed kind 10050 list by
// author and returns its relays.
func ParseInboxRelays(ev *gn.Event, author [32]byte) ([]string, error) {
	if ev == nil || ev.Kind != gn.KindDMRelayList || ev.PubKey != hex.EncodeToString(author[:]) {
		return nil, ErrMalformed
	}
	if ok, _ := ev.CheckSignature(); !ok {
		return nil, ErrMalformed
	}
	var urls []string
	for _, t := range ev.Tags {
		if len(t) >= 2 && t[0] == "relay" {
			urls = append(urls, t[1])
		}
	}
	urls = normalizeAll(urls)
	if len(urls) == 0 {
		return nil, ErrNoRelays
	}
	return urls, nil
}

// LookupInboxRelays asks the configured relays for recipient's newest kind
// 10050 list and returns its relays. It does not change the transport; pass
// the result to SetInboxRelays.
func (t *Transport) LookupInboxRelays(ctx context.Context, recipient [32]byte) ([]string, error) {
	var newest *gn.Event
	for _, u := range t.cfg.Relays {
		qctx, cancel := context.WithTimeout(ctx, t.cfg.PublishTimeout)
		c, err := t.dial(qctx, u)
		if err != nil {
			cancel()
			continue
		}
		evs, err := c.querySync(qctx, gn.Filter{
			Kinds:   []int{gn.KindDMRelayList},
			Authors: []string{hex.EncodeToString(recipient[:])},
			Limit:   1,
		})
		c.close()
		cancel()
		if err != nil {
			continue
		}
		for _, ev := range evs {
			if _, err := ParseInboxRelays(ev, recipient); err != nil {
				continue
			}
			if newest == nil || ev.CreatedAt > newest.CreatedAt {
				newest = ev
			}
		}
	}
	if newest == nil {
		return nil, ErrNoRelays
	}
	return ParseInboxRelays(newest, recipient)
}
