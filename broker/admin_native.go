package main

import (
	"encoding/json"
	"net/http"
	"sort"
	"strings"
	"time"
)

type nativeDevice struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Model     string `json:"model"`
	Approved  bool   `json:"approved"`
	Revoked   bool   `json:"revoked"`
	CreatedAt int64  `json:"createdAt"`
}
type nativeDeleted struct {
	Name      string `json:"name"`
	Model     string `json:"model"`
	DeletedAt int64  `json:"deletedAt"`
}
type nativeResponse struct {
	Devices     []nativeDevice  `json:"devices"`
	Deleted     []nativeDeleted `json:"deleted"`
	AutoApprove bool            `json:"autoApprove"`
	ServerTime  int64           `json:"serverTime"`
}

// adminNative is only called inside Broker.admin AFTER its loopback,
// forwarded-header and broker-mutex checks. It never exposes stored keys.
func (b *Broker) adminNative(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	if r.Header.Get("X-FT-Native-Client") != "1" {
		http.Error(w, `{"error":"native client required"}`, http.StatusForbidden)
		return
	}
	if r.URL.Path == "/admin/api/v1/devices" && r.Method == http.MethodGet {
		devices := make([]nativeDevice, 0, len(b.devices))
		for id, d := range b.devices {
			devices = append(devices, nativeDevice{
				ID: id, Name: d.Name, Model: d.Model, Approved: d.Approved,
				Revoked: d.Revoked, CreatedAt: d.CreatedAt,
			})
		}
		sort.Slice(devices, func(i, j int) bool {
			if devices[i].Name == devices[j].Name {
				return devices[i].ID < devices[j].ID
			}
			return strings.ToLower(devices[i].Name) < strings.ToLower(devices[j].Name)
		})
		deleted := make([]nativeDeleted, 0, len(b.deleted))
		for _, record := range b.deleted {
			deleted = append(deleted, nativeDeleted{record.Name, record.Model, record.DeletedAt})
		}
		_ = json.NewEncoder(w).Encode(nativeResponse{devices, deleted, b.config.AutoApprove, time.Now().Unix()})
		return
	}
	if r.URL.Path != "/admin/api/v1/device" || r.Method != http.MethodPost {
		http.NotFound(w, r)
		return
	}
	// The edge gateway authenticates each request, rejects browser origins,
	// and injects the native marker. Validate it independently here.
	if r.Header.Get("Origin") != "http://"+r.Host ||
		!strings.HasPrefix(r.Header.Get("Content-Type"), "application/json") {
		http.Error(w, `{"error":"invalid request origin"}`, http.StatusForbidden)
		return
	}
	var request struct {
		ID     string `json:"id"`
		Action string `json:"action"`
	}
	r.Body = http.MaxBytesReader(w, r.Body, 4096)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if decoder.Decode(&request) != nil || len(request.ID) != 64 ||
		(request.Action != "approve" && request.Action != "revoke" && request.Action != "delete") {
		http.Error(w, `{"error":"invalid action"}`, http.StatusBadRequest)
		return
	}
	d := b.devices[request.ID]
	if d == nil {
		http.Error(w, `{"error":"device not found"}`, 404)
		return
	}
	switch request.Action {
	case "approve":
		approved, _, revoked := b.capacity()
		if !d.Approved && (approved >= maxDevices || (!d.Revoked && approved+revoked >= maxRevoked)) {
			http.Error(w, `{"error":"capacity reached"}`, http.StatusServiceUnavailable)
			return
		}
		d.Approved = true
		d.Revoked = false
	case "revoke":
		approved, _, revoked := b.capacity()
		if !d.Approved && !d.Revoked && approved+revoked >= maxRevoked {
			http.Error(w, `{"error":"capacity reached"}`, http.StatusServiceUnavailable)
			return
		}
		d.Approved = false
		d.Revoked = true
		d.IP = ""
		d.Envelope = ""
	case "delete":
		b.deleted = append(b.deleted, DeletedRecord{d.Name, d.Model, time.Now().Unix()})
		if len(b.deleted) > maxDeletionHistory {
			b.deleted = b.deleted[len(b.deleted)-maxDeletionHistory:]
		}
		delete(b.devices, request.ID)
	}
	if b.save() != nil {
		http.Error(w, `{"error":"storage unavailable"}`, http.StatusServiceUnavailable)
		return
	}
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"ok":true}`))
}
