package main

import (
	"bytes"
	"strings"
	"testing"
)

// Made-up test keys (deterministic fixtures, published labels, worth no
// funds). Same values as internal/fund tests use.
const (
	testActivist = "tpubD6NzVbkrYhZ4Xyy6F7VcSyvBgQvPvYQVM8w6X5DD8iecsynJnghdWL2oPg7M6oYi8LRzhe5bMvgzv9zSqomwt4oSGT7FUUjf4EnFu6CXcuy"
	testG1       = "tpubD6NzVbkrYhZ4X6v8h14EQ95yzvR9zNBjaZSyVGj97qANpktdEKGC7Qd6WsU3CRVmdZyeZdgRWPNgQSgxBXZUb5oyyDV7BPk9q3uNKco4sa4"
	testG2       = "tpubD6NzVbkrYhZ4YrNHw4xJMw5WKxodLaaQ1KfQEJGHj1HFSMn5xb7VvS1SWT3o8wgWxX1BYhZpesPJJiy2W9gmtARJbK8XMLVs7vr9vzoj8Xz"
	testG3       = "tpubD6NzVbkrYhZ4XmmLbnus3k8DTutwnP6W8ewoYvULeEYXNNbvWmCgJmaEWRnFN4YQG3HRDc5wQa55uodp311BneVecLSAkNK6v7Jj8BS5L6B"
)

func TestCLI(t *testing.T) {
	var out bytes.Buffer
	if err := cli([]string{"version"}, &out); err != nil || !strings.Contains(out.String(), version) {
		t.Fatalf("version: %v %q", err, out.String())
	}
	out.Reset()
	args := []string{"-activist", testActivist, "-guardian1", testG1, "-guardian2", testG2, "-guardian3", testG3}
	if err := cli(args, &out); err != nil {
		t.Fatal(err)
	}
	s := out.String()
	for _, want := range []string{
		"wsh(or_d(pk(", "multi(2,", "#",
		"tb1q",
		"anwani", // Kiswahili label present
		"mwanaharakati",
		"signet pekee",
	} {
		if !strings.Contains(s, want) {
			t.Errorf("output lacks %q:\n%s", want, s)
		}
	}
}

func TestCLIFailures(t *testing.T) {
	cases := [][]string{
		{},
		{"nope", "extra"},
		{"-activist", testActivist}, // missing guardians
		{"-activist", testActivist, "-guardian1", testG1, "-guardian2", testG2, "-guardian3", testG3, "-network", "mainnet"},
		{"-activist", "xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet8", "-guardian1", testG1, "-guardian2", testG2, "-guardian3", testG3},
		{"-activist", testActivist, "-guardian1", testG1, "-guardian2", testG2, "-guardian3", testG3, "extra"},
	}
	for _, args := range cases {
		var out bytes.Buffer
		if err := cli(args, &out); err == nil {
			t.Errorf("accepted %v", args)
		}
	}
}
