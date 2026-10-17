//go:build windows

package main

import (
	"errors"
	"os"
	"os/exec"
	"os/user"
	"syscall"
	"unsafe"
)

type nativeProtector struct{}
type dataBlob struct {
	Size uint32
	Data *byte
}

func dpapi(input []byte, decrypt bool) ([]byte, error) {
	if len(input) == 0 {
		return nil, errors.New("empty protected state")
	}
	in := dataBlob{uint32(len(input)), &input[0]}
	var out dataBlob
	dll := syscall.NewLazyDLL("crypt32.dll")
	name := "CryptProtectData"
	if decrypt {
		name = "CryptUnprotectData"
	}
	proc := dll.NewProc(name)
	ok, _, err := proc.Call(uintptr(unsafe.Pointer(&in)), 0, 0, 0, 0, 1, uintptr(unsafe.Pointer(&out)))
	if ok == 0 {
		return nil, err
	}
	defer syscall.NewLazyDLL("kernel32.dll").NewProc("LocalFree").Call(uintptr(unsafe.Pointer(out.Data)))
	result := append([]byte(nil), unsafe.Slice(out.Data, int(out.Size))...)
	return result, nil
}
func (nativeProtector) Protect(b []byte) ([]byte, error)   { return dpapi(b, false) }
func (nativeProtector) Unprotect(b []byte) ([]byte, error) { return dpapi(b, true) }
func secureDirectory(dir string) error {
	if e := os.MkdirAll(dir, 0700); e != nil {
		return e
	}
	u, e := user.Current()
	if e != nil {
		return e
	}
	cmd := exec.Command("icacls.exe", dir, "/inheritance:r", "/grant:r", "*"+u.Uid+":(OI)(CI)F", "*S-1-5-18:(OI)(CI)F")
	if e = cmd.Run(); e != nil {
		return errors.New("cannot secure private directory ACL")
	}
	return nil
}
