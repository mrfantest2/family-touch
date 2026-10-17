package main

import (
	"fmt"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestPendingExpiryDoesNotRemoveApprovedOrRevoked(t *testing.T) {
	b, _ := NewBroker(t.TempDir(), identityProtector{}, nil, nil)
	now := time.Now().Unix()
	b.devices["pending"] = &Device{CreatedAt: now - pendingTTL - 1}
	b.devices["approved"] = &Device{Approved: true, CreatedAt: 1}
	b.devices["revoked"] = &Device{Revoked: true, CreatedAt: 1}
	b.prunePending(now)
	if b.devices["pending"] != nil || b.devices["approved"] == nil || b.devices["revoked"] == nil {
		t.Fatal("incorrect expiry")
	}
	active, pending, revoked := b.capacity()
	if active != 1 || pending != 0 || revoked != 1 {
		t.Fatal(active, pending, revoked)
	}
}
func TestSourceThrottleBoundsAndProxyTrust(t *testing.T) {
	b, _ := NewBroker(t.TempDir(), identityProtector{}, nil, nil)
	now := time.Now().Unix()
	r := httptest.NewRequest("POST", "/api/v1/enroll", nil)
	r.RemoteAddr = "192.0.2.1:12"
	r.Header.Set("X-FT-Public-Proxy", "1")
	r.Header.Set("X-FT-Client-IP", "198.51.100.1")
	if sourceIP(r, true) != "192.0.2.1" {
		t.Fatal("remote spoof accepted")
	}
	r.RemoteAddr = "127.0.0.1:12"
	if sourceIP(r, false) != "127.0.0.1" {
		t.Fatal("proxy trust enabled by default")
	}
	if sourceIP(r, true) != "198.51.100.1" {
		t.Fatal("trusted explicit proxy IP rejected")
	}
	r.Header.Set("X-FT-Client-IP", "198.51.100.1, 198.51.100.2")
	if sourceIP(r, true) != "127.0.0.1" {
		t.Fatal("ambiguous proxy IP trusted")
	}
	for i := 0; i < sourceRequestLimit; i++ {
		if !b.allowSource("source", now) {
			t.Fatal("early throttle")
		}
	}
	if b.allowSource("source", now) {
		t.Fatal("unbounded source")
	}
	if !b.allowSource("source", now+60) {
		t.Fatal("window did not expire")
	}
	b.sources = map[string]sourceWindow{}
	for i := 0; i < maxSources; i++ {
		b.sources[string(rune(i))] = sourceWindow{Start: now}
	}
	if b.allowSource("new", now) {
		t.Fatal("unbounded source map")
	}
	if !b.allowSource("new", now+60) {
		t.Fatal("expired sources not reclaimed")
	}
}
func TestPendingPoolSeparateAndCapacityReclaimed(t *testing.T) {
	b, _ := NewBroker(t.TempDir(), identityProtector{}, nil, nil)
	now := time.Now().Unix()
	for i := 0; i < maxPending; i++ {
		b.devices[fmt.Sprint("pending", i)] = &Device{CreatedAt: now}
	}
	c := identity(t)
	if w := c.call(b, "/api/v1/enroll", c.enroll(false), ""); w.Code != 503 {
		t.Fatal("pending capacity not enforced", w.Code)
	}
	b.config.AutoApprove = true
	if w := c.call(b, "/api/v1/enroll", c.enroll(false), ""); w.Code != 200 || !b.devices[c.id].Approved {
		t.Fatal("pending pool consumed approved capacity", w.Code)
	}
	delete(b.devices, c.id)
	b.config.AutoApprove = false
	for _, d := range b.devices {
		d.CreatedAt = now - pendingTTL
	}
	if w := c.call(b, "/api/v1/enroll", c.enroll(false), ""); w.Code != 200 {
		t.Fatal("expired pending capacity not reclaimed", w.Code)
	}
	if len(b.devices) != 1 {
		t.Fatal("expired records retained")
	}
	created := b.devices[c.id].CreatedAt
	if w := c.call(b, "/api/v1/enroll", c.enroll(false), ""); w.Code != 200 || b.devices[c.id].CreatedAt != created {
		t.Fatal("polling extended pending lifetime")
	}
}
func TestRevokedRecordsDoNotConsumeApprovedCapacity(t *testing.T) {
	b, _ := NewBroker(t.TempDir(), identityProtector{}, nil, nil)
	for i := 0; i < maxDevices; i++ {
		b.devices[fmt.Sprint(i)] = &Device{Revoked: true}
	}
	b.config.AutoApprove = true
	c := identity(t)
	if w := c.call(b, "/api/v1/enroll", c.enroll(false), ""); w.Code != 200 {
		t.Fatal("revocations consumed approved slots", w.Code)
	}
	b.devices[c.id].Approved = false
	b.devices[c.id].Revoked = true
	if w := c.call(b, "/api/v1/enroll", c.enroll(false), ""); w.Code != 403 {
		t.Fatal("revoked identity reauthorized", w.Code)
	}
}
func TestHTTPThrottleRunsBeforeParsing(t *testing.T) {
	b, _ := NewBroker(t.TempDir(), identityProtector{}, nil, nil)
	for i := 0; i <= sourceRequestLimit; i++ {
		r := httptest.NewRequest("POST", "/api/v1/enroll", strings.NewReader("not json"))
		w := httptest.NewRecorder()
		b.ServeHTTP(w, r)
		want := 400
		if i == sourceRequestLimit {
			want = 429
		}
		if w.Code != want {
			t.Fatal(i, w.Code)
		}
	}
	if len(b.devices) != 0 {
		t.Fatal("invalid requests registered")
	}
}
