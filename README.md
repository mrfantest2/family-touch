# Family Touch — embedded Tailscale branch

Native Android family messages, attachments and WebRTC audio/video calls over a private Tailscale network.

This branch embeds the upstream Tailscale Android Go engine in **one APK**, with an Android VpnService. The standalone Tailscale application is not required. Package: win.fantest.familytouch. Android 10+ (API29), target API36. Existing contact invitation and message/call functionality is retained.

Settings → Embedded private network provides Connect/Disconnect and encrypted device provisioning import/export. Android VPN authorization is required once. Only one VPN can be active in an Android profile. Device identities are encrypted with Android Keystore and reused on subsequent connections. No account password or reusable tailnet key is packaged. See [implementation, provisioning and limitations](docs/EMBEDDED_TAILSCALE_PLAN.md) for the exact initial enrollment workflow and honest validation status.

Build with Java17/21, Android SDK36, Gradle8.10.2:

    gradle :app:testReleaseUnitTest :app:assembleRelease :app:lintRelease

Release signing requires the existing FAMILY_TOUCH_KEYSTORE_FILE, FAMILY_TOUCH_KEYSTORE_PASSWORD, FAMILY_TOUCH_KEY_ALIAS and FAMILY_TOUCH_KEY_PASSWORD environment variables. Do not commit their values. The pinned native AAR is checked in; rebuild instructions and license notices are under third_party/tailscale, with licenses also included in APK assets.

This is an initial integration, not a claim of completed authenticated two-device QA. Without a tailnet provisioning credential, enrollment and calls over the embedded network cannot be verified. Preserve existing data and signing compatibility when installing. Never uninstall an existing copy to bypass a certificate mismatch. S25 deployment is install-only: do not launch automatically.

TCP port48321 carries pairing, messaging and signaling. Invitations are shared privately between trusted tailnet members; membership alone does not authorize a contact. No public STUN/TURN or hosted offline mailbox is used. Android background restrictions and tailnet ACLs still apply.
