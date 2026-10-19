package main

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"errors"
	"os"
)

var portableHeader = []byte("FamilyTouch registry v1\n")

func stateCipher(key []byte) (cipher.AEAD, error) {
	if len(key) != 32 {
		return nil, errors.New("state key must contain 32 bytes")
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(block)
}
func sealPortable(key, plain []byte) ([]byte, error) {
	a, err := stateCipher(key)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, a.NonceSize())
	if _, err = rand.Read(nonce); err != nil {
		return nil, err
	}
	out := append(append([]byte{}, portableHeader...), nonce...)
	return a.Seal(out, nonce, plain, portableHeader), nil
}
func openPortable(key, sealed []byte) ([]byte, error) {
	a, err := stateCipher(key)
	if err != nil {
		return nil, err
	}
	if !bytes.HasPrefix(sealed, portableHeader) || len(sealed) < len(portableHeader)+a.NonceSize()+a.Overhead() {
		return nil, errors.New("invalid protected registry")
	}
	body := sealed[len(portableHeader):]
	return a.Open(nil, body[:a.NonceSize()], body[a.NonceSize():], portableHeader)
}
func protectedKey(path string) ([]byte, error) {
	if path == "" {
		return nil, errors.New("state key file required")
	}
	info, err := os.Stat(path)
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() || info.Size() != 32 {
		return nil, errors.New("invalid state key file")
	}
	return os.ReadFile(path)
}
