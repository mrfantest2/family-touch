package win.fantest.familytouch

/**
 * Bind enrollment HTTPS to the physical underlay only while Family Touch
 * owns (or is shutting down) the Android VPN service. Otherwise the default
 * network may be the user's independently authenticated external Tailscale VPN.
 */
internal object DirectoryBootstrapRoute {
    fun usePhysicalUnderlay(serviceActive: Boolean, stopping: Boolean): Boolean =
        serviceActive || stopping
}
