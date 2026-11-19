package fund

import (
	"crypto/sha256"
	"errors"
)

// Base58 alphabet used by Bitcoin (BIP32 xpub encoding).
const base58Alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

// EncodeBase58 encodes raw bytes in Bitcoin base58.
func EncodeBase58(raw []byte) string {
	zeros := 0
	for zeros < len(raw) && raw[zeros] == 0 {
		zeros++
	}
	// Worst case length: every byte becomes ~1.37 chars.
	out := make([]byte, 0, len(raw)*138/100+2)
	num := append([]byte(nil), raw[zeros:]...)
	for len(num) > 0 {
		var carry uint32
		next := num[:0]
		for _, b := range num {
			cur := carry<<8 | uint32(b)
			q := cur / 58
			carry = cur % 58
			if len(next) > 0 || q > 0 {
				next = append(next, byte(q))
			}
		}
		num = next
		out = append(out, base58Alphabet[carry])
	}
	for i := 0; i < zeros; i++ {
		out = append(out, '1')
	}
	// Digits were produced least significant first.
	for i, j := 0, len(out)-1; i < j; i, j = i+1, j-1 {
		out[i], out[j] = out[j], out[i]
	}
	return string(out)
}

// DecodeBase58Check decodes a base58check string (payload plus a 4-byte
// checksum of the payload's double SHA-256) and returns the payload.
func DecodeBase58Check(s string) ([]byte, error) {
	if s == "" {
		return nil, errors.New("empty base58 string")
	}
	// Each leading '1' is one zero byte; the rest decodes as a
	// base-58 number.
	zeros := 0
	for zeros < len(s) && s[zeros] == '1' {
		zeros++
	}
	raw := []byte{}
	for i := zeros; i < len(s); i++ {
		digit := -1
		for j := 0; j < len(base58Alphabet); j++ {
			if base58Alphabet[j] == s[i] {
				digit = j
				break
			}
		}
		if digit < 0 {
			return nil, errors.New("invalid base58 character")
		}
		var carry = uint32(digit)
		for j := len(raw) - 1; j >= 0; j-- {
			cur := uint32(raw[j])*58 + carry
			raw[j] = byte(cur)
			carry = cur >> 8
		}
		for carry > 0 {
			raw = append([]byte{byte(carry)}, raw...)
			carry >>= 8
		}
	}
	payload := make([]byte, zeros+len(raw))
	copy(payload[zeros:], raw)
	if len(payload) < 4 {
		return nil, errors.New("base58 payload too short")
	}
	body, want := payload[:len(payload)-4], payload[len(payload)-4:]
	first := sha256.Sum256(body)
	second := sha256.Sum256(first[:])
	for i := 0; i < 4; i++ {
		if want[i] != second[i] {
			return nil, errors.New("base58 checksum mismatch")
		}
	}
	return body, nil
}

// EncodeBase58Check appends a base58check checksum and encodes.
func EncodeBase58Check(body []byte) string {
	first := sha256.Sum256(body)
	second := sha256.Sum256(first[:])
	buf := make([]byte, 0, len(body)+4)
	buf = append(buf, body...)
	buf = append(buf, second[:4]...)
	return EncodeBase58(buf)
}
