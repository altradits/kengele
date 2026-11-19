package meshcarry

import "errors"

var (
	// ErrNotKengele means the bytes are other mesh traffic. Callers must
	// ignore them silently, not report them.
	ErrNotKengele = errors.New("meshcarry: not a Kengele fragment")
	// ErrFragment means a Kengele fragment is malformed.
	ErrFragment = errors.New("meshcarry: bad fragment")
	// ErrDatagramSize means a datagram is not exactly the 228-byte
	// sealed wire message this layer carries.
	ErrDatagramSize = errors.New("meshcarry: datagram is not 228 bytes")
)
