package fund

import (
	"strings"
	"testing"
)

func TestChecksumBIP380Vector(t *testing.T) {
	sum, err := Checksum("raw(deadbeef)")
	if err != nil {
		t.Fatal(err)
	}
	if sum != "89f8spxm" {
		t.Fatalf("checksum = %q, want 89f8spxm", sum)
	}
	full, err := WithChecksum("raw(deadbeef)")
	if err != nil {
		t.Fatal(err)
	}
	if full != "raw(deadbeef)#89f8spxm" {
		t.Fatalf("full = %q", full)
	}
	if !VerifyChecksum(full) {
		t.Error("valid checksum rejected")
	}
}

func TestVerifyChecksumFailures(t *testing.T) {
	// Cases adapted from the BIP380 test list.
	bad := []string{
		"raw(deadbeef)",
		"raw(deadbeef)#",
		"raw(deadbeef)#89f8spxmx",
		"raw(deadbeef)#89f8spx",
		"raw(deedbeef)#89f8spxm",
		"raw(deadbeef)#89f8spxn",
		"raw(deadbeef)#89f8SPXM",
		"",
		"#89f8spxm",
	}
	for _, s := range bad {
		if VerifyChecksum(s) {
			t.Errorf("accepted %q", s)
		}
	}
	if _, err := Checksum("raw(Ü)"); err == nil {
		t.Error("accepted non-charset character")
	}
}

func TestChecksumDetectsSingleCharMutation(t *testing.T) {
	desc, err := WithChecksum(BuildDescriptor("tpubACT", [3]string{"tpubG1", "tpubG2", "tpubG3"}))
	if err != nil {
		t.Fatal(err)
	}
	if !VerifyChecksum(desc) {
		t.Fatal("generated descriptor does not verify")
	}
	body := desc[:len(desc)-9]
	for i := 0; i < len(body); i++ {
		mut := body[:i] + "z" + body[i+1:] + desc[len(desc)-9:]
		if mut == desc {
			continue
		}
		if VerifyChecksum(mut) {
			t.Fatalf("mutation at byte %d undetected", i)
		}
	}
}

func TestBuildDescriptorShape(t *testing.T) {
	got := BuildDescriptor("tpubACT", [3]string{"tpubG1", "tpubG2", "tpubG3"})
	want := "wsh(or_d(pk(tpubACT/<0;1>/*),multi(2,tpubG1/<0;1>/*,tpubG2/<0;1>/*,tpubG3/<0;1>/*)))"
	if got != want {
		t.Fatalf("descriptor:\n got %s\nwant %s", got, want)
	}
}

func TestWitnessScriptBytes(t *testing.T) {
	key := func(prefix byte, fill byte) []byte {
		k := make([]byte, 33)
		k[0] = prefix
		for i := 1; i < 33; i++ {
			k[i] = fill
		}
		return k
	}
	a, g1, g2, g3 := key(0x02, 0x11), key(0x03, 0x22), key(0x02, 0x33), key(0x03, 0x44)
	script, err := WitnessScript(a, g1, g2, g3)
	if err != nil {
		t.Fatal(err)
	}
	want := []byte{0x21}
	want = append(want, a...)
	want = append(want, 0xac, 0x64, 0x52, 0x21)
	want = append(want, g1...)
	want = append(want, 0x21)
	want = append(want, g2...)
	want = append(want, 0x21)
	want = append(want, g3...)
	want = append(want, 0x53, 0xae, 0x68)
	if string(script) != string(want) {
		t.Fatalf("script:\n got %x\nwant %x", script, want)
	}
	if len(script) != 142 {
		t.Fatalf("script length = %d, want 142", len(script))
	}
}

func TestWitnessScriptFailures(t *testing.T) {
	good := make([]byte, 33)
	good[0] = 0x02
	for _, bad := range [][]byte{nil, make([]byte, 32), make([]byte, 34), append([]byte{0x04}, make([]byte, 32)...)} {
		if _, err := WitnessScript(bad, good, good, good); err == nil {
			t.Errorf("accepted bad key %x", bad)
		}
	}
	if _, err := WitnessScript(good, good, good, good[:32]); err == nil {
		t.Error("accepted short guardian key")
	}
	if !strings.Contains(BuildDescriptor("a", [3]string{"b", "c", "d"}), "multi(2,") {
		t.Error("descriptor lost the 2-of-3")
	}
}
