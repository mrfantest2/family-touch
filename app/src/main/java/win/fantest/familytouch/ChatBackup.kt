package win.fantest.familytouch

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Encrypted, merge-only chat history backup. Does NOT export pairing tokens, PIN or media bytes. */
internal object ChatBackup {
    private val magic = "FTBACKUP1".toByteArray(Charsets.US_ASCII)
    private const val MAX_BYTES = 12 * 1024 * 1024
    private const val ITERATIONS = 210_000

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        require(password.size >= 8) { "Use a backup passphrase of at least 8 characters" }
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
    }

    fun snapshot(): String {
        val data = JSONArray()
        FamilyStore.messages.takeLast(5000).forEach { item ->
            data.put(JSONObject().put("id", item.id).put("peerId", item.peerId)
                .put("outgoing", item.outgoing).put("text", item.text).put("time", item.time)
                .put("senderId", item.senderId).put("editedAt", item.editedAt)
                .put("deleted", item.deletedForEveryone))
        }
        val json = JSONObject().put("format", "family-touch-chats")
            .put("version", 1).put("createdAt", System.currentTimeMillis())
            .put("messages", data).toString()
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "Chat archive too large; export a smaller history"
        }
        return json
    }

    fun encrypt(snapshot: String, password: CharArray): ByteArray {
        val plain = snapshot.toByteArray(Charsets.UTF_8)
        require(plain.size <= MAX_BYTES)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, nonce))
        val payload = cipher.doFinal(plain)
        return ByteArrayOutputStream().apply {
            write(magic); write(salt); write(nonce); write(payload)
        }.toByteArray()
    }

    fun decrypt(raw: ByteArray, password: CharArray): List<ChatItem> {
        require(raw.size in (magic.size + 16 + 12 + 16)..(MAX_BYTES + 1024)) {
            "Invalid or oversized backup"
        }
        require(raw.copyOfRange(0, magic.size).contentEquals(magic)) {
            "Not a Family Touch encrypted chat backup"
        }
        val salt = raw.copyOfRange(magic.size, magic.size + 16)
        val nonce = raw.copyOfRange(magic.size + 16, magic.size + 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, nonce))
        val plaintext = cipher.doFinal(raw, magic.size + 28, raw.size - magic.size - 28)
        val root = JSONObject(String(plaintext, Charsets.UTF_8))
        require(root.getString("format") == "family-touch-chats" && root.getInt("version") == 1) {
            "Unsupported backup format"
        }
        val rows = root.getJSONArray("messages")
        require(rows.length() <= 5000) { "Too many messages in backup" }
        return (0 until rows.length()).mapNotNull { index ->
            runCatching {
                val row = rows.getJSONObject(index)
                ChatItem(id = row.getString("id"), peerId = row.getString("peerId"),
                    outgoing = row.getBoolean("outgoing"), text = row.getString("text"),
                    time = row.getLong("time"), delivered = true,
                    senderId = row.optString("senderId"), sent = true, read = true,
                    editedAt = row.optLong("editedAt"), deletedForEveryone = row.optBoolean("deleted"))
            }.getOrNull()
        }
    }

    fun read(context: Context, uri: Uri): ByteArray {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val bytes = ByteArray(65536)
            while (true) {
                val n = input.read(bytes)
                if (n < 0) break
                require(out.size() + n <= MAX_BYTES + 1024) { "Backup file too large" }
                out.write(bytes, 0, n)
            }
            return out.toByteArray()
        }
        error("Unable to open backup")
    }

    fun write(context: Context, uri: Uri, bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES + 1024)
        context.contentResolver.openOutputStream(uri, "w")?.use {
            it.write(bytes); it.flush()
        } ?: error("Unable to save backup")
    }
}
