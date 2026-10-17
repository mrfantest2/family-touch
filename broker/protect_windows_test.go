//go:build windows

package main

import (
	"bytes"
	"testing"
)

func TestNativeDPAPI(t *testing.T) {
	p := nativeProtector{}
	clear := []byte("test-private-state")
	encrypted, e := p.Protect(clear)
	if e != nil {
		t.Fatal(e)
	}
	if bytes.Contains(encrypted, clear) {
		t.Fatal("plaintext state")
	}
	result, e := p.Unprotect(encrypted)
	if e != nil || !bytes.Equal(clear, result) {
		t.Fatal("DPAPI roundtrip failed", e)
	}
	encrypted[len(encrypted)/2] ^= 1
	if _, e = p.Unprotect(encrypted); e == nil {
		t.Fatal("corrupt state accepted")
	}
}
