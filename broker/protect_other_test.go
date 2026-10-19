//go:build !windows

package main

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
)

func TestUnixRegistryNeedsPrivateKeyAndRejectsTampering(t *testing.T) {
	path := filepath.Join(t.TempDir(), "key")
	if err := os.WriteFile(path, bytes.Repeat([]byte{9}, 32), 0600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("FAMILY_TOUCH_STATE_KEY_FILE", path)
	p := nativeProtector{}
	sealed, err := p.Protect([]byte("sensitive fixture"))
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(sealed, []byte("sensitive fixture")) {
		t.Fatal("plaintext state")
	}
	plain, err := p.Unprotect(sealed)
	if err != nil || string(plain) != "sensitive fixture" {
		t.Fatal("roundtrip")
	}
	sealed[len(sealed)-1] ^= 1
	if _, err = p.Unprotect(sealed); err == nil {
		t.Fatal("tampered state accepted")
	}
	if err = os.Chmod(path, 0644); err != nil {
		t.Fatal(err)
	}
	if _, err = p.Protect([]byte("x")); err == nil {
		t.Fatal("public keyfile permissions accepted")
	}
}
