package win.fantest.familytouch

/**
 * Reserve 40 for the quiet connection service and 41 for the incoming call.
 * Reuse one notification ID per sender instead of stacking every message.
 */
internal object NotificationIds {
    const val CONNECTION = 40
    const val INCOMING_CALL = 41
    fun messageForPeer(peerId: String): Int = 1_000 + (peerId.hashCode() and 0x3fff_ffff)
}
