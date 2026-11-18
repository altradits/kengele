package fund

import (
	"errors"
	"fmt"
	"strings"
)

// Networks this tool accepts. Mainnet is refused: the fund stays on
// signet until an independent security review says otherwise
// (docs/threat-model.md section 4.6).
const (
	NetworkSignet  = "signet"
	NetworkTestnet = "testnet"
	NetworkRegtest = "regtest"
)

// Wallet is one generated legal fund: the importable descriptor and the
// first receive address.
type Wallet struct {
	// Descriptor is the ranged miniscript descriptor with checksum.
	Descriptor string
	// Address is the first receive address (chain 0, index 0).
	Address string
	// Network is the network the address is encoded for.
	Network string
	// Fingerprints identifies the four keys (activist first) so the
	// user can check nothing was pasted twice or swapped.
	Fingerprints [4]string
}

// hrpForNetwork maps an accepted network to its bech32 HRP.
func hrpForNetwork(network string) (string, error) {
	switch strings.ToLower(strings.TrimSpace(network)) {
	case "", NetworkSignet, NetworkTestnet:
		return "tb", nil
	case NetworkRegtest:
		return "bcrt", nil
	default:
		return "", fmt.Errorf("network %q refused, use signet (or testnet, regtest); mainnet is never accepted / mtandao %q haukubaliwi, tumia signet; mtandao mkuu haukubaliwi kamwe", network, network)
	}
}

// Generate builds the fund descriptor and first receive address from
// the activist tpub and exactly 3 guardian tpubs.
func Generate(activist string, guardians []string, network string) (*Wallet, error) {
	if len(guardians) != 3 {
		return nil, fmt.Errorf("need exactly 3 guardian keys, got %d / funguo 3 za walezi zinahitajika, zimepatikana %d", len(guardians), len(guardians))
	}
	hrp, err := hrpForNetwork(network)
	if err != nil {
		return nil, err
	}
	if network == "" {
		network = NetworkSignet
	}
	roles := []string{"activist / mwanaharakati", "guardian 1 / mlezi 1", "guardian 2 / mlezi 2", "guardian 3 / mlezi 3"}
	keys := make([]*XPub, 4)
	raw := append([]string{activist}, guardians...)
	for i, s := range raw {
		s = strings.TrimSpace(s)
		if s == "" {
			return nil, fmt.Errorf("%s: empty key / funguo tupu", roles[i])
		}
		x, err := ParseXPub(s, roles[i])
		if err != nil {
			return nil, err
		}
		keys[i] = x
	}
	// Refuse duplicates: the same key twice would silently weaken the
	// policy (for example, activist == guardian, or 2-of-3 with a
	// repeated key becoming 1-of-2).
	seen := map[[33]byte]string{}
	for i, x := range keys {
		if prev, dup := seen[x.Key]; dup {
			return nil, fmt.Errorf("%s: same key already used for %s / funguo ile ile imeshatumika kwa %s", roles[i], prev, prev)
		}
		seen[x.Key] = roles[i]
	}
	// First receive address: chain 0, index 0 of every key.
	derived := make([][]byte, 4)
	for i, x := range keys {
		child, err := x.Derive(0, 0)
		if err != nil {
			return nil, fmt.Errorf("%s: cannot derive address: %v", roles[i], err)
		}
		k := child.Key
		derived[i] = append([]byte(nil), k[:]...)
	}
	script, err := WitnessScript(derived[0], derived[1], derived[2], derived[3])
	if err != nil {
		return nil, err
	}
	addr, err := P2WSHAddress(hrp, script)
	if err != nil {
		return nil, err
	}
	trimmed := append([]string{strings.TrimSpace(activist)},
		strings.TrimSpace(guardians[0]), strings.TrimSpace(guardians[1]), strings.TrimSpace(guardians[2]))
	desc, err := WithChecksum(BuildDescriptor(trimmed[0], [3]string{trimmed[1], trimmed[2], trimmed[3]}))
	if err != nil {
		return nil, err
	}
	w := &Wallet{Descriptor: desc, Address: addr, Network: strings.ToLower(strings.TrimSpace(network))}
	for i, x := range keys {
		w.Fingerprints[i] = x.FingerprintHex()
	}
	if !VerifyChecksum(w.Descriptor) {
		return nil, errors.New("internal error: generated checksum does not verify / hitilafu ya ndani: checksum haijathibitishwa")
	}
	return w, nil
}
