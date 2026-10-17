//go:build !windows

package main

import (
	"errors"
	"os"
)

type nativeProtector struct{}

func (nativeProtector) Protect([]byte) ([]byte, error) {
	return nil, errors.New("production storage requires Windows DPAPI")
}
func (nativeProtector) Unprotect([]byte) ([]byte, error) {
	return nil, errors.New("production storage requires Windows DPAPI")
}
func secureDirectory(dir string) error { return os.MkdirAll(dir, 0700) }
