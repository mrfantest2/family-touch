# Family Touch private enrollment broker

Windows Go standard-library service. It registers app signing identities, issues RSA-encrypted single-use VPN credentials after approval, verifies private IP ownership, and returns only approved app peers. No provider credentials belong in Android, Apache, a web root, Git, logs, command lines, or chat.

Build/test from this directory:

```powershell
& C:/Tools/Go-1.27.2/go/bin/go.exe test -count=1 ./...
& C:/Tools/Go-1.27.2/go/bin/go.exe vet ./...
& C:/Tools/Go-1.27.2/go/bin/go.exe build -o family-touch-broker.exe .
```

Start as the same ordinary Windows user who will run the service:

```powershell
.\family-touch-broker.exe -listen 127.0.0.1:8797 -state-dir "$env:LOCALAPPDATA\FamilyTouchBroker"
```

The listener must be a literal loopback IP. State defaults to `%LOCALAPPDATA%\FamilyTouchBroker`; use a dedicated private directory outside all web roots. Startup protects directory inheritance and grants current-user and SYSTEM access. `registry.protected` contains DPAPI current-user encrypted configuration, registry, cached ciphertext, persistent replay nonces, and random pair master. Moving it to another Windows identity cannot decrypt it. Non-Windows production startup intentionally fails; tests inject an identity protector. No raw VPN auth key is persisted. Preserve this directory to preserve pair tokens and approvals.

Open `http://127.0.0.1:8797/admin` locally. Enter OAuth client ID and secret in password fields, leave **Owner approval** selected, and save. OAuth requires only `auth_keys` permissions with `tag:family-touch` eligibility. OAuth tokens are obtained server-side on demand. The form never echoes the saved ID or secret. Administrator forms enforce an unpredictable per-process CSRF token, exact loopback Origin, bounded bodies, HTML escaping, and restrictive CSP. Requests bearing public proxy forwarding markers are rejected. Devices appear after their first signed enrollment; use Approve or Revoke locally. Revocation removes peers from later rosters and rejects that install's API calls. It does not erase Android history or immediately disconnect an already-established provider session.

Automatic admission is supported in the setup selector but defaults off. Selecting it authorizes future registrants without individual approval; policy isolation and explicit owner approval are required before selecting it. This code does not modify provider ACLs or deploy itself.

Reverse proxy **only** external `/family-touch/api/v1/` to broker `/api/v1/`. Never proxy `/admin`. API signatures cover the broker path (`/api/v1/enroll` or `/api/v1/sync`), not the external prefix. Set `X-FT-Public-Proxy: 1` on forwarded requests, including any accidental admin request; admin rejects this marker. Do not expose the local listener directly. Public responses use `Cache-Control: no-store`.

API traffic is throttled before reading bodies or checking cryptography: 120 requests per source per 60-second window, with at most 4,096 live source buckets. Rejected requests return HTTP429 and Retry-After60. By default source means socket peer; proxied traffic therefore shares a conservative loopback bucket. `X-Forwarded-For` is never trusted. For production per-client throttling, configure Apache to **overwrite**, not append, `X-FT-Client-IP` from its verified client address (after any correctly configured trusted CDN address processing), and overwrite `X-FT-Public-Proxy` with `1`; only then launch with `-trust-proxy-client-ip`. This opt-in trusts a single parsed literal IP only from a loopback socket with that marker. Do not enable it while an external client can control either header.

Pending registrations have a separate 128-entry pool and expire 24 hours after their first registration; polling cannot extend that deadline. Expired pending entries are reclaimed at startup and before API authentication. They never consume the 1,024 approved-device slots. Revoked identities remain deny records, preserving revocation even when automatic admission is later enabled, but no longer consume approved-device slots. The combined approved-plus-revoked identity history is bounded at 2,048; reaching that lifetime bound refuses new approvals/automatic admission rather than forgetting revocations. Existing approved identities can always be revoked. Pending capacity exhaustion does not prevent syncing or approving already registered devices.

API contract is in `../docs/superpowers/plans/2026-10-17-family-enrollment.md`. Headers authenticate P-256 SPKI identities with DER ECDSA signatures, a 90-second clock window, and bounded persistent replay tracking. JSON bodies are limited to 16 KiB, approved registry to 1,024 devices (pending/deny limits above) and active replay entries to 16,384. Profile UUIDs are immutable per identity and cannot be claimed by another active registered device. Credential lifetime is 600 seconds, single-use, preauthorized, non-ephemeral, fixed `tag:family-touch`; cached ciphertext is returned until expiration or client acknowledgment via lowercase hex SHA256 of the base64 envelope string. New issuance is rate-limited to once per minute per install.

IP proof connects only to IPv4 in `100.64.0.0/10`, TCP port 48321. It writes a newline-delimited JSON `{type:"identity_probe",challenge:<64 hex characters>}` and checks response `{device:<signing fingerprint>,signature:<base64 DER>}` against SHA256 of UTF-8 `FamilyTouch probe v1` + one LF + challenge. The optional `ok` response field is ignored. Invalid/unreachable proofs cannot register an IP. Pair secrets are lowercase hex HMAC-SHA256 of sorted signing fingerprints joined by one LF.

Tests use injected key providers and proof transports, never real OAuth credentials or account access. They cover approval, replay, stale timestamps, real RSA decryption and envelope caching, provider errors, proof signatures/domain separation, IP range/proof rejection, roster revocation, stable distinct pair tokens, and local admin CSRF/Origin/proxy guards. No live provider or physical Android success is implied by these tests.
