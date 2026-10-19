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
    private fun allowed(mime: String) = mime.startsWith("image/") || mime.startsWith("video/") || VoiceNotePolicy.audioMime(mime)
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
        val uri = localUri(context, info)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, info.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
    fun localUri(context: Context, info: Info): Uri {
        validatedId(info.id)
        val file = File(dir(context), info.id + ".bin")
        require(file.isFile && file.length() == info.size) { "File is missing or incomplete" }
        return FileProvider.getUriForFile(context, context.packageName + ".files", file)
    }
    fun share(context: Context, info: Info) {
        val intent = Intent(Intent.ACTION_SEND).setType(info.mime)
            .putExtra(Intent.EXTRA_STREAM, localUri(context, info))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Share " + info.name))
    }
    private fun digest() = MessageDigest.getInstance("SHA-256")
    private fun toHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    internal fun prepare(context: Context, uri: Uri): Info {
        val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
        require(allowed(mime)) { "Unsupported media type" }
        var filename = "Family Touch media"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) filename = it.getString(0) ?: filename
        }
        val id = UUID.randomUUID().toString()
        val file = File(dir(context), "$id.bin")
        try {
            var size = 0L
            requireNotNull(context.contentResolver.openInputStream(uri)).use { source ->
                file.outputStream().buffered().use { target ->
                    val bytes = ByteArray(32768)
                    while (true) {
                        val count = source.read(bytes); if (count < 0) break
                        size += count; require(size <= MAX_BYTES) { "Attachment exceeds 50 MiB" }
                        target.write(bytes, 0, count)
                    }
                }
            }
            require(size > 0) { "Empty attachment" }
            return Info(id, nameOf(filename), mime, size)
        } catch (e: Exception) { file.delete(); throw e }
    }
    internal fun sendPrepared(context: Context, peer: Peer, info: Info, group: FamilyGroup? = null) {
        localUri(context, info)
        val file = File(dir(context), info.id + ".bin")
        val sha = digest()
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(32768)
            while (true) { val count = input.read(buffer); if (count < 0) break; sha.update(buffer, 0, count) }
        }
        val header = (group?.json() ?: JSONObject()).put("type", "attachment").put("id", FamilyStore.deviceId)
            .put("nick", FamilyStore.nickname).put("secret", peer.secret)
            .put("attachmentId", info.id).put("mime", info.mime).put("name", info.name)
            .put("size", info.size).put("sha256", toHex(sha.digest()))
        Socket().use { socket ->
            socket.connect(InetSocketAddress(peer.ip, Tailnet.PORT), 7000); socket.soTimeout = 90000
            val output = socket.getOutputStream().buffered()
            output.write((header.toString() + "\n").toByteArray())
            file.inputStream().buffered().use { it.copyTo(output) }; output.flush()
            require(JSONObject(Tailnet.readWireLine(socket.getInputStream().buffered(), 32768)).optBoolean("ok")) { "Attachment rejected" }
        }
    }

    suspend fun send(context: Context, peer: Peer, uris: List<Uri>, onSent: (ChatItem) -> Unit) =
        withContext(Dispatchers.IO) {
            require(uris.size in 1..MAX_BATCH)
            uris.forEach { uri ->
                val info = prepare(context, uri)
                withContext(Dispatchers.Main) {
                    try { onSent(PeerOutbox.queue(peer, "", info)) }
                    catch (e: Exception) { File(dir(context), info.id + ".bin").delete(); throw e }
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

