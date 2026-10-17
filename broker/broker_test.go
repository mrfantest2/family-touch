package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestEnrollmentReplayAndAdmission(t *testing.T) {
	b, err := NewBroker(t.TempDir(), identityProtector{}, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	k, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	spki, _ := x509.MarshalPKIXPublicKey(&k.PublicKey)
	hash := sha256.Sum256(spki)
	id := hex.EncodeToString(hash[:])
	rsaKey, _ := rsa.GenerateKey(rand.Reader, 3072)
	rsaDER, _ := x509.MarshalPKIXPublicKey(&rsaKey.PublicKey)
	rsaPub := base64.StdEncoding.EncodeToString(rsaDER)
	body := `{"publicKey":"` + base64.StdEncoding.EncodeToString(spki) + `","rsaPublicKey":"` + rsaPub + `","rsaDeviceId":"` + fingerprint(rsaPub) + `","name":"Test","profileId":"00000000-0000-4000-8000-000000000001"}`
	request := func(nonce string) int {
		r := httptest.NewRequest("POST", "/api/v1/enroll", strings.NewReader(body))
		ts := time.Now().Format("2006")
		_ = ts
		stamp := fmtUnix()
		r.Header.Set("X-FT-Device", id)
		r.Header.Set("X-FT-Time", stamp)
		r.Header.Set("X-FT-Nonce", nonce)
		digest := sha256.Sum256([]byte(canonical(r, id, stamp, nonce, []byte(body))))
		sig, _ := ecdsa.SignASN1(rand.Reader, k, digest[:])
		r.Header.Set("X-FT-Signature", base64.StdEncoding.EncodeToString(sig))
		w := httptest.NewRecorder()
		b.ServeHTTP(w, r)
		return w.Code
	}
	if c := request("0123456789abcdef0123456789abcdef"); c != 200 {
		t.Fatal(c)
	}
	if b.devices[id].Approved {
		t.Fatal("default admission must be pending")
	}
	b, err = NewBroker(b.dir, identityProtector{}, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if c := request("0123456789abcdef0123456789abcdef"); c != 401 {
		t.Fatal("replay accepted", c)
	}
	if c := request("invalid"); c != 401 {
		t.Fatal("short nonce accepted", c)
	}
}

func TestPairSecretStableSymmetric(t *testing.T) {
	b, e := NewBroker(t.TempDir(), identityProtector{}, nil, nil)
	if e != nil {
		t.Fatal(e)
	}
	if b.pair("a", "b") != b.pair("b", "a") {
		t.Fatal("asymmetric")
	}
	c, e := NewBroker(b.dir, identityProtector{}, nil, nil)
	if e != nil {
		t.Fatal(e)
	}
	if b.pair("a", "b") != c.pair("a", "b") {
		t.Fatal("unstable")
	}
	if b.pair("a", "b") == b.pair("a", "c") {
		t.Fatal("shared token")
	}
}
func TestAdminRejectsProxyAndRemote(t *testing.T) {
	b, _ := NewBroker(t.TempDir(), identityProtector{}, nil, nil)
	for _, remote := range []string{"192.0.2.1:12", "127.0.0.1:12"} {
		r := httptest.NewRequest("GET", "/admin", nil)
		r.RemoteAddr = remote
		if strings.HasPrefix(remote, "127.") {
			r.Header.Set("X-Forwarded-For", "127.0.0.1")
		}
		w := httptest.NewRecorder()
		b.ServeHTTP(w, r)
		if w.Code != 403 {
			t.Fatal(w.Code)
		}
	}
}
