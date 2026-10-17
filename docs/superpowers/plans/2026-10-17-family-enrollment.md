# Family Touch automatic enrollment and device directory — implementation plan

Owner authorizes completing the embedded VPN, Chrome admin configuration, removing provider branding from normal UI, mandatory in-app VPN consent, automatic registration and automatic device contacts. Preserve the existing signed alongside package, user data, calls/messages/attachments and installed Alpha01. No S25 auto-launch.

## Security and product rulings

- All long-lived provisioning credentials stay in a private local broker on MASTER-PC, outside web root/Git, protected at rest with Windows DPAPI and directory ACLs.
- No anonymous public APK downloader gets private-tailnet access by default. Broker admission defaults to owner approval. Owner question about private approval versus open isolated app registration is pending; use a runtime configuration switch so shared implementation can continue. Open mode requires explicit owner choice plus isolation of tag:family-touch from existing infrastructure.
- Existing console policy is grants-based and currently src*/dst*/ip*. tag:family-touch and tag:s25-server already exist. Any policy change must preserve existing member/server connectivity and isolate only family app sources. Browser security-sensitive credential/policy final actions need the required action-time confirmation after exact changes are reviewable.
- Peer discovery returns only broker-registered, approved devices, not every PC/TV on the tailnet. Each install has Android Keystore signing identity; each peer pair gets a distinct server-derived token. Preserve existing message auth, never accept arbitrary unpaired inbound peers.
- Android VPN consent remains mandatory once. App contains no provider login or file import flow. Required license notices remain packaged in assets.

## Broker/client API contract

Base client URL https://fantest.win/family-touch/api/v1. Apache proxies only this API prefix to 127.0.0.1:8797/api/v1; never admin paths. No-store responses; bounded request bodies; no secrets in logs.

All API calls are POST JSON with headers X-FT-Device (SHA256 lowercase of ECDSA SPKI), X-FT-Time (Unix seconds), X-FT-Nonce (random128+ bits hex), X-FT-Signature (base64 DER ECDSA P256 SHA256). Signed canonical bytes: METHOD + newline + URL PATH (broker path /api/v1/...) + newline + device fingerprint + newline + timestamp + newline + nonce + newline + lowercase SHA256(body). Reject stale time (>90sec), nonce replay, invalid signatures/keys, oversized data. Signing key purpose uses Android Keystore EC separately from existing RSA3072 provisioning decryption key.

POST /enroll body: publicKey (base64 ECDSA SPKI), rsaPublicKey (base64 RSA SPKI), rsaDeviceId (SHA256 RSA SPKI), profileId (existing UUID), name (nickname max36), model (max80), needsAuth (bool), previousEnvelopeHash (optional SHA256 base64 ciphertext string). First call creates registered/pending install. Pending response state=pending. Approved needsAuth=false response state=approved. Approved needsAuth=true creates/caches a five/ten-minute single-use non-ephemeral preauthorized auth key restricted to tag:family-touch, encrypted with RSA3072 OAEP SHA1. Envelope clear JSON {device:rsaDeviceId,expires:unixSeconds,key:authKey}, ciphertext base64 in response {state:credential,envelope:...}. Existing Android DeviceProvisioning.decrypt handles it. Never put raw keys on disk or in API responses. Provider auth/key failures produce generic retryable503 with no secret fields. Reissue limits and expired cached ciphertext handled; no broad API keys needed, just auth_keys scope.

POST /sync body: name, profileId, ip (100.64/10 IPv4). Approved request only. Broker verifies IP ownership with TCP48321 identity_probe challenge before accepting new IP. Random32-byte hex challenge; PeerService response {device:signingFingerprint,signature:base64 ECDSA(SHA256('FamilyTouch probe v1\\n'+challenge))}. Probe requests are accepted only over current private VPN, strict bounded random challenge; domain separation prevents reusing signatures as API authentication. This avoids trusting arbitrary caller-reported IP or exposing infrastructure addresses to clients. No device-list API permission required.

Response {state:ready,peers:[{id:profileId,name,ip,secret}]}. Only approved verified devices returned, excluding caller; per-pair secret HMAC-SHA256(pairMaster, sorted device fingerprint A + newline + B), stable across restarts. Registry is persistent and bounded. Revocation removes a device from subsequent rosters. Existing chat/call history stays intact. Pair tokens stay in app-private existing contact store, never in logs or public files.

Broker admin: loopback-only routes separate from public API, reject public proxy marker. Local setup HTML uses CSRF/Origin checks and password inputs to accept OAuth ID/secret directly from browser without exposing them in chat/tool output; DPAPI storage. Runtime configured name/tag/listen/state dir and AutoApprove. Admin page lists fingerprint/name/model and can approve/revoke; owner loopback browser management is separate from family UI. No setup credentials in query strings. CLI start/setup/state options documented; native Windows protected storage, tests on non-Windows use injected config/provider.

## Work sequence and owned files

1. Broker implementer owns broker/** only: Go standard-library broker, HTTP auth/registry/crypto/OAuth provider, local admin/setup, DPAPI persistence, tests. Do not deploy or access credentials. Root coordinates API integration.
2. Root owns Android changes: FamilyIdentity signing; FamilyDirectory automatic enrollment and sync; integrate existing service, private identity_probe, managed contact updates, no manual pairing UI, branding removal, VPN-first dialog after nickname.
3. Root configures private runtime and Apache API route, browser setup and exact security-reviewed OAuth/policy changes with required confirmation. No production Play/account key changes.
4. Verify Go tests, Android tests/lint/signed APK, backend API security/probes, emulator two-device enrollment/roster/message/reconnect, then real verified S25 update only, HTTPS checksum, docs and GitHub push.

Do not call the system finished based on UI/build alone. Record genuine auth/admin/device blockers while continuing independent work.
