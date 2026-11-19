package fund

import (
	"crypto/sha256"
	"errors"
	"fmt"
	"strings"
)

// Descriptor checksum (BIP380).

const (
	descInputCharset    = "0123456789()[],'/*abcdefgh@:$%{}IJKLMNOPQRSTUVWXYZ&+-.;<=>?!^_|~ijklmnopqrstuvwxyzABCDEFGH`#\"\\ "
	descChecksumCharset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
)

var descGen = [5]uint64{0xf5dee51989, 0xa9fdca3312, 0x1bab10e32d, 0x3706b1677a, 0x644d626ffd}

func descPolymod(symbols []int) uint64 {
	chk := uint64(1)
	for _, v := range symbols {
		top := chk >> 35
		chk = ((chk & 0x7ffffffff) << 5) ^ uint64(v)
		for i := 0; i < 5; i++ {
			if (top>>i)&1 == 1 {
				chk ^= descGen[i]
			}
		}
	}
	return chk
}

// descExpand maps each descriptor character to its symbols: its position
// within its group of 32, plus one combined group symbol per 3 chars.
func descExpand(s string) ([]int, error) {
	symbols := make([]int, 0, len(s)+len(s)/3+1)
	groups := make([]int, 0, 3)
	for i := 0; i < len(s); i++ {
		p := strings.IndexByte(descInputCharset, s[i])
		if p < 0 {
			return nil, fmt.Errorf("character %q not allowed in descriptors", s[i])
		}
		symbols = append(symbols, p&31)
		groups = append(groups, p>>5)
		if len(groups) == 3 {
			symbols = append(symbols, groups[0]*9+groups[1]*3+groups[2])
			groups = groups[:0]
		}
	}
	if len(groups) == 1 {
		symbols = append(symbols, groups[0])
	} else if len(groups) == 2 {
		symbols = append(symbols, groups[0]*3+groups[1])
	}
	return symbols, nil
}

// Checksum computes the 8-character BIP380 checksum of a descriptor
// without checksum.
func Checksum(desc string) (string, error) {
	symbols, err := descExpand(desc)
	if err != nil {
		return "", err
	}
	symbols = append(symbols, 0, 0, 0, 0, 0, 0, 0, 0)
	sum := descPolymod(symbols) ^ 1
	out := make([]byte, 8)
	for i := 0; i < 8; i++ {
		out[i] = descChecksumCharset[(sum>>(5*(7-i)))&31]
	}
	return string(out), nil
}

// WithChecksum appends "#<checksum>" to a descriptor.
func WithChecksum(desc string) (string, error) {
	sum, err := Checksum(desc)
	if err != nil {
		return "", err
	}
	return desc + "#" + sum, nil
}

// VerifyChecksum reports whether a "descriptor#checksum" string carries
// a correct checksum.
func VerifyChecksum(desc string) bool {
	if len(desc) < 9 || desc[len(desc)-9] != '#' {
		return false
	}
	body, sum := desc[:len(desc)-9], desc[len(desc)-8:]
	for i := 0; i < 8; i++ {
		if strings.IndexByte(descChecksumCharset, sum[i]) < 0 {
			return false
		}
	}
	symbols, err := descExpand(body)
	if err != nil {
		return false
	}
	for i := 0; i < 8; i++ {
		symbols = append(symbols, strings.IndexByte(descChecksumCharset, sum[i]))
	}
	return descPolymod(symbols) == 1
}

// BuildDescriptor returns the fund miniscript descriptor without
// checksum. Keys are ranged (<0;1>/*) so wallets derive fresh receive
// (chain 0) and change (chain 1) addresses.
func BuildDescriptor(activist string, guardians [3]string) string {
	return "wsh(or_d(pk(" + activist + "/<0;1>/*),multi(2," +
		guardians[0] + "/<0;1>/*," +
		guardians[1] + "/<0;1>/*," +
		guardians[2] + "/<0;1>/*)))"
}

// Witness opcodes used by the fund script.
const (
	opCheckSig      = 0xac
	opCheckMultiSig = 0xae
	opNotIf         = 0x64
	opEndIf         = 0x68
	op2             = 0x52
	op3             = 0x53
)

// WitnessScript compiles or_d(pk(A),multi(2,G1,G2,G3)) for four
// compressed public keys into the witness script:
//
//	<A> CHECKSIG NOTIF <2> <G1> <G2> <G3> <3> CHECKMULTISIG ENDIF
func WitnessScript(activist, g1, g2, g3 []byte) ([]byte, error) {
	for i, k := range [][]byte{activist, g1, g2, g3} {
		if len(k) != 33 || (k[0] != 0x02 && k[0] != 0x03) {
			return nil, fmt.Errorf("key %d is not a compressed public key", i)
		}
	}
	script := make([]byte, 0, 142)
	push := func(k []byte) {
		script = append(script, byte(len(k)))
		script = append(script, k...)
	}
	push(activist)
	script = append(script, opCheckSig, opNotIf, op2)
	push(g1)
	push(g2)
	push(g3)
	script = append(script, op3, opCheckMultiSig, opEndIf)
	return script, nil
}

// P2WSHAddress returns the bech32 address of a witness script.
func P2WSHAddress(hrp string, script []byte) (string, error) {
	if hrp == "" {
		return "", errors.New("empty human-readable part")
	}
	sum := sha256.Sum256(script)
	return SegwitAddress(hrp, sum[:])
}
