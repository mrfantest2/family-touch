# Family Touch Admin v0.3.3 — native loginless S25 enrollment

## Purpose
The S25 Admin app displays native Contacts and Users/Devices views. Admin does not use Android's VPN slot. A device-bound Android Keystore P-256 signing key replaces username/password prompts after one operator-authorized enrollment. This is not anonymous access.

## First-launch enrollment
1. The signed S25 Admin app creates a non-exportable Android Keystore EC P-256 private key and sends a signed public-key enrollment request to the MASTER-PC HTTPS gateway.
2. The gateway stores a pending, non-privileged public key. Its private key never leaves the phone. Pending keys cannot read or modify the Admin registry.
3. The operator verifies the pending request came from the authorized S25 (compare its displayed fingerprint, model and time with the controlled S25). On MASTER-PC use: python C:\ProgramData\FamilyTouchBroker\device_auth.py status. Approve the exact verified key: python C:\ProgramData\FamilyTouchBroker\device_auth.py approve FULL_KEY_ID.
4. Admin automatically rechecks every 15 seconds until approved, then opens without a username or password.

Do not approve a device based on its self-reported model alone. The Android model check is for user experience, not a security boundary. Hardware attestation and formal device policy remain future production gates.

## Per-request enforcement
- The gateway requires an enrolled, approved key on all native Admin device list and management API requests.
- Every request signs method, URL path, Unix timestamp, 128-bit nonce and SHA-256 of its request bytes. The gateway verifies ECDSA P-256 and rejects requests outside the 120-second window or replayed nonces.
- Web /admin and HTTP Basic credentials remain protected for existing administrative recovery; they are not included in the APK.
- Admin neither stores a password nor declares an Android VPN service.

## Key revocation and phone replacement
MASTER-PC operator command: python C:\ProgramData\FamilyTouchBroker\device_auth.py revoke FULL_KEY_ID. This deletes pending and approved credentials for that identity. A revoked key cannot authorize requests; a fresh install must enroll and be approved again. Protect master-admin-devices.json against modification by non-administrators.

## User interface
Contacts mirrors normal Family Touch family rows: avatar, name, approval and model subtitle, details on tap. Admin adds Users and Devices segmented views, explicit approval/revoke/delete/Retry VPN controls, and a status-bar-safe top header. Chats and calls remain unavailable in VPN-free Admin until relay and signaling are implemented.

## Release limitations
Update only the Admin APK on S25; do not replace or launch the normal Family Touch VPN. Keep PR draft until real launch, device-key approval, authenticated management, UI screenshots and production security checks pass.
