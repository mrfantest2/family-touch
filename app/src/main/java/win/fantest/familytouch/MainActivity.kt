package win.fantest.familytouch

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
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
import java.util.Locale
import java.io.ByteArrayOutputStream

private val bg = Color(0xFF071424)
private val surface = Color(0xFF0E2339)
private val accent = Color(0xFF299AF4)
private val ink = Color(0xFFE8F3FE)
private val muted = Color(0xFF9DAFBE)

class MainActivity : ComponentActivity() {
    override fun onStart() { super.onStart(); FamilyStore.appVisible = true }
    override fun onStop() { FamilyStore.appVisible = false; super.onStop() }

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            askPermissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FamilyStore.init(this)
        Tailnet.init(this)
        if (getSharedPreferences("vpn", Context.MODE_PRIVATE).getBoolean("enabled", false) && android.net.VpnService.prepare(this) == null) {
            ContextCompat.startForegroundService(this, Intent(this, FamilyVpnService::class.java))
        }
        CallController.init(this)
        try { ContextCompat.startForegroundService(this, Intent(this, PeerService::class.java)) }
        catch (ex: Exception) { FamilyStore.lastError = "Service: " + ex.message }
        if (!FamilyStore.needsNicknamePrompt) requestNotificationPermission()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = accent, onPrimary = Color.White, background = bg, surface = surface, onBackground = ink, onSurface = ink
            )) { Surface(modifier = Modifier.fillMaxSize(), color = bg, contentColor = ink) { FamilyTouchUI(askCallPermissions = { video ->
                val permissions = if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                    else arrayOf(Manifest.permission.RECORD_AUDIO)
                val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isNotEmpty()) {
                    askPermissions.launch(missing.toTypedArray())
                    false
                } else true
            }, onNicknameFinished = { requestNotificationPermission() }) } }
        }
    }
}

@Composable
private fun FamilyTouchUI(askCallPermissions: (Boolean) -> Boolean, onNicknameFinished: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var addPeerDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var networkStatus by remember { mutableStateOf(EmbeddedTailnet.status) }
    val contacts = FamilyStore.contacts.toList()
    val selectedPeer = FamilyStore.selectedPeer?.let { FamilyStore.find(it) }
    val incoming = FamilyStore.incomingCall
    val active = FamilyStore.activeCall

    LaunchedEffect(Unit) { while (true) {
        networkStatus = EmbeddedTailnet.status
        delay(500)
    } }
    Column(Modifier.fillMaxSize().background(bg).statusBarsPadding().navigationBarsPadding()) {
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
                Text(networkStatus.label,
                    fontSize = 12.sp, color = if (networkStatus.connected) Color(0xFF5DDAA7) else Color(0xFFFFC466))
            }
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
                    else -> SettingsPage(networkStatus)
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

    if (FamilyStore.needsNicknamePrompt && incoming == null && active == null) {
        var nickname by rememberSaveable { mutableStateOf(FamilyStore.nickname.take(36)) }
        fun finish(value: String?) {
            if (FamilyStore.finishNicknameSetup(value)) onNicknameFinished()
        }
        AlertDialog(onDismissRequest = { finish(null) },
            title = { Text("Welcome to Family Touch") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Would you like to change your nickname? Your family will see this name.")
                    OutlinedTextField(value = nickname, onValueChange = { nickname = it.take(36) },
                        label = { Text("Nickname") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (nickname.isNotBlank()) finish(nickname) }))
                }
            },
            confirmButton = { TextButton(enabled = nickname.isNotBlank(), onClick = { finish(nickname) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { finish(null) }) { Text("Skip") } })
    }
    if (incoming != null && active == null) {
        val peerName = FamilyStore.find(incoming.peerId)?.name ?: "Family device"
        AlertDialog(onDismissRequest = {}, title = { Text("Incoming " + (if (incoming.isVideo) "video" else "voice") + " call") },
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
    val log = FamilyStore.callHistory.toList()
    LazyColumn(contentPadding = PaddingValues(bottom = 14.dp)) {
        item {
            Text("Start a call", Modifier.padding(start = 18.dp, top = 16.dp, bottom = 8.dp),
                fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        if (peers.isEmpty()) item {
            Text("Pair a family device to call privately.", Modifier.padding(18.dp), color = muted)
        }
        items(peers, key = { "contact-" + it.id }) { peer ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Avatar(peer.name, FamilyStore.peerPhotos[peer.id])
                Spacer(Modifier.width(14.dp))
                Text(peer.name, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                IconButton(onClick = { onCall(peer, false) }) {
                    Icon(Icons.Outlined.Call, "Voice call", tint = Color(0xFF54DDA6))
                }
                IconButton(onClick = { onCall(peer, true) }) {
                    Icon(Icons.Outlined.Videocam, "Video call", tint = accent)
                }
            }
        }
        item {
            HorizontalDivider(color = Color(0xFF24415C))
            Text("Call history", Modifier.padding(start = 18.dp, top = 14.dp, bottom = 10.dp),
                fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        if (log.isEmpty()) item {
            Text("No calls recorded yet. Call time, direction and duration will appear here.",
                Modifier.padding(18.dp), color = muted)
        }
        items(log, key = { "call-" + it.id }) { entry ->
            val peer = FamilyStore.find(entry.peerId)
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(if (entry.isVideo) Icons.Outlined.Videocam else Icons.Outlined.Call,
                    null, tint = if (entry.outcome == "missed") Color(0xFFFF9090) else accent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(peer?.name ?: "Family device", fontWeight = FontWeight.Medium)
                    Text((if (entry.outgoing) "Outgoing" else "Incoming") +
                        " · " + entry.outcome.replaceFirstChar { it.uppercase() } +
                        " · " + durationText(entry.durationMs),
                        color = muted, fontSize = 12.sp)
                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(entry.startedAt)), color = muted, fontSize = 11.sp)
                }
                if (peer != null) IconButton(onClick = { onCall(peer, entry.isVideo) }) {
                    Icon(Icons.Outlined.Call, "Call again")
                }
            }
            HorizontalDivider(color = Color(0xFF152B40), thickness = 0.5.dp)
        }
    }
}

private fun durationText(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1000L
    return "%02d:%02d:%02d".format(seconds / 3600L, (seconds / 60L) % 60L, seconds % 60L)
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
        Avatar(peer.name, FamilyStore.peerPhotos[peer.id]); Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(peer.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(FamilyStore.presenceLabel(peer.id) + " · " + subtitle, color = muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
        }
    }
    HorizontalDivider(color = Color(0xFF152B40), thickness = 0.5.dp)
}

@Composable
private fun Avatar(name: String, photo: String? = null) {
    val decoded = remember(photo) {
        try {
            if (photo.isNullOrEmpty()) null else {
                val bytes = Base64.decode(photo, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        } catch (_: Exception) { null }
    }
    Box(Modifier.size(48.dp).background(Color(0xFF164674), CircleShape),
        contentAlignment = Alignment.Center) {
        if (decoded != null) Image(decoded, "Profile image", Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop)
        else Text(name.take(1).uppercase(), fontWeight = FontWeight.Bold,
            fontSize = 21.sp, color = Color.White)
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
    val context = LocalContext.current
    var locationPreview by remember { mutableStateOf<String?>(null) }
    var locationBusy by remember { mutableStateOf(false) }
    var sendingMedia by remember { mutableStateOf(false) }
    val selectMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.size > AttachmentTransport.MAX_BATCH)
            FamilyStore.lastError = "Choose at most 10 photos or videos per batch"
        else if (uris.isNotEmpty()) {
            sendingMedia = true
            scope.launch {
                try {
                    AttachmentTransport.send(context, peer, uris) { item ->
                        FamilyStore.addChat(item)
                    }
                } catch (e: Exception) {
                    FamilyStore.lastError = "Attachment transfer failed: " + e.message
                } finally { sendingMedia = false }
            }
        }
    }

    val sendChat: (String) -> Unit = { message ->
        val text = message.trim()
        if (text.isNotEmpty()) {
            val id = UUID.randomUUID().toString()
            FamilyStore.addChat(ChatItem(id, peer.id, true, text, System.currentTimeMillis(), false))
            scope.launch {
                try {
                    Tailnet.send(peer, "message", JSONObject().put("text", text).put("messageId", id))
                    FamilyStore.delivered(id)
                } catch (e: Exception) {
                    FamilyStore.lastError = "Message not delivered: " + e.message
                }
            }
        }
    }
    val requestLocation: () -> Unit = {
        locationBusy = true
        try {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val provider = if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                LocationManager.NETWORK_PROVIDER else LocationManager.GPS_PROVIDER
            if (Build.VERSION.SDK_INT >= 30) {
                manager.getCurrentLocation(provider, null, context.mainExecutor) { loc ->
                    locationBusy = false
                    if (loc == null) FamilyStore.lastError = "Could not obtain a location. Enable Location in Android settings."
                    else locationPreview = "https://www.google.com/maps?q=" +
                        String.format(Locale.US, "%.6f,%.6f", loc.latitude, loc.longitude)
                }
            } else {
                val last = manager.getLastKnownLocation(provider)
                locationBusy = false
                if (last != null && System.currentTimeMillis() - last.time < 120000L)
                    locationPreview = "https://www.google.com/maps?q=" +
                        String.format(Locale.US, "%.6f,%.6f", last.latitude, last.longitude)
                else FamilyStore.lastError = "No recent location available; enable device Location."
            }
        } catch (e: Exception) {
            locationBusy = false
            FamilyStore.lastError = "Location unavailable: " + e.message
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) requestLocation() else FamilyStore.lastError = "Location permission was declined."
    }

    if (locationPreview != null) {
        AlertDialog(onDismissRequest = { locationPreview = null },
            title = { Text("Share your location?") },
            text = { Text("Send this one-time map location to " + peer.name +
                "? It will remain in the chat history. This does not enable live tracking.") },
            confirmButton = {
                Button(onClick = {
                    locationPreview?.let { url -> sendChat("Location: " + url) }
                    locationPreview = null
                }) { Text("Send location") }
            }, dismissButton = {
                TextButton(onClick = { locationPreview = null }) { Text("Cancel") }
            })
    }
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
                        val attachment = AttachmentTransport.parse(msg.text)
                        if (attachment != null) {
                            Text((if (attachment.mime.startsWith("image/")) "Photo" else "Video") +
                                " · " + attachment.name, color = Color.White, maxLines = 2)
                            Text(((attachment.size + 1023) / 1024).toString() + " KB",
                                fontSize = 11.sp, color = Color(0xFFBFDBEF))
                            Text("Tap to open attachment", color = Color(0xFFACE1FF),
                                modifier = Modifier.clickable {
                                    try { AttachmentTransport.open(context, attachment) }
                                    catch (e: Exception) { FamilyStore.lastError =
                                        "Cannot open attachment: " + e.message }
                                })
                        } else if (msg.text.startsWith("Location: https://www.google.com/maps?q=")) {
                            Text("Shared location", color = Color.White)
                            Text("Open in Maps", color = Color(0xFFACE1FF),
                                modifier = Modifier.clickable {
                                    try {
                                        context.startActivity(Intent(Intent.ACTION_VIEW,
                                            Uri.parse(msg.text.removePrefix("Location: "))))
                                    } catch (_: Exception) {
                                        FamilyStore.lastError = "No maps app or browser available"
                                    }
                                })
                        } else Text(msg.text, color = Color.White)
                        Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(msg.time)),
                            color = Color(0xFFBFDBEF), fontSize = 10.sp)
                        if (msg.outgoing) Text(if (msg.delivered) "Delivered" else "Not delivered",
                            color = Color(0xFFBFDBEF), fontSize = 10.sp)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(Color(0xFF0D2135)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { selectMedia.launch(arrayOf("image/*", "video/*")) },
                enabled = !sendingMedia) {
                Icon(Icons.Outlined.AttachFile, "Attach up to 10 photos or videos", tint = accent)
            }
            IconButton(onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED) requestLocation()
                else locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }, enabled = !locationBusy) {
                Icon(Icons.Outlined.LocationOn, "Share one-time location", tint = accent)
            }
            OutlinedTextField(value = draft, onValueChange = { draft = it.take(6000) },
                modifier = Modifier.weight(1f), placeholder = { Text("Message") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { }),
                shape = RoundedCornerShape(24.dp), maxLines = 5)
            Spacer(Modifier.width(6.dp))
            FilledIconButton(enabled = draft.isNotBlank(), onClick = {
                val text = draft.trim()
                draft = ""
                sendChat(text)
            }) { Icon(Icons.Outlined.Send, "Send") }
        }
    }
}

@Composable
private fun SettingsPage(networkStatus: VpnConnectionStatus) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(FamilyStore.nickname) }
    val scope = rememberCoroutineScope()
    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) try {
            val source = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = 4 })
            }
            if (source == null) FamilyStore.lastError = "Unable to open picture"
            else {
                val thumbnail = Bitmap.createScaledBitmap(source, 128, 128, true)
                val out = ByteArrayOutputStream()
                var quality = 85
                do {
                    out.reset()
                    thumbnail.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    quality -= 10
                } while (out.size() > 16000 && quality >= 20)
                source.recycle()
                if (thumbnail !== source) thumbnail.recycle()
                if (out.size() > 16000) FamilyStore.lastError = "Picture is too complex to share"
                else {
                    val encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    FamilyStore.updateProfilePhoto(encoded)
                    scope.launch {
                        FamilyStore.contacts.forEach { peer ->
                            try { Tailnet.send(peer, "profile_update", JSONObject().put("avatar", encoded)) }
                            catch (_: Exception) { }
                        }
                    }
                }
            }
        } catch (e: Exception) { FamilyStore.lastError = "Picture selection failed: " + e.message }
    }
    val code = if (networkStatus.connected) Tailnet.invitation(context) else null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
        Text("Your device", fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(FamilyStore.nickname, FamilyStore.profilePhoto)
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { pickAvatar.launch("image/*") }) {
                Text("Change profile picture")
            }
        }
        OutlinedTextField(value = name, onValueChange = { name = it.take(36) },
            label = { Text("Nickname") }, modifier = Modifier.fillMaxWidth(),
            trailingIcon = { IconButton(onClick = { FamilyStore.rename(name) }) {
                Icon(Icons.Outlined.CheckCircle, "Save nickname", tint = accent)
            } })
        Text("Your identity is created automatically when this app is first installed. No account or password required.",
            color = muted, fontSize = 12.sp)
        HorizontalDivider(color = Color(0xFF24415C))
        VpnSettings()
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
private fun CallControls(onHangup: () -> Unit, modifier: Modifier = Modifier) {
    val speaker = CallController.speakerEnabled
    val muted = CallController.microphoneMuted
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { CallController.selectSpeakerRoute(!speaker) }, modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(if (speaker) "Speaker on" else "Earpiece", fontSize = 11.sp, maxLines = 1)
        }
        OutlinedButton(onClick = { CallController.toggleMicrophoneMute(!muted) }, modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(if (muted) "Unmute" else "Mute mic", fontSize = 11.sp, maxLines = 1)
        }
        Button(onClick = onHangup, modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE95058), contentColor = Color.White)) {
            Text("End call", fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
private fun CallPage(call: CallInvite, onHangup: () -> Unit) {
    val name = FamilyStore.find(call.peerId)?.name ?: "Family device"
    var clock by remember(call.peerId) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.peerId) {
        while (true) { delay(1000); clock = System.currentTimeMillis() }
    }
    val connectedAt = CallController.connectedSinceMs
    val timer = if (connectedAt == null) "Connecting..." else durationText(clock - connectedAt)
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
            Column(Modifier.align(Alignment.TopStart).padding(20.dp)) {
                Text(name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(timer, fontSize = 15.sp, color = Color.White)
            }
            CallControls(onHangup, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
        }
    } else {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Avatar(name)
            Spacer(Modifier.height(20.dp))
            Text(name, fontWeight = FontWeight.Bold, fontSize = 27.sp)
            Text("Private Tailscale voice call", color = muted, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Text(timer, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(55.dp))
            CallControls(onHangup)
        }
    }
}
