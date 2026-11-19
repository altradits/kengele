// Command kengele-talk is a minimal demo chat over Nostr. It proves there
// is no company server in the middle: both sides bring their own keys and
// name the public relays to use (-relays has no default), and the typed
// note travels sealed inside an ordinary check-in event.
//
// Hakuna seva ya kampuni katikati: kila upande unaleta ufunguo wake na
// relays za umma anazochagua (-relays haina chaguo-msingi), na ujumbe
// husafiri ukiwa umefungwa ndani ya check-in ya kawaida.
//
// Not for real use. / Si kwa matumizi halisi.
package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/altradits/kengele/internal/message"
	"github.com/altradits/kengele/internal/transport/nostr"
	"github.com/btcsuite/btcd/btcec/v2"
)

// version is set at build time with -ldflags "-X main.version=...".
var version = "0.0.0-dev"

// sendTimeout bounds one send round across all relays.
const sendTimeout = 60 * time.Second

func main() {
	if err := cli(os.Args[1:], os.Stdout, os.Stderr); err != nil {
		fmt.Fprintln(os.Stderr, "kengele-talk:", err)
		os.Exit(1)
	}
}

func usage(w io.Writer) {
	fmt.Fprintln(w, `usage / matumizi:
  kengele-talk version
  kengele-talk keygen -out key.hex
  kengele-talk send -key key.hex -to <recipient-pubkey-hex> -relays wss://a,wss://b -note "text"
  kengele-talk listen -key key.hex -relays wss://a,wss://b -senders <csv pubkeys>

There is no company server in the middle. Messages are encrypted and travel
over Nostr, an open network that no single company runs. Both sides bring
their own keys and name their own relays: -relays is required and has no
default, and that is the proof nobody central runs it.
Hakuna seva ya kampuni katikati. Ujumbe umesimbwa na husafiri kupitia Nostr,
mtandao wa wazi ambao hakuna kampuni moja unaouendesha. Kila upande unaleta
ufunguo wake na relay zake: -relays inahitajika na haina chaguo-msingi, na
hiyo ndiyo uthibitisho kwamba hakuna wa kati anayeiuendesha.

A note is at most 86 bytes and travels inside an ordinary check-in event.
Ujumbe ni baiti 86 kwa zaidi na husafiri ndani ya ripoti ya kawaida.
Not for real use. / Si kwa matumizi halisi.`)
}

func cli(args []string, out, warn io.Writer) error {
	if len(args) == 0 {
		usage(out)
		return errors.New("no command / hakuna amri")
	}
	switch args[0] {
	case "version", "-version", "--version":
		fmt.Fprintln(out, "kengele-talk", version)
		return nil
	case "keygen":
		return keygenCmd(args[1:], out)
	case "send":
		return sendCmd(args[1:], out, warn)
	case "listen":
		return listenCmd(args[1:], out, warn)
	case "help", "-h", "--help":
		usage(out)
		return nil
	}
	usage(out)
	return fmt.Errorf("unknown command %q / amri %q haijulikani", args[0], args[0])
}

// splitCSV splits a comma-separated flag value, dropping blanks.
func splitCSV(s string) []string {
	var out []string
	for _, p := range strings.Split(s, ",") {
		if t := strings.TrimSpace(p); t != "" {
			out = append(out, t)
		}
	}
	return out
}

// parsePubkey decodes a 64-character x-only public key.
func parsePubkey(flag, s string) ([32]byte, error) {
	var k [32]byte
	b, err := hex.DecodeString(strings.TrimSpace(s))
	if err != nil || len(b) != 32 {
		return k, fmt.Errorf("bad %s: want 64 hex characters / %s si sahihi: inahitaji herufi 64 za heksi", flag, flag)
	}
	copy(k[:], b)
	return k, nil
}

// parseSenders decodes the -senders list. At least one sender is required:
// the transport drops anything from an unknown key.
func parseSenders(csv string) ([][32]byte, error) {
	parts := splitCSV(csv)
	if len(parts) == 0 {
		return nil, errors.New("listen needs -senders / listen inahitaji -senders")
	}
	out := make([][32]byte, 0, len(parts))
	for _, p := range parts {
		k, err := parsePubkey("-senders", p)
		if err != nil {
			return nil, err
		}
		out = append(out, k)
	}
	return out, nil
}

// loadKey reads a hex private key from path. A world-readable key file
// earns a warning on warn: anyone on the machine can read the key.
func loadKey(warn io.Writer, path string) (*btcec.PrivateKey, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	if st, err := os.Stat(path); err == nil && st.Mode().Perm()&0o004 != 0 {
		fmt.Fprintln(warn, "warning: key file is world-readable; run chmod 600 on it / onyo: faili la ufunguo linasomeka na wote; tumia chmod 600")
	}
	b, err := hex.DecodeString(strings.TrimSpace(string(raw)))
	if err != nil || len(b) != 32 {
		return nil, errors.New("bad key file: want 64 hex characters / faili la ufunguo si sahihi: inahitaji herufi 64 za heksi")
	}
	zero := true
	for _, c := range b {
		if c != 0 {
			zero = false
			break
		}
	}
	if zero {
		return nil, errors.New("bad key file: key is zero / faili la ufunguo si sahihi: ufunguo ni sifuri")
	}
	key, _ := btcec.PrivKeyFromBytes(b)
	return key, nil
}

// fingerprint is the short public name of a key: its first 8 hex characters.
// Transcripts cite fingerprints so full keys stay out of logs.
// queuedLine reports a send that reached no relay. Pure so tests pin the
// honest wording: nothing about this demo retries after it exits.
func queuedLine(from, to [32]byte, accepted, total int) string {
	return fmt.Sprintf("sent from %s to %s: accepted by %d of %d relays, not delivered: this demo does not retry after it exits / imetumwa kutoka %s kwenda %s: relay %d kati ya %d zimeikubali, haikutumwa: demo hii haijaribu tena inapoisha",
		fingerprint(from), fingerprint(to), accepted, total, fingerprint(from), fingerprint(to), accepted, total)
}

func fingerprint(pub [32]byte) string {
	return hex.EncodeToString(pub[:4])
}

// lastSeq keeps sequence numbers unique when several events are created
// in the same second: receivers drop a repeated (signer, seq) as a replay,
// so a second send in the same second must not reuse the first one's seq.
var lastSeq uint32

func nextSeq(now time.Time) uint32 {
	s := uint32(now.Unix())
	if s <= lastSeq {
		s = lastSeq + 1
	}
	lastSeq = s
	return s
}

// buildCheckIn packs the typed note into an ordinary check-in event. The
// note is at most message.MaxBodySize bytes; the sequence number rises
// with every event this CLI creates.
func buildCheckIn(note string, now time.Time) (message.Event, error) {
	if len(note) > message.MaxBodySize {
		return message.Event{}, fmt.Errorf("note is %d bytes, max %d / ujumbe ni baiti %d, kiwango %d", len(note), message.MaxBodySize, len(note), message.MaxBodySize)
	}
	e := message.Event{
		Kind:      message.KindCheckIn,
		CreatedAt: now,
		Seq:       nextSeq(now),
	}
	if note != "" {
		e.Body = []byte(note)
	}
	return e, nil
}

func keygenCmd(args []string, out io.Writer) error {
	fs := flag.NewFlagSet("keygen", flag.ContinueOnError)
	dst := fs.String("out", "", "file to write the hex private key to")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *dst == "" {
		return errors.New("keygen needs -out / keygen inahitaji -out")
	}
	var seed [32]byte
	if _, err := rand.Read(seed[:]); err != nil {
		return err
	}
	key, _ := btcec.PrivKeyFromBytes(seed[:])
	if key == nil {
		return errors.New("keygen failed / keygen imeshindwa")
	}
	if err := os.WriteFile(*dst, []byte(hex.EncodeToString(key.Serialize())+"\n"), 0o600); err != nil {
		return err
	}
	// WriteFile only sets the mode for new files; force 0600 in case the
	// file already existed with wider permissions.
	if err := os.Chmod(*dst, 0o600); err != nil {
		return err
	}
	pub := message.PublicKey(key)
	fmt.Fprintf(out, "wrote %s (mode 0600). pubkey %s, fingerprint %s\n", *dst, hex.EncodeToString(pub[:]), fingerprint(pub))
	fmt.Fprintf(out, "Keep the key file secret. / Weka faili la ufunguo kwa siri.\n")
	return nil
}

func sendCmd(args []string, out, warn io.Writer) error {
	fs := flag.NewFlagSet("send", flag.ContinueOnError)
	keyFile := fs.String("key", "", "file holding your hex private key")
	toFlag := fs.String("to", "", "recipient's 64-hex-character public key")
	relaysFlag := fs.String("relays", "", "comma-separated Nostr relay URLs (required, no default) / relay za Nostr (inahitajika, haina chaguo-msingi)")
	note := fs.String("note", "", "note to send, at most 86 bytes")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *keyFile == "" {
		return errors.New("send needs -key / send inahitaji -key")
	}
	if *toFlag == "" {
		return errors.New("send needs -to / send inahitaji -to")
	}
	relays := splitCSV(*relaysFlag)
	if len(relays) == 0 {
		return errors.New("send needs -relays: name the relays to use, there is no default / send inahitaji -relays: taja relay za kutumia, hakuna chaguo-msingi")
	}
	key, err := loadKey(warn, *keyFile)
	if err != nil {
		return err
	}
	to, err := parsePubkey("-to", *toFlag)
	if err != nil {
		return err
	}
	ev, err := buildCheckIn(*note, time.Now())
	if err != nil {
		return err
	}
	wire, err := message.Seal(ev, key, to)
	if err != nil {
		return err
	}
	nt, err := nostr.New(key, nostr.Config{Relays: relays})
	if err != nil {
		return err
	}
	defer nt.Close()
	ctx, cancel := context.WithTimeout(context.Background(), sendTimeout)
	defer cancel()
	res, err := nt.Send(ctx, wire, to)
	if err != nil {
		return err
	}
	from := message.PublicKey(key)
	if res.Queued {
		// The outbox is memory-only and this process exits here, so a
		// queued message is never retried: say it was not delivered.
		fmt.Fprintln(out, queuedLine(from, to, res.Accepted, len(relays)))
		return errors.New("send failed: no relay accepted the message / ujumbe haujafika relay yoyote")
	}
	fmt.Fprintf(out, "sent from %s to %s: accepted by %d of %d relays / imetumwa kutoka %s kwenda %s: relay %d kati ya %d zimeikubali\n",
		fingerprint(from), fingerprint(to), res.Accepted, len(relays), fingerprint(from), fingerprint(to), res.Accepted, len(relays))
	return nil
}

func listenCmd(args []string, out, warn io.Writer) error {
	fs := flag.NewFlagSet("listen", flag.ContinueOnError)
	keyFile := fs.String("key", "", "file holding your hex private key")
	relaysFlag := fs.String("relays", "", "comma-separated Nostr relay URLs (required, no default) / relay za Nostr (inahitajika, haina chaguo-msingi)")
	sendersFlag := fs.String("senders", "", "comma-separated sender public keys to accept")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *keyFile == "" {
		return errors.New("listen needs -key / listen inahitaji -key")
	}
	relays := splitCSV(*relaysFlag)
	if len(relays) == 0 {
		return errors.New("listen needs -relays: name the relays to use, there is no default / listen inahitaji -relays: taja relay za kutumia, hakuna chaguo-msingi")
	}
	senders, err := parseSenders(*sendersFlag)
	if err != nil {
		return err
	}
	key, err := loadKey(warn, *keyFile)
	if err != nil {
		return err
	}
	nt, err := nostr.New(key, nostr.Config{Relays: relays, Senders: senders})
	if err != nil {
		return err
	}
	defer nt.Close()
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	fmt.Fprintf(out, "listening on %d relays for %d senders (Ctrl-C to stop) / inasikiliza kwenye relay %d kutoka watumaji %d (Ctrl-C kusimamisha)\n", len(relays), len(senders), len(relays), len(senders))
	for d := range nt.Receive(ctx) {
		if d.Event == nil {
			continue
		}
		fmt.Fprintln(out, formatDelivery(d))
	}
	return nil
}

// formatDelivery renders one received note with the sender fingerprint and
// the signed time.
func formatDelivery(d nostr.Delivery) string {
	return fmt.Sprintf("%s from %s: %s",
		d.Event.CreatedAt.UTC().Format(time.RFC3339), fingerprint(d.Event.Signer), string(d.Event.Body))
}
