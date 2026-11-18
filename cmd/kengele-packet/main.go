// Command kengele-packet fills the Kenya habeas corpus template
// (docs/habeas-kenya.md) from a JSON alarm record.
//
// The input names a missing person, so the output is written with mode 0600.
// A Kenyan advocate must review any real packet before use; the output says
// so in English and Swahili. Not for real use until the security review in
// SECURITY.md is complete.
package main

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"

	"github.com/altradits/kengele/internal/packet"
)

// version is set at build time with -ldflags "-X main.version=...".
var version = "0.0.0-dev"

func main() {
	if err := cli(os.Args[1:], os.Stdout); err != nil {
		fmt.Fprintln(os.Stderr, "kengele-packet:", err)
		os.Exit(1)
	}
}

func usage(w io.Writer) {
	fmt.Fprintln(w, `usage: / matumizi:
  kengele-packet -in record.json -out packet.md
  kengele-packet version

-in reads the JSON alarm record (packet.Record); -out writes the Markdown packet with mode 0600.
-in inasoma kumbukumbu ya tahadhari ya JSON; -out inaandika jalada la Markdown kwa ruhusa 0600.`)
}

func cli(args []string, out io.Writer) error {
	if len(args) == 1 && (args[0] == "version" || args[0] == "-version" || args[0] == "--version") {
		fmt.Fprintln(out, "kengele-packet", version)
		return nil
	}
	if len(args) == 1 && (args[0] == "help" || args[0] == "-h" || args[0] == "--help") {
		usage(out)
		return nil
	}
	fs := flag.NewFlagSet("kengele-packet", flag.ContinueOnError)
	fs.SetOutput(out)
	in := fs.String("in", "", "JSON alarm record to read / kumbukumbu ya JSON ya kusoma")
	dst := fs.String("out", "", "Markdown packet to write / jalada la Markdown la kuandika")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *in == "" || *dst == "" {
		usage(out)
		return errors.New("need -in and -out / -in na -out zinahitajika")
	}
	raw, err := os.ReadFile(*in)
	if err != nil {
		return err
	}
	var rec packet.Record
	if err := json.Unmarshal(raw, &rec); err != nil {
		return fmt.Errorf("bad JSON record / kumbukumbu mbaya ya JSON: %w", err)
	}
	md, err := packet.Render(rec)
	if err != nil {
		return err
	}
	if err := os.WriteFile(*dst, []byte(md), 0o600); err != nil {
		return err
	}
	fmt.Fprintf(out, "wrote %s; a Kenyan advocate must review it before any real use / ameandika %s; wakili wa Kenya lazima apitie kabla ya matumizi halisi\n", *dst, *dst)
	return nil
}
