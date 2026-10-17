package win.fantest.familytouch

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID

/** Authenticated tailnet-only file transfer with 10 x 50 MiB limits. */
object AttachmentTransport {
    const val MAX_BYTES = 50L * 1024L * 1024L
    const val MAX_BATCH = 10
    private const val MARK = "[FT_FILE]"
    private fun dir(context: Context): File = File(context.filesDir, "attachments").also { it.mkdirs() }
    private fun validatedId(id: String) = UUID.fromString(id).toString()
    private fun nameOf(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9 ._()-]"), "_").take(110).ifBlank { "Attachment" }
    private fun allowed(mime: String) = mime.startsWith("image/") || mime.startsWith("video/")
    fun message(id: String, name: String, mime: String, size: Long) =
        "$MARK|$id|" + nameOf(name) + "|$mime|$size"
    data class Info(val id: String, val name: String, val mime: String, val size: Long)
    fun parse(text: String): Info? {
        if (!text.startsWith("$MARK|")) return null
        return try {
            val a = text.split("|", limit = 5)
            if (a.size != 5) return null
            val size = a[4].toLong()
            if (size !in 1..MAX_BYTES || !allowed(a[3])) return null
            Info(validatedId(a[1]), nameOf(a[2]), a[3], size)
        } catch (_: Exception) { null }
    }
    fun open(context: Context, info: Info) {
        val file = File(dir(context), info.id + ".bin")
        require(file.isFile && file.length() == info.size) { "File is missing or incomplete" }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, info.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
    private fun digest() = MessageDigest.getInstance("SHA-256")
    private fun toHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    suspend fun send(context: Context, peer: Peer, uris: List<Uri>, onSent: (ChatItem) -> Unit) =
        withContext(Dispatchers.IO) {
            require(uris.size in 1..MAX_BATCH) { "Select 1 to 10 attachments" }
            require(Tailnet.vpnIp(context) != null) { "Connect private network first" }
            uris.forEach { uri ->
                val resolver = context.contentResolver
                val mime = resolver.getType(uri).orEmpty().lowercase()
                require(allowed(mime)) { "Only photos and videos are supported" }
                var filename = "Family Touch file"
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) filename = it.getString(0) ?: filename
                }
                val id = UUID.randomUUID().toString()
                val dest = File(dir(context), "$id.bin")
                val sha = digest()
                var amount = 0L
                try {
                    resolver.openInputStream(uri).use { incoming ->
                        requireNotNull(incoming) { "Cannot read attachment" }
                        dest.outputStream().buffered().use { output ->
                            val buf = ByteArray(32 * 1024)
                            while (true) {
                                val n = incoming.read(buf)
                                if (n < 0) break
                                amount += n
                                require(amount <= MAX_BYTES) { "Attachment exceeds 50 MiB" }
                                output.write(buf, 0, n)
                                sha.update(buf, 0, n)
                            }
                        }
                    }
                    require(amount in 1..MAX_BYTES) { "Attachment is empty" }
                    val header = JSONObject()
                        .put("type", "attachment").put("id", FamilyStore.deviceId)
                        .put("nick", FamilyStore.nickname).put("secret", peer.secret)
                        .put("attachmentId", id).put("mime", mime)
                        .put("name", nameOf(filename)).put("size", amount)
                        .put("sha256", toHex(sha.digest()))
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(peer.ip, Tailnet.PORT), 7000)
                        socket.soTimeout = 90_000
                        val output = socket.getOutputStream().buffered()
                        output.write((header.toString() + "\n").toByteArray(Charsets.UTF_8))
                        dest.inputStream().buffered().use { source -> source.copyTo(output, 32 * 1024) }
                        output.flush()
                        val result = JSONObject(Tailnet.readWireLine(socket.getInputStream().buffered(), 32768))
                        require(result.optBoolean("ok")) { result.optString("error", "Transfer rejected") }
                    }
                    withContext(Dispatchers.Main) { onSent(ChatItem(id, peer.id, true, message(id, filename, mime, amount),
                        System.currentTimeMillis(), true)) }
                } catch (e: Exception) {
                    dest.delete()
                    throw e
                }
            }
        }

    fun receive(context: Context, input: InputStream, json: JSONObject, peerId: String): ChatItem {
        val id = validatedId(json.getString("attachmentId"))
        val mime = json.getString("mime").lowercase()
        require(allowed(mime)) { "Unsupported attachment type" }
        val name = nameOf(json.optString("name"))
        val size = json.getLong("size")
        require(size in 1..MAX_BYTES) { "Attachment must be 1 to 50 MiB" }
        val checksum = json.getString("sha256").lowercase()
        require(checksum.matches(Regex("[a-f0-9]{64}"))) { "Invalid checksum" }
        val dest = File(dir(context), "$id.bin")
        val temp = File(dir(context), "$id.pending")
        var remaining = size
        val sha = digest()
        try {
            temp.outputStream().buffered().use { output ->
                val chunk = ByteArray(32 * 1024)
                while (remaining > 0) {
                    val n = input.read(chunk, 0, minOf(chunk.size.toLong(), remaining).toInt())
                    require(n > 0) { "Attachment was interrupted" }
                    sha.update(chunk, 0, n)
                    output.write(chunk, 0, n)
                    remaining -= n
                }
            }
            require(toHex(sha.digest()) == checksum) { "File integrity check failed" }
            if (!dest.exists()) require(temp.renameTo(dest)) { "Could not save attachment" }
            else temp.delete()
            return ChatItem(id, peerId, false, message(id, name, mime, size),
                System.currentTimeMillis(), true)
        } finally { temp.delete() }
    }
}

