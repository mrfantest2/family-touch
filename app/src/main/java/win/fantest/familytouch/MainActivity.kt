package win.fantest.familytouch

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.SurfaceViewRenderer
import java.util.UUID

private val bg = Color(0xFF071424)
private val surface = Color(0xFF0E2339)
private val accent = Color(0xFF299AF4)
private val ink = Color(0xFFE8F3FE)
private val muted = Color(0xFF9DAFBE)

class MainActivity : ComponentActivity() {
    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FamilyStore.init(this)
        CallController.init(this)
        try { ContextCompat.startForegroundService(this, Intent(this, PeerService::class.java)) }
        catch (ex: Exception) { FamilyStore.lastError = "Service: " + ex.message }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            askPermissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = accent, background = bg, surface = surface, onBackground = ink, onSurface = ink
            )) { FamilyTouchUI(askCallPermissions = { video ->
                val permissions = if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                    else arrayOf(Manifest.permission.RECORD_AUDIO)
                val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isNotEmpty()) {
                    askPermissions.launch(missing.toTypedArray())
                    false
                } else true
            }) }
        }
    }
}

@Composable
private fun FamilyTouchUI(askCallPermissions: (Boolean) -> Boolean) {
    var tab by remember { mutableIntStateOf(0) }
    var addPeerDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ip = remember { mutableStateOf(Tailnet.vpnIp(context)) }
    val contacts = FamilyStore.contacts.toList()
    val selectedPeer = FamilyStore.selectedPeer?.let { FamilyStore.find(it) }
    val incoming = FamilyStore.incomingCall
    val active = FamilyStore.activeCall

    LaunchedEffect(Unit) { ip.value = Tailnet.vpnIp(context) }
    Column(Modifier.fillMaxSize().background(bg)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (selectedPeer != null && active == null) IconButton(onClick = { FamilyStore.selectedPeer = null }) {
                Icon(Icons.Outlined.ArrowBack, "Back")
            } else {
                SurfaceLogo()
                Spacer(Modifier.width(11.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(selectedPeer?.name ?: "Family Touch", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(if (ip.value != null) "Private Tailscale network" else "Connect Tailscale to start",
                    fontSize = 12.sp, color = if (ip.value != null) Color(0xFF5DDAA7) else Color(0xFFFFC466))
            }
            IconButton(onClick = { ip.value = Tailnet.vpnIp(context) }) { Icon(Icons.Outlined.Wifi, "Refresh Tailscale") }
            if (tab == 2 && selectedPeer == null) IconButton(onClick = { addPeerDialog = true }) {
                Icon(Icons.Outlined.Add, "Pair new contact")
            }
        }
        HorizontalDivider(color = Color(0xFF20364B))
        if (active != null) {
            CallPage(active, onHangup = { CallController.hangup() })
        } else if (selectedPeer != null && tab == 0) {
            Conversation(peer = selectedPeer, onCall = { video ->
                if (askCallPermissions(video)) CallController.start(selectedPeer, video)
            })
        } else {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> ChatsPage(contacts, onOpen = { FamilyStore.selectedPeer = it; tab = 0 })
                    1 -> CallsPage(contacts) { peer, video ->
                        if (askCallPermissions(video)) CallController.start(peer, video)
                    }
                    2 -> ContactsPage(contacts, onOpen = { FamilyStore.selectedPeer = it; tab = 0 }, onPair = { addPeerDialog = true })
                    else -> SettingsPage(ip.value, onRefresh = { ip.value = Tailnet.vpnIp(context) })
                }
            }
            NavigationBar(containerColor = Color(0xFF0B1B2D)) {
                val navItems = listOf(Triple("Chats", Icons.Outlined.ChatBubbleOutline, 0),
                    Triple("Calls", Icons.Outlined.Call, 1),
                    Triple("Contacts", Icons.Outlined.Contacts, 2),
                    Triple("Settings", Icons.Outlined.Settings, 3))
                navItems.forEach { (label, icon, idx) ->
                    NavigationBarItem(selected = tab == idx, onClick = { tab = idx; FamilyStore.selectedPeer = null },
                        icon = { Icon(icon, label) }, label = { Text(label, fontSize = 11.sp) })
                }
            }
        }
    }

    if (incoming != null && active == null) {
        val peerName = FamilyStore.find(incoming.peerId)?.name ?: "Family device"
        AlertDialog(onDismissRequest = {}, title = { Text("Incoming " + if (incoming.isVideo) "video" else "voice" + " call") },
            text = { Text(peerName + " is calling over your private Tailscale network.") },
            confirmButton = {
                Button(onClick = { if (askCallPermissions(incoming.isVideo)) CallController.accept() }) { Text("Answer") }
            }, dismissButton = {
                TextButton(onClick = { CallController.hangup() }) { Text("Decline") }
            })
    }
    if (addPeerDialog) {
        var code by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { if (!busy) addPeerDialog = false }, title = { Text("Pair family device") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("On the other device: Settings → Copy invitation. Both devices must be logged in to Tailscale.",
                        fontSize = 13.sp, color = muted)
                    OutlinedTextField(value = code, onValueChange = { code = it.trim() },
                        label = { Text("FT1 invitation code") }, minLines = 2, maxLines = 5)
                    if (error.isNotEmpty()) Text(error, color = Color(0xFFFF9898), fontSize = 13.sp)
                }
            }, confirmButton = {
                Button(enabled = !busy && code.isNotBlank(), onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val peer = Tailnet.parseInvitation(code)
                            Tailnet.pair(peer)
                            addPeerDialog = false
                        } catch (ex: Exception) { error = ex.message.orEmpty() }
                        finally { busy = false }
                    }
                }) { Text(if (busy) "Pairing..." else "Pair") }
            }, dismissButton = { TextButton(onClick = { addPeerDialog = false }) { Text("Cancel") } })
    }
    if (FamilyStore.lastError.isNotBlank()) {
        AlertDialog(onDismissRequest = { FamilyStore.lastError = "" },
            title = { Text("Family Touch") }, text = { Text(FamilyStore.lastError) },
            confirmButton = { TextButton(onClick = { FamilyStore.lastError = "" }) { Text("OK") } })
    }
}

@Composable
private fun SurfaceLogo() {
    Box(Modifier.size(40.dp).background(
        brush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(accent, Color(0xFF7A3AD0))),
        shape = RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Person, "Family Touch", tint = Color.White)
    }
}

@Composable
private fun ChatsPage(peers: List<Peer>, onOpen: (String) -> Unit) {
    if (peers.isEmpty()) EmptyState("Welcome to Family Touch", "Pair another device from Contacts to start messaging your family.")
    else LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        items(peers, key = { it.id }) { peer ->
            val message = FamilyStore.messages.lastOrNull { it.peerId == peer.id }
            PeerRow(peer, message?.text ?: "Start a conversation", onClick = { onOpen(peer.id) })
        }
    }
}

@Composable
private fun CallsPage(peers: List<Peer>, onCall: (Peer, Boolean) -> Unit) {
    if (peers.isEmpty()) EmptyState("No contacts yet", "Pair your family's devices to make private calls.")
    else LazyColumn {
        items(peers, key = { it.id }) { peer ->
            Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(peer.name)
                Spacer(Modifier.width(14.dp))
                Text(peer.name, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                IconButton(onClick = { onCall(peer, false) }) { Icon(Icons.Outlined.Call, "Voice call", tint = Color(0xFF54DDA6)) }
                IconButton(onClick = { onCall(peer, true) }) { Icon(Icons.Outlined.Videocam, "Video call", tint = accent) }
            }
        }
    }
}

@Composable
private fun ContactsPage(peers: List<Peer>, onOpen: (String) -> Unit, onPair: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Button(onClick = onPair, modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Add a family device")
        }
        if (peers.isEmpty()) EmptyState("Private family circle", "Only invited devices from your Tailscale network appear here.")
        else LazyColumn {
            items(peers, key = { it.id }) { peer ->
                PeerRow(peer, peer.ip, onClick = { onOpen(peer.id) })
            }
        }
    }
}

@Composable
private fun PeerRow(peer: Peer, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 19.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Avatar(peer.name); Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(peer.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(subtitle, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
        }
    }
    HorizontalDivider(color = Color(0xFF152B40), thickness = 0.5.dp)
}

@Composable
private fun Avatar(name: String) {
    Box(Modifier.size(48.dp).background(Color(0xFF164674), CircleShape), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), fontWeight = FontWeight.Bold, fontSize = 21.sp, color = Color.White)
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(Modifier.fillMaxSize().padding(35.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.ChatBubbleOutline, null, modifier = Modifier.size(58.dp), tint = accent)
        Spacer(Modifier.height(18.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = muted, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun Conversation(peer: Peer, onCall: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var draft by remember(peer.id) { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { onCall(false) }) { Icon(Icons.Outlined.Call, "Voice call", tint = Color(0xFF54DDA6)) }
            IconButton(onClick = { onCall(true) }) { Icon(Icons.Outlined.Videocam, "Video call", tint = accent) }
        }
        val history = FamilyStore.messages.filter { it.peerId == peer.id }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(history, key = { it.id }) { msg ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.outgoing) Arrangement.End else Arrangement.Start) {
                    Column(Modifier.widthIn(max = 300.dp).background(if (msg.outgoing) Color(0xFF1268AE) else surface,
                        RoundedCornerShape(16.dp)).padding(horizontal = 15.dp, vertical = 11.dp)) {
                        Text(msg.text, color = Color.White)
                        if (msg.outgoing) Text(if (msg.delivered) "Delivered" else "Not delivered",
                            color = Color(0xFFBFDBEF), fontSize = 10.sp)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(Color(0xFF0D2135)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = draft, onValueChange = { draft = it.take(6000) },
                modifier = Modifier.weight(1f), placeholder = { Text("Message") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { }),
                shape = RoundedCornerShape(24.dp), maxLines = 5)
            Spacer(Modifier.width(6.dp))
            FilledIconButton(enabled = draft.isNotBlank(), onClick = {
                val text = draft.trim()
                draft = ""
                val id = UUID.randomUUID().toString()
                FamilyStore.addChat(ChatItem(id, peer.id, true, text, System.currentTimeMillis(), false))
                scope.launch {
                    try {
                        Tailnet.send(peer, "message", JSONObject().put("text", text).put("messageId", id))
                        FamilyStore.delivered(id)
                    } catch (e: Exception) { FamilyStore.lastError = "Message not delivered: " + e.message }
                }
            }) { Icon(Icons.Outlined.Send, "Send") }
        }
    }
}

@Composable
private fun SettingsPage(ip: String?, onRefresh: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(FamilyStore.nickname) }
    val code = Tailnet.invitation(context)
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
        Text("Your device", fontSize = 21.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = name, onValueChange = { name = it.take(36) },
            label = { Text("Nickname") }, modifier = Modifier.fillMaxWidth(),
            trailingIcon = { IconButton(onClick = { FamilyStore.rename(name) }) {
                Icon(Icons.Outlined.CheckCircle, "Save nickname", tint = accent)
            } })
        Text("Your identity is created automatically when this app is first installed. No account or password required.",
            color = muted, fontSize = 12.sp)
        HorizontalDivider(color = Color(0xFF24415C))
        Text("Tailscale", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(if (ip != null) "Connected privately • " + ip else "Not connected — open the Tailscale app and sign in.",
            color = if (ip != null) Color(0xFF5DDAA7) else Color(0xFFFFBD76))
        OutlinedButton(onClick = onRefresh) { Icon(Icons.Outlined.Wifi, null); Spacer(Modifier.width(6.dp)); Text("Refresh connection") }
        HorizontalDivider(color = Color(0xFF24415C))
        Text("Invite a family device", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("Share this code privately with a trusted person who is already on your Tailscale network. Never post it publicly.",
            color = muted, fontSize = 13.sp)
        if (code != null) {
            OutlinedButton(onClick = {
                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("Family Touch invitation", code))
            }) {
                Icon(Icons.Outlined.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text("Copy invitation code")
            }
        } else {
            Text("Connect Tailscale to generate an invitation.", color = Color(0xFFFFC466), fontSize = 13.sp)
        }
        Text("Port " + Tailnet.PORT + " • No hosted application server • Messages stored on devices",
            color = muted, fontSize = 12.sp)
    }
}

@Composable
private fun CallPage(call: CallInvite, onHangup: () -> Unit) {
    val name = FamilyStore.find(call.peerId)?.name ?: "Family device"
    if (call.isVideo) {
        val context = LocalContext.current
        val local = remember { SurfaceViewRenderer(context) }
        val remote = remember { SurfaceViewRenderer(context) }
        DisposableEffect(local, remote) {
            local.init(CallController.eglContext, null)
            remote.init(CallController.eglContext, null)
            local.setMirror(true)
            remote.setMirror(false)
            CallController.setRenderers(local, remote)
            onDispose {
                CallController.setRenderers(null, null)
                local.release()
                remote.release()
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { remote }, modifier = Modifier.fillMaxSize())
            AndroidView(factory = { local }, modifier = Modifier.align(Alignment.TopEnd).padding(18.dp)
                .width(110.dp).height(155.dp))
            Text(name, Modifier.align(Alignment.TopStart).padding(20.dp), fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold)
            Button(onClick = onHangup, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE95058)),
                modifier = Modifier.align(Alignment.BottomCenter).padding(30.dp)) {
                Icon(Icons.Outlined.Call, null); Spacer(Modifier.width(9.dp)); Text("End call")
            }
        }
    } else {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Avatar(name)
            Spacer(Modifier.height(20.dp))
            Text(name, fontWeight = FontWeight.Bold, fontSize = 27.sp)
            Text("Encrypted Tailscale connection", color = muted, fontSize = 13.sp)
            Spacer(Modifier.height(55.dp))
            Button(onClick = onHangup, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE95058))) {
                Icon(Icons.Outlined.Call, null); Spacer(Modifier.width(8.dp)); Text("End call")
            }
        }
    }
}
