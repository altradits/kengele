// Command kengele-exit is the organisation-run Meshtastic exit point. It
// listens on a LoRa mesh for Kengele gift wraps, publishes them to Nostr
// relays, and queues them while no relay can be reached.
//
// An organisation runs this on its own hardware with its own relays.
// Altradits runs no exit nodes. See docs/meshtastic-exit.md.
//
// Not for real use.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/altradits/kengele/internal/meshexit"
)

// version is set at build time with -ldflags "-X main.version=...".
var version = "0.0.0-dev"

func main() {
	if err := cli(os.Args[1:], os.Stdout); err != nil {
		fmt.Fprintln(os.Stderr, "kengele-exit:", err)
		os.Exit(1)
	}
}

func usage(w io.Writer) {
	fmt.Fprintln(w, `usage / matumizi:
  kengele-exit version
  kengele-exit run -mesh sim|tcp://host:port|serial:/dev/ttyUSB0 -relays wss://a,wss://b [-queue queue.json] [-min-accepted 1] [-tick 30s]

Forwards Kengele gift wraps from a Meshtastic mesh to Nostr relays.
Husambaza vifurushi vilivyofungwa vya Kengele kutoka mesh ya Meshtastic kwenda relay za Nostr.
Not for real use. / Si kwa matumizi halisi.`)
}

func cli(args []string, out io.Writer) error {
	if len(args) == 0 {
		usage(out)
		return errors.New("no command / hakuna amri")
	}
	switch args[0] {
	case "version", "-version", "--version":
		fmt.Fprintln(out, "kengele-exit", version)
		return nil
	case "run":
		return runCmd(args[1:], out)
	case "help", "-h", "--help":
		usage(out)
		return nil
	}
	usage(out)
	return fmt.Errorf("unknown command %q / amri %q haijulikani", args[0], args[0])
}

func runCmd(args []string, out io.Writer) error {
	fs := flag.NewFlagSet("run", flag.ContinueOnError)
	meshSpec := fs.String("mesh", "", "mesh to listen on: sim, tcp://host:port or serial:/path / mesh ya kusikiliza: sim, tcp://host:port au serial:/path")
	relays := fs.String("relays", "", "comma-separated Nostr relay URLs / anwani za relay za Nostr zikitenganishwa na koma")
	queuePath := fs.String("queue", "", "queue file for messages Nostr cannot take yet (empty: memory only) / faili la foleni kwa ujumbe ambao Nostr haijapokea bado (tupu: kumbukumbu tu)")
	minAccepted := fs.Int("min-accepted", 1, "relays that must accept a message / relay zinazopaswa kukubali ujumbe")
	every := fs.Duration("tick", 30*time.Second, "how often to retry the queue / kila baada ya muda gani kujaribu foleni tena")
	reasmTimeout := fs.Duration("reassemble-timeout", 10*time.Minute, "how long to wait for missing fragments / kusubiri kwa muda gani vipande vilivyopotea")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *meshSpec == "" {
		return errors.New("run needs -mesh / run inahitaji -mesh")
	}
	relayList := splitList(*relays)
	if len(relayList) == 0 {
		return errors.New("run needs -relays / run inahitaji -relays")
	}
	if *minAccepted < 1 || *minAccepted > len(relayList) {
		return errors.New("bad -min-accepted / -min-accepted si sahihi (1..relays)")
	}
	mesh, err := openMesh(*meshSpec)
	if err != nil {
		return err
	}
	defer mesh.Close()
	var q meshexit.Queue = meshexit.NewMemoryQueue()
	if *queuePath != "" {
		fq, err := meshexit.OpenFileQueue(*queuePath)
		if err != nil {
			return err
		}
		q = fq
	}
	pub := &meshexit.RelayPublisher{Relays: relayList, MinAccepted: *minAccepted}
	ex, err := meshexit.New(mesh, pub, meshexit.Config{
		Queue:             q,
		RetryInterval:     *every,
		ReassembleTimeout: *reasmTimeout,
	})
	if err != nil {
		return err
	}
	fmt.Fprintf(out, "kengele-exit %s: mesh %s, %d relays (not for real use / si kwa matumizi halisi)\n", version, *meshSpec, len(relayList))
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	return ex.Run(ctx, out)
}

func openMesh(spec string) (meshexit.Mesh, error) {
	if spec == "sim" {
		return meshexit.NewSimulator(meshexit.SimConfig{}), nil
	}
	if addr, ok := strings.CutPrefix(spec, "tcp://"); ok {
		if addr == "" {
			return nil, errors.New("bad -mesh tcp address / anwani ya -mesh si sahihi")
		}
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		return meshexit.DialTCP(ctx, addr)
	}
	if path, ok := strings.CutPrefix(spec, "serial:"); ok {
		if path == "" {
			return nil, errors.New("bad -mesh serial path / njia ya -mesh si sahihi")
		}
		return meshexit.OpenSerial(path)
	}
	return nil, fmt.Errorf("bad -mesh %q, want sim, tcp://host:port or serial:/path / -mesh %q si sahihi", spec, spec)
}

func splitList(s string) []string {
	var out []string
	for _, p := range strings.Split(s, ",") {
		if t := strings.TrimSpace(p); t != "" {
			out = append(out, t)
		}
	}
	return out
}
