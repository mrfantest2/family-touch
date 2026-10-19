package win.fantest.familytouch
internal object MessageDelivery {
    fun nextUnread(messages: List<ChatItem>, peerId: String) = messages.asSequence()
        .filter { it.peerId == peerId && !it.outgoing && !it.read }.take(100).map { it.id }.toList()
    fun label(sent: Boolean, delivered: Boolean, read: Boolean) = when {
        delivered && read -> "Read"
        delivered -> "Delivered"
        sent -> "Sent"
        else -> "Queued"
    }
}
