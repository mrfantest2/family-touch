package win.fantest.familytouch

import java.text.DateFormat
import java.util.Date

/** User-approved local text export; never includes pairing tokens or raw protocol metadata. */
internal object ChatExport {
    const val MAX_MESSAGES = 200
    fun plainText(peer: Peer, messages: List<ChatItem>, nickname: String): String {
        val selected = messages.filter { it.peerId == peer.id }.takeLast(MAX_MESSAGES)
        val title = peer.name.replace('\n', ' ').take(60)
        return buildString {
            append("Family Touch conversation with ").append(title).append('\n')
            append("Local export; media bytes and pairing credentials not included.\n\n")
            selected.forEach { item ->
                val name = if (item.outgoing) nickname else title
                val stamp = DateFormat.getDateTimeInstance().format(Date(item.time))
                val body = if (item.deletedForEveryone) "Message deleted" else
                    ChatPreviewFormatter.forContact(item.text)
                append('[').append(stamp).append("] ").append(name.take(60))
                    .append(": ").append(body.take(1000)).append('\n')
            }
            if (selected.isEmpty()) append("No local messages to export.\n")
            if (messages.count { it.peerId == peer.id } > MAX_MESSAGES)
                append("\nOnly the most recent ").append(MAX_MESSAGES).append(" messages exported.\n")
        }
    }
}
