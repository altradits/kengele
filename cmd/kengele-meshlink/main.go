// Command kengele-meshlink demonstrates Kengele's blind crowd carry on
// a simulated 3-node chain: A broadcasts, B relays bytes it cannot
// read, C reassembles and opens. Everything runs in memory; no radio,
// no network, no real keys.
//
// The demo seals a made-up check-in, so B's log lines carry hop
// control only (message id, part, TTL) while C prints the opened
// event. With --drop B the middle node discards everything and the run
// ends in honest silence. The qr subcommand prints the same datagram
// as the 304-character text form a human courier could carry as a QR
// code (see docs/offgrid-comms.md).
//
// kengele-meshlink never promises delivery: a destroyed or isolated
// phone with no peers nearby sends nothing, and this demo shows that
// case on purpose.
package main

import (
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"strings"
	"time"

	"github.com/altradits/kengele/internal/meshcarry"
	"github.com/altradits/kengele/internal/message"
	"github.com/btcsuite/btcd/btcec/v2"
)

// version is set at build time with -ldflags "-X main.version=...".
var version = "0.0.0-dev"

// Made-up demo keys. Never use these for anything real.
const (
	demoActivistHex = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"
	demoGuardianHex = "b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2"
)

// demoBody is the made-up check-in body the demo seals.
const demoBody = "Mtihani-trill"

// errMisuse marks flag misuse; main maps it to exit 2.
var errMisuse = errors.New("misuse")

// uiStrings is the English/Swahili table for the demo. The first five
// rows are the shared Bluetooth-carry strings (kept identical to
// docs/mesh-bitchat.md "Planned user-facing strings" plus the two this
// demo adds); the rest are this demo's line formats.
type uiStrings struct {
	broadcastTitle  string // Broadcast over Bluetooth
	meshNearby      string // Mesh: %d phones nearby
	bluetoothOff    string // Bluetooth is off... (Android string; the demo's "radio" is always on)
	relayingBlindly string // Relaying blindly
	noPathFailsSafe string // No path — silence fails safe

	title       string // demo banner
	blindNote   string // B forwards bytes without reading them
	broadcast   string // A broadcast msg 0x123456 frag 0/2 ttl 7
	relay       string // B relay msg 0x123456 frag 0/2 ttl 6 (blind: no decrypt)
	opaque      string // B reassembled 228 opaque bytes (blind: no decrypt)
	reassembled string // C reassembled 228B
	delivered   string // C delivered: kind=check-in seq=1 created=<ts> hops=2
	received0   string // C received 0 (no path); silence fails safe
	dropping    string // B drops everything (--drop B)
	qrShow      string // Show this QR / Onyesha QR hii
}

var enStrings = uiStrings{
	broadcastTitle:  "Broadcast over Bluetooth",
	meshNearby:      "Mesh: %d phones nearby",
	bluetoothOff:    "Bluetooth is off. Turn it on to reach nearby phones.",
	relayingBlindly: "Relaying blindly",
	noPathFailsSafe: "No path — silence fails safe",

	title:       "kengele-meshlink demo: A->B->C, blind carry, in-memory (no radio)",
	blindNote:   "B forwards bytes without reading them",
	broadcast:   "%s broadcast msg 0x%06x frag %d/%d ttl %d",
	relay:       "%s relay msg 0x%06x frag %d/%d ttl %d (blind: no decrypt)",
	opaque:      "%s reassembled %d opaque bytes (blind: no decrypt)",
	reassembled: "%s reassembled %dB",
	delivered:   "%s delivered: kind=%s seq=%d created=%s hops=%d",
	received0:   "%s received 0 (no path); silence fails safe",
	dropping:    "%s drops everything (--drop %s)",
	qrShow:      "Show this QR / Onyesha QR hii",
}

var swStrings = uiStrings{
	broadcastTitle:  "Tangaza kwa Bluetooth",
	meshNearby:      "Mesh: simu %d karibu",
	bluetoothOff:    "Bluetooth imezima. Iwashe ili kufikia simu zilizo karibu.",
	relayingBlindly: "Inasambaza bila kusoma",
	noPathFailsSafe: "Hakuna njia — kimya ni salama",

	title:       "demo ya kengele-meshlink: A->B->C, usambazaji wa upofu, kwenye kumbukumbu (hakuna redio)",
	blindNote:   "B inasambaza baiti bila kuzisoma",
	broadcast:   "%s imetangaza msg 0x%06x kipande %d/%d ttl %d",
	relay:       "%s inasambaza msg 0x%06x kipande %d/%d ttl %d (bila kusoma)",
	opaque:      "%s imeunganisha baiti %d za siri (bila kusoma)",
	reassembled: "%s imeunganisha %dB",
	delivered:   "%s imepokea: kind=%s seq=%d created=%s hops=%d",
	received0:   "%s imepokea 0 (hakuna njia); kimya ni salama",
	dropping:    "%s inatupa kila kitu (--drop %s)",
	qrShow:      "Show this QR / Onyesha QR hii",
}

func main() {
	if err := cli(os.Args[1:], os.Stdout); err != nil {
		fmt.Fprintln(os.Stderr, "kengele-meshlink:", err)
		if errors.Is(err, errMisuse) {
			os.Exit(2)
		}
		os.Exit(1)
	}
}

func usage(w io.Writer) {
	fmt.Fprintln(w, `usage: / matumizi:
  kengele-meshlink demo [--ttl 7] [--drop B] [--sw]
  kengele-meshlink qr
  kengele-meshlink version

demo runs a 3-node A->B->C blind-carry chain in memory; --drop lists
nodes that discard everything (proves silence fails safe); --sw prints
Swahili strings. qr prints the demo datagram as 304 characters for a
QR courier.`)
}

func cli(args []string, out io.Writer) error {
	if len(args) == 1 && (args[0] == "version" || args[0] == "-version" || args[0] == "--version") {
		fmt.Fprintln(out, "kengele-meshlink", version)
		return nil
	}
	if len(args) == 1 && (args[0] == "help" || args[0] == "-h" || args[0] == "--help") {
		usage(out)
		return nil
	}
	if len(args) == 0 {
		usage(out)
		return fmt.Errorf("%w: need a subcommand", errMisuse)
	}
	switch args[0] {
	case "demo":
		return demo(args[1:], out)
	case "qr":
		if len(args) != 1 {
			return fmt.Errorf("%w: qr takes no arguments", errMisuse)
		}
		return qr(out)
	default:
		usage(out)
		return fmt.Errorf("%w: unknown subcommand %q", errMisuse, args[0])
	}
}

func demoKey(h string) (*btcec.PrivateKey, error) {
	b, err := hex.DecodeString(h)
	if err != nil {
		return nil, err
	}
	k, _ := btcec.PrivKeyFromBytes(b)
	return k, nil
}

// sealDemo seals the made-up demo check-in for the demo guardian.
func sealDemo(now time.Time) (wire []byte, activist, guardian *btcec.PrivateKey, err error) {
	activist, err = demoKey(demoActivistHex)
	if err != nil {
		return nil, nil, nil, err
	}
	guardian, err = demoKey(demoGuardianHex)
	if err != nil {
		return nil, nil, nil, err
	}
	wire, err = message.Seal(message.Event{
		Kind:      message.KindCheckIn,
		CreatedAt: now,
		Seq:       1,
		Body:      []byte(demoBody),
	}, activist, message.PublicKey(guardian))
	if err != nil {
		return nil, nil, nil, err
	}
	return wire, activist, guardian, nil
}

func demo(args []string, out io.Writer) error {
	fs := flag.NewFlagSet("demo", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	ttl := fs.Int("ttl", int(meshcarry.DefaultTTL), "broadcast TTL / TTL ya matangazo")
	drop := fs.String("drop", "", "comma-separated nodes that discard everything / nodi zinazotupa kila kitu")
	sw := fs.Bool("sw", false, "Swahili strings / maneno ya Kiswahili")
	if err := fs.Parse(args); err != nil {
		return fmt.Errorf("%w: %v", errMisuse, err)
	}
	if fs.NArg() != 0 {
		return fmt.Errorf("%w: demo takes no positional arguments", errMisuse)
	}
	if *ttl < 0 || *ttl > 255 {
		return fmt.Errorf("%w: --ttl must be 0..255", errMisuse)
	}
	ui := enStrings
	if *sw {
		ui = swStrings
	}

	sim := meshcarry.NewSimulator()
	for _, id := range []string{"A", "B", "C"} {
		sim.AddNode(id)
	}
	sim.Link("A", "B")
	sim.Link("B", "C")
	sim.SendTTL = uint8(*ttl)
	if *drop != "" {
		for _, id := range strings.Split(*drop, ",") {
			id = strings.TrimSpace(id)
			n := sim.Node(id)
			if n == nil {
				return fmt.Errorf("%w: --drop names unknown node %q (demo nodes are A,B,C)", errMisuse, id)
			}
			n.Drop = true
		}
	}

	now := time.Now().UTC()
	wire, activist, guardian, err := sealDemo(now)
	if err != nil {
		return err
	}

	fmt.Fprintln(out, ui.title)
	fmt.Fprintln(out, ui.broadcastTitle)
	fmt.Fprintf(out, ui.meshNearby+"\n", len(sim.Peers("B")))
	fmt.Fprintln(out, ui.relayingBlindly+": "+ui.blindNote)
	if *drop != "" {
		for _, id := range strings.Split(*drop, ",") {
			fmt.Fprintf(out, ui.dropping+"\n", strings.TrimSpace(id), strings.TrimSpace(id))
		}
	}
	sim.OnTrace = func(ev meshcarry.TraceEvent) {
		switch ev.Kind {
		case meshcarry.TraceBroadcast:
			fmt.Fprintf(out, ui.broadcast+"\n", ev.Node, ev.MsgID, ev.Index, ev.Total, ev.TTL)
		case meshcarry.TraceRelay:
			fmt.Fprintf(out, ui.relay+"\n", ev.Node, ev.MsgID, ev.Index, ev.Total, ev.TTL)
		case meshcarry.TraceReassembled:
			if ev.Node == "C" {
				fmt.Fprintf(out, ui.reassembled+"\n", ev.Node, ev.Bytes)
			} else {
				fmt.Fprintf(out, ui.opaque+"\n", ev.Node, ev.Bytes)
			}
		}
	}
	if err := sim.Inject("A", wire); err != nil {
		return err
	}
	sim.Run(10)

	got := sim.Deliveries("C")
	if len(got) == 0 {
		fmt.Fprintf(out, ui.received0+"\n", "C")
		return nil
	}
	opened, err := message.Open(got[0].Datagram, guardian, [][32]byte{message.PublicKey(activist)}, time.Now())
	if err != nil {
		return fmt.Errorf("C cannot open its delivery: %w", err)
	}
	fmt.Fprintf(out, ui.delivered+"\n", "C", opened.Kind, opened.Seq,
		opened.CreatedAt.UTC().Format(time.RFC3339), got[0].Hops)
	return nil
}

// qr prints the demo datagram as the 304-character text form a human
// courier shows as a QR code in a total blackout.
func qr(out io.Writer) error {
	wire, _, _, err := sealDemo(time.Now().UTC())
	if err != nil {
		return err
	}
	fmt.Fprintln(out, message.EncodeText(wire))
	fmt.Fprintln(out, enStrings.qrShow)
	return nil
}
