package win.fantest.familytouch

import android.os.Build
import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private val green = Color(0xFF008F56)
private val mint = Color(0xFFE4F6EB)
private val ink = Color(0xFF142722)
private val muted = Color(0xFF667670)
private const val HOST = "https://family-admin.fantest.win/admin/api/v1"

private data class DeviceRecord(
    val id: String, val name: String, val model: String,
    val approved: Boolean, val revoked: Boolean
)
private data class Dashboard(
    val devices: List<DeviceRecord>, val deleted: Int, val auto: Boolean
)

private object AdminApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).build()

    private fun send(user: String, password: String, endpoint: String, body: String? = null): JSONObject {
        val builder = Request.Builder().url(HOST + endpoint)
            .header("Authorization", Credentials.basic(user, password))
            .header("X-Requested-With", "FamilyTouchNative")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
        if (body == null) builder.get() else
            builder.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 401) throw IOException("Incorrect username or password")
            if (response.code == 403) throw IOException("Administrator access denied")
            if (!response.isSuccessful) throw IOException("Server error (HTTP " + response.code + ")")
            return JSONObject(response.body?.string()?.take(65536) ?: throw IOException("Empty reply"))
        }
    }

    suspend fun load(user: String, pass: String): Dashboard = withContext(Dispatchers.IO) {
        val response = send(user, pass, "/devices")
        val arr = response.optJSONArray("devices") ?: throw IOException("No device data")
        val devices = buildList {
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                add(DeviceRecord(item.getString("id"), item.optString("name", "Unknown"),
                    item.optString("model", ""), item.optBoolean("approved"), item.optBoolean("revoked")))
            }
        }
        Dashboard(devices, response.optJSONArray("deleted")?.length() ?: 0,
            response.optBoolean("autoApprove"))
    }

    suspend fun update(user: String, pass: String, device: DeviceRecord, operation: String) =
        withContext(Dispatchers.IO) {
            require(operation in setOf("approve", "revoke", "delete"))
            val payload = JSONObject().put("id", device.id).put("action", operation).toString()
            if (!send(user, pass, "/device", payload).optBoolean("ok")) throw IOException("Update failed")
        }
}

@Composable
internal fun FamilyTouchAdminUI() {
    val s25 = Build.MANUFACTURER.equals("samsung", true) && Build.MODEL == "SM-S938B"
    val context = LocalContext.current
    // S25-only encrypted storage; the key is managed by Android Keystore.
    // Nothing is persisted until the server has actually authenticated the user.
    val securePrefs = remember {
        runCatching {
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(context, "admin_saved_login", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        }.getOrNull()
    }
    var user by remember { mutableStateOf(securePrefs?.getString("username", "") ?: "") }
    var pass by remember { mutableStateOf(securePrefs?.getString("password", "") ?: "") }
    var rememberLogin by remember { mutableStateOf(securePrefs?.contains("password") == true) }
    var loggedIn by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(3) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var dashboard by remember { mutableStateOf(Dashboard(emptyList(), 0, false)) }
    var pending by remember { mutableStateOf<Pair<DeviceRecord, String>?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (rememberLogin && user.isNotEmpty() && pass.isNotEmpty()) {
            busy = true
            try {
                dashboard = AdminApi.load(user, pass)
                loggedIn = true
                error = ""
            } catch (e: Exception) {
                error = e.message ?: "Unable to restore login"
                // Stale credentials must not cause repeated automatic login attempts.
                if (error.contains("Incorrect username")) {
                    securePrefs?.edit()?.clear()?.apply()
                    rememberLogin = false
                    pass = ""
                }
            } finally { busy = false }
        }
    }

    fun reload() {
        busy = true
        scope.launch {
            try { dashboard = AdminApi.load(user, pass); error = "" }
            catch (e: Exception) { error = e.message ?: "Server unavailable" }
            finally { busy = false }
        }
    }
    MaterialTheme(colorScheme = lightColorScheme(
        primary = green, onPrimary = Color.White, background = Color(0xFFF7FAF8),
        surface = Color.White, onBackground = ink, onSurface = ink,
        secondary = green, secondaryContainer = mint, onSecondaryContainer = ink,
        surfaceContainerLow = Color.White, surfaceContainer = Color.White,
        surfaceVariant = Color(0xFFF3F7F4), onSurfaceVariant = muted
    )) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFFF7FAF8)) {
            if (!s25) {
                Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Outlined.Security, null, Modifier.size(55.dp), tint = green)
                    Text("Family Touch Admin is restricted to S25")
                    Text("Server-enforced hardware authorization is still pending.", color = muted)
                }
            } else if (!loggedIn) {
                Column(Modifier.fillMaxSize().padding(26.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Security, null, Modifier.size(75.dp), tint = green)
                    Spacer(Modifier.height(18.dp))
                    Text("Family Touch Admin", fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text("Secure family management", color = muted)
                    Spacer(Modifier.height(18.dp))
                    Surface(color = mint, shape = RoundedCornerShape(15.dp)) {
                        Text("Native Android  •  HTTPS  •  No VPN", Modifier.padding(13.dp), color = green)
                    }
                    Spacer(Modifier.height(22.dp))
                    OutlinedTextField(user, { user = it.take(80) }, label = { Text("Username") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(pass, { pass = it.take(160) }, label = { Text("Password") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp))
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()) {
                        Checkbox(checked = rememberLogin, onCheckedChange = { rememberLogin = it },
                            enabled = securePrefs != null)
                        Text("Remember my login securely on this S25", fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    dashboard = AdminApi.load(user, pass)
                                    if (rememberLogin && securePrefs != null) {
                                        securePrefs.edit().putString("username", user)
                                            .putString("password", pass).apply()
                                    } else {
                                        securePrefs?.edit()?.clear()?.apply()
                                    }
                                    error = ""; loggedIn = true
                                } catch (e: Exception) { error = e.message ?: "Login unavailable" }
                                finally { busy = false }
                            }
                        }, enabled = !busy && user.isNotBlank() && pass.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(20.dp)
                    ) { Text(if (busy) "Verifying…" else "Sign in") }
                    Text("Saved login is encrypted with Android Keystore. Sign out clears it.",
                        fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 12.dp))
                }
            } else {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth().background(Color.White).padding(15.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Security, null, tint = green)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Family Touch Admin", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                            Text("MASTER-PC • Secure HTTPS • No VPN", fontSize = 11.sp, color = green)
                        }
                        TextButton(onClick = {
                            securePrefs?.edit()?.clear()?.apply()
                            rememberLogin = false
                            user = ""; pass = ""; loggedIn = false; error = ""
                            dashboard = Dashboard(emptyList(), 0, false)
                        }) { Text("Sign out") }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Box(Modifier.weight(1f)) {
                        when (tab) {
                            3 -> Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text("Devices & approvals", fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { reload() }, enabled = !busy) { Text("Refresh") }
                                }
                                Surface(color = mint, shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        dashboard.devices.size.toString() + " registered • " +
                                            dashboard.devices.count { it.approved }.toString() +
                                            " approved • " + dashboard.deleted.toString() + " deleted",
                                        Modifier.padding(13.dp), color = green, fontSize = 13.sp)
                                }
                                if (dashboard.auto) Text("Automatic admission enabled",
                                    color = MaterialTheme.colorScheme.error)
                                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.height(10.dp))
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                    items(dashboard.devices, key = { it.id }) { device ->
                                        ElevatedCard(shape = RoundedCornerShape(17.dp),
                                            colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
                                            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)) {
                                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Box(Modifier.size(42.dp).background(mint, CircleShape),
                                                        contentAlignment = Alignment.Center) {
                                                        Text(device.name.take(1).uppercase(),
                                                            color = green, fontWeight = FontWeight.Bold)
                                                    }
                                                    Spacer(Modifier.width(11.dp))
                                                    Column(Modifier.weight(1f)) {
                                                        Text(device.name, fontWeight = FontWeight.Bold)
                                                        Text(device.model, fontSize = 12.sp, color = muted)
                                                    }
                                                    Text(if (device.revoked) "Revoked" else
                                                        if (device.approved) "Approved" else "Pending",
                                                        color = if (device.approved) green else muted, fontSize = 12.sp)
                                                }
                                                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                                    if (!device.approved) OutlinedButton(onClick = {
                                                        pending = device to "approve"
                                                    }, enabled = !busy) { Text("Approve") }
                                                    if (!device.revoked) OutlinedButton(onClick = {
                                                        pending = device to "revoke"
                                                    }, enabled = !busy) { Text("Revoke") }
                                                    TextButton(onClick = { pending = device to "delete" },
                                                        enabled = !busy) { Text("Delete") }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            2 -> Column(Modifier.fillMaxSize().padding(20.dp)) {
                                Text("Family contacts", fontWeight = FontWeight.Bold, fontSize = 23.sp)
                                Text("Registered family devices", color = muted)
                                dashboard.devices.forEach { device ->
                                    Text(device.name + " • " +
                                        if (device.approved) "Approved" else "Not approved",
                                        Modifier.padding(vertical = 12.dp))
                                    HorizontalDivider()
                                }
                            }
                            else -> Column(Modifier.fillMaxSize().padding(26.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center) {
                                Icon(if (tab == 0) Icons.Outlined.ChatBubbleOutline else Icons.Outlined.Call,
                                    null, Modifier.size(54.dp), tint = green)
                                Text(if (tab == 0) "Chats" else "Calls", fontSize = 24.sp)
                                Text("The VPN-free messaging and calling relay is not yet implemented. " +
                                    "Use the separate Family Touch app for these features.",
                                    color = muted, modifier = Modifier.padding(top = 12.dp))
                            }
                        }
                    }
                    NavigationBar(containerColor = Color.White) {
                        listOf(
                            Triple("Chats", Icons.Outlined.ChatBubbleOutline, 0),
                            Triple("Calls", Icons.Outlined.Call, 1),
                            Triple("Contacts", Icons.Outlined.Contacts, 2),
                            Triple("Admin", Icons.Outlined.Security, 3)
                        ).forEach { (label, icon, index) ->
                            NavigationBarItem(selected = tab == index, onClick = { tab = index },
                                icon = { Icon(icon, label) }, label = { Text(label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = green, selectedTextColor = green,
                                    indicatorColor = mint, unselectedIconColor = muted,
                                    unselectedTextColor = muted))
                        }
                    }
                }
            }
        }
        pending?.let { (device, action) ->
            AlertDialog(onDismissRequest = { pending = null },
                title = { Text(action.replaceFirstChar { it.uppercase() } + " " + device.name + "?") },
                text = { Text(when (action) {
                    "delete" -> "Remove this registration? Re-enrollment will be required. This cannot be undone."
                    "revoke" -> "Revoke this device's access to future private credentials?"
                    else -> "Approve this device for family access?"
                }) },
                confirmButton = {
                    TextButton(enabled = !busy, onClick = {
                        pending = null; busy = true
                        scope.launch {
                            try {
                                AdminApi.update(user, pass, device, action)
                                dashboard = AdminApi.load(user, pass)
                                error = ""
                            } catch (e: Exception) { error = e.message ?: "Action failed" }
                            finally { busy = false }
                        }
                    }) { Text("Confirm") }
                },
                dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } }
            )
        }
    }
}
