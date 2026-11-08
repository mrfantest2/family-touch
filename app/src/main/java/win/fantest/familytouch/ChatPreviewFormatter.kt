package win.fantest.familytouch

/** A chat-list preview must never expose serialized live-location coordinates or session IDs. */
internal object ChatPreviewFormatter {
    fun forContact(text: String, nowMs: Long = System.currentTimeMillis()): String {
        if (text.startsWith("[FT_FILE]|")) {
            return AttachmentTransport.parse(text)?.let { "Attachment: " + it.name }
                ?: "Attachment"
        }
        if (!text.startsWith(LiveLocationProtocol.PREFIX)) return text
        val fix = LiveLocationProtocol.parse(text) ?: return "Location update"
        return when {
            fix.ended -> "Live location stopped"
            fix.expiresAt <= nowMs -> "Live location expired"
            else -> "Live location shared"
        }
    }
}
