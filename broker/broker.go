package main

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/hmac"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha1"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"html/template"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	maxDevices               = 1024 // Approved devices; pending identities cannot consume these slots.
	maxPending               = 128
	maxRevoked               = 2048 // Bounded lifetime approved identities, including retained deny records.
	pendingTTL         int64 = 24 * 60 * 60
	maxSources               = 4096
	sourceRequestLimit       = 120
	maxDeletionHistory       = 250 // Bounded deletion history; no removed keys or device IDs retained.
)

type sourceWindow struct {
	Start int64
	Count int
}

type Protector interface {
	Protect([]byte) ([]byte, error)
	Unprotect([]byte) ([]byte, error)
}
type identityProtector struct{}

func (identityProtector) Protect(b []byte) ([]byte, error)   { return b, nil }
func (identityProtector) Unprotect(b []byte) ([]byte, error) { return b, nil }

type KeyProvider interface {
	AuthKey(context.Context) (string, error)
}
type IPProber interface {
	Verify(context.Context, string, string, *ecdsa.PublicKey) error
}
// Private Tailnet Serve requests carry the authenticated WireGuard source IP.
// Only the isolated tailnet-only listener in main.go may set this context key.
type verifiedTailnetSourceKey struct{}
type Device struct {
	PublicKey       string
	RSA             string
	RSADeviceID     string
	ProfileID       string
	Name            string
	Model           string
	Approved        bool
	Revoked         bool
	IP              string
	Envelope        string
	EnvelopeExpires int64
	LastIssue       int64
	CreatedAt       int64
}
type DeletedRecord struct {
	Name string
	Model string
	DeletedAt int64
}
type Config struct {
	OAuthID     string
	OAuthSecret string
	AutoApprove bool
	Tailnet     string
}
type diskState struct {
	Master  []byte
	Config  Config
	Devices map[string]*Device
	Nonces  map[string]int64
	Deleted []DeletedRecord
}
type Broker struct {
	mu                 sync.Mutex
	dir                string
	protector          Protector
	provider           KeyProvider
	prober             IPProber
	config             Config
	master             []byte
	devices            map[string]*Device
	nonces             map[string]int64
	deleted            []DeletedRecord
	csrf               string
	sources            map[string]sourceWindow
	trustProxyClientIP bool
}

func randomHex(n int) string {
	b := make([]byte, n)
	if _, e := rand.Read(b); e != nil {
		panic(e)
	}
	return hex.EncodeToString(b)
}
func fmtUnix() string { return strconv.FormatInt(time.Now().Unix(), 10) }
func NewBroker(dir string, p Protector, k KeyProvider, probe IPProber) (*Broker, error) {
	if p == nil {
		return nil, errors.New("protected storage required")
	}
	if err := secureDirectory(dir); err != nil {
		return nil, err
	}
	b := &Broker{dir: dir, protector: p, provider: k, prober: probe, devices: map[string]*Device{}, nonces: map[string]int64{}, sources: map[string]sourceWindow{}, csrf: randomHex(32)}
	data, e := os.ReadFile(filepath.Join(dir, "registry.protected"))
	if e == nil {
		plain, e := p.Unprotect(data)
		if e != nil {
			return nil, e
		}
		var s diskState
		if e = json.Unmarshal(plain, &s); e != nil {
			return nil, e
		}
		b.master = s.Master
		b.config = s.Config
		b.devices = s.Devices
		b.deleted = s.Deleted
		if len(b.deleted) > maxDeletionHistory {
			b.deleted = b.deleted[len(b.deleted)-maxDeletionHistory:]
		}
		if s.Nonces != nil {
			b.nonces = s.Nonces
		}
	} else if !os.IsNotExist(e) {
		return nil, e
	}
	if b.devices == nil {
		b.devices = map[string]*Device{}
	}
	b.prunePending(time.Now().Unix())
	if len(b.devices) > maxPending+maxRevoked {
		return nil, errors.New("registry exceeds limit")
	}
	if len(b.master) == 0 {
		b.master = make([]byte, 32)
		if _, e = rand.Read(b.master); e != nil {
			return nil, e
		}
		if e = b.save(); e != nil {
			return nil, e
		}
	}
	if len(b.master) != 32 {
		return nil, errors.New("invalid master")
	}
	if b.prober == nil {
		b.prober = tcpProber{}
	}
	return b, nil
}
func (b *Broker) save() error {
	plain, e := json.Marshal(diskState{b.master, b.config, b.devices, b.nonces, b.deleted})
	if e != nil {
		return e
	}
	data, e := b.protector.Protect(plain)
	if e != nil {
		return e
	}
	tmp := filepath.Join(b.dir, "registry.tmp")
	if e = os.WriteFile(tmp, data, 0600); e != nil {
		return e
	}
	return os.Rename(tmp, filepath.Join(b.dir, "registry.protected"))
}

// Only the explicit header overwritten by the local reverse proxy is trusted.
// X-Forwarded-For is intentionally ignored, including chains and client input.
func sourceIP(r *http.Request, trustProxy bool) string {
	host, _, e := net.SplitHostPort(r.RemoteAddr)
	if e != nil {
		return "unknown"
	}
	peer := net.ParseIP(host)
	if peer == nil {
		return "unknown"
	}
	if trustProxy && peer.IsLoopback() && r.Header.Get("X-FT-Public-Proxy") == "1" {
		if forwarded := net.ParseIP(r.Header.Get("X-FT-Client-IP")); forwarded != nil {
			return forwarded.String()
		}
	}
	return peer.String()
}
func (b *Broker) allowSource(source string, now int64) bool {
	for key, window := range b.sources {
		if now-window.Start >= 60 {
			delete(b.sources, key)
		}
	}
	window, exists := b.sources[source]
	if !exists {
		if len(b.sources) >= maxSources {
			return false
		}
		window = sourceWindow{Start: now}
	}
	if window.Count >= sourceRequestLimit {
		return false
	}
	window.Count++
	b.sources[source] = window
	return true
}
func (b *Broker) prunePending(now int64) {
	for id, d := range b.devices {
		if !d.Approved && !d.Revoked && now-d.CreatedAt >= pendingTTL {
			delete(b.devices, id)
		}
	}
}
func (b *Broker) capacity() (approved, pending, revoked int) {
	for _, d := range b.devices {
		if d.Revoked {
			revoked++
		} else if d.Approved {
			approved++
		} else {
			pending++
		}
	}
	return
}
func canonical(r *http.Request, id, ts, nonce string, body []byte) string {
	h := sha256.Sum256(body)
	return r.Method + "\n" + r.URL.Path + "\n" + id + "\n" + ts + "\n" + nonce + "\n" + hex.EncodeToString(h[:])
}
func parseEC(s string) (*ecdsa.PublicKey, error) {
	data, e := base64.StdEncoding.DecodeString(s)
	if e != nil {
		return nil, e
	}
	v, e := x509.ParsePKIXPublicKey(data)
	if e != nil {
		return nil, e
	}
	k, ok := v.(*ecdsa.PublicKey)
	if !ok || k.Curve.Params().Name != "P-256" {
		return nil, errors.New("invalid signing key")
	}
	return k, nil
}
func fingerprint(s string) string {
	d, e := base64.StdEncoding.DecodeString(s)
	if e != nil {
		return ""
	}
	h := sha256.Sum256(d)
	return hex.EncodeToString(h[:])
}
func validProfile(s string) bool {
	if len(s) != 36 || s[8] != '-' || s[13] != '-' || s[18] != '-' || s[23] != '-' {
		return false
	}
	raw, e := hex.DecodeString(strings.ReplaceAll(s, "-", ""))
	return e == nil && len(raw) == 16
}
func jsonReply(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
func (b *Broker) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	if strings.HasPrefix(r.URL.Path, "/admin") {
		b.admin(w, r)
		return
	}
	if r.Method != "POST" || (r.URL.Path != "/api/v1/enroll" && r.URL.Path != "/api/v1/sync") {
		http.NotFound(w, r)
		return
	}
	b.mu.Lock()
	allowed := b.allowSource(sourceIP(r, b.trustProxyClientIP), time.Now().Unix())
	b.mu.Unlock()
	if !allowed {
		w.Header().Set("Retry-After", "60")
		http.Error(w, "retry later", 429)
		return
	}
	body, e := io.ReadAll(http.MaxBytesReader(w, r.Body, 16384))
	if e != nil {
		http.Error(w, "invalid request", 413)
		return
	}
	var req struct {
		PublicKey            string `json:"publicKey"`
		RSAPublicKey         string `json:"rsaPublicKey"`
		RSADeviceID          string `json:"rsaDeviceId"`
		ProfileID            string `json:"profileId"`
		Name                 string `json:"name"`
		Model                string `json:"model"`
		NeedsAuth            bool   `json:"needsAuth"`
		PreviousEnvelopeHash string `json:"previousEnvelopeHash"`
		IP                   string `json:"ip"`
	}
	if json.Unmarshal(body, &req) != nil || len([]rune(req.Name)) > 36 || len([]rune(req.Model)) > 80 || !validProfile(req.ProfileID) {
		http.Error(w, "invalid request", 400)
		return
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	id, ts, nonce := r.Header.Get("X-FT-Device"), r.Header.Get("X-FT-Time"), r.Header.Get("X-FT-Nonce")
	stamp, e := strconv.ParseInt(ts, 10, 64)
	now := time.Now().Unix()
	b.prunePending(now)
	nonceBytes, ne := hex.DecodeString(nonce)
	d := b.devices[id]
	pub := req.PublicKey
	if d != nil {
		pub = d.PublicKey
	}
	key, ke := parseEC(pub)
	sig, se := base64.StdEncoding.DecodeString(r.Header.Get("X-FT-Signature"))
	digest := sha256.Sum256([]byte(canonical(r, id, ts, nonce, body)))
	for n, t := range b.nonces {
		if t < now-90 {
			delete(b.nonces, n)
		}
	}
	_, replay := b.nonces[id+":"+nonce]
	if e != nil || stamp < now-90 || stamp > now+90 || ne != nil || len(nonceBytes) < 16 || len(nonceBytes) > 64 || len(id) != 64 || fingerprint(pub) != id || ke != nil || se != nil || replay || len(b.nonces) >= 16384 || !ecdsa.VerifyASN1(key, digest[:], sig) {
		http.Error(w, "unauthorized", 401)
		return
	}
	b.nonces[id+":"+nonce] = stamp
	if b.save() != nil {
		http.Error(w, "storage unavailable", 503)
		return
	}
	if d != nil && d.ProfileID != "" && d.ProfileID != req.ProfileID {
		http.Error(w, "profile identity cannot change", 409)
		return
	}
	for other, peer := range b.devices {
		if other != id && peer.ProfileID == req.ProfileID && !peer.Revoked {
			http.Error(w, "profile identity already registered", 409)
			return
		}
	}
	if r.URL.Path == "/api/v1/enroll" {
		if req.RSAPublicKey == "" || req.PublicKey != pub {
			http.Error(w, "enrollment keys required", 400)
			return
		}
		if d == nil {
			approved, pending, revoked := b.capacity()
			if (b.config.AutoApprove && (approved >= maxDevices || approved+revoked >= maxRevoked)) || (!b.config.AutoApprove && pending >= maxPending) {
				http.Error(w, "capacity reached", 503)
				return
			}
			d = &Device{PublicKey: pub, Approved: b.config.AutoApprove, CreatedAt: now}
			b.devices[id] = d
		}
		if d.Revoked {
			http.Error(w, "forbidden", 403)
			return
		}
		if req.RSAPublicKey != "" {
			raw, re := base64.StdEncoding.DecodeString(req.RSAPublicKey)
			parsed, pe := x509.ParsePKIXPublicKey(raw)
			rsaKey, ok := parsed.(*rsa.PublicKey)
			if re != nil || pe != nil || !ok || rsaKey.N.BitLen() != 3072 || fingerprint(req.RSAPublicKey) != req.RSADeviceID {
				if d.RSA == "" {
					delete(b.devices, id)
				}
				http.Error(w, "invalid encryption key", 400)
				return
			}
			if d.RSA != req.RSAPublicKey {
				d.Envelope = ""
				d.EnvelopeExpires = 0
			}
			d.RSA = req.RSAPublicKey
			d.RSADeviceID = req.RSADeviceID
		}
		d.Name = req.Name
		d.Model = req.Model
		d.ProfileID = req.ProfileID
		if e = b.save(); e != nil {
			http.Error(w, "storage unavailable", 503)
			return
		}
		if !d.Approved {
			jsonReply(w, 200, map[string]string{"state": "pending"})
			return
		}
		if !req.NeedsAuth {
			jsonReply(w, 200, map[string]string{"state": "approved"})
			return
		}
		if d.EnvelopeExpires > now && d.Envelope != "" {
			h := sha256.Sum256([]byte(d.Envelope))
			if req.PreviousEnvelopeHash != hex.EncodeToString(h[:]) {
				jsonReply(w, 200, map[string]string{"state": "credential", "envelope": d.Envelope})
				return
			}
		}
		if now-d.LastIssue < 60 {
			http.Error(w, "retry later", 429)
			return
		}
		raw, _ := base64.StdEncoding.DecodeString(d.RSA)
		parsed, pe := x509.ParsePKIXPublicKey(raw)
		rsaKey, ok := parsed.(*rsa.PublicKey)
		if pe != nil || !ok {
			http.Error(w, "encryption key required", 400)
			return
		}
		provider := b.provider
		if provider == nil {
			provider = oauthProvider{b.config}
		}
		ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
		defer cancel()
		auth, e := provider.AuthKey(ctx)
		if e != nil {
			http.Error(w, "temporarily unavailable", 503)
			return
		}
		expires := now + 600
		clear, _ := json.Marshal(map[string]any{"device": d.RSADeviceID, "expires": expires, "key": auth})
		encrypted, e := rsa.EncryptOAEP(sha1.New(), rand.Reader, rsaKey, clear, nil)
		if e != nil {
			http.Error(w, "temporarily unavailable", 503)
			return
		}
		d.Envelope = base64.StdEncoding.EncodeToString(encrypted)
		d.EnvelopeExpires = expires
		d.LastIssue = now
		if b.save() != nil {
			http.Error(w, "storage unavailable", 503)
			return
		}
		jsonReply(w, 200, map[string]string{"state": "credential", "envelope": d.Envelope})
		return
	}
	if d == nil || !d.Approved || d.Revoked {
		http.Error(w, "forbidden", 403)
		return
	}
	ip := net.ParseIP(req.IP)
	v := ip.To4()
	if v == nil || v[0] != 100 || v[1] < 64 || v[1] > 127 {
		http.Error(w, "invalid address", 400)
		return
	}
	// On the private Tailscale HTTPS listener the Tailscale proxy has already
	// authenticated the originating node. The signed app identity and IP must
	// match that same node. This avoids reverse TCP probes blocked by ACLs.
	verifiedSource, isTailnetRequest := r.Context().Value(verifiedTailnetSourceKey{}).(string)
	if isTailnetRequest && verifiedSource != req.IP {
		http.Error(w, "private connection address mismatch", 403)
		return
	}
	if d.IP != req.IP {
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		defer cancel()
		if !isTailnetRequest && b.prober.Verify(ctx, req.IP, id, key) != nil {
			http.Error(w, "address verification failed", 403)
			return
		}
		for other, peer := range b.devices {
			if other != id && peer.IP == req.IP {
				http.Error(w, "address already registered", 409)
				return
			}
		}
		d.IP = req.IP
	}
	d.Name = req.Name
	d.ProfileID = req.ProfileID
	if b.save() != nil {
		http.Error(w, "storage unavailable", 503)
		return
	}
	peers := []map[string]string{}
	for other, peer := range b.devices {
		if other != id && peer.Approved && !peer.Revoked && peer.IP != "" {
			peers = append(peers, map[string]string{"id": peer.ProfileID, "name": peer.Name, "ip": peer.IP, "secret": b.pair(id, other)})
		}
	}
	sort.Slice(peers, func(i, j int) bool { return peers[i]["id"] < peers[j]["id"] })
	jsonReply(w, 200, map[string]any{"state": "ready", "peers": peers})
}
func (b *Broker) pair(a, c string) string {
	if a > c {
		a, c = c, a
	}
	h := hmac.New(sha256.New, b.master)
	h.Write([]byte(a + "\n" + c))
	return hex.EncodeToString(h.Sum(nil))
}

type tcpProber struct {
	dial func(context.Context, string, string) (net.Conn, error)
}

func (p tcpProber) Verify(ctx context.Context, ip, id string, key *ecdsa.PublicKey) error {
	dial := p.dial
	if dial == nil {
		dial = (&net.Dialer{}).DialContext
	}
	conn, e := dial(ctx, "tcp", net.JoinHostPort(ip, "48321"))
	if e != nil {
		return e
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(4 * time.Second))
	challenge := randomHex(32)
	if e = json.NewEncoder(conn).Encode(map[string]string{"type": "identity_probe", "challenge": challenge}); e != nil {
		return e
	}
	var reply struct {
		Device    string `json:"device"`
		Signature string `json:"signature"`
	}
	if e = json.NewDecoder(io.LimitReader(conn, 4096)).Decode(&reply); e != nil {
		return e
	}
	sig, e := base64.StdEncoding.DecodeString(reply.Signature)
	hash := sha256.Sum256([]byte("FamilyTouch probe v1\n" + challenge))
	if e != nil || reply.Device != id || !ecdsa.VerifyASN1(key, hash[:], sig) {
		return errors.New("invalid proof")
	}
	return nil
}

type oauthProvider struct{ config Config }

func (p oauthProvider) AuthKey(ctx context.Context) (string, error) {
	if p.config.OAuthID == "" || p.config.OAuthSecret == "" {
		return "", errors.New("not configured")
	}
	client := &http.Client{Timeout: 15 * time.Second}
	form := url.Values{"grant_type": {"client_credentials"}, "client_id": {p.config.OAuthID}, "client_secret": {p.config.OAuthSecret}}
	req, _ := http.NewRequestWithContext(ctx, "POST", "https://api.tailscale.com/api/v2/oauth/token", strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, e := client.Do(req)
	if e != nil {
		return "", errors.New("provider unavailable")
	}
	var token struct {
		AccessToken string `json:"access_token"`
	}
	e = json.NewDecoder(io.LimitReader(resp.Body, 16384)).Decode(&token)
	resp.Body.Close()
	if e != nil || resp.StatusCode != 200 || token.AccessToken == "" {
		return "", errors.New("provider unavailable")
	}
	payload := []byte(`{"capabilities":{"devices":{"create":{"reusable":false,"ephemeral":false,"preauthorized":true,"tags":["tag:family-touch"]}}},"expirySeconds":600,"description":"Family Touch enrollment"}`)
	req, _ = http.NewRequestWithContext(ctx, "POST", "https://api.tailscale.com/api/v2/tailnet/-/keys", bytes.NewReader(payload))
	req.Header.Set("Authorization", "Bearer "+token.AccessToken)
	req.Header.Set("Content-Type", "application/json")
	resp, e = client.Do(req)
	if e != nil {
		return "", errors.New("provider unavailable")
	}
	defer resp.Body.Close()
	var result struct {
		Key string `json:"key"`
	}
	e = json.NewDecoder(io.LimitReader(resp.Body, 16384)).Decode(&result)
	if e != nil || resp.StatusCode != 200 || result.Key == "" {
		return "", errors.New("provider unavailable")
	}
	return result.Key, nil
}

var adminTemplate = template.Must(template.New("admin").Parse(`<!doctype html><meta charset="utf-8"><title>Family Touch local administration</title><h1>Family Touch administration</h1><form method="post" action="/admin/setup"><input type="hidden" name="csrf" value="{{.CSRF}}"><label>OAuth client ID <input type="password" name="id" required autocomplete="off"></label><label>OAuth secret <input type="password" name="secret" required autocomplete="new-password"></label><label>Admission <select name="admission"><option value="approval">Owner approval</option><option value="auto">Automatic (requires isolated policy)</option></select></label><button>Save protected configuration</button></form><p>Current automatic admission: {{.Auto}}</p>{{range .Rows}}<form method="post" action="/admin/device"><input type="hidden" name="csrf" value="{{$.CSRF}}"><input type="hidden" name="device" value="{{.ID}}"><p>{{.ID}} — {{.Device.Name}} — {{.Device.Model}} (approved {{.Device.Approved}}, revoked {{.Device.Revoked}})</p><button name="action" value="approve">Approve</button><button name="action" value="revoke">Revoke</button><button name="action" value="delete">Delete</button></form>{{end}}<h2>Deleted device history (UTC Unix timestamps)</h2>{{range .Deleted}}<p class="deletion">{{.Name}} — {{.Model}} — {{.DeletedAt}}</p>{{end}}`))

func (b *Broker) admin(w http.ResponseWriter, r *http.Request) {
	host, _, e := net.SplitHostPort(r.RemoteAddr)
	ip := net.ParseIP(host)
	if e != nil || ip == nil || !ip.IsLoopback() || r.Header.Get("Forwarded") != "" || r.Header.Get("X-Forwarded-For") != "" || r.Header.Get("X-Forwarded-Host") != "" || r.Header.Get("X-Forwarded-Proto") != "" || r.Header.Get("X-FT-Public-Proxy") != "" {
		http.Error(w, "local access only", 403)
		return
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	w.Header().Set("Content-Security-Policy", "default-src 'none'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
	if r.Method == "GET" && r.URL.Path == "/admin" {
		type row struct {
			ID     string
			Device *Device
		}
		rows := []row{}
		for id, d := range b.devices {
			rows = append(rows, row{id, d})
		}
		sort.Slice(rows, func(i, j int) bool { return rows[i].ID < rows[j].ID })
		_ = adminTemplate.Execute(w, struct {
			CSRF string
			Auto bool
			Rows []row
			Deleted []DeletedRecord
		}{b.csrf, b.config.AutoApprove, rows, b.deleted})
		return
	}
	origin := r.Header.Get("Origin")
	expected := "http://" + r.Host
	if r.Method != "POST" || origin != expected {
		http.Error(w, "invalid origin", 403)
		return
	}
	originURL, e := url.Parse(origin)
	if e != nil || net.ParseIP(originURL.Hostname()) == nil || !net.ParseIP(originURL.Hostname()).IsLoopback() {
		http.Error(w, "invalid origin", 403)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 8192)
	if r.ParseForm() != nil || !hmac.Equal([]byte(r.Form.Get("csrf")), []byte(b.csrf)) {
		http.Error(w, "invalid request", 403)
		return
	}
	switch r.URL.Path {
	case "/admin/setup":
		id, secret := r.Form.Get("id"), r.Form.Get("secret")
		if id == "" || secret == "" || len(id) > 512 || len(secret) > 2048 || (r.Form.Get("admission") != "approval" && r.Form.Get("admission") != "auto") {
			http.Error(w, "invalid setup", 400)
			return
		}
		b.config = Config{OAuthID: id, OAuthSecret: secret, AutoApprove: r.Form.Get("admission") == "auto"}
	case "/admin/device":
		d := b.devices[r.Form.Get("device")]
		if d == nil {
			http.NotFound(w, r)
			return
		}
		switch r.Form.Get("action") {
		case "approve":
			approved, _, revoked := b.capacity()
			if !d.Approved && (approved >= maxDevices || (!d.Revoked && approved+revoked >= maxRevoked)) {
				http.Error(w, "approved capacity reached", 503)
				return
			}
			d.Approved = true
			d.Revoked = false
		case "revoke":
			approved, _, revoked := b.capacity()
			if !d.Approved && !d.Revoked && approved+revoked >= maxRevoked {
				http.Error(w, "deny registry capacity reached", 503)
				return
			}
			d.Approved = false
			d.Revoked = true
			d.IP = ""
			d.Envelope = ""
		case "delete":
			// Remove the active identity; audit stores no cryptographic keys or device IDs.
			b.deleted = append(b.deleted, DeletedRecord{d.Name, d.Model, time.Now().Unix()})
			if len(b.deleted) > maxDeletionHistory {
				b.deleted = b.deleted[len(b.deleted)-maxDeletionHistory:]
			}
			delete(b.devices, r.Form.Get("device"))
		default:
			http.Error(w, "invalid action", 400)
			return
		}
	default:
		http.NotFound(w, r)
		return
	}
	if b.save() != nil {
		http.Error(w, "storage unavailable", 503)
		return
	}
	http.Redirect(w, r, "/admin", http.StatusSeeOther)
}
