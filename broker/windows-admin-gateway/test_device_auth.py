import os, base64, hashlib, json, pathlib, secrets, tempfile, time
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import hashes, serialization
import device_auth
with tempfile.TemporaryDirectory() as tmp:
    device_auth.STORE=pathlib.Path(tmp)/"isolated-test.json"
    private=ec.generate_private_key(ec.SECP256R1())
    public=private.public_key().public_bytes(
        serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)
    kid=hashlib.sha256(public).hexdigest()
    def signed(method,path,body):
        ts=str(int(time.time()))
        nonce=secrets.token_hex(16)
        canonical=(method+"\n"+path+"\n"+ts+"\n"+nonce+"\n"+
                    hashlib.sha256(body).hexdigest()).encode()
        sig=private.sign(canonical,ec.ECDSA(hashes.SHA256()))
        return {"X-FT-KeyId":kid,"X-FT-Timestamp":ts,"X-FT-Nonce":nonce,
            "X-FT-Signature":base64.b64encode(sig).decode()}
    enrollment=json.dumps({"publicKey":base64.b64encode(public).decode(),
                           "model":"SM-S938B","label":"isolated QA"},separators=(',',':')).encode()
    h=signed("POST","/admin/api/v1/device-enroll",enrollment)
    assert device_auth.enroll(enrollment,h)["state"]=="pending"
    assert not device_auth.authenticate("GET","/admin/api/v1/devices",b"",
                                       signed("GET","/admin/api/v1/devices",b""))
    assert device_auth.approve(kid)
    signed_headers=signed("GET","/admin/api/v1/devices",b"")
    assert device_auth.authenticate("GET","/admin/api/v1/devices",b"",signed_headers)
    assert not device_auth.authenticate("GET","/admin/api/v1/devices",b"",signed_headers)
    assert not device_auth.authenticate("GET","/admin/api/v1/device",b"",
                                        signed("GET","/admin/api/v1/devices",b""))
    assert kid in [r["id"] for r in device_auth.status()["approved"]]
print("DEVICE_AUTH_ISOLATED_TESTS_PASSED: pending approval, signed access, replay blocking, path binding")
