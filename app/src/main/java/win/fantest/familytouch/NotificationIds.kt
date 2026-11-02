package win.fantest.familytouch

/**
 * Reserve 40 for the quiet connection service and 41 for the incoming call.
 * Reuse one notification ID per sender instead of stacking every message.
 */
internal object NotificationIds {
    const val CONNECTION = 40
    const val INCOMING_CALL = 41
    const val ACTION_ANSWER_CALL = "win.fantest.familytouch.action.ANSWER_CALL"
    const val ACTION_DECLINE_CALL = "win.fantest.familytouch.action.DECLINE_CALL"
    const val EXTRA_CALL_SESSION = "familytouch_call_session"
    fun messageForPeer(peerId: String): Int = 1_000 + (peerId.hashCode() and 0x3fff_ffff)
}
