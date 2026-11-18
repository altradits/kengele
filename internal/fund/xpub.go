package fund

import (
	"crypto/hmac"
	"crypto/sha256"
	"crypto/sha512"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"

	"github.com/btcsuite/btcd/btcec/v2"
	secp "github.com/decred/dcrd/dcrec/secp256k1/v4"
	"golang.org/x/crypto/ripemd160"
)

// BIP32 version bytes. Only tpub is accepted: this tool runs on signet
// and test networks, never mainnet.
const (
	verTPUB uint32 = 0x043587cf
	verXPUB uint32 = 0x0488b21e
	verYPUB uint32 = 0x049d7cb2
	verZPUB uint32 = 0x04b24746
	verUPUB uint32 = 0x044a5262
	verVPUB uint32 = 0x045f1cf6

	verTPRV uint32 = 0x04358394
	verXPRV uint32 = 0x0488ade4
	verYPRV uint32 = 0x049d7878
	verZPRV uint32 = 0x04b2430c
	verUPRV uint32 = 0x044a4e28
	verVPRV uint32 = 0x045f18bc
)

// HardenedBit marks hardened BIP32 child numbers, which need the
// private key and are always refused here.
const HardenedBit uint32 = 0x80000000

// XPub is a parsed extended public key (BIP32).
type XPub struct {
	Version   uint32
	Depth     byte
	ParentFP  [4]byte
	ChildNum  uint32
	ChainCode [32]byte
	Key       [33]byte
}

// ParseXPub parses and validates a tpub string. It refuses mainnet keys,
// private keys and anything that is not a well-formed tpub.
//
// User-facing errors are bilingual (English / Kiswahili).
func ParseXPub(s, role string) (*XPub, error) {
	if looksLikePrivateKey(s) {
		return nil, fmt.Errorf("%s: private keys are never accepted, paste the tpub instead / funguo za siri hazikubaliwi kamwe, bandika tpub badala yake", role)
	}
	body, err := DecodeBase58Check(s)
	if err != nil {
		return nil, fmt.Errorf("%s: not a valid extended key (%v) / si funguo salama (%v)", role, err, err)
	}
	if len(body) != 78 {
		return nil, fmt.Errorf("%s: extended key is %d bytes, want 78 / funguo ni baiti %d, inatakiwa 78", role, len(body), len(body))
	}
	ver := binary.BigEndian.Uint32(body[:4])
	switch ver {
	case verTPRV, verXPRV, verYPRV, verZPRV, verUPRV, verVPRV:
		return nil, fmt.Errorf("%s: private keys are never accepted, paste the tpub instead / funguo za siri hazikubaliwi kamwe, bandika tpub badala yake", role)
	case verXPUB, verYPUB, verZPUB:
		return nil, fmt.Errorf("%s: mainnet key refused, this tool is signet-only / funguo ya mtandao mkuu haikubaliwi, chombo hiki ni cha signet pekee", role)
	case verUPUB, verVPUB:
		return nil, fmt.Errorf("%s: multisig-version key refused, export the plain tpub instead / funguo ya toleo la multisig haikubaliwi, hamisha tpub ya kawaida badala yake", role)
	case verTPUB:
		// Accepted below.
	default:
		return nil, fmt.Errorf("%s: unknown key version %08x, need a tpub / toleo la funguo halijulikani %08x, tpub inahitajika", role, ver, ver)
	}
	if body[45] != 0x02 && body[45] != 0x03 {
		return nil, errors.New(role + ": key data is not a compressed public key / data ya funguo si ufunguo wa umma uliobanwa")
	}
	if _, err := btcec.ParsePubKey(body[45:78]); err != nil {
		return nil, fmt.Errorf("%s: public key is not on the curve: %v / ufunguo wa umma haupo kwenye mviringo: %v", role, err, err)
	}
	x := &XPub{Version: ver, Depth: body[4], ChildNum: binary.BigEndian.Uint32(body[9:13])}
	copy(x.ParentFP[:], body[5:9])
	copy(x.ChainCode[:], body[13:45])
	copy(x.Key[:], body[45:78])
	return x, nil
}

// looksLikePrivateKey spots WIF and raw-hex private key material before
// base58 parsing, so the error can say what went wrong.
func looksLikePrivateKey(s string) bool {
	if len(s) == 51 || len(s) == 52 {
		if s[0] == '5' || s[0] == 'K' || s[0] == 'L' || s[0] == '9' || s[0] == 'c' {
			return true
		}
	}
	if len(s) == 64 {
		if _, err := hex.DecodeString(s); err == nil {
			return true
		}
	}
	return false
}

// Derive derives a non-hardened child path (BIP32 public derivation,
// CKDpub). Hardened steps are refused: they need the private key.
func (x *XPub) Derive(path ...uint32) (*XPub, error) {
	cur := *x
	for _, step := range path {
		if step&HardenedBit != 0 {
			return nil, errors.New("hardened derivation needs the private key and is refused / utoaji wa hardened unahitaji funguo ya siri na haukubaliwi")
		}
		next, err := cur.ckdPub(step)
		if err != nil {
			return nil, err
		}
		cur = *next
	}
	return &cur, nil
}

// ckdPub derives one non-hardened child: Il = HMAC-SHA512(chain, key||i),
// childKey = key + Il*G, childChain = Ir.
func (x *XPub) ckdPub(i uint32) (*XPub, error) {
	data := make([]byte, 0, 37)
	data = append(data, x.Key[:]...)
	var num [4]byte
	binary.BigEndian.PutUint32(num[:], i)
	data = append(data, num[:]...)
	mac := hmac.New(sha512.New, x.ChainCode[:])
	mac.Write(data)
	out := mac.Sum(nil)
	il, ir := out[:32], out[32:]

	var scalar secp.ModNScalar
	if overflow := scalar.SetByteSlice(il); overflow {
		return nil, errors.New("derived scalar out of range, try another index / namba iliyopatikana nje ya kipimo, jaribu nambari nyingine")
	}
	if scalar.IsZero() {
		return nil, errors.New("derived zero scalar, try another index / namba sifuri ilipatikana, jaribu nambari nyingine")
	}

	parent, err := btcec.ParsePubKey(x.Key[:])
	if err != nil {
		return nil, fmt.Errorf("parent key invalid: %v", err)
	}
	var parentPoint, tweakPoint, childPoint secp.JacobianPoint
	parent.AsJacobian(&parentPoint)
	secp.ScalarBaseMultNonConst(&scalar, &tweakPoint)
	secp.AddNonConst(&parentPoint, &tweakPoint, &childPoint)
	childPoint.ToAffine()
	if childPoint.X.IsZero() && childPoint.Y.IsZero() {
		return nil, errors.New("derived point at infinity, try another index / nukta isiyo na kikomo ilipatikana, jaribu nambari nyingine")
	}
	xb := childPoint.X.Bytes()
	var child XPub
	child.Version = x.Version
	child.Depth = x.Depth + 1
	child.ParentFP = x.Fingerprint()
	child.ChildNum = i
	copy(child.ChainCode[:], ir)
	child.Key[0] = 0x02
	if childPoint.Y.IsOdd() {
		child.Key[0] = 0x03
	}
	copy(child.Key[1:], xb[:])
	return &child, nil
}

// Fingerprint is the BIP32 key fingerprint: the first 4 bytes of the
// HASH160 of the compressed public key.
func (x *XPub) Fingerprint() (fp [4]byte) {
	sha := sha256.Sum256(x.Key[:])
	h := ripemd160.New()
	h.Write(sha[:])
	copy(fp[:], h.Sum(nil)[:4])
	return fp
}

// PubKeyHex returns the compressed public key as lowercase hex.
func (x *XPub) PubKeyHex() string { return hex.EncodeToString(x.Key[:]) }

// FingerprintHex returns the fingerprint as 8 lowercase hex characters.
func (x *XPub) FingerprintHex() string {
	fp := x.Fingerprint()
	return hex.EncodeToString(fp[:])
}

// Payload returns the 78-byte BIP32 serialization payload (no checksum).
// It is used by tests to compare against spec vectors.
func (x *XPub) Payload() []byte {
	out := make([]byte, 78)
	binary.BigEndian.PutUint32(out[:4], x.Version)
	out[4] = x.Depth
	copy(out[5:9], x.ParentFP[:])
	binary.BigEndian.PutUint32(out[9:13], x.ChildNum)
	copy(out[13:45], x.ChainCode[:])
	copy(out[45:78], x.Key[:])
	return out
}
