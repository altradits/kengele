// Command kengele-fund builds a legal-fund miniscript descriptor on
// signet: the activist alone, or 2 of 3 guardians, can spend.
//
//	kengele-fund -activist <tpub> -guardian1 <tpub> -guardian2 <tpub> -guardian3 <tpub> [-network signet]
//
// It prints the ranged descriptor with checksum and the first receive
// address. It refuses mainnet and private keys; paste tpubs only.
//
// Not for real use. Every descriptor must be verified in Sparrow or
// Bitcoin Core and test-spent on signet first. See
// docs/legal-fund-signet.md and docs/threat-model.md section 4.6.
package main

import (
	"errors"
	"flag"
	"fmt"
	"io"
	"os"

	"github.com/altradits/kengele/internal/fund"
)

// version is set at build time with -ldflags "-X main.version=...".
var version = "0.0.0-dev"

func main() {
	if err := cli(os.Args[1:], os.Stdout); err != nil {
		fmt.Fprintln(os.Stderr, "kengele-fund:", err)
		os.Exit(1)
	}
}

func usage(w io.Writer) {
	fmt.Fprintln(w, `usage / matumizi:
  kengele-fund version
  kengele-fund -activist <tpub> -guardian1 <tpub> -guardian2 <tpub> -guardian3 <tpub> [-network signet]

Builds the legal-fund descriptor and first receive address.
Hutengeneza maelezo ya mfuko wa kisheria na anwani ya kwanza ya kupokea.

Signet only; mainnet is refused. Paste tpubs only: private keys are
never accepted.
Signet pekee; mtandao mkuu haukubaliwi. Bandika tpub pekee: funguo za
siri hazikubaliwi kamwe.`)
}

func cli(args []string, out io.Writer) error {
	if len(args) == 0 {
		usage(out)
		return errors.New("no arguments / hakuna hoja")
	}
	switch args[0] {
	case "version", "-version", "--version":
		fmt.Fprintln(out, "kengele-fund", version)
		return nil
	case "help", "-h", "--help":
		usage(out)
		return nil
	}
	fs := flag.NewFlagSet("kengele-fund", flag.ContinueOnError)
	fs.SetOutput(out)
	activist := fs.String("activist", "", "activist tpub / tpub ya mwanaharakati")
	g1 := fs.String("guardian1", "", "guardian 1 tpub / tpub ya mlezi 1")
	g2 := fs.String("guardian2", "", "guardian 2 tpub / tpub ya mlezi 2")
	g3 := fs.String("guardian3", "", "guardian 3 tpub / tpub ya mlezi 3")
	network := fs.String("network", "signet", "signet, testnet or regtest (mainnet refused) / signet, testnet au regtest (mtandao mkuu haukubaliwi)")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() > 0 {
		usage(out)
		return fmt.Errorf("unexpected argument %q / hoja isiyotarajiwa %q", fs.Arg(0), fs.Arg(0))
	}
	w, err := fund.Generate(*activist, []string{*g1, *g2, *g3}, *network)
	if err != nil {
		return err
	}
	fmt.Fprintln(out, "descriptor / maelezo ya pochi:")
	fmt.Fprintln(out, w.Descriptor)
	fmt.Fprintln(out, "first receive address (index 0) / anwani ya kwanza ya kupokea (nambari 0):")
	fmt.Fprintln(out, w.Address)
	fmt.Fprintln(out, "key fingerprints / alama za funguo:")
	fmt.Fprintf(out, "  activist / mwanaharakati: %s\n", w.Fingerprints[0])
	for i := 1; i < 4; i++ {
		fmt.Fprintf(out, "  guardian %d / mlezi %d: %s\n", i, i, w.Fingerprints[i])
	}
	fmt.Fprintln(out, "signet only; verify in Sparrow or Bitcoin Core before use / signet pekee; thibitisha kwenye Sparrow au Bitcoin Core kabla ya kutumia")
	return nil
}
