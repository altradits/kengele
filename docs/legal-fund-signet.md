# Legal fund on signet

> **Status: testing only. Do not use this for real money.** The fund must
> be verified in Sparrow or Bitcoin Core and test-spent on signet first,
> and mainnet needs an independent security review (see
> [threat-model.md](threat-model.md), section 4.6).

The legal fund is a Bitcoin wallet that pays the lawyer while the
activist is held. Spending policy: **the activist alone, or 2 of 3
guardians**. Kengele never holds keys or money: it only builds the
descriptor from public keys, and guardians spend with existing wallets.

Threat model: [section 4.6](threat-model.md#46-steal-or-freeze-the-legal-fund).
This document answers to it as follows:

- One guardian cannot spend alone (2-of-3 guardian path).
- Two colluding guardians *can* spend. This is residual and accepted:
  keep the fund small and choose guardians in different places.
- A bug in the generator could lock funds. Mitigation: verify every
  descriptor in a second wallet and test-spend on signet before any
  mainnet use. That is what this document walks through.
- The tool refuses mainnet keys and private keys. Paste tpubs only.

## The descriptor

`cmd/kengele-fund` takes the activist tpub plus 3 guardian tpubs and
prints a ranged miniscript descriptor plus the first receive address:

```text
wsh(or_d(pk(ACTIVIST/<0;1>/*),multi(2,GUARDIAN1/<0;1>/*,GUARDIAN2/<0;1>/*,GUARDIAN3/<0;1>/*)))#CHECKSUM
```

- `wsh(...)` means native segwit (P2WSH, `tb1q...` addresses on signet).
- The `pk(...)` branch is the activist path; the `multi(2,...)` branch
  is the guardian path.
- `/<0;1>/*` means the wallet derives fresh receive addresses (chain
  `0`) and change addresses (chain `1`).
- `#CHECKSUM` is the BIP380 checksum. Wallets reject a descriptor whose
  checksum is wrong, which catches copy mistakes.

The tool also prints each key's fingerprint so you can check nothing
was pasted twice or swapped. Tool output labels are bilingual
(English / Kiswahili); the Swahili wording is a draft and needs review
by a translator before real use.

## 1. Generate a fund

Each of the 4 people needs a signet wallet that can export an account
**tpub** (public key only, never the seed or private key). Sparrow on
signet can do this; any wallet that exports a plain `tpub...` works.

```sh
go run ./cmd/kengele-fund \
  -activist  <tpub ya mwanaharakati / activist tpub> \
  -guardian1 <tpub ya mlezi 1> \
  -guardian2 <tpub ya mlezi 2> \
  -guardian3 <tpub ya mlezi 3>
```

### Worked example (fake keys, do not use)

The keys below are made-up test fixtures with published labels. They
are worthless and must never hold real funds. Expected output, verified
by a second implementation before it was written here:

```text
$ kengele-fund -activist tpubD6NzVbkrYhZ4Xyy6F7VcSyvBgQvPvYQVM8w6X5DD8iecsynJnghdWL2oPg7M6oYi8LRzhe5bMvgzv9zSqomwt4oSGT7FUUjf4EnFu6CXcuy -guardian1 tpubD6NzVbkrYhZ4X6v8h14EQ95yzvR9zNBjaZSyVGj97qANpktdEKGC7Qd6WsU3CRVmdZyeZdgRWPNgQSgxBXZUb5oyyDV7BPk9q3uNKco4sa4 -guardian2 tpubD6NzVbkrYhZ4YrNHw4xJMw5WKxodLaaQ1KfQEJGHj1HFSMn5xb7VvS1SWT3o8wgWxX1BYhZpesPJJiy2W9gmtARJbK8XMLVs7vr9vzoj8Xz -guardian3 tpubD6NzVbkrYhZ4XmmLbnus3k8DTutwnP6W8ewoYvULeEYXNNbvWmCgJmaEWRnFN4YQG3HRDc5wQa55uodp311BneVecLSAkNK6v7Jj8BS5L6B
descriptor / maelezo ya pochi:
wsh(or_d(pk(tpubD6NzVbkrYhZ4Xyy6F7VcSyvBgQvPvYQVM8w6X5DD8iecsynJnghdWL2oPg7M6oYi8LRzhe5bMvgzv9zSqomwt4oSGT7FUUjf4EnFu6CXcuy/<0;1>/*),multi(2,tpubD6NzVbkrYhZ4X6v8h14EQ95yzvR9zNBjaZSyVGj97qANpktdEKGC7Qd6WsU3CRVmdZyeZdgRWPNgQSgxBXZUb5oyyDV7BPk9q3uNKco4sa4/<0;1>/*,tpubD6NzVbkrYhZ4YrNHw4xJMw5WKxodLaaQ1KfQEJGHj1HFSMn5xb7VvS1SWT3o8wgWxX1BYhZpesPJJiy2W9gmtARJbK8XMLVs7vr9vzoj8Xz/<0;1>/*,tpubD6NzVbkrYhZ4XmmLbnus3k8DTutwnP6W8ewoYvULeEYXNNbvWmCgJmaEWRnFN4YQG3HRDc5wQa55uodp311BneVecLSAkNK6v7Jj8BS5L6B/<0;1>/*)))#utvyxfye
first receive address (index 0) / anwani ya kwanza ya kupokea (nambari 0):
tb1qdnluw9ls4c2gpfhdznxunhsy6fnsdtpr5wkmxmhupvfxmnmwu90sdyh60q
key fingerprints / alama za funguo:
  activist / mwanaharakati: 20fcbbde
  guardian 1 / mlezi 1: e90ff80c
  guardian 2 / mlezi 2: 97b73dba
  guardian 3 / mlezi 3: 67b3f166
```

If your output for these inputs differs by even one character, stop:
the tool is broken. Do not work around it; file an issue.

## 2. Verify the descriptor (do this every time)

1. Copy the **whole** descriptor including the `#` checksum.
2. Check it in Bitcoin Core (any synced signet node):
   `bitcoin-cli -signet getdescriptorinfo '<descriptor>'`.
   It must return `"isrange": true, "issolvable": true` with no error.
   A checksum error means the text was damaged in transit: re-copy.
3. After importing (sections 3-5), the wallet's first receive address
   must equal the tool's address exactly. If not, stop and investigate;
   never send funds to an address the tool did not print.

## 3. Import into Sparrow (signet)

Sparrow supports miniscript through output descriptors. Steps:

1. Start Sparrow on signet: `Sparrow -n signet`. Confirm the title bar
   or status shows signet, not mainnet.
2. File → New Wallet. Name it (for example `kengele-fund-signet`),
   then Create Wallet.
3. In Settings, choose **xPub / Watch Only** as the keystore source,
   then **Descriptor**. Paste the full descriptor including `#...`.
   (On versions where that entry looks different: Settings → Script
   Policy → Edit next to Descriptor → paste → OK → Apply.)
4. Sparrow shows the policy and the derived keys. Set a password to
   encrypt the wallet file when asked.
5. Open the Receive tab. The first address must equal the tool's
   first receive address. If it does not, stop: do not fund this wallet.
6. Fund it from any public signet faucet and wait for one confirmation.
   The balance appears under Transactions.

## 4. Import into Liana (signet)

Liana's restore flow accepts a descriptor ("Load a wallet from
descriptor": paste it and click Next), but Liana only opens
**Liana-shaped** descriptors, which pair a spend key with a timelocked
recovery path. Our fund descriptor has no timelock, so Liana rejects
it. Exact steps to confirm this (takes two minutes):

1. Start Liana and choose signet at the network step.
2. Choose to restore/recover a wallet, then **Load a wallet from
   descriptor**.
3. Paste the full fund descriptor including `#...` and click Next.
4. Expected result: Liana reports the descriptor invalid (it is not a
   Liana wallet descriptor). This is the known outcome, not a bug in
   your copy.

Consequence: on signet the fund is managed and spent in Sparrow
(section 3) or Bitcoin Core (section 5), not in Liana. A Liana-shaped
variant of the fund (guardian path behind a timelock) is possible
future work, not part of this issue.

## 5. Import into Bitcoin Core (signet, fallback)

```sh
bitcoin-cli -signet -rpcwallet=kengele-fund createwallet kengele-fund
bitcoin-cli -signet -rpcwallet=kengele-fund importdescriptor \
  '[{"desc": "<descriptor>", "active": true, "range": [0, 1000], "timestamp": "now"}]'
bitcoin-cli -signet -rpcwallet=kengele-fund deriveaddresses '<descriptor>' '[0,0]'
```

`deriveaddresses` prints two addresses: `/0/0` (first receive) and
`/1/0` (first change). The first must equal the tool's address.

## 6. Test-spend drill on signet

Do this with 4 throwaway test seeds the tester controls (test seeds
only, never reused). Export their tpubs, build the fund, import into
Sparrow, fund from a faucet, then:

1. **Activist path.** In Sparrow, Send tab: pay a small amount back to
   the faucet or a second address you own. Sign with the activist
   keystore and broadcast. One confirmation ends the test.
2. **Guardian path.** Build a second Send in the watch-only wallet,
   save the unsigned PSBT, open it in a wallet holding guardian seed 1
   and sign, do the same with guardian seed 2, combine/finalize, and
   broadcast. One signature alone must fail to finalize: try
   broadcasting with a single guardian signature and confirm the wallet
   refuses (proves 2-of-3, not 1-of-3).
3. **Change check.** The change from each spend must arrive at a fresh
   `tb1q...` address from chain 1. Confirm the wallet balance is right
   after one confirmation.

Signet spend screenshots are still missing: they need real wallets and
network access, which this change did not have. They stay an open item;
the steps above are written so anyone with Sparrow on signet can follow
them and attach the screenshots.

## 7. Mainnet

The tool refuses mainnet: `-network mainnet`, `xpub...`/`ypub...`/
`zpub...` keys and any `...prv` key are all errors. Mainnet use needs
the audit named in the threat model first.

## References

- [threat-model.md](threat-model.md), section 4.6 (legal fund).
- BIP380 (descriptors), BIP32 (tpubs), BIP173 (addresses). Unit tests in
  `internal/fund` check the implementation against all three.
- Sparrow miniscript import: File → New Wallet → xPub / Watch Only →
  Descriptor (Sparrow docs and in-app Settings → Script Policy).
- Liana restore: "Load a wallet from descriptor" (Liana support site).
