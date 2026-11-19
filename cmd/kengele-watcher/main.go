// Command kengele-watcher is the guardian-side watcher. It follows the
// activists in an encrypted watch plan and raises the alarm when a
// check-in is missed, pausing an activist's clock while every transport
// that activist uses is down for the guardian.
//
// Without -relays it runs the deadline engine against a transport that
// delivers nothing, so it is only useful for trying out plans (not for
// real use). With -relays and -key-file it follows the plan over the
// real Nostr transport and can run an alert hook per alarming notice.
package main

import (
	"context"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"os/exec"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/altradits/kengele/internal/message"
	"github.com/altradits/kengele/internal/schedule"
	"github.com/altradits/kengele/internal/transport"
	"github.com/altradits/kengele/internal/transport/nostr"
	"github.com/btcsuite/btcd/btcec/v2"
)

// version is set at build time with -ldflags "-X main.version=...".
var version = "0.0.0-dev"

const passEnv = "KENGELE_PASSPHRASE"

func main() {
	if err := cli(os.Args[1:], os.Stdout, os.Getenv(passEnv)); err != nil {
		fmt.Fprintln(os.Stderr, "kengele-watcher:", err)
		os.Exit(1)
	}
}

func usage(w io.Writer) {
	fmt.Fprintln(w, `usage: / matumizi:
  kengele-watcher version
  kengele-watcher seal-plan -in plan.json -out plan.kgp
  kengele-watcher run -plan plan.kgp [-tick 30s] [-offline] [-down nostr,sms] [-relays wss://a,wss://b -key-file guardian.key] [-alert-hook ./hook.sh]

The plan passphrase is read from `+passEnv+`.

Without -relays the watcher runs a demo with no transport (not for real use).
Bila -relays, mlinzi anaendesha onyesho bila transport (si kwa matumizi halisi).
There is no SMS sender yet: SMS follows -offline/-down only.
Hakuna mtumaji wa SMS bado: SMS inafuata -offline/-down tu.`)
}

func cli(args []string, out io.Writer, pass string) error {
	if len(args) == 0 {
		usage(out)
		return errors.New("no command / hakuna amri")
	}
	switch args[0] {
	case "version", "-version", "--version":
		fmt.Fprintln(out, "kengele-watcher", version)
		return nil
	case "seal-plan":
		return sealPlan(args[1:], out, pass)
	case "run":
		return runCmd(args[1:], out, pass)
	case "help", "-h", "--help":
		usage(out)
		return nil
	}
	usage(out)
	return fmt.Errorf("unknown command %q / amri %q haijulikani", args[0], args[0])
}

func sealPlan(args []string, out io.Writer, pass string) error {
	fs := flag.NewFlagSet("seal-plan", flag.ContinueOnError)
	in := fs.String("in", "", "plain JSON plan / mpango wa JSON ambao haujasimbwa")
	dst := fs.String("out", "", "encrypted plan file to write / faili la mpango lililosimbwa la kuandika")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *in == "" || *dst == "" {
		return errors.New("seal-plan needs -in and -out / seal-plan inahitaji -in na -out")
	}
	if pass == "" {
		return errors.New(passEnv + " is not set / haijawekwa")
	}
	plain, err := os.ReadFile(*in)
	if err != nil {
		return err
	}
	sealed, err := schedule.SealPlan(plain, []byte(pass))
	if err != nil {
		return err
	}
	if err := os.WriteFile(*dst, sealed, 0o600); err != nil {
		return err
	}
	fmt.Fprintf(out, "wrote %s; delete %s now, it is not encrypted / imeandika %s; futa %s sasa, haijasimbwa\n", *dst, *in, *dst, *in)
	return nil
}

func runCmd(args []string, out io.Writer, pass string) error {
	fs := flag.NewFlagSet("run", flag.ContinueOnError)
	planPath := fs.String("plan", "", "encrypted plan file / faili la mpango lililosimbwa")
	every := fs.Duration("tick", 30*time.Second, "how often to check deadlines / kila baada ya muda gani kuangalia kama muda umeisha")
	offline := fs.Bool("offline", false, "pretend every transport is down (shutdown mode) / iga kwamba kila transport imekatika (hali ya mtandao kukatika)")
	downList := fs.String("down", "", "pretend these transports are down, comma separated (nostr, sms) / iga kwamba transport hizi zimekatika, zikitenganishwa na koma (nostr, sms)")
	relaysFlag := fs.String("relays", "", "comma-separated Nostr relay URLs for live mode (empty runs the offline demo) / relay za Nostr kwa modi halisi (tupu inaendesha onyesho)")
	keyFile := fs.String("key-file", "", "file holding the guardian's hex Nostr private key, for live mode / faili lenye ufunguo wa Nostr wa mlinzi, kwa modi halisi")
	alertHook := fs.String("alert-hook", "", "executable run once per alarming notice / programu inayoendeshwa kwa kila tahadhari")
	if err := fs.Parse(args); err != nil {
		return err
	}
	p := staticProbe{}
	if *offline {
		for _, k := range transport.Supported {
			p[k] = true
		}
	}
	if *downList != "" {
		for _, name := range strings.Split(*downList, ",") {
			k, err := transport.Parse(strings.TrimSpace(name))
			if err != nil {
				return err
			}
			p[k] = true
		}
	}
	if *planPath == "" {
		return errors.New("run needs -plan / run inahitaji -plan")
	}
	if pass == "" {
		return errors.New(passEnv + " is not set / haijawekwa")
	}
	data, err := os.ReadFile(*planPath)
	if err != nil {
		return err
	}
	plan, err := schedule.OpenPlan(data, []byte(pass))
	if err != nil {
		return err
	}
	relays := splitRelays(*relaysFlag)
	if len(relays) == 0 {
		ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
		defer stop()
		ticks := time.NewTicker(*every)
		defer ticks.Stop()
		return runLoop(ctx, plan, ticks.C, time.Now, p, nopTransport{}, out, runOpts{hook: *alertHook})
	}
	if *keyFile == "" {
		return errors.New("run with -relays needs -key-file / run na -relays inahitaji -key-file")
	}
	key, err := loadGuardianKey(os.Stderr, *keyFile)
	if err != nil {
		return err
	}
	senders := make([][32]byte, 0, len(plan.Activists))
	for _, a := range plan.Activists {
		k, err := a.Key()
		if err != nil {
			return err
		}
		senders = append(senders, k)
	}
	conn := &connectivity{}
	nt, err := nostr.New(key, nostr.Config{Relays: relays, Senders: senders, Connectivity: conn})
	if err != nil {
		return err
	}
	defer nt.Close()
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	ticks := time.NewTicker(*every)
	defer ticks.Stop()
	return runLoop(ctx, plan, ticks.C, time.Now, liveProbe{down: p, conn: conn}, nostrReceiver{ch: nt.Receive(ctx)}, out, runOpts{live: true, relays: len(relays), hook: *alertHook})
}

// splitRelays splits a comma-separated -relays value, dropping blanks.
func splitRelays(s string) []string {
	var out []string
	for _, p := range strings.Split(s, ",") {
		if t := strings.TrimSpace(p); t != "" {
			out = append(out, t)
		}
	}
	return out
}

// loadGuardianKey reads the guardian's hex Nostr private key from path. A
// world-readable key file earns a warning on warn: anyone on the machine
// can read the key.
func loadGuardianKey(warn io.Writer, path string) (*btcec.PrivateKey, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	if st, err := os.Stat(path); err == nil && st.Mode().Perm()&0o004 != 0 {
		fmt.Fprintln(warn, "warning: -key-file is world-readable; run chmod 600 on it / onyo: -key-file inasomeka na wote; tumia chmod 600")
	}
	b, err := hex.DecodeString(strings.TrimSpace(string(raw)))
	if err != nil || len(b) != 32 {
		return nil, errors.New("bad -key-file: want 64 hex characters / -key-file si sahihi: inahitaji herufi 64 za heksi")
	}
	zero := true
	for _, c := range b {
		if c != 0 {
			zero = false
			break
		}
	}
	if zero {
		return nil, errors.New("bad -key-file: key is zero / -key-file si sahihi: ufunguo ni sifuri")
	}
	key, _ := btcec.PrivKeyFromBytes(b)
	return key, nil
}

// probe reports, per transport, whether the guardian can use it. staticProbe
// is fixed from -offline/-down; liveProbe follows the Nostr transport's
// connectivity signal for Nostr.
type probe interface{ Up(transport.Kind) bool }

// staticProbe lists the transports that are down; the rest are up.
type staticProbe map[transport.Kind]bool

func (p staticProbe) Up(k transport.Kind) bool { return !p[k] }

// connectivity tracks the last RelayStatus from the Nostr transport. Nostr
// counts as up until the first report, then while at least one relay is
// reachable.
type connectivity struct {
	mu   sync.Mutex
	seen bool
	up   bool
}

// RelayStatus implements nostr.Connectivity. It must not block.
func (c *connectivity) RelayStatus(s nostr.Status) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.seen = true
	c.up = s.Reachable > 0
}

// Up reports whether Nostr counts as up: true until the first report, then
// whether any relay was reachable.
func (c *connectivity) Up() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.seen {
		return true
	}
	return c.up
}

// liveProbe reports Nostr from the transport's connectivity signal (unless
// -offline/-down forces it down) and every other transport from the static
// -offline/-down flags.
type liveProbe struct {
	down staticProbe
	conn *connectivity
}

func (p liveProbe) Up(k transport.Kind) bool {
	if p.down[k] {
		return false
	}
	if k == transport.Nostr {
		return p.conn.Up()
	}
	return true
}

// delivery is one message that already passed message.Open, tagged with
// the transport it arrived on.
type delivery struct {
	via transport.Kind
	ev  message.Event
}

// receiver hands over deliveries.
type receiver interface{ Receive() []delivery }

type nopTransport struct{}

func (nopTransport) Receive() []delivery { return nil }

// nostrReceiver adapts the Nostr transport's channel to the receiver
// interface. It drains without blocking, so a slow relay never stalls a
// tick; anything still in flight arrives on the next one.
type nostrReceiver struct {
	ch <-chan nostr.Delivery
}

func (r nostrReceiver) Receive() []delivery {
	var out []delivery
	for {
		select {
		case d, ok := <-r.ch:
			if !ok {
				return out
			}
			if d.Event == nil {
				continue
			}
			out = append(out, delivery{via: transport.Nostr, ev: *d.Event})
		default:
			return out
		}
	}
}

// hookTimeout bounds one run of the alert hook.
const hookTimeout = 30 * time.Second

// alarmingKind reports whether a notice kind (a schedule NoticeKind
// rendered with String) should trigger the alert hook.
func alarmingKind(kind string) bool {
	switch kind {
	case "missed", "alarm", "mass-silence", "still-paused":
		return true
	}
	return false
}

// fireHook runs the alert hook once for n, with the notice passed in
// KENGELE_KIND/LABEL/TIME/REASON/DEADLINE. A failing hook is reported on
// stderr and never stops the watch loop.
func fireHook(hook string, n schedule.Notice, label string) {
	if hook == "" || !alarmingKind(n.Kind.String()) {
		return
	}
	deadline := ""
	if !n.Deadline.IsZero() {
		deadline = n.Deadline.UTC().Format(time.RFC3339)
	}
	ctx, cancel := context.WithTimeout(context.Background(), hookTimeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, hook)
	cmd.Env = append(os.Environ(),
		"KENGELE_KIND="+n.Kind.String(),
		"KENGELE_LABEL="+label,
		"KENGELE_TIME="+n.At.UTC().Format(time.RFC3339),
		"KENGELE_REASON="+n.Reason,
		"KENGELE_DEADLINE="+deadline,
	)
	if err := cmd.Run(); err != nil {
		fmt.Fprintf(os.Stderr, "kengele-watcher: alert hook failed: %v / hook ya tahadhari imeshindwa: %v\n", err, err)
	}
}

// runOpts tunes runLoop.
type runOpts struct {
	live   bool   // a live Nostr transport feeds the engine
	relays int    // relay count for the live banner
	hook   string // alert hook path; empty disables it
}

// watch runs the engine until ctx ends. It owns no goroutines.
func watch(ctx context.Context, plan *schedule.Plan, ticks <-chan time.Time, now func() time.Time, p probe, tr receiver, out io.Writer) error {
	return runLoop(ctx, plan, ticks, now, p, tr, out, runOpts{})
}

// runLoop is watch with live-mode options: a live banner and the alert hook.
func runLoop(ctx context.Context, plan *schedule.Plan, ticks <-chan time.Time, now func() time.Time, p probe, tr receiver, out io.Writer, opts runOpts) error {
	e := schedule.New(plan.Config(), now())
	if err := plan.Load(e); err != nil {
		return err
	}
	labels := map[[32]byte]string{}
	for _, a := range plan.Activists {
		k, _ := a.Key()
		labels[k] = a.Label
	}
	print := func(ns []schedule.Notice) {
		for _, n := range ns {
			line := fmt.Sprintf("%s %-12s %s", n.At.UTC().Format(time.RFC3339), n.Kind, labels[n.Activist])
			if !n.Deadline.IsZero() {
				line += " deadline " + n.Deadline.UTC().Format(time.RFC3339)
			}
			if n.Reason != "" {
				line += " (" + n.Reason + ")"
			}
			fmt.Fprintln(out, line)
			fireHook(opts.hook, n, labels[n.Activist])
		}
	}
	if opts.live {
		fmt.Fprintf(out, "kengele-watcher %s: watching %d via Nostr (%d relays) / inafuatilia %d kupitia Nostr (relay %d)\n", version, len(plan.Activists), opts.relays, len(plan.Activists), opts.relays)
	} else {
		fmt.Fprintf(out, "kengele-watcher %s: watching %d, no transport yet (not for real use) / inafuatilia %d, bado hakuna transport (si kwa matumizi halisi)\n", version, len(plan.Activists), len(plan.Activists))
	}
	step := func(t time.Time) {
		for _, k := range transport.Supported {
			print(e.SetTransport(t, k, p.Up(k)))
		}
		for _, d := range tr.Receive() {
			print(e.Observe(t, d.via, d.ev))
		}
	}
	step(now())
	for {
		select {
		case <-ctx.Done():
			return nil
		case t, ok := <-ticks:
			if !ok {
				return nil
			}
			step(t)
		}
	}
}
