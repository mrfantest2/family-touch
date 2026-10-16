package win.fantest.familytouch

internal object ProvisioningPolicy {
    fun accepts(expected: String, actual: String, expires: Long, now: Long): Boolean =
        expected.isNotBlank() && expected == actual && expires > now && expires - now <= 3600
}
