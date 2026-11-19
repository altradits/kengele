package meshexit

import (
	"bytes"
	"context"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// The simulator and the gateway shim must speak the same framed protocol:
// a 2-byte big-endian length plus the payload, over TCP and over serial.
// These cross-tests pin the exact bytes on the wire and prove both paths
// carry the same fragments.

func TestEncodeFrameBytes(t *testing.T) {
	payload := []byte("over the air")
	frame := encodeFrame(payload)
	if len(frame) != 2+len(payload) {
		t.Fatalf("len=%d", len(frame))
	}
	if frame[0] != 0 || frame[1] != byte(len(payload)) {
		t.Fatalf("header=% x", frame[:2])
	}
	if !bytes.Equal(frame[2:], payload) {
		t.Fatal("payload mangled")
	}
	// A real Kengele fragment frames the same way.
	parts, err := Split(testBytes(201), 0x123456)
	if err != nil {
		t.Fatal(err)
	}
	f := encodeFrame(parts[0])
	if int(f[0])<<8|int(f[1]) != len(parts[0]) || !bytes.Equal(f[2:], parts[0]) {
		t.Fatal("fragment frame wrong")
	}
}

func TestFramedSendWritesExactBytes(t *testing.T) {
	raw, conn := net.Pipe()
	m := newFramedMesh(conn, "f", 0)
	defer m.Close()
	defer raw.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	payloads := [][]byte{[]byte("first"), testBytes(200)}
	for _, want := range payloads {
		errCh := make(chan error, 1)
		go func(p []byte) { errCh <- m.Send(ctx, p) }(want)
		hdr := make([]byte, 2)
		if _, err := io.ReadFull(raw, hdr); err != nil {
			t.Fatal(err)
		}
		n := int(hdr[0])<<8 | int(hdr[1])
		if n != len(want) {
			t.Fatalf("length %d, want %d", n, len(want))
		}
		got := make([]byte, n)
		if _, err := io.ReadFull(raw, got); err != nil {
			t.Fatal(err)
		}
		if !bytes.Equal(got, want) {
			t.Fatal("payload differs")
		}
		if err := <-errCh; err != nil {
			t.Fatalf("Send: %v", err)
		}
	}
}

func TestFramedReceivesSplitWrites(t *testing.T) {
	raw, conn := net.Pipe()
	m := newFramedMesh(conn, "f", 0)
	defer m.Close()
	defer raw.Close()
	payload := []byte("split across TCP segments")
	frame := encodeFrame(payload)
	// Header alone, then the payload in two halves: TCP may split anywhere.
	writes := [][]byte{frame[:1], frame[1:2], frame[2:5], frame[5:]}
	go func() {
		for _, w := range writes {
			if _, err := raw.Write(w); err != nil {
				return
			}
			time.Sleep(5 * time.Millisecond)
		}
	}()
	got := recv(t, m.Receive())
	if !bytes.Equal(got.Payload, payload) {
		t.Fatalf("got %q", got.Payload)
	}
}

func TestFramedBackToBackFrames(t *testing.T) {
	raw, conn := net.Pipe()
	m := newFramedMesh(conn, "f", 0)
	defer m.Close()
	defer raw.Close()
	a, b := []byte("one"), []byte("two")
	joined := append(encodeFrame(a), encodeFrame(b)...)
	go func() {
		_, _ = raw.Write(joined)
	}()
	if got := recv(t, m.Receive()); string(got.Payload) != "one" {
		t.Fatalf("first: %q", got.Payload)
	}
	if got := recv(t, m.Receive()); string(got.Payload) != "two" {
		t.Fatalf("second: %q", got.Payload)
	}
}

func TestFramedOversizeLengthDropsLink(t *testing.T) {
	raw, conn := net.Pipe()
	m := newFramedMesh(conn, "f", 0)
	defer m.Close()
	defer raw.Close()
	// Length 1000 exceeds the MTU: a protocol violation, like zero.
	go func() {
		_, _ = raw.Write([]byte{0x03, 0xE8})
	}()
	select {
	case _, ok := <-m.Receive():
		if ok {
			t.Fatal("oversize length must drop the link")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("timed out waiting for the drop")
	}
}

func TestFramedTruncatedPayloadDropsLink(t *testing.T) {
	raw, conn := net.Pipe()
	m := newFramedMesh(conn, "f", 0)
	defer m.Close()
	go func() {
		_, _ = raw.Write([]byte{0, 10}) // promises 10 bytes
		_, _ = raw.Write([]byte{1, 2, 3, 4, 5})
		_ = raw.Close() // then hangs up
	}()
	select {
	case _, ok := <-m.Receive():
		if ok {
			t.Fatal("truncated payload must not deliver")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("timed out waiting for the drop")
	}
}

// TestSimulatorFramedInterop feeds the same fragments through the simulator
// and through a framed pipe and requires byte-identical payloads and the
// same reassembled datagram.
func TestSimulatorFramedInterop(t *testing.T) {
	raw := giftWrapDatagram(t)
	parts, err := Split(raw, 0xABCDEF)
	if err != nil {
		t.Fatal(err)
	}
	// Path A: simulator.
	sim := NewSimulator(SimConfig{})
	defer sim.Close()
	for _, p := range parts {
		sim.Inject("node-1", p)
	}
	var viaSim [][]byte
	for range parts {
		viaSim = append(viaSim, recv(t, sim.Receive()).Payload)
	}
	// Path B: framed pipe.
	a, b := net.Pipe()
	ma, mb := newFramedMesh(a, "a", 0), newFramedMesh(b, "b", 0)
	defer ma.Close()
	defer mb.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	go func() {
		for _, p := range parts {
			_ = ma.Send(ctx, p)
		}
	}()
	var viaFramed [][]byte
	for range parts {
		viaFramed = append(viaFramed, recv(t, mb.Receive()).Payload)
	}
	if len(viaSim) != len(viaFramed) {
		t.Fatalf("sim %d parts, framed %d", len(viaSim), len(viaFramed))
	}
	for i := range viaSim {
		if !bytes.Equal(viaSim[i], viaFramed[i]) {
			t.Fatalf("part %d differs between sim and framed", i)
		}
	}
	// Both reassemble to the original gift wrap.
	for name, path := range map[string][][]byte{"sim": viaSim, "framed": viaFramed} {
		re := NewReassembler(time.Minute, nil)
		var out []byte
		var done bool
		for _, p := range path {
			out, _, done, err = re.Add(p)
			if err != nil {
				t.Fatalf("%s: %v", name, err)
			}
		}
		if !done || !bytes.Equal(out, raw) {
			t.Fatalf("%s did not reassemble", name)
		}
	}
}

// TestSendDatagramSameBytes proves the phone-side fragmentation is
// identical whether the exit transmits through the simulator or a framed
// gateway link.
func TestSendDatagramSameBytes(t *testing.T) {
	raw := giftWrapDatagram(t)
	const msgID = 0xC0FFEE
	pub := &stubPublisher{}

	sim := NewSimulator(SimConfig{})
	defer sim.Close()
	exSim, err := New(sim, pub, Config{})
	if err != nil {
		t.Fatal(err)
	}
	if err := exSim.SendDatagram(context.Background(), raw, msgID); err != nil {
		t.Fatal(err)
	}

	a, b := net.Pipe()
	ma, mb := newFramedMesh(a, "a", 0), newFramedMesh(b, "b", 0)
	defer ma.Close()
	defer mb.Close()
	exFramed, err := New(ma, pub, Config{})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	sendErr := make(chan error, 1)
	go func() { sendErr <- exFramed.SendDatagram(ctx, raw, msgID) }()
	var received [][]byte
	for range sim.Sent() {
		received = append(received, recv(t, mb.Receive()).Payload)
	}
	if err := <-sendErr; err != nil {
		t.Fatal(err)
	}
	sent := sim.Sent()
	if len(sent) != len(received) {
		t.Fatalf("sim %d parts, framed %d", len(sent), len(received))
	}
	for i := range sent {
		if !bytes.Equal(sent[i], received[i]) {
			t.Fatalf("part %d differs", i)
		}
	}
}

func TestDialTCPRoundTrip(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		if strings.Contains(err.Error(), "operation not permitted") {
			t.Skipf("cannot bind: %v", err)
		}
		t.Fatal(err)
	}
	defer ln.Close()
	serverErr := make(chan error, 1)
	go func() {
		c, err := ln.Accept()
		if err != nil {
			serverErr <- err
			return
		}
		defer c.Close()
		m := newFramedMesh(c, "server", 0)
		defer m.Close()
		p := recv(t, m.Receive())
		if string(p.Payload) != "hello tcp" {
			serverErr <- io.ErrUnexpectedEOF
			return
		}
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		serverErr <- m.Send(ctx, []byte("hello back"))
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	client, err := DialTCP(ctx, ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	if err := client.Send(ctx, []byte("hello tcp")); err != nil {
		t.Fatal(err)
	}
	if got := recv(t, client.Receive()); string(got.Payload) != "hello back" {
		t.Fatalf("got %q", got.Payload)
	}
	if err := <-serverErr; err != nil {
		t.Fatalf("server: %v", err)
	}
}

func TestOpenSerialErrors(t *testing.T) {
	if _, err := OpenSerial("/nonexistent-kengele-test-device"); err == nil {
		t.Fatal("missing device accepted")
	}
	if _, err := OpenSerial(""); err == nil {
		t.Fatal("empty path accepted")
	}
	if _, err := OpenSerial(t.TempDir()); err == nil {
		t.Fatal("directory accepted")
	}
}

func TestOpenSerialFramingMatchesTCP(t *testing.T) {
	// A serial link frames exactly like TCP: length + payload. The file
	// itself is the wire here; the test reads back what Send wrote.
	path := filepath.Join(t.TempDir(), "serial.bin")
	if err := os.WriteFile(path, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	m, err := OpenSerial(path)
	if err != nil {
		t.Fatal(err)
	}
	payload := []byte("serial frame")
	if err := m.Send(context.Background(), payload); err != nil {
		_ = m.Close()
		t.Fatal(err)
	}
	if err := m.Close(); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(raw, encodeFrame(payload)) {
		t.Fatalf("serial bytes %x", raw)
	}
}
