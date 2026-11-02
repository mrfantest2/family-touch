package main

import (
    "crypto/ecdsa"
    "crypto/sha256"
    "encoding/base64"
    "encoding/hex"
    "encoding/json"
    "io"
    "net/http"
    "strconv"
    "time"
)

// The broker only queues opaque encrypted envelopes for approved devices.
// Its existing protected storage provides restart durability. No admin route is exposed.
type RelayEnvelope struct {
    ID string `json:"id"`
    From string `json:"from"`
    Ciphertext string `json:"ciphertext"`
    Created int64 `json:"created"`
}
type relayRequest struct {
    To string `json:"to"`
    ID string `json:"id"`
    Ciphertext string `json:"ciphertext"`
    Ack []string `json:"ack"`
}
const relayTTL = 7 * 24 * 3600

func (b *Broker) publicRelay(w http.ResponseWriter, r *http.Request) {
    if r.Method != http.MethodPost { http.NotFound(w,r); return }
    b.mu.Lock()
    defer b.mu.Unlock()
    if !b.allowSource(sourceIP(r,b.trustProxyClientIP),time.Now().Unix()) {
        w.Header().Set("Retry-After","60")
        http.Error(w,"retry later",429); return
    }
    body,err:=io.ReadAll(http.MaxBytesReader(w,r.Body,16384))
    if err!=nil { http.Error(w,"invalid request",413);return }
    var request relayRequest
    if json.Unmarshal(body,&request)!=nil { http.Error(w,"invalid request",400);return }
    id,ts,nonce:=r.Header.Get("X-FT-Device"),r.Header.Get("X-FT-Time"),r.Header.Get("X-FT-Nonce")
    d:=b.devices[id]
    if d==nil || !d.Approved || d.Revoked || !validProfile(d.ProfileID) { http.Error(w,"forbidden",403);return }
    now:=time.Now().Unix()
    stamp,e:=strconv.ParseInt(ts,10,64)
    nonceBytes,ne:=hex.DecodeString(nonce)
    key,ke:=parseEC(d.PublicKey)
    signature,se:=base64.StdEncoding.DecodeString(r.Header.Get("X-FT-Signature"))
    digest:=sha256.Sum256([]byte(canonical(r,id,ts,nonce,body)))
    _,replay:=b.nonces[id+":"+nonce]
    if e!=nil || stamp<now-90 || stamp>now+90 || ne!=nil || len(nonceBytes)<16 || len(nonceBytes)>64 ||
        len(id)!=64 || fingerprint(d.PublicKey)!=id || ke!=nil || se!=nil || replay ||
        len(b.nonces)>=16384 || !ecdsa.VerifyASN1(key,digest[:],signature) {
        http.Error(w,"unauthorized",401);return
    }
    for n,t:=range b.nonces { if t<now-90 { delete(b.nonces,n) } }
    b.nonces[id+":"+nonce]=now
    // Write request replay state even for rejected operations.
    if err=b.save();err!=nil { http.Error(w,"storage unavailable",503);return }

    switch r.URL.Path {
    case "/api/v1/relay/send":
        if !validProfile(request.To) || len(request.ID)<8 || len(request.ID)>100 ||
            len(request.Ciphertext)<24 || len(request.Ciphertext)>12000 {
            http.Error(w,"invalid envelope",400);return
        }
        var target *Device
        for _,peer:=range b.devices {
            if peer.ProfileID==request.To && peer.Approved && !peer.Revoked { target=peer;break }
        }
        if target==nil || target.ProfileID==d.ProfileID { http.Error(w,"unknown recipient",403);return }
        mail:=b.relay[request.To]
        active:=make([]RelayEnvelope,0,len(mail))
        for _,item:=range mail { if item.Created>now-relayTTL { active=append(active,item) } }
        for _,item:=range active {
            if item.From==d.ProfileID && item.ID==request.ID {
                jsonReply(w,200,map[string]any{"ok":true,"queued":true});return
            }
        }
        if len(active)>=96 { http.Error(w,"mailbox full",429);return }
        count:=0
        for _,box:=range b.relay { count+=len(box) }
        if count>=8192 { http.Error(w,"relay capacity reached",503);return }
        b.relay[request.To]=append(active,RelayEnvelope{ID:request.ID,From:d.ProfileID,Ciphertext:request.Ciphertext,Created:now})
        if b.save()!=nil { http.Error(w,"storage unavailable",503);return }
        jsonReply(w,200,map[string]any{"ok":true,"queued":true})
    case "/api/v1/relay/pull":
        if len(request.Ack)>64 { http.Error(w,"ack too large",400);return }
        ack:=make(map[string]bool,len(request.Ack))
        for _,id:=range request.Ack { if len(id)<8 || len(id)>100 { http.Error(w,"bad ack",400);return };ack[id]=true }
        var mail []RelayEnvelope
        for _,item:=range b.relay[d.ProfileID] {
            if item.Created>now-relayTTL && !ack[item.ID] { mail=append(mail,item) }
        }
        b.relay[d.ProfileID]=mail
        if b.save()!=nil { http.Error(w,"storage unavailable",503);return }
        if len(mail)>32 {mail=mail[:32]}
        jsonReply(w,200,map[string]any{"ok":true,"messages":mail})
    default:
        http.NotFound(w,r)
    }
}
