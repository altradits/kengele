package fund

import (
	"strings"
	"testing"
)

func testKeys(t *testing.T) (string, []string) {
	t.Helper()
	return testTpub(t, "activist"), []string{
		testTpub(t, "guardian-1"),
		testTpub(t, "guardian-2"),
		testTpub(t, "guardian-3"),
	}
}

func TestGenerateSuccess(t *testing.T) {
	act, gs := testKeys(t)
	w, err := Generate(act, gs, "signet")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(w.Descriptor, "wsh(or_d(pk(") {
		t.Errorf("descriptor shape: %s", w.Descriptor)
	}
	for _, k := range append([]string{act}, gs...) {
		if !strings.Contains(w.Descriptor, k+"/<0;1>/*") {
			t.Errorf("descriptor lacks ranged key %s...", k[:12])
		}
	}
	if !strings.Contains(w.Descriptor, "multi(2,") {
		t.Errorf("descriptor lacks 2-of-3: %s", w.Descriptor)
	}
	if !VerifyChecksum(w.Descriptor) {
		t.Error("generated checksum does not verify")
	}
	if !strings.HasPrefix(w.Address, "tb1q") {
		t.Errorf("address = %q, want tb1q...", w.Address)
	}
	if w.Network != "signet" {
		t.Errorf("network = %q", w.Network)
	}
	seen := map[string]bool{}
	for _, fp := range w.Fingerprints {
		if len(fp) != 8 || seen[fp] {
			t.Errorf("bad fingerprints: %v", w.Fingerprints)
		}
		seen[fp] = true
	}
	// Deterministic: the same keys always give the same wallet.
	w2, err := Generate(act, gs, "signet")
	if err != nil {
		t.Fatal(err)
	}
	if *w != *w2 {
		t.Error("second call differs")
	}
}

// TestGenerateGolden pins the full output for the fixed test keys. The
// descriptor checksum and address below were verified by an independent
// Python implementation (BIPs 32/173/380) before pinning.
func TestGenerateGolden(t *testing.T) {
	act, gs := testKeys(t)
	w, err := Generate(act, gs, "signet")
	if err != nil {
		t.Fatal(err)
	}
	wantDesc := "wsh(or_d(pk(tpubD6NzVbkrYhZ4Xyy6F7VcSyvBgQvPvYQVM8w6X5DD8iecsynJnghdWL2oPg7M6oYi8LRzhe5bMvgzv9zSqomwt4oSGT7FUUjf4EnFu6CXcuy/<0;1>/*),multi(2,tpubD6NzVbkrYhZ4X6v8h14EQ95yzvR9zNBjaZSyVGj97qANpktdEKGC7Qd6WsU3CRVmdZyeZdgRWPNgQSgxBXZUb5oyyDV7BPk9q3uNKco4sa4/<0;1>/*,tpubD6NzVbkrYhZ4YrNHw4xJMw5WKxodLaaQ1KfQEJGHj1HFSMn5xb7VvS1SWT3o8wgWxX1BYhZpesPJJiy2W9gmtARJbK8XMLVs7vr9vzoj8Xz/<0;1>/*,tpubD6NzVbkrYhZ4XmmLbnus3k8DTutwnP6W8ewoYvULeEYXNNbvWmCgJmaEWRnFN4YQG3HRDc5wQa55uodp311BneVecLSAkNK6v7Jj8BS5L6B/<0;1>/*)))#utvyxfye"
	if w.Descriptor != wantDesc {
		t.Errorf("descriptor:\n got %s\nwant %s", w.Descriptor, wantDesc)
	}
	if wantAddr := "tb1qdnluw9ls4c2gpfhdznxunhsy6fnsdtpr5wkmxmhupvfxmnmwu90sdyh60q"; w.Address != wantAddr {
		t.Errorf("address = %s, want %s", w.Address, wantAddr)
	}
	wantFPs := [4]string{"20fcbbde", "e90ff80c", "97b73dba", "67b3f166"}
	if w.Fingerprints != wantFPs {
		t.Errorf("fingerprints = %v, want %v", w.Fingerprints, wantFPs)
	}
}

func TestGenerateNetworks(t *testing.T) {
	act, gs := testKeys(t)
	for _, tc := range []struct {
		network  string
		wantAddr string
		wantNet  string
	}{
		{"", "tb1q", "signet"},
		{"signet", "tb1q", "signet"},
		{"testnet", "tb1q", "testnet"},
		{"regtest", "bcrt1q", "regtest"},
	} {
		w, err := Generate(act, gs, tc.network)
		if err != nil {
			t.Errorf("network %q: %v", tc.network, err)
			continue
		}
		if !strings.HasPrefix(w.Address, tc.wantAddr) || w.Network != tc.wantNet {
			t.Errorf("network %q: addr=%s net=%s", tc.network, w.Address, w.Network)
		}
	}
	// Same descriptor on every network; only the address encoding differs.
	ws, err := Generate(act, gs, "signet")
	if err != nil {
		t.Fatal(err)
	}
	wr, err := Generate(act, gs, "regtest")
	if err != nil {
		t.Fatal(err)
	}
	if ws.Descriptor != wr.Descriptor {
		t.Error("descriptor differs by network")
	}
	if ws.Address == wr.Address {
		t.Error("address identical across networks")
	}
}

func TestGenerateRefusals(t *testing.T) {
	act, gs := testKeys(t)
	mainnet := "xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet8"
	xprv := "xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi"
	cases := []struct {
		name      string
		activist  string
		guardians []string
		network   string
		want      string
	}{
		{"mainnet network", act, gs, "mainnet", "never accepted"},
		{"main network", act, gs, "MAINNET", "never accepted"},
		{"bogus network", act, gs, "fakenet", "refused"},
		{"mainnet activist", mainnet, gs, "signet", "mainnet"},
		{"mainnet guardian", act, []string{gs[0], mainnet, gs[2]}, "signet", "mainnet"},
		{"xprv activist", xprv, gs, "signet", "never accepted"},
		{"xprv guardian", act, []string{gs[0], gs[1], xprv}, "signet", "never accepted"},
		{"two guardians", act, gs[:2], "signet", "exactly 3"},
		{"four guardians", act, append(append([]string{}, gs...), gs[0]), "signet", "exactly 3"},
		{"duplicate guardian", act, []string{gs[0], gs[0], gs[2]}, "signet", "already used"},
		{"activist as guardian", act, []string{act, gs[1], gs[2]}, "signet", "already used"},
		{"empty activist", "", gs, "signet", "empty"},
		{"empty guardian", act, []string{gs[0], "", gs[2]}, "signet", "empty"},
		{"garbage guardian", act, []string{gs[0], "zzz", gs[2]}, "signet", "not a valid"},
	}
	for _, c := range cases {
		_, err := Generate(c.activist, c.guardians, c.network)
		if err == nil {
			t.Errorf("%s: accepted", c.name)
			continue
		}
		if !strings.Contains(strings.ToLower(err.Error()), c.want) {
			t.Errorf("%s: error %q lacks %q", c.name, err, c.want)
		}
	}
}

func TestGenerateAddressMatchesScript(t *testing.T) {
	act, gs := testKeys(t)
	w, err := Generate(act, gs, "signet")
	if err != nil {
		t.Fatal(err)
	}
	// Re-derive /0/0 independently through the parser and rebuild the
	// address; Generate must agree with the pieces.
	raw := append([]string{act}, gs...)
	derived := make([][]byte, 4)
	for i, s := range raw {
		x, err := ParseXPub(s, "test")
		if err != nil {
			t.Fatal(err)
		}
		c, err := x.Derive(0, 0)
		if err != nil {
			t.Fatal(err)
		}
		k := c.Key
		derived[i] = append([]byte(nil), k[:]...)
	}
	script, err := WitnessScript(derived[0], derived[1], derived[2], derived[3])
	if err != nil {
		t.Fatal(err)
	}
	addr, err := P2WSHAddress("tb", script)
	if err != nil {
		t.Fatal(err)
	}
	if addr != w.Address {
		t.Fatalf("address %s != rebuilt %s", w.Address, addr)
	}
	if _, prog, err := DecodeSegwitAddress(w.Address); err != nil || len(prog) != 32 {
		t.Fatalf("address does not decode to a 32-byte program: %v", err)
	}
}
