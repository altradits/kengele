package ots

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

const (
	// acceptHeader is the media type calendars serve timestamps as.
	acceptHeader = "application/vnd.opentimestamps.v1"
	// maxResponseLength caps calendar responses, as in the reference client.
	maxResponseLength = 10000
	// defaultTimeout bounds one calendar request.
	defaultTimeout = 20 * time.Second
	// userAgent identifies this client to calendar operators.
	userAgent = "kengele-ots/1.0"
)

// Calendar is one public OpenTimestamps calendar server. The zero value is
// usable once URL is set. Only the 32-byte commitment is ever sent; see
// the package comment.
type Calendar struct {
	URL string
	// HTTP overrides the default client (timeouts, redirect policy).
	HTTP *http.Client
}

// checkURL rejects anything but http(s) URLs.
func checkURL(raw string) error {
	u, err := url.ParseRequestURI(raw)
	if err != nil {
		return fmt.Errorf("ots: bad calendar URL %q: %w", raw, err)
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return fmt.Errorf("ots: bad calendar URL %q: scheme must be http or https", raw)
	}
	return nil
}

func (c *Calendar) client() *http.Client {
	if c.HTTP != nil {
		return c.HTTP
	}
	return &http.Client{
		Timeout: defaultTimeout,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			// Like the reference client: follow only to https on the
			// same host, so a calendar cannot bounce us elsewhere.
			if req.URL.Scheme != "https" || req.URL.Hostname() != via[len(via)-1].URL.Hostname() {
				return http.ErrUseLastResponse
			}
			if len(via) >= 5 {
				return http.ErrUseLastResponse
			}
			return nil
		},
	}
}

func (c *Calendar) endpoint(path string) string {
	return strings.TrimSuffix(c.URL, "/") + path
}

// Submit sends a 32-byte commitment and parses the calendar's timestamp
// for it.
func (c *Calendar) Submit(ctx context.Context, commitment []byte) (*Timestamp, error) {
	if err := checkURL(c.URL); err != nil {
		return nil, err
	}
	if len(commitment) == 0 {
		return nil, fmt.Errorf("ots: empty commitment")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.endpoint("/digest"), bytes.NewReader(commitment))
	if err != nil {
		return nil, fmt.Errorf("ots: submit to %s: %w", c.URL, err)
	}
	req.Header.Set("Accept", acceptHeader)
	req.Header.Set("User-Agent", userAgent)
	return c.roundTrip(req, commitment)
}

// Get fetches the calendar's current timestamp for a commitment. It
// returns ErrNotFound when the calendar has no such commitment (yet).
func (c *Calendar) Get(ctx context.Context, commitment []byte) (*Timestamp, error) {
	if err := checkURL(c.URL); err != nil {
		return nil, err
	}
	hex := strings.ToLower(fmt.Sprintf("%x", commitment))
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.endpoint("/timestamp/"+hex), nil)
	if err != nil {
		return nil, fmt.Errorf("ots: get from %s: %w", c.URL, err)
	}
	req.Header.Set("Accept", acceptHeader)
	req.Header.Set("User-Agent", userAgent)

	resp, err := c.client().Do(req)
	if err != nil {
		return nil, fmt.Errorf("ots: get from %s: %w", c.URL, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound {
		return nil, ErrNotFound
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("ots: get from %s: status %d", c.URL, resp.StatusCode)
	}
	return readBody(resp.Body, c.URL, commitment)
}

func (c *Calendar) roundTrip(req *http.Request, commitment []byte) (*Timestamp, error) {
	resp, err := c.client().Do(req)
	if err != nil {
		return nil, fmt.Errorf("ots: submit to %s: %w", c.URL, err)
	}
	defer resp.Body.Close()
	// Only the status code is reported, never server bytes.
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("ots: submit to %s: status %d", c.URL, resp.StatusCode)
	}
	return readBody(resp.Body, c.URL, commitment)
}

func readBody(r io.Reader, calURL string, commitment []byte) (*Timestamp, error) {
	raw, err := io.ReadAll(io.LimitReader(r, maxResponseLength+1))
	if err != nil {
		return nil, fmt.Errorf("ots: read from %s: %w", calURL, err)
	}
	if len(raw) > maxResponseLength {
		return nil, fmt.Errorf("ots: response from %s exceeded size limit", calURL)
	}
	ts, err := ParseTimestamp(commitment, raw)
	if err != nil {
		return nil, fmt.Errorf("ots: bad timestamp from %s: %w", calURL, err)
	}
	return ts, nil
}
