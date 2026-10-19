package main

import (
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
)

func TestAdminDeleteWithTimestampAndPersistence(t *testing.T) {
	dir := t.TempDir()
	b, e := NewBroker(dir, identityProtector{}, nil, nil)
	if e != nil {
		t.Fatal(e)
	}
	id := strings.Repeat("a", 64)
	b.devices[id] = &Device{Name: "Test phone", Model: "Mock", Approved: true, CreatedAt: time.Now().Unix()}
	if e = b.save(); e != nil {
		t.Fatal(e)
	}
	before := time.Now().Unix()
	form := url.Values{"device": {id}, "action": {"delete"}, "csrf": {b.csrf}}.Encode()
	req := httptest.NewRequest("POST", "http://127.0.0.1:8797/admin/device", strings.NewReader(form))
	req.RemoteAddr = "127.0.0.1:100"
	req.Header.Set("Origin", "http://127.0.0.1:8797")
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp := httptest.NewRecorder()
	b.admin(resp, req)
	if resp.Code != 303 {
		t.Fatalf("delete status %d: %s", resp.Code, resp.Body.String())
	}
	if _, ok := b.devices[id]; ok {
		t.Fatal("deleted device still active")
	}
	if len(b.deleted) != 1 {
		t.Fatalf("audit events: %d", len(b.deleted))
	}
	event := b.deleted[0]
	if event.Name != "Test phone" || event.Model != "Mock" || event.DeletedAt < before || event.DeletedAt > time.Now().Unix() {
		t.Fatalf("invalid deletion record: %+v", event)
	}
	rebuilt, e := NewBroker(dir, identityProtector{}, nil, nil)
	if e != nil {
		t.Fatal(e)
	}
	if _, ok := rebuilt.devices[id]; ok {
		t.Fatal("device reappeared after restart")
	}
	if len(rebuilt.deleted) != 1 || rebuilt.deleted[0] != event {
		t.Fatal("deletion history not persistent")
	}
	page := httptest.NewRecorder()
	read := httptest.NewRequest("GET", "http://127.0.0.1:8797/admin", nil)
	read.RemoteAddr = "127.0.0.1:100"
	rebuilt.admin(page, read)
	if page.Code != 200 || !strings.Contains(page.Body.String(), "Test phone") || !strings.Contains(page.Body.String(), "deletion") {
		t.Fatal("audit not visible in admin")
	}
}

func TestAdminDeleteRejectsBadCSRF(t *testing.T) {
	b, e := NewBroker(t.TempDir(), identityProtector{}, nil, nil)
	if e != nil {
		t.Fatal(e)
	}
	id := strings.Repeat("b", 64)
	b.devices[id] = &Device{Name: "Stay", Approved: true}
	form := url.Values{"device": {id}, "action": {"delete"}, "csrf": {"not-valid"}}.Encode()
	req := httptest.NewRequest("POST", "http://127.0.0.1:8797/admin/device", strings.NewReader(form))
	req.RemoteAddr = "127.0.0.1:100"
	req.Header.Set("Origin", "http://127.0.0.1:8797")
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp := httptest.NewRecorder()
	b.admin(resp, req)
	if resp.Code != 403 {
		t.Fatalf("bad CSRF status %d", resp.Code)
	}
	if _, ok := b.devices[id]; !ok {
		t.Fatal("deleted despite bad CSRF")
	}
	if len(b.deleted) != 0 {
		t.Fatal("unexpected audit log")
	}
}
