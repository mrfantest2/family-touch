# Family connection UX — owner direction

Owner request: one Connect/Disconnect toggle, truthful status, and eventually one installation with no family-facing provisioning files, separate VPN application or repeated authentication.

## This update

The existing connection flow is simplified to one button. While starting or running, it reads Disconnect; after stopping, Connect. Status is shared by the header, Settings and foreground notification. Connected requires native Running state, an established native tunnel, a matching Tailscale IPv4 address on the Android VPN, and a validated physical network. A service notification or Android VPN permission alone does not mean connected. No enrollment is shown as Family setup needed; no internet is shown explicitly. Duplicate refresh/disconnected controls are removed. Existing encrypted enrollment tools remain collapsed under Owner setup for recovery; they are not presented as automatic enrollment.

Startup and shutdown are ordered: cancellation is checked after blocking native calls, shutdown waits for startup to finish, then applies the final stop. The service remains in Disconnecting until cleanup completes. Existing Android VPN permission and single-VPN behavior remain.

## Remaining install-once enrollment work

This update does not solve enrollment. A public APK cannot prove that its downloader belongs to this family. Shipping reusable tailnet credentials or a shared node identity would let unrelated downloaders join. The intended architecture is an owner-authorized private enrollment service which issues short-lived single-use credentials encrypted to each installation and admits only approved family devices (or a private one-use invitation). Family devices would submit their own request and retrieve the encrypted response inside the app, with no import/export steps. Android VPN consent remains a platform requirement.

No Tailscale admin credential or authorized enrollment service is configured. Owner must authenticate once to the Tailscale admin console on MASTER-PC and authorize server-side enrollment. Do not claim automatic enrollment or authenticated calls work until the service, device admission, credential delivery and two-device runtime tests are implemented and verified. Owner authorization and device admission are prerequisites, not substitutes for that remaining implementation. No account password or API secret belongs in the APK, public Git, chat, logcat or public web root.

## First-launch nickname

Fresh installations show a one-time nickname choice (device suggestion, Save/Skip) before notification permission. Existing identities migrate silently. Both choices persist the name and completion marker, and nickname remains editable in Settings. See RELEASE_EMBEDDED04_20261017.md for validation and download.
