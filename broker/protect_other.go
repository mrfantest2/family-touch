//go:build !windows

package main

import (
	"errors"
	"os"
)

type nativeProtector struct{}

func unixStateKey() ([]byte, error) {
	path := os.Getenv("FAMILY_TOUCH_STATE_KEY_FILE")
	info, err := os.Stat(path)
	if err != nil {
		return nil, err
	}
	if info.Mode().Perm()&0077 != 0 {
		return nil, errors.New("state key permissions must be private")
	}
	return protectedKey(path)
}
func (nativeProtector) Protect(data []byte) ([]byte, error) {
	key, err := unixStateKey()
	if err != nil {
		return nil, err
	}
	return sealPortable(key, data)
}
func (nativeProtector) Unprotect(data []byte) ([]byte, error) {
	key, err := unixStateKey()
	if err != nil {
		return nil, err
	}
	return openPortable(key, data)
}
func secureDirectory(dir string) error {
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	return os.Chmod(dir, 0700)
}
