package win.fantest.familytouch

import android.content.Context
import android.content.Intent
import android.net.Uri

internal enum class ConnectionMode(val key: String) {
    BUILT_IN("built_in"), EXTERNAL("external");
    companion object {
        fun restore(saved: String?, embeddedAvailable: Boolean) =
            if (!embeddedAvailable || saved == "external") EXTERNAL else BUILT_IN
    }
}
internal object FamilyNetworkMode {
    const val CLIENT = "com.tailscale.ipn"
    fun selected(context: Context) = ConnectionMode.restore(
        context.getSharedPreferences("vpn", Context.MODE_PRIVATE).getString("mode", null), BuildConfig.EMBEDDED_VPN)
    fun change(context: Context, mode: ConnectionMode) {
        check(FamilyStore.activeCall == null && FamilyStore.incomingCall == null &&
            GroupCallController.session == null && GroupCallController.incoming == null) { "End the call before changing connection mode" }
        check(mode != ConnectionMode.BUILT_IN || BuildConfig.EMBEDDED_VPN)
        check(context.getSharedPreferences("vpn", Context.MODE_PRIVATE).edit().putString("mode", mode.key).commit())
    }
    fun installed(context: Context) = runCatching { context.packageManager.getPackageInfo(CLIENT, 0); true }.getOrDefault(false)
    fun externalStatus(context: Context) = ExternalVpnPolicy.status(installed(context), Tailnet.vpnIp(context))
    fun openExternal(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(CLIENT)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$CLIENT"))
        context.startActivity(intent)
    }
}
