package main

import (
	"fmt"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestNativeManualReauthorizationPreservesApproval(t *testing.T) {
	b, err := NewBroker(t.TempDir(), identityProtector{}, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	id := strings.Repeat("a", 64)
	b.devices[id] = &Device{
		Name: "Approved test device", Model: "Test", Approved: true,
		Envelope: "encrypted-credential", EnvelopeExpires: 123456789, LastIssue: 123456,
	}
	perform := func() int {
		body := fmt.Sprintf("{\"id\":%q,\"action\":\"reauthorize\"}", id)
		r := httptest.NewRequest("POST", "/admin/api/v1/device", strings.NewReader(body))
		r.RemoteAddr = "127.0.0.1:12345"
		r.Header.Set("X-FT-Native-Client", "1")
		r.Header.Set("Origin", "http://"+r.Host)
		r.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		b.adminNative(w, r)
		return w.Code
	}
	if code := perform(); code != 200 {
		t.Fatalf("approved reauthorize HTTP %d", code)
	}
	d := b.devices[id]
	if !d.Approved || d.Revoked || d.Envelope != "" || d.EnvelopeExpires != 0 || d.LastIssue != 0 {
		t.Fatal("reauthorization must reset only credential issuance state")
	}
	if err := b.save(); err != nil {
		t.Fatal(err)
	}
	b, err = NewBroker(b.dir, identityProtector{}, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if !b.devices[id].Approved {
		t.Fatal("approval lost after reload")
	}
	b.devices[id].Approved = false
	b.devices[id].Revoked = true
	b.devices[id].Envelope = "old"
	if code := perform(); code != 409 {
		t.Fatalf("revoked device unexpectedly reauthorized: HTTP %d", code)
	}
	if !b.devices[id].Revoked || b.devices[id].Envelope != "old" {
		t.Fatal("revoked device state changed")
	}
}
