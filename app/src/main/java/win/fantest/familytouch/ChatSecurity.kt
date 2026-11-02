package win.fantest.familytouch

import android.content.Context
import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Separate per-conversation six-digit PINs. They never replace the app's main PIN. */
internal object ChatSecurity {
    private fun key(peerId: String): String {
        require(peerId.isNotBlank())
        return MessageDigest.getInstance("SHA-256")
            .digest(peerId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context.applicationContext, "private_chat_pins",
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    private fun hash(pin: String, salt: ByteArray): ByteArray {
        require(pin.length == 6 && pin.all { it in '0'..'9' })
        val spec = PBEKeySpec(pin.toCharArray(), salt, 180_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    fun enabled(context: Context, peerId: String): Boolean =
        prefs(context).getBoolean(key(peerId) + "_enabled", false)

    fun set(context: Context, peerId: String, pin: String) {
        val id = key(peerId)
        val salt = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val stored = hash(pin, salt)
        check(prefs(context).edit().putString(id + "_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(id + "_hash", Base64.encodeToString(stored, Base64.NO_WRAP))
            .putBoolean(id + "_enabled", true).putLong(id + "_until", 0)
            .putInt(id + "_attempts", 0).commit()) { "Cannot save chat PIN" }
        salt.fill(0); stored.fill(0)
    }
    fun verify(context: Context, peerId: String, pin: String): Boolean {
        if (!FamilyPin.valid(pin)) return false
        val id = key(peerId)
        val p = prefs(context)
        if (!p.getBoolean(id + "_enabled", false)) return false
        val now = System.currentTimeMillis()
        if (now < p.getLong(id + "_until", 0L)) return false
        val salt = Base64.decode(p.getString(id + "_salt", ""), Base64.NO_WRAP)
        val expected = Base64.decode(p.getString(id + "_hash", ""), Base64.NO_WRAP)
        if (salt.size != 24 || expected.size != 32) return false
        val candidate = hash(pin, salt)
        val matches = MessageDigest.isEqual(candidate, expected)
        candidate.fill(0); salt.fill(0); expected.fill(0)
        val edits = p.edit()
        if (matches) edits.putInt(id + "_attempts", 0).putLong(id + "_until", 0L)
        else {
            val attempts = p.getInt(id + "_attempts", 0) + 1
            edits.putInt(id + "_attempts", attempts)
            if (attempts >= 5) edits.putLong(id + "_until", now + 30_000L)
        }
        check(edits.commit()) { "Cannot save chat PIN state" }
        return matches
    }
    fun cooldown(context: Context, peerId: String): Long =
        ((prefs(context).getLong(key(peerId) + "_until", 0) - System.currentTimeMillis()) / 1000)
            .coerceAtLeast(0)
    fun disable(context: Context, peerId: String, pin: String): Boolean {
        if (!verify(context, peerId, pin)) return false
        val id = key(peerId)
        check(prefs(context).edit()
            .remove(id + "_salt").remove(id + "_hash").remove(id + "_enabled")
            .remove(id + "_attempts").remove(id + "_until").commit())
        return true
    }
}

@Composable
internal fun ChatSecurityGate(peerId: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var isUnlocked by remember(peerId) { mutableStateOf(false) }
    var digits by remember(peerId) { mutableStateOf("") }
    var error by remember(peerId) { mutableStateOf("") }
    DisposableEffect(peerId, lifecycle) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { isUnlocked = false; digits = "" }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val locked = try { ChatSecurity.enabled(context, peerId) } catch (_: Exception) { true }
    if (!locked || isUnlocked) {
        content()
        return
    }
    Column(Modifier.fillMaxSize().padding(22.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Private conversation", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(9.dp))
        Text("Enter this chat's six-digit PIN to open it.")
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(digits, onValueChange = { digits = it.filter(Char::isDigit).take(6) },
            modifier = Modifier.fillMaxWidth(), label = { Text("Chat PIN") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Button(enabled = digits.length == 6, modifier = Modifier.fillMaxWidth(),
            onClick = {
                try {
                    isUnlocked = ChatSecurity.verify(context, peerId, digits)
                    error = if (isUnlocked) "" else if (ChatSecurity.cooldown(context, peerId) > 0)
                        "Too many attempts. Try later." else "Incorrect chat PIN"
                } catch (_: Exception) { error = "Chat PIN verification unavailable" }
                digits = ""
            }) { Text("Unlock conversation") }
        Text("Your app PIN and this conversation PIN are separate.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun ChatSecuritySettingsDialog(peerId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember(peerId) { mutableStateOf(runCatching {
        ChatSecurity.enabled(context, peerId)
    }.getOrDefault(false)) }
    var old by remember(peerId) { mutableStateOf("") }
    var fresh by remember(peerId) { mutableStateOf("") }
    var confirm by remember(peerId) { mutableStateOf("") }
    var remove by remember(peerId) { mutableStateOf(false) }
    var error by remember(peerId) { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Conversation security") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (enabled) "This chat is protected by its own PIN."
                    else "Add a different six-digit PIN for this chat.")
                if (enabled) {
                    OutlinedTextField(old, onValueChange = { old = it.filter(Char::isDigit).take(6) },
                        label = { Text("Existing chat PIN") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    TextButton(onClick = { remove = !remove }) {
                        Text(if (remove) "Keep chat lock" else "Remove this chat lock")
                    }
                }
                if (!remove) {
                    OutlinedTextField(fresh, onValueChange = { fresh = it.filter(Char::isDigit).take(6) },
                        label = { Text("New six-digit PIN") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    OutlinedTextField(confirm, onValueChange = { confirm = it.filter(Char::isDigit).take(6) },
                        label = { Text("Confirm PIN") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                }
                Text("Only this phone is locked. A family member's other device is not locked by your PIN.",
                    style = MaterialTheme.typography.bodySmall)
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { Button(onClick = {
            try {
                if (enabled && !ChatSecurity.verify(context, peerId, old)) {
                    error = "Existing PIN incorrect or temporarily blocked"
                } else if (remove) {
                    if (ChatSecurity.disable(context, peerId, old)) onDismiss()
                    else error = "Could not remove PIN"
                } else if (fresh != confirm || !FamilyPin.valid(fresh)) {
                    error = "Enter matching six-digit PINs"
                } else {
                    ChatSecurity.set(context, peerId, fresh)
                    enabled = true; onDismiss()
                }
            } catch (_: Exception) { error = "Secure PIN storage is unavailable" }
        }) { Text(if (remove) "Remove lock" else "Save lock") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
