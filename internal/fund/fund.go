// Package fund builds the Kengele legal-fund descriptor on signet.
//
// The fund is a miniscript wallet the activist alone, or 2 of 3
// guardians, can spend:
//
//	wsh(or_d(pk(ACTIVIST/<0;1>/*),multi(2,G1/<0;1>/*,G2/<0;1>/*,G3/<0;1>/*)))
//
// This package only handles extended PUBLIC keys (tpub). It refuses
// mainnet keys and private keys. It cannot derive from seeds: there is
// no seed or private-key code path here by design, only in tests.
//
// Threat model: docs/threat-model.md section 4.6 ("Steal or freeze the
// legal fund"). Residual risks from that section apply: a guardian
// majority can spend, and a bug here could lock funds, so every
// descriptor must be verified in Sparrow or Bitcoin Core and test-spent
// on signet before any mainnet use.
package fund
