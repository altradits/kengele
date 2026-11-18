package transport

import "testing"

func TestParse(t *testing.T) {
	for name, want := range map[string]Kind{"nostr": Nostr, "sms": SMS} {
		k, err := Parse(name)
		if err != nil || k != want || k.String() != name {
			t.Errorf("Parse(%q) = %v, %v", name, k, err)
		}
		if !IsSupported(k) {
			t.Errorf("%v should be supported", k)
		}
	}
	for _, bad := range []string{"mesh", "", "SMS", "carrier pigeon"} {
		if _, err := Parse(bad); err == nil {
			t.Errorf("Parse(%q) accepted", bad)
		}
	}
	if IsSupported(Mesh) || IsSupported(Unknown) {
		t.Error("mesh and unknown must not be supported yet")
	}
	if Kind(99).String() != "transport(99)" {
		t.Error("unexpected name for an out-of-range kind")
	}
}
