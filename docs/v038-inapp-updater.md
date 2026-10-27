# Family Touch v0.3.8 — Pull-to-refresh, unread badges, presence, updater

## Client updates
- Chats and Contacts support Material 3 pull-to-refresh. Contacts were already implemented.
- Incoming unread messages show a per-conversation count, a total Chats navigation badge, and persist a last-viewed timestamp per conversation. These badges reflect locally received messages, not server-side cloud sync.
- Presence dot colors: available green, on-call blue, away amber, offline gray. Background PeerService polls presence roughly every 45 seconds; it does not infer actual presence from approval alone.
- Admin and standard packages check `https://family-updates.fantest.win/manifests/{packageName}.json` at launch and offer a verified in-app download and Android installer prompt. The normal app also has a Settings action, and Admin has a toolbar action.
- Android requires the user to allow installation from this app once and confirm each update. No silent install or Play Store API is used.

## Release publication
The MASTER-PC service `FamilyTouchUpdatesServer` runs `C:\ProgramData\FamilyTouchUpdates\server.py` on loopback port 18798 via the dedicated Cloudflare ingress for `family-updates.fantest.win`.
- Signed Android APKs: `app/build/outputs/apk/embedded/app-embedded.apk`, `app/build/outputs/apk/admin/app-admin.apk`.
- Publishing script: `C:\ProgramData\FamilyTouchUpdates\publish.py`.
- Public paths: `/apks/embedded.apk`, `/apks/admin.apk`; manifests `/manifests/win.fantest.familytouch.embedded.json` and `...admin.json`.
- On each update, increment versionCode, build both signed variants with the same original signing certificate, run tests, run `publish.py`, verify HTTPS manifest/package byte lengths/hashes and download, then test on a device before advertising broadly.
- The updater checks HTTPS origin, package identity, increasing version code, APK length, SHA-256 and matching installed APK signing certificates. No secrets are embedded in the APK.
- Public APK links make binaries retrievable by anyone. No private backend state or credentials are exposed by the update server.

## Remaining validation
- End-to-end UI update/installer consent flow on physical Android devices, including Unknown Apps permission, must be confirmed.
- Family Touch private-network signaling/call failures are separate from this UI/update build and are not resolved by app update publication.
- Review the draft PR CI before merging or promoting to Play.
