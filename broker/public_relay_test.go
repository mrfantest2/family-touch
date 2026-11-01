package main

import (
    "crypto/ecdsa"
    "crypto/elliptic"
    "crypto/rand"
    "crypto/sha256"
    "crypto/x509"
    "encoding/base64"
    "encoding/hex"
    "encoding/json"
    "net/http"
    "net/http/httptest"
    "strings"
    "testing"
)

type relayIdentity struct { id, profile string; key *ecdsa.PrivateKey }
func newRelayIdentity(t *testing.T, profile string) relayIdentity {
    t.Helper()
    k,e:=ecdsa.GenerateKey(elliptic.P256(),rand.Reader)
    if e!=nil {t.Fatal(e)}
    b,e:=x509.MarshalPKIXPublicKey(&k.PublicKey)
    if e!=nil {t.Fatal(e)}
    h:=sha256.Sum256(b)
    return relayIdentity{id:hex.EncodeToString(h[:]),profile:profile,key:k}
}
func signedRelayCall(t *testing.T,b *Broker,identity relayIdentity,path,body,nonce string)*httptest.ResponseRecorder {
    t.Helper()
    req:=httptest.NewRequest(http.MethodPost,path,strings.NewReader(body))
    stamp:=fmtUnix()
    req.Header.Set("X-FT-Device",identity.id)
    req.Header.Set("X-FT-Time",stamp)
    req.Header.Set("X-FT-Nonce",nonce)
    h:=sha256.Sum256([]byte(canonical(req,identity.id,stamp,nonce,[]byte(body))))
    sig,e:=ecdsa.SignASN1(rand.Reader,identity.key,h[:])
    if e!=nil {t.Fatal(e)}
    req.Header.Set("X-FT-Signature",base64.StdEncoding.EncodeToString(sig))
    result:=httptest.NewRecorder()
    b.ServeHTTP(result,req)
    return result
}
func TestPublicRelaySecurityPersistence(t *testing.T) {
    b,e:=NewBroker(t.TempDir(),identityProtector{},nil,nil)
    if e!=nil {t.Fatal(e)}
    sender:=newRelayIdentity(t,"00000000-0000-4000-8000-000000000001")
    recipient:=newRelayIdentity(t,"00000000-0000-4000-8000-000000000002")
    intruder:=newRelayIdentity(t,"00000000-0000-4000-8000-000000000003")
    for _,who:=range []relayIdentity{sender,recipient} {
        der,_:=x509.MarshalPKIXPublicKey(&who.key.PublicKey)
        b.devices[who.id]=&Device{PublicKey:base64.StdEncoding.EncodeToString(der),ProfileID:who.profile,Approved:true}
    }
    if e=b.save();e!=nil {t.Fatal(e)}
    payload:=`{"to":"00000000-0000-4000-8000-000000000002","id":"message:test001","ciphertext":"abcdefghijklmnopqrstuvwxyz1234567890"}`
    denied:=signedRelayCall(t,b,intruder,"/api/v1/relay/send",payload,"00000000000000000000000000000001")
    if denied.Code!=403 {t.Fatalf("unknown device accepted: %d",denied.Code)}
    posted:=signedRelayCall(t,b,sender,"/api/v1/relay/send",payload,"00000000000000000000000000000002")
    if posted.Code!=200 {t.Fatalf("send: %d %s",posted.Code,posted.Body.String())}
    replay:=signedRelayCall(t,b,sender,"/api/v1/relay/send",payload,"00000000000000000000000000000002")
    if replay.Code!=401 {t.Fatalf("replay accepted: %d",replay.Code)}
    other:=signedRelayCall(t,b,sender,"/api/v1/relay/pull",`{"ack":[]}`,"00000000000000000000000000000003")
    if other.Code!=200 || strings.Contains(other.Body.String(),"message:test001") {t.Fatal("recipient mailbox leaked",other.Body.String())}
    first:=signedRelayCall(t,b,recipient,"/api/v1/relay/pull",`{"ack":[]}`,"00000000000000000000000000000004")
    if first.Code!=200 {t.Fatalf("pull: %d %s",first.Code,first.Body.String())}
    var response struct{Messages []RelayEnvelope}
    if e=json.Unmarshal(first.Body.Bytes(),&response);e!=nil || len(response.Messages)!=1 {t.Fatal("missing encrypted envelope",first.Body.String(),e)}
    if response.Messages[0].From!=sender.profile {t.Fatal("sender spoofed")}
    b,e=NewBroker(b.dir,identityProtector{},nil,nil)
    if e!=nil {t.Fatal(e)}
    ack:=signedRelayCall(t,b,recipient,"/api/v1/relay/pull",`{"ack":["message:test001"]}`,"00000000000000000000000000000005")
    if ack.Code!=200 || strings.Contains(ack.Body.String(),"message:test001") {t.Fatal("message not acknowledged",ack.Body.String())}
    again:=signedRelayCall(t,b,recipient,"/api/v1/relay/pull",`{"ack":[]}`,"00000000000000000000000000000006")
    if again.Code!=200 || strings.Contains(again.Body.String(),"message:test001") {t.Fatal("ack not durable")}
}
