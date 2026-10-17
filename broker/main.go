package main

import (
	"flag"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"time"
)

func main() {
	listen := flag.String("listen", "127.0.0.1:8797", "loopback HTTP listener")
	state := flag.String("state-dir", filepath.Join(os.Getenv("LOCALAPPDATA"), "FamilyTouchBroker"), "private state directory outside web root")
	trustProxy := flag.Bool("trust-proxy-client-ip", false, "trust X-FT-Client-IP only after local proxy overwrites it from its verified client address")
	flag.Parse()
	host, _, e := net.SplitHostPort(*listen)
	ip := net.ParseIP(host)
	if e != nil || ip == nil || !ip.IsLoopback() {
		log.Fatal("listener must be a loopback IP")
	}
	absolute, e := filepath.Abs(*state)
	if e != nil {
		log.Fatal("invalid state directory")
	}
	b, e := NewBroker(absolute, nativeProtector{}, nil, nil)
	if e != nil {
		log.Fatal("protected broker initialization failed")
	}
	b.trustProxyClientIP = *trustProxy
	server := &http.Server{Addr: *listen, Handler: b, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 25 * time.Second, WriteTimeout: 30 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 8192}
	log.Printf("Family Touch broker listening on %s; local setup /admin", *listen)
	log.Fatal(server.ListenAndServe())
}
