package win.fantest.familytouch

internal object ExternalVpnPolicy {
    fun status(installed: Boolean, vpnAddress: String?): VpnConnectionStatus = when {
        !installed -> VpnConnectionStatus("Install Tailscale to connect")
        vpnAddress == null || !Tailnet.isTailIp(vpnAddress) -> VpnConnectionStatus("Connect Tailscale to your family network")
        else -> VpnConnectionStatus("Private VPN connected", true, vpnAddress)
    }
}
