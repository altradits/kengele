package fund

import (
	"errors"
	"strings"
)

// Bech32 encoding (BIP173) for P2WSH receive addresses. Only version-0
// witness programs are needed; decoding exists so tests can verify
// vectors and round-trips.

const bech32Charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

var bech32Gen = [5]uint32{0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3}

func bech32Polymod(values []int) uint32 {
	chk := uint32(1)
	for _, v := range values {
		top := chk >> 25
		chk = (chk&0x1ffffff)<<5 ^ uint32(v)
		for i := 0; i < 5; i++ {
			if (top>>i)&1 == 1 {
				chk ^= bech32Gen[i]
			}
		}
	}
	return chk
}

func bech32HRPExpand(hrp string) []int {
	out := make([]int, 0, 2*len(hrp)+1)
	for i := 0; i < len(hrp); i++ {
		out = append(out, int(hrp[i]>>5))
	}
	out = append(out, 0)
	for i := 0; i < len(hrp); i++ {
		out = append(out, int(hrp[i]&31))
	}
	return out
}

func bech32CreateChecksum(hrp string, data []int) []int {
	vals := append(bech32HRPExpand(hrp), data...)
	vals = append(vals, 0, 0, 0, 0, 0, 0)
	mod := bech32Polymod(vals) ^ 1
	out := make([]int, 6)
	for i := 0; i < 6; i++ {
		out[i] = int((mod >> (5 * (5 - i))) & 31)
	}
	return out
}

// EncodeBech32 encodes data values (each 0-31) with the human-readable
// part. It returns an error for an invalid HRP or overlong output.
func EncodeBech32(hrp string, data []int) (string, error) {
	if len(hrp) < 1 || len(hrp) > 83 {
		return "", errors.New("bech32 human-readable part must be 1-83 characters")
	}
	for i := 0; i < len(hrp); i++ {
		if hrp[i] < 33 || hrp[i] > 126 {
			return "", errors.New("bech32 human-readable part out of range")
		}
	}
	lower := strings.ToLower(hrp)
	var b strings.Builder
	b.WriteString(lower)
	b.WriteByte('1')
	for _, v := range data {
		if v < 0 || v > 31 {
			return "", errors.New("bech32 data value out of range")
		}
		b.WriteByte(bech32Charset[v])
	}
	for _, v := range bech32CreateChecksum(lower, data) {
		b.WriteByte(bech32Charset[v])
	}
	if b.Len() > 90 {
		return "", errors.New("bech32 string over 90 characters")
	}
	return b.String(), nil
}

// DecodeBech32 decodes a bech32 string into its HRP and data values
// (without the checksum).
func DecodeBech32(s string) (string, []int, error) {
	if len(s) < 8 || len(s) > 90 {
		return "", nil, errors.New("bech32 length out of range")
	}
	lowered, uppered := strings.ToLower(s), strings.ToUpper(s)
	if s != lowered && s != uppered {
		return "", nil, errors.New("bech32 mixed case")
	}
	s = lowered
	sep := strings.LastIndexByte(s, '1')
	if sep < 1 || sep+7 > len(s) {
		return "", nil, errors.New("bech32 separator misplaced")
	}
	hrp, rest := s[:sep], s[sep+1:]
	for i := 0; i < len(hrp); i++ {
		if hrp[i] < 33 || hrp[i] > 126 {
			return "", nil, errors.New("bech32 human-readable part out of range")
		}
	}
	data := make([]int, len(rest))
	for i := 0; i < len(rest); i++ {
		p := strings.IndexByte(bech32Charset, rest[i])
		if p < 0 {
			return "", nil, errors.New("bech32 invalid data character")
		}
		data[i] = p
	}
	vals := append(bech32HRPExpand(hrp), data...)
	if bech32Polymod(vals) != 1 {
		return "", nil, errors.New("bech32 checksum mismatch")
	}
	return hrp, data[:len(data)-6], nil
}

// convertBits regroups bytes from fromBits to toBits wide. Pad must be
// true when encoding and false when decoding (BIP173).
func convertBits(data []byte, fromBits, toBits uint, pad bool) ([]int, error) {
	var acc, bits uint
	out := []int{}
	maxv := (1 << toBits) - 1
	for _, b := range data {
		acc = (acc << fromBits) | uint(b)
		bits += fromBits
		for bits >= toBits {
			bits -= toBits
			out = append(out, int((acc>>bits)&uint(maxv)))
		}
	}
	if pad {
		if bits > 0 {
			out = append(out, int((acc<<(toBits-bits))&uint(maxv)))
		}
	} else if bits >= fromBits || ((acc<<(toBits-bits))&uint(maxv)) != 0 {
		return nil, errors.New("bech32 invalid padding")
	}
	return out, nil
}

// SegwitAddress encodes a version-0 witness program (20 or 32 bytes) as
// a bech32 address with the given HRP ("tb" on signet/testnet, "bcrt"
// on regtest).
func SegwitAddress(hrp string, program []byte) (string, error) {
	if len(program) != 20 && len(program) != 32 {
		return "", errors.New("witness program must be 20 or 32 bytes")
	}
	conv, err := convertBits(program, 8, 5, true)
	if err != nil {
		return "", err
	}
	return EncodeBech32(hrp, append([]int{0}, conv...))
}

// DecodeSegwitAddress decodes a version-0 segwit address into its HRP
// and witness program.
func DecodeSegwitAddress(addr string) (string, []byte, error) {
	hrp, data, err := DecodeBech32(addr)
	if err != nil {
		return "", nil, err
	}
	if len(data) < 1 || data[0] != 0 {
		return "", nil, errors.New("not a version-0 segwit address")
	}
	prog5 := make([]byte, len(data)-1)
	for i, v := range data[1:] {
		prog5[i] = byte(v)
	}
	conv, err := convertBits(prog5, 5, 8, false)
	if err != nil {
		return "", nil, err
	}
	prog := make([]byte, len(conv))
	for i, v := range conv {
		prog[i] = byte(v)
	}
	if len(prog) != 20 && len(prog) != 32 {
		return "", nil, errors.New("witness program must be 20 or 32 bytes")
	}
	return hrp, prog, nil
}
