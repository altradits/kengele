// Command kengele-watcher is the guardian-side watcher. It follows the
// activists in an encrypted watch plan and raises the alarm when a
// check-in is missed, pausing the clock while the guardian is offline.
//
// This version has no network transport yet (#8): it runs the deadline
// engine against a transport that delivers nothing, so it is only useful
// for trying out plans. Not for real use.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/altradits/kengele/internal/message"
	"github.com/altradits/kengele/internal/schedule"
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
	fmt.Fprintln(w, `usage:
  kengele-watcher version
  kengele-watcher seal-plan -in plan.json -out plan.kgp
  kengele-watcher run -plan plan.kgp [-tick 30s] [-offline]

The plan passphrase is read from `+passEnv+`.`)
}

func cli(args []string, out io.Writer, pass string) error {
	if len(args) == 0 {
		usage(out)
		return errors.New("no command")
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
	return fmt.Errorf("unknown command %q", args[0])
}

func sealPlan(args []string, out io.Writer, pass string) error {
	fs := flag.NewFlagSet("seal-plan", flag.ContinueOnError)
	in := fs.String("in", "", "plain JSON plan")
	dst := fs.String("out", "", "encrypted plan file to write")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *in == "" || *dst == "" {
		return errors.New("seal-plan needs -in and -out")
	}
	if pass == "" {
		return errors.New(passEnv + " is not set")
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
	fmt.Fprintf(out, "wrote %s; delete %s now, it is not encrypted\n", *dst, *in)
	return nil
}

func runCmd(args []string, out io.Writer, pass string) error {
	fs := flag.NewFlagSet("run", flag.ContinueOnError)
	planPath := fs.String("plan", "", "encrypted plan file")
	every := fs.Duration("tick", 30*time.Second, "how often to check deadlines")
	offline := fs.Bool("offline", false, "pretend the guardian is offline (shutdown mode)")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *planPath == "" {
		return errors.New("run needs -plan")
	}
	if pass == "" {
		return errors.New(passEnv + " is not set")
	}
	data, err := os.ReadFile(*planPath)
	if err != nil {
		return err
	}
	plan, err := schedule.OpenPlan(data, []byte(pass))
	if err != nil {
		return err
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	ticks := time.NewTicker(*every)
	defer ticks.Stop()
	return watch(ctx, plan, ticks.C, time.Now, staticProbe(!*offline), nopTransport{}, out)
}

// probe reports whether the guardian can reach the network. The real one
// checks the relays (#8); this one is fixed.
type probe interface{ Online() bool }

type staticProbe bool

func (p staticProbe) Online() bool { return bool(p) }

// transport hands over messages that already passed message.Open.
type transport interface{ Receive() []message.Event }

type nopTransport struct{}

func (nopTransport) Receive() []message.Event { return nil }

// watch runs the engine until ctx ends. It owns no goroutines.
func watch(ctx context.Context, plan *schedule.Plan, ticks <-chan time.Time, now func() time.Time, p probe, tr transport, out io.Writer) error {
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
		}
	}
	fmt.Fprintf(out, "kengele-watcher %s: watching %d, no transport yet (not for real use)\n", version, len(plan.Activists))
	step := func(t time.Time) {
		print(e.SetOnline(t, p.Online()))
		for _, ev := range tr.Receive() {
			print(e.Observe(t, ev))
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
