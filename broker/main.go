package main

import (
	"context"
	"strings"
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
	publicListen := flag.String("public-listen", "", "optional loopback API-only proxy listener; never exposes admin")
	tailnetListen := flag.String("tailnet-listen", "", "optional loopback API-only listener for Tailscale Serve HTTPS, with verified peer source")
	exportFile := flag.String("export-transfer", "", "export encrypted registry for standby migration, then exit")
	importFile := flag.String("import-transfer", "", "import encrypted registry into an empty state directory, then exit")
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
	if *exportFile != "" || *importFile != "" {
		key, err := protectedKey(os.Getenv("FAMILY_TOUCH_TRANSFER_KEY_FILE"))
		if err != nil {
			log.Fatal("transfer key unavailable")
		}
		target := filepath.Join(absolute, "registry.protected")
		var data []byte
		if *exportFile != "" {
			data, err = os.ReadFile(target)
			if err == nil {
				data, err = (nativeProtector{}).Unprotect(data)
			}
			if err == nil {
				data, err = sealPortable(key, data)
			}
			if err == nil {
				err = os.WriteFile(*exportFile, data, 0600)
			}
		} else {
			if _, err = os.Stat(target); !os.IsNotExist(err) {
				log.Fatal("import requires an empty state directory")
			}
			data, err = os.ReadFile(*importFile)
			if err == nil {
				data, err = openPortable(key, data)
			}
			if err == nil {
				err = secureDirectory(absolute)
			}
			if err == nil {
				data, err = (nativeProtector{}).Protect(data)
			}
			if err == nil {
				err = os.WriteFile(target, data, 0600)
			}
		}
		if err != nil {
			log.Fatal("encrypted state transfer failed")
		}
		log.Print("encrypted state transfer complete")
		return
	}
	b, e := NewBroker(absolute, nativeProtector{}, nil, nil)
	if e != nil {
		log.Fatal("protected broker initialization failed")
	}
	b.trustProxyClientIP = *trustProxy
	if *publicListen != "" {
		publicHost, _, err := net.SplitHostPort(*publicListen)
		if err != nil || net.ParseIP(publicHost) == nil || !net.ParseIP(publicHost).IsLoopback() {
			log.Fatal("public proxy listener must be loopback")
		}
		api := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path != "/api/v1/enroll" && r.URL.Path != "/api/v1/sync" {
				http.NotFound(w, r)
				return
			}
			r.Header.Set("X-FT-Public-Proxy", "1")
			r.Header.Del("X-FT-Client-IP")
			b.ServeHTTP(w, r)
		})
		proxy := &http.Server{Addr: *publicListen, Handler: api, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 25 * time.Second, WriteTimeout: 30 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 8192}
		go func() { log.Fatal(proxy.ListenAndServe()) }()
	}
	if *tailnetListen != "" {
		localHost, _, err := net.SplitHostPort(*tailnetListen)
		if err != nil || net.ParseIP(localHost) == nil || !net.ParseIP(localHost).IsLoopback() {
			log.Fatal("private Tailnet API must bind to loopback only")
		}
		privateAPI := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Method != "POST" || (r.URL.Path != "/api/v1/enroll" && r.URL.Path != "/api/v1/sync") {
				http.NotFound(w, r)
				return
			}
			remoteHost, _, err := net.SplitHostPort(r.RemoteAddr)
			if err != nil || net.ParseIP(remoteHost) == nil || !net.ParseIP(remoteHost).IsLoopback() {
				http.Error(w, "private proxy only", 403)
				return
			}
			// The LAST forwarded address is appended by the trusted Tailscale
			// Serve reverse proxy. Ignore any earlier client-supplied addresses.
			forwarded := strings.Split(r.Header.Get("X-Forwarded-For"), ",")
			source := strings.TrimSpace(forwarded[len(forwarded)-1])
			ip := net.ParseIP(source).To4()
			if ip == nil || ip[0] != 100 || ip[1] < 64 || ip[1] > 127 {
				http.Error(w, "Tailscale source required", 403)
				return
			}
			b.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), verifiedTailnetSourceKey{}, source)))
		})
		go func() {
			s := &http.Server{Addr: *tailnetListen, Handler: privateAPI, ReadHeaderTimeout: 5 * time.Second,
				ReadTimeout: 25 * time.Second, WriteTimeout: 30 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 8192}
			log.Fatal(s.ListenAndServe())
		}()
	}
	server := &http.Server{Addr: *listen, Handler: b, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 25 * time.Second, WriteTimeout: 30 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 8192}
	log.Printf("Family Touch broker listening on %s; local setup /admin", *listen)
	log.Fatal(server.ListenAndServe())
}
