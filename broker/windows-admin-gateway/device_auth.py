"""Device-bound native Admin authentication. MASTER-PC operator approval required once.
No admin password or token is included in the Android APK.
"""
import base64, hashlib, hmac, json, os, pathlib, secrets, threading, time
from cryptography.hazmat.primitives.serialization import load_der_public_key
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import hashes

ROOT=pathlib.Path(r"C:\ProgramData\FamilyTouchBroker")
STORE=ROOT/"master-admin-devices.json"
_LOCK=threading.RLock()
_RECENT={}
def _load():
    if not STORE.exists():return {"approved":{},"pending":{}}
    try:
        j=json.loads(STORE.read_text(encoding="utf-8"))
        return {"approved":j.get("approved",{}),"pending":j.get("pending",{})}
    except Exception: return {"approved":{},"pending":{}}
def _save(value):
    tmp=STORE.with_name(STORE.name+".new")
    with open(tmp,"w",encoding="utf-8") as f:
        json.dump(value,f,sort_keys=True,indent=2);f.flush();os.fsync(f.fileno())
    os.replace(tmp,STORE)
def _key(raw):
    try:
        der=base64.b64decode(raw,validate=True)
        if not 64<len(der)<220:raise ValueError("key too large")
        key=load_der_public_key(der)
        if not isinstance(key,ec.EllipticCurvePublicKey) or not isinstance(key.curve,ec.SECP256R1):
            raise ValueError("expected P-256 key")
        return der,key
    except Exception as e:raise ValueError("invalid public key") from e
def _canonical(method,path,timestamp,nonce,body):
    return (method+"\n"+path+"\n"+timestamp+"\n"+nonce+"\n"+hashlib.sha256(body).hexdigest()).encode("utf-8")
def _sig_ok(key,signature,canonical):
    try:
        if len(signature)>150:return False
        sig=base64.b64decode(signature,validate=True)
        key.verify(sig,canonical,ec.ECDSA(hashes.SHA256()))
        return True
    except Exception:return False
def _headers(h):
    kid=h.get("X-FT-KeyId","")
    ts=h.get("X-FT-Timestamp","")
    nonce=h.get("X-FT-Nonce","")
    signature=h.get("X-FT-Signature","")
    if len(kid)!=64 or len(ts)>12 or len(nonce)!=32 or len(signature)>150:return None
    try:
        if abs(time.time()-int(ts))>120 or not all(c in "0123456789abcdef" for c in kid+nonce):return None
    except ValueError:return None
    return kid,ts,nonce,signature
def enroll(body, headers):
    if len(body)>1024:raise ValueError("invalid enrollment size")
    j=json.loads(body)
    raw=j.get("publicKey","")
    if not isinstance(raw,str):raise ValueError("invalid key format")
    der,key=_key(raw)
    kid=hashlib.sha256(der).hexdigest()
    parts=_headers(headers)
    if parts is None or parts[0]!=kid or not _sig_ok(key,parts[3],_canonical("POST","/admin/api/v1/device-enroll",parts[1],parts[2],body)):
        raise PermissionError("device signature required")
    with _LOCK:
        db=_load()
        if kid in db["approved"]:return {"state":"approved","keyId":kid}
        now=int(time.time())
        db["pending"]={k:v for k,v in db["pending"].items() if now-int(v.get("created",0))<86400}
        if kid not in db["pending"] and len(db["pending"])>=8:raise PermissionError("enrollment queue full")
        db["pending"][kid]={"publicKey":raw,"created":now,"model":str(j.get("model",""))[:60],"label":str(j.get("label",""))[:60]}
        _save(db)
        return {"state":"pending","keyId":kid}
def authenticate(method,path,body,headers):
    parts=_headers(headers)
    if parts is None:return False
    kid,ts,nonce,signature=parts
    with _LOCK:
        db=_load()
        item=db["approved"].get(kid)
        if item is None:return False
        try:
            der,key=_key(item["publicKey"])
            if not hmac.compare_digest(hashlib.sha256(der).hexdigest(),kid):return False
        except Exception:return False
        if not _sig_ok(key,signature,_canonical(method,path,ts,nonce,body)):return False
        now=time.monotonic()
        for k,v in list(_RECENT.items()):
            if now-v>180:del _RECENT[k]
        replay=kid+":"+nonce
        if replay in _RECENT:return False
        _RECENT[replay]=now
        return True
def approve(kid):
    with _LOCK:
        db=_load()
        pending=db["pending"].get(kid)
        if not pending:return False
        if pending.get("model")!="SM-S938B":raise ValueError("not an S25 candidate")
        # One explicit MASTER-PC operator approval, no public approval API.
        db["approved"][kid]=pending
        del db["pending"][kid]
        _save(db)
        return True
def revoke(kid):
    with _LOCK:
        db=_load()
        if kid not in db["approved"] and kid not in db["pending"]:
            return False
        db["approved"].pop(kid,None)
        db["pending"].pop(kid,None)
        _save(db)
        return True

def status():
    with _LOCK:
        db=_load()
        return {"approved":[{"id":k,"model":v.get("model"),"label":v.get("label")}
                             for k,v in db["approved"].items()],
                "pending":[{"id":k,"model":v.get("model"),"label":v.get("label"),"created":v.get("created")}
                           for k,v in db["pending"].items()]}
if __name__=="__main__":
    import sys
    if len(sys.argv)==3 and sys.argv[1]=="approve":
        print("APPROVED" if approve(sys.argv[2]) else "NOT_FOUND")
    elif len(sys.argv)==3 and sys.argv[1]=="revoke":
        print("REVOKED" if revoke(sys.argv[2]) else "NOT_FOUND")
    elif len(sys.argv)==2 and sys.argv[1]=="status":
        print(json.dumps(status()))
    else:print("Use status or approve <full fingerprint>")
