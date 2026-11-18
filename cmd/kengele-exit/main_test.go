package main

import (
	"bytes"
	"strings"
	"testing"
)

func TestCLI(t *testing.T) {
	var out bytes.Buffer
	if err := cli([]string{"version"}, &out); err != nil || !strings.Contains(out.String(), version) {
		t.Fatalf("version: %v %q", err, out.String())
	}
	out.Reset()
	if err := cli([]string{"help"}, &out); err != nil || !strings.Contains(out.String(), "matumizi") {
		t.Fatalf("help lacks Swahili usage: %v %q", err, out.String())
	}
	// Every failure below must happen before the command would block.
	cases := map[string][]string{
		"no command":            {},
		"unknown":               {"nope"},
		"run needs mesh":        {"run", "-relays", "wss://relay.example.org"},
		"run needs relays":      {"run", "-mesh", "sim"},
		"run blank relays":      {"run", "-mesh", "sim", "-relays", " , "},
		"bad mesh":              {"run", "-mesh", "radio", "-relays", "wss://relay.example.org"},
		"empty tcp":             {"run", "-mesh", "tcp://", "-relays", "wss://relay.example.org"},
		"empty serial":          {"run", "-mesh", "serial:", "-relays", "wss://relay.example.org"},
		"missing serial device": {"run", "-mesh", "serial:/nonexistent-kengele-test", "-relays", "wss://relay.example.org"},
		"min-accepted zero":     {"run", "-mesh", "sim", "-relays", "wss://relay.example.org", "-min-accepted", "0"},
		"min-accepted too high": {"run", "-mesh", "sim", "-relays", "wss://relay.example.org", "-min-accepted", "2"},
	}
	for name, args := range cases {
		t.Run(name, func(t *testing.T) {
			out.Reset()
			if err := cli(args, &out); err == nil {
				t.Fatalf("%q accepted", args)
			}
		})
	}
}
