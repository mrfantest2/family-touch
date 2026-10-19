package main

import (
	"bytes"
	"testing"
)

func TestPortableStateAuthenticatedAndKeySpecific(t *testing.T) {
	key := bytes.Repeat([]byte{7}, 32)
	sealed, err := sealPortable(key, []byte("private registry"))
	if err != nil {
		t.Fatal(err)
	}
	opened, err := openPortable(key, sealed)
	if err != nil || string(opened) != "private registry" {
		t.Fatal("roundtrip failed")
	}
	sealed[len(sealed)-1] ^= 1
	if _, err := openPortable(key, sealed); err == nil {
		t.Fatal("tampering accepted")
	}
	if _, err := openPortable(bytes.Repeat([]byte{8}, 32), sealed); err == nil {
		t.Fatal("wrong key accepted")
	}
	if _, err := sealPortable([]byte{1}, []byte("x")); err == nil {
		t.Fatal("short key accepted")
	}
}
