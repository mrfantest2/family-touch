package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"testing"
	"time"
)

func TestConsumedOneTimeCredentialRotatesAfterThrottle(t *testing.T) {
	provider := &testProvider{}
	broker, err := NewBroker(t.TempDir(), identityProtector{}, provider, &testProber{})
	if err != nil {
		t.Fatal(err)
	}
	client := identity(t)
	broker.config.AutoApprove = true
	initial := client.call(broker, "/api/v1/enroll", client.enroll(true), "")
	if initial.Code != 200 {
		t.Fatalf("first enroll HTTP %d", initial.Code)
	}
	var first struct {
		State    string
		Envelope string
	}
	if err := json.Unmarshal(initial.Body.Bytes(), &first); err != nil {
		t.Fatal(err)
	}
	if first.State != "credential" || first.Envelope == "" || provider.calls != 1 {
		t.Fatal("initial credential not issued")
	}
	h := sha256.Sum256([]byte(first.Envelope))
	body := client.enroll(true)
	body["previousEnvelopeHash"] = hex.EncodeToString(h[:])

	early := client.call(broker, "/api/v1/enroll", body, "")
	if early.Code != 429 || provider.calls != 1 {
		t.Fatalf("early rotation must be rate limited: HTTP %d, calls %d", early.Code, provider.calls)
	}
	broker.devices[client.id].LastIssue = time.Now().Unix() - 61
	rotated := client.call(broker, "/api/v1/enroll", body, "")
	if rotated.Code != 200 || provider.calls != 2 {
		t.Fatalf("did not rotate consumed key: HTTP %d calls %d", rotated.Code, provider.calls)
	}
	var second struct {
		State    string
		Envelope string
	}
	if err := json.Unmarshal(rotated.Body.Bytes(), &second); err != nil {
		t.Fatal(err)
	}
	if second.State != "credential" || second.Envelope == first.Envelope {
		t.Fatal("reused an acknowledged one-time credential")
	}
	// A new client that has not attempted its key still gets a cached envelope.
	untouched := client.call(broker, "/api/v1/enroll", client.enroll(true), "")
	if untouched.Code != 200 || provider.calls != 2 {
		t.Fatal("safe cache reuse for an unapplied envelope regressed")
	}
}
