package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha1"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestTCPProbeSignatureDomain(t *testing.T) {
	k, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	for _, valid := range []bool{true, false} {
		probe := tcpProber{dial: func(context.Context, string, string) (net.Conn, error) {
			a, b := net.Pipe()
			go func() {
				defer b.Close()
				var req map[string]string
				_ = json.NewDecoder(b).Decode(&req)
				domain := "FamilyTouch probe v1\n"
				if !valid {
					domain = "POST\n"
				}
				hash := sha256.Sum256([]byte(domain + req["challenge"]))
				sig, _ := ecdsa.SignASN1(rand.Reader, k, hash[:])
				_ = json.NewEncoder(b).Encode(map[string]any{"ok": true, "device": "fingerprint", "signature": base64.StdEncoding.EncodeToString(sig)})
			}()
			return a, nil
		}}
		e := probe.Verify(context.Background(), "100.64.0.1", "fingerprint", &k.PublicKey)
		if (e == nil) != valid {
			t.Fatalf("probe validity %v: %v", valid, e)
		}
	}
}

type testProvider struct {
	calls int
	fail  bool
}

func (p *testProvider) AuthKey(context.Context) (string, error) {
	p.calls++
	if p.fail {
		return "", errors.New("secret must never appear")
	}
	return "test-single-use-key", nil
}

type testProber struct {
	fail  bool
	calls int
}

func (p *testProber) Verify(context.Context, string, string, *ecdsa.PublicKey) error {
	p.calls++
	if p.fail {
		return errors.New("bad proof")
	}
	return nil
}

type clientIdentity struct {
	k   *ecdsa.PrivateKey
	rsa *rsa.PrivateKey
	pub string
	id  string
}

func (c clientIdentity) profile() string {
	s := c.id[:32]
	return s[:8] + "-" + s[8:12] + "-" + s[12:16] + "-" + s[16:20] + "-" + s[20:]
}

func identity(t *testing.T) clientIdentity {
	t.Helper()
	k, e := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if e != nil {
		t.Fatal(e)
	}
	rsaKey, e := rsa.GenerateKey(rand.Reader, 3072)
	if e != nil {
		t.Fatal(e)
	}
	spki, _ := x509.MarshalPKIXPublicKey(&k.PublicKey)
	pub := base64.StdEncoding.EncodeToString(spki)
	return clientIdentity{k, rsaKey, pub, fingerprint(pub)}
}
func (c clientIdentity) call(b *Broker, path string, body any, stamp string) *httptest.ResponseRecorder {
	raw, _ := json.Marshal(body)
	r := httptest.NewRequest("POST", path, strings.NewReader(string(raw)))
	nonce := randomHex(16)
	if stamp == "" {
		stamp = fmtUnix()
	}
	r.Header.Set("X-FT-Device", c.id)
	r.Header.Set("X-FT-Time", stamp)
	r.Header.Set("X-FT-Nonce", nonce)
	h := sha256.Sum256([]byte(canonical(r, c.id, stamp, nonce, raw)))
	sig, _ := ecdsa.SignASN1(rand.Reader, c.k, h[:])
	r.Header.Set("X-FT-Signature", base64.StdEncoding.EncodeToString(sig))
	w := httptest.NewRecorder()
	b.ServeHTTP(w, r)
	return w
}
func (c clientIdentity) enroll(needs bool) map[string]any {
	raw, _ := x509.MarshalPKIXPublicKey(&c.rsa.PublicKey)
	pub := base64.StdEncoding.EncodeToString(raw)
	return map[string]any{"publicKey": c.pub, "rsaPublicKey": pub, "rsaDeviceId": fingerprint(pub), "profileId": c.profile(), "name": "Family", "model": "Test", "needsAuth": needs}
}
func TestEncryptedCredentialCacheAndFailure(t *testing.T) {
	p := &testProvider{}
	b, _ := NewBroker(t.TempDir(), identityProtector{}, p, &testProber{})
	c := identity(t)
	b.config.AutoApprove = true
	w := c.call(b, "/api/v1/enroll", c.enroll(true), "")
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	var response struct{ State, Envelope string }
	_ = json.Unmarshal(w.Body.Bytes(), &response)
	encrypted, e := base64.StdEncoding.DecodeString(response.Envelope)
	if e != nil {
		t.Fatal(e)
	}
	clear, e := rsa.DecryptOAEP(sha1.New(), rand.Reader, c.rsa, encrypted, nil)
	if e != nil {
		t.Fatal(e)
	}
	var payload struct {
		Device, Key string
		Expires     int64
	}
	if json.Unmarshal(clear, &payload) != nil || payload.Key != "test-single-use-key" || payload.Expires <= time.Now().Unix() {
		t.Fatal("invalid encrypted payload")
	}
	if strings.Contains(w.Body.String(), payload.Key) {
		t.Fatal("plaintext key leak")
	}
	w = c.call(b, "/api/v1/enroll", c.enroll(true), "")
	if w.Code != 200 || p.calls != 1 {
		t.Fatal("cache failed")
	}
	b.devices[c.id].EnvelopeExpires = 0
	b.devices[c.id].LastIssue = 0
	p.fail = true
	w = c.call(b, "/api/v1/enroll", c.enroll(true), "")
	if w.Code != 503 || strings.Contains(w.Body.String(), "secret") {
		t.Fatal("provider failure leaked")
	}
}
func TestRosterProofRevocationAndPrivateRange(t *testing.T) {
	probe := &testProber{}
	b, _ := NewBroker(t.TempDir(), identityProtector{}, &testProvider{}, probe)
	a, c := identity(t), identity(t)
	b.config.AutoApprove = true
	for _, client := range []clientIdentity{a, c} {
		if w := client.call(b, "/api/v1/enroll", client.enroll(false), ""); w.Code != 200 {
			t.Fatal(w.Code)
		}
	}
	syncBody := func(ip string) map[string]any {
		client := a
		if ip == "100.64.0.2" {
			client = c
		}
		return map[string]any{"ip": ip, "name": "Family", "profileId": client.profile()}
	}
	if w := a.call(b, "/api/v1/sync", syncBody("192.168.1.1"), ""); w.Code != 400 {
		t.Fatal("non private VPN address accepted")
	}
	probe.fail = true
	if w := a.call(b, "/api/v1/sync", syncBody("100.64.0.1"), ""); w.Code != 403 {
		t.Fatal("invalid proof accepted")
	}
	probe.fail = false
	if w := a.call(b, "/api/v1/sync", syncBody("100.64.0.1"), ""); w.Code != 200 {
		t.Fatal(w.Code)
	}
	w := c.call(b, "/api/v1/sync", syncBody("100.64.0.2"), "")
	if w.Code != 200 || !strings.Contains(w.Body.String(), b.pair(a.id, c.id)) {
		t.Fatal("missing pair token", w.Code)
	}
	b.devices[a.id].Revoked = true
	w = c.call(b, "/api/v1/sync", syncBody("100.64.0.2"), "")
	if strings.Contains(w.Body.String(), "100.64.0.1") {
		t.Fatal("revoked peer disclosed")
	}
	w = a.call(b, "/api/v1/sync", syncBody("100.64.0.1"), "")
	if w.Code != 403 {
		t.Fatal("revoked device accepted")
	}
	w = c.call(b, "/api/v1/sync", syncBody("100.64.0.2"), "1")
	if w.Code != 401 {
		t.Fatal("stale request accepted")
	}
}
func TestAdminCSRFAndOrigin(t *testing.T) {
	b, _ := NewBroker(t.TempDir(), identityProtector{}, nil, nil)
	for _, origin := range []string{"", "https://evil.example", "http://127.0.0.1:8797"} {
		form := "csrf=wrong&id=id&secret=secret&admission=auto"
		r := httptest.NewRequest("POST", "http://127.0.0.1:8797/admin/setup", strings.NewReader(form))
		r.RemoteAddr = "127.0.0.1:100"
		r.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		r.Header.Set("Origin", origin)
		w := httptest.NewRecorder()
		b.ServeHTTP(w, r)
		if w.Code != 403 {
			t.Fatal("unsafe setup", w.Code)
		}
	}
	if b.config.AutoApprove {
		t.Fatal("setup changed")
	}
	form := "csrf=" + b.csrf + "&id=test-id&secret=test-secret&admission=approval"
	r := httptest.NewRequest("POST", "http://127.0.0.1:8797/admin/setup", strings.NewReader(form))
	r.RemoteAddr = "127.0.0.1:100"
	r.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	r.Header.Set("Origin", "http://127.0.0.1:8797")
	w := httptest.NewRecorder()
	b.ServeHTTP(w, r)
	if w.Code != 303 {
		t.Fatal("valid setup rejected", w.Code)
	}
	c, e := NewBroker(b.dir, identityProtector{}, nil, nil)
	if e != nil || c.config.OAuthSecret != "test-secret" || c.config.AutoApprove {
		t.Fatal("configuration persistence", e)
	}
}
