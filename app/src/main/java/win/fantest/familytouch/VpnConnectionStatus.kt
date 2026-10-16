package win.fantest.familytouch
internal data class VpnConnectionStatus(val label: String, val connected: Boolean = false, val address: String? = null) {
    companion object {
        fun fromBackend(backend: String, tunnel: Boolean, nodeIp: String?, vpnIp: String?, networkAvailable: Boolean = true): VpnConnectionStatus = when (backend) {
            "NeedsLogin", "NoState" -> VpnConnectionStatus("Family setup needed")
            "NeedsMachineAuth" -> VpnConnectionStatus("Waiting for family approval")
            "Running" -> if (!networkAvailable) VpnConnectionStatus("Waiting for internet")
            else if (tunnel && nodeIp != null && Tailnet.isTailIp(nodeIp) && nodeIp == vpnIp)
                VpnConnectionStatus("Connected", true, nodeIp)
            else VpnConnectionStatus("Connecting")
            "Stopped", "Stopping" -> VpnConnectionStatus("Disconnected")
            else -> VpnConnectionStatus("Connecting")
        }
    }
}
