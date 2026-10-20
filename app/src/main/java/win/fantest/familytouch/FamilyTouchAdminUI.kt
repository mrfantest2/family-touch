package win.fantest.familytouch

import android.os.Build
import android.webkit.HttpAuthHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

private val green = Color(0xFF008F56)
private val pale = Color(0xFFE4F6EB)
private val textColor = Color(0xFF142722)
private const val ADMIN_HOST = "family-admin.fantest.win"
private const val ADMIN_PAGE = "https://family-admin.fantest.win/admin"

/**
 * v0.3 admin preview: HTTPS access to existing authenticated management gateway.
 * No VpnService is ever launched. The server-side hardware-bound admin session
 * and secure chat/call relay are mandatory checkpoints before production use.
 */
@Composable
internal fun FamilyTouchAdminUI() {
    val s25 = Build.MANUFACTURER.equals("samsung", true) && Build.MODEL == "SM-S938B"
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var entered by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(3) }
    var authAttempt by remember { mutableIntStateOf(0) }
    MaterialTheme(colorScheme = lightColorScheme(
        primary = green, surface = Color.White, background = Color(0xFFF7FAF8),
        onBackground = textColor, onSurface = textColor
    )) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFFF7FAF8)) {
            if (!s25) {
                Column(Modifier.fillMaxSize().padding(30.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Security, null, Modifier.size(54.dp), tint = green)
                    Spacer(Modifier.height(16.dp))
                    Text("Administrator access unavailable", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("This management build is restricted to the authorized Galaxy S25. " +
                        "Server-enforced device enrollment is required before release.")
                }
            } else if (!entered) {
                Column(Modifier.fillMaxSize().padding(horizontal = 26.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Security, "Family Touch Admin", Modifier.size(76.dp), tint = green)
                    Spacer(Modifier.height(18.dp))
                    Text("Family Touch Admin", fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text("Manage. Protect. Stay connected.", color = Color(0xFF65746F))
                    Spacer(Modifier.height(20.dp))
                    Surface(shape = RoundedCornerShape(14.dp), color = pale) {
                        Text("S25 administrator access • Internet only • No VPN",
                            modifier = Modifier.padding(12.dp), color = green)
                    }
                    Spacer(Modifier.height(25.dp))
                    OutlinedTextField(username, { username = it.take(80) },
                        label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(password, { password = it.take(160) },
                        label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp))
                    Spacer(Modifier.height(20.dp))
                    Button(
                        enabled = username.isNotBlank() && password.isNotBlank(),
                        onClick = { authAttempt = 0; error = ""; entered = true },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(20.dp)
                    ) { Text("Sign in") }
                    Spacer(Modifier.height(14.dp))
                    Text("Credentials are sent only to the HTTPS management gateway, " +
                        "not stored in app preferences.", fontSize = 12.sp, color = Color(0xFF65746F))
                }
            } else {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth().background(Color.White).padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Security, null, tint = green)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Family Touch Admin", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                            Text("Secure HTTPS • S25 • No VPN", fontSize = 12.sp, color = green)
                        }
                        TextButton(onClick = {
                            username = ""; password = ""; entered = false; authAttempt = 0
                        }) { Text("Sign out") }
                    }
                    Box(Modifier.weight(1f)) {
                        if (tab == 3) {
                            // The current gateway provides working approvals/revokes/deletes.
                            // WebView does not enable JavaScript, file access, mixed content, or arbitrary hosts.
                            key(username) {
                                AndroidView(factory = { context ->
                                    WebView(context).apply {
                                        settings.javaScriptEnabled = false
                                        settings.domStorageEnabled = false
                                        settings.allowFileAccess = false
                                        settings.allowContentAccess = false
                                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                        webViewClient = object : WebViewClient() {
                                            override fun shouldOverrideUrlLoading(
                                                view: WebView?, request: WebResourceRequest?
                                            ): Boolean {
                                                val u = request?.url ?: return true
                                                return u.scheme != "https" || u.host != ADMIN_HOST
                                            }
                                            override fun onReceivedHttpAuthRequest(
                                                view: WebView?, handler: HttpAuthHandler?,
                                                host: String?, realm: String?
                                            ) {
                                                if (host == ADMIN_HOST && authAttempt < 2) {
                                                    authAttempt++
                                                    handler?.proceed(username, password)
                                                } else {
                                                    handler?.cancel()
                                                    error = "Sign-in rejected. Check credentials and retry."
                                                    entered = false
                                                }
                                            }
                                        }
                                        loadUrl(ADMIN_PAGE)
                                    }
                                }, modifier = Modifier.fillMaxSize())
                            }
                        } else {
                            Column(Modifier.fillMaxSize().padding(25.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center) {
                                Icon(Icons.Outlined.Security, null, tint = green,
                                    modifier = Modifier.size(52.dp))
                                Spacer(Modifier.height(16.dp))
                                Text(listOf("Chats", "Calls", "Contacts")[tab],
                                    fontSize = 23.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(12.dp))
                                Text("Secure family messaging and calling through the HTTPS relay " +
                                    "is being developed. No private Tailscale route or Android VPN slot will be used.",
                                    color = Color(0xFF65746F))
                            }
                        }
                    }
                    NavigationBar(containerColor = Color.White) {
                        val items = listOf(
                            Triple("Chats", Icons.Outlined.ChatBubbleOutline, 0),
                            Triple("Calls", Icons.Outlined.Call, 1),
                            Triple("Contacts", Icons.Outlined.Contacts, 2),
                            Triple("Admin", Icons.Outlined.Security, 3)
                        )
                        items.forEach { (label, icon, index) ->
                            NavigationBarItem(selected = tab == index,
                                onClick = { tab = index },
                                icon = { Icon(icon, label) }, label = { Text(label) })
                        }
                    }
                }
            }
        }
    }
}
