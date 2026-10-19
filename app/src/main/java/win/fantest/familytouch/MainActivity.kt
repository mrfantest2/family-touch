package win.fantest.familytouch

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
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
        GroupStore.init(this)
        Tailnet.init(this)
        PeerOutbox.init(this)
        MessageReactions.init(this)
        resumeFamilyNetwork(this)
        CallController.init(this)
        GroupCallController.init(this)
        CallRecordingEngine.init(this)
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
    var recordingsLibrary by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var networkStatus by remember { mutableStateOf(EmbeddedTailnet.status) }
    val contacts = FamilyStore.contacts.toList()
    val selectedPeer = FamilyStore.selectedPeer?.let { id -> FamilyStore.find(id) ?: GroupStore.find(id)?.let {
        Peer(it.id, it.title, "", "")
    } }
    val incoming = FamilyStore.incomingCall
    val active = FamilyStore.activeCall
    val groupCall = GroupCallController.session
    val groupIncoming = GroupCallController.incoming
    RecordingPermissionDialog()
    var confirmBack by remember { mutableStateOf(false) }
    BackHandler {
        when {
            active != null || groupCall != null -> confirmBack = true
            FamilyStore.selectedPeer != null -> FamilyStore.selectedPeer = null
            tab != 0 -> tab = 0
            else -> confirmBack = true
        }
    }
    if (confirmBack) AlertDialog(onDismissRequest = { confirmBack = false },
        title = { Text(if (active != null) "Keep this call running?" else "Leave Family Touch?") },
        text = { Text("You can keep Family Touch open or minimize it without disconnecting your private connection.") },
        confirmButton = { TextButton(onClick = {
            confirmBack = false
            (context as? android.app.Activity)?.moveTaskToBack(true)
        }) { Text("Minimize") } },
        dismissButton = { TextButton(onClick = { confirmBack = false }) { Text("Keep open") } })

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

        }
        HorizontalDivider(color = Color(0xFF20364B))
        if (recordingsLibrary) {
            RecordingLibraryScreen { recordingsLibrary = false }
        } else if (groupCall != null) {
            GroupCallScreen(groupCall)
        } else if (active != null) {
            CallPage(active, onHangup = { CallController.hangup() })
        } else if (selectedPeer != null && tab == 0) {
            Conversation(peer = selectedPeer, onCall = { video ->
                if (askCallPermissions(video)) try {
                    val group = GroupStore.find(selectedPeer.id)
                    if (group != null) GroupCallController.start(group, video) else CallController.start(selectedPeer, video)
                } catch (e: Exception) { FamilyStore.lastError = e.message ?: "Cannot start call" }
            })
        } else {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> ChatsPage(contacts, onOpen = { FamilyStore.selectedPeer = it; tab = 0 })
                    1 -> CallsPage(contacts) { peer, video ->
                        if (askCallPermissions(video)) CallController.start(peer, video)
                    }
                    2 -> ContactsPage(contacts, onOpen = { FamilyStore.selectedPeer = it; tab = 0 },
                        onConnectionSettings = { tab = 3 })
                    else -> SettingsPage(networkStatus, onRecordings = { recordingsLibrary = true })
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

    if (groupIncoming != null) AlertDialog(onDismissRequest = {}, title = { Text("Incoming group call") },
        text = { Text(GroupStore.find(groupIncoming.groupId)?.title ?: "Family group") },
        confirmButton = { Button(onClick = {
            if (askCallPermissions(groupIncoming.video)) GroupCallController.accept()
        }) { Text("Join") } }, dismissButton = {
            TextButton(onClick = { GroupCallController.hangup() }) { Text("Decline") }
        })
    if (!FamilyStore.needsNicknamePrompt && !networkStatus.connected && incoming == null && active == null && groupCall == null) {
        AlertDialog(onDismissRequest = {}, title = { Text("Turn on your private connection") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (FamilyNetworkMode.selected(context) == ConnectionMode.BUILT_IN) "Family Touch needs VPN permission for private calls and messages. Your family devices will appear automatically."
                    else "This version needs the standalone Tailscale app connected to your family network. Family Touch does not create or replace a VPN.")
                VpnSettings()
            } }, confirmButton = {})
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
            text = { Text(peerName + " is calling over your private family network.") },
            confirmButton = {
                Button(onClick = { if (askCallPermissions(incoming.isVideo)) CallController.accept() }) { Text("Answer") }
            }, dismissButton = {
                TextButton(onClick = { CallController.hangup() }) { Text("Decline") }
            })
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
    var newGroup by remember { mutableStateOf(false) }
    if (newGroup) GroupEditor(onDismiss = { newGroup = false }, onSaved = { newGroup = false; onOpen(it.id) })
    if (peers.isEmpty() && GroupStore.groups.isEmpty()) EmptyState("Welcome to Family Touch", "Your family devices appear automatically after connecting.")
    else LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item { TextButton(onClick = { newGroup = true }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Create group") } }
        items(GroupStore.groups.toList(), key = { it.id }) { group ->
            PeerRow(Peer(group.id, group.title, "", ""), "Group · ${group.members.size} devices", onClick = { onOpen(group.id) })
        }
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
            Text("Your family devices appear automatically after connecting.", Modifier.padding(18.dp), color = muted)
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
private fun ContactsPage(peers: List<Peer>, onOpen: (String) -> Unit, onConnectionSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connected = EmbeddedTailnet.status.connected
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Registered family devices", fontWeight = FontWeight.Bold)
                Text(if (connected) FamilyStore.directoryStatus else
                    "Private network disconnected - connect before discovery", color = muted, fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                if (connected) scope.launch { FamilyDirectory.refresh(context) }
                else onConnectionSettings()
            }) { Text(if (connected) "Refresh" else "Connect") }
        }
        if (peers.isEmpty()) EmptyState("Private family circle", "Registered family devices appear automatically after connecting.")
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
    Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF164674)),
        contentAlignment = Alignment.Center) {
        if (decoded != null) Image(decoded, "Profile image", Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit)
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
    val group = GroupStore.find(peer.id)
    val scope = rememberCoroutineScope()
    var draft by remember(peer.id) { mutableStateOf("") }
    var replyTo by remember(peer.id) { mutableStateOf<ChatItem?>(null) }
    var actionMessage by remember(peer.id) { mutableStateOf<ChatItem?>(null) }
    var gallery by remember(peer.id) { mutableStateOf(false) }
    var searching by remember(peer.id) { mutableStateOf(false) }
    var query by remember(peer.id) { mutableStateOf("") }
    if (gallery) MediaGallery(peer.id) { gallery = false }
    actionMessage?.let { item -> AlertDialog(onDismissRequest = { actionMessage = null }, title = { Text("Message") },
        text = { Column {
            Text((AttachmentTransport.parse(item.text)?.name ?: item.text).take(180), maxLines = 3)
            TextButton(onClick = { replyTo = item; actionMessage = null }) { Text("Reply") }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                ReactionPolicy.choices.forEach { emoji -> TextButton(onClick = {
                    try { MessageReactions.react(item, emoji); actionMessage = null }
                    catch (_: Exception) { FamilyStore.lastError = "Reaction queue is full; try again after reconnecting" }
                }) { Text(emoji) } }
            }
            TextButton(onClick = {
                try { MessageReactions.react(item, ""); actionMessage = null }
                catch (_: Exception) { FamilyStore.lastError = "Reaction queue is full; try again after reconnecting" }
            }) { Text("Remove my reaction") }
        } }, confirmButton = { TextButton(onClick = { actionMessage = null }) { Text("Close") } }) }
    val context = LocalContext.current
    var locationPreview by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(peer.id) {
        while (true) {
            if (group == null && FamilyStore.appVisible) {
                val ids = MessageDelivery.nextUnread(FamilyStore.messages, peer.id)
                if (ids.isNotEmpty()) try {
                    Tailnet.send(peer, "read_receipt", JSONObject().put("messageIds", org.json.JSONArray(ids)))
                    FamilyStore.acknowledgeIncomingRead(peer.id, ids.toSet())
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { /* Old/offline clients retain Delivered, never falsely Read. */ }
            }
            delay(10000)
        }
    }
    var locationBusy by remember { mutableStateOf(false) }
    var sendingMedia by remember { mutableStateOf(false) }
    var editGroup by remember(peer.id) { mutableStateOf(false) }
    if (editGroup && group != null) GroupEditor(group, onDismiss = { editGroup = false }, onSaved = { editGroup = false })
    var viewedMedia by remember(peer.id) { mutableStateOf<AttachmentTransport.Info?>(null) }
    viewedMedia?.let { ChatMediaViewer(it, onDismiss = { viewedMedia = null }) }
    var locationJob by remember(peer.id) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var locationDetails by remember(peer.id) { mutableStateOf("") }
    var voiceNote by remember(peer.id) { mutableStateOf(false) }
    val voicePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) voiceNote = true else FamilyStore.lastError = "Microphone permission was declined"
    }
    if (voiceNote) VoiceNoteDialog(onDismiss = { voiceNote = false }, onSend = { uri, done ->
        scope.launch {
            try {
                if (group != null) GroupStore.sendMedia(group, listOf(uri))
                else AttachmentTransport.send(context, peer, listOf(uri)) { FamilyStore.addChat(it) }
                done(true)
            }
            catch (_: Exception) { done(false); FamilyStore.lastError = "Voice note transfer failed; please try again" }
        }
    })
    DisposableEffect(peer.id) { onDispose { locationJob?.cancel() } }
    val selectMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.size > AttachmentTransport.MAX_BATCH)
            FamilyStore.lastError = "Choose at most 10 photos or videos per batch"
        else if (uris.isNotEmpty()) {
            sendingMedia = true
            scope.launch {
                try {
                    if (group != null) GroupStore.sendMedia(group, uris)
                    else AttachmentTransport.send(context, peer, uris) { item ->
                        FamilyStore.addChat(item)
                    }
                } catch (e: Exception) {
                    FamilyStore.lastError = "Attachment transfer failed: " + e.message
                } finally { sendingMedia = false }
            }
        }
    }

    val sendChat: (String) -> Boolean = { message ->
        val text = message.trim()
        if (text.isNotEmpty() && group != null) {
            try { require(text.length <= 6000) { "Reply is too long; shorten it before sending" }; GroupStore.queue(group, text); true }
            catch (e: Exception) { FamilyStore.lastError = e.message ?: "Group message could not be queued"; false }
        } else if (text.isNotEmpty()) {
            try { PeerOutbox.queue(peer, text); true }
            catch (e: Exception) { FamilyStore.lastError = e.message ?: "Message could not be queued"; false }
        } else false
    }
    val requestLocation: () -> Unit = {
        locationBusy = true
        locationJob?.cancel()
        locationJob = scope.launch {
            try {
                val loc = LocationShareController.request(context)
                locationPreview = "https://www.google.com/maps?q=" +
                    String.format(Locale.US, "%.6f,%.6f", loc.latitude, loc.longitude)
                locationDetails = "Accuracy about ${loc.accuracy.toInt()} m · " +
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(loc.time))
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                FamilyStore.lastError = "No fresh location within 30 seconds. Check Location settings and try again."
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) {
                FamilyStore.lastError = "Location unavailable. Check Android Location and permissions."
            } finally { locationBusy = false }
            }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.ACCESS_COARSE_LOCATION] == true || it[Manifest.permission.ACCESS_FINE_LOCATION] == true)
            requestLocation() else FamilyStore.lastError = "Location permission was declined."
    }

    if (locationPreview != null) {
        AlertDialog(onDismissRequest = { locationPreview = null },
            title = { Text("Share your location?") },
            text = { Text("Send this one-time map location to " + peer.name +
                "? $locationDetails. It will remain in chat history. This does not enable live tracking.") },
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
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { searching = !searching; if (!searching) query = "" }) { Text("Search") }
            TextButton(onClick = { gallery = true }) { Text("Media") }
            if (group != null) TextButton(onClick = {
                if (group.owner == FamilyStore.deviceId) editGroup = true
                else scope.launch {
                    try { GroupStore.leave(group); FamilyStore.selectedPeer = null }
                    catch (_: Exception) { FamilyStore.lastError = "Creator unavailable; try leaving again later" }
                }
            }) { Text(if (group.owner == FamilyStore.deviceId) "Members" else "Leave group") }
            IconButton(onClick = { onCall(false) }) { Icon(Icons.Outlined.Call, "Voice call", tint = Color(0xFF54DDA6)) }
            IconButton(onClick = { onCall(true) }) { Icon(Icons.Outlined.Videocam, "Video call", tint = accent) }
        }
        if (searching) OutlinedTextField(query, { query = it.take(200) }, Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            placeholder = { Text("Search this conversation") }, singleLine = true)
        val history = FamilyStore.messages.filter { it.peerId == peer.id && (query.isBlank() || it.text.contains(query, ignoreCase = true)) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(history, key = { it.id }) { msg ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.outgoing) Arrangement.End else Arrangement.Start) {
                    Column(Modifier.widthIn(max = 300.dp).pointerInput(msg.id) { detectTapGestures(onLongPress = { actionMessage = msg }) }
                        .background(if (msg.outgoing) Color(0xFF1268AE) else surface,
                        RoundedCornerShape(16.dp)).padding(horizontal = 15.dp, vertical = 11.dp)) {
                        val attachment = AttachmentTransport.parse(msg.text)
                        if (group != null && !msg.outgoing) Text(FamilyStore.find(msg.senderId)?.name ?: "Family member", fontSize = 11.sp)
                        if (attachment != null) {
                            Text((if (attachment.mime.startsWith("image/")) "Photo" else if (attachment.mime.startsWith("audio/")) "Voice / audio" else "Video") +
                                " · " + attachment.name, color = Color.White, maxLines = 2)
                            Text(((attachment.size + 1023) / 1024).toString() + " KB",
                                fontSize = 11.sp, color = Color(0xFFBFDBEF))
                            Text("Tap to open attachment", color = Color(0xFFACE1FF),
                                modifier = Modifier.clickable {
                                    viewedMedia = attachment
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
                        val reactions = MessageReactions.summary(msg.id)
                        if (reactions.isNotBlank()) Text(reactions, color = Color.White)
                        Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(msg.time)),
                            color = Color(0xFFBFDBEF), fontSize = 10.sp)
                        if (msg.outgoing) Text(if (group != null) (if (msg.delivered) "Delivered" else GroupStore.delivery(msg.id) ?: "Queued")
                            else MessageDelivery.label(msg.sent, msg.delivered, msg.read),
                            color = Color(0xFFBFDBEF), fontSize = 10.sp)
                    }
                }
            }
        }
        replyTo?.let { item -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Replying to: " + (AttachmentTransport.parse(item.text)?.name ?: item.text).take(140), Modifier.weight(1f), maxLines = 2)
            TextButton(onClick = { replyTo = null }) { Text("Cancel") }
        } }
        Row(Modifier.fillMaxWidth().background(Color(0xFF0D2135)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                    voiceNote = true else voicePermission.launch(Manifest.permission.RECORD_AUDIO)
            }, enabled = !sendingMedia) { Icon(Icons.Outlined.Mic, "Record voice note", tint = accent) }
            IconButton(onClick = { selectMedia.launch(arrayOf("image/*", "video/*")) },
                enabled = !sendingMedia) {
                Icon(Icons.Outlined.AttachFile, "Attach up to 10 photos or videos", tint = accent)
            }
            IconButton(onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED) requestLocation()
                else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
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
                val quoted = replyTo?.let { (AttachmentTransport.parse(it.text)?.name ?: it.text).replace('\n', ' ').take(180) }
                val text = if (quoted != null) "↪ $quoted\n\n${draft.trim()}" else draft.trim()
                if (sendChat(text)) { draft = ""; replyTo = null }
            }) { Icon(Icons.Outlined.Send, "Send") }
        }
    }
}

@Composable
private fun SettingsPage(networkStatus: VpnConnectionStatus, onRecordings: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(FamilyStore.nickname) }
    val scope = rememberCoroutineScope()
    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) try {
            val source = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                // ImageDecoder applies EXIF orientation; bound memory without stretching either axis.
                val scale = minOf(1.0, 128.0 / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()),
                    maxOf(1, (info.size.height * scale).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            if (source == null) FamilyStore.lastError = "Unable to open picture"
            else {
                val thumbnail = source
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
        OutlinedButton(onClick = onRecordings) { Text("Local call recordings") }
        HorizontalDivider(color = Color(0xFF24415C))
        Text("Family devices appear automatically after connecting. Messages stay on your devices.", color = muted, fontSize = 13.sp)
    }
}

@Composable
private fun CallControls(onHangup: () -> Unit, modifier: Modifier = Modifier) {
    val speaker = CallController.speakerEnabled
    val muted = CallController.microphoneMuted
    Column(modifier) {
    RecordingControls(FamilyStore.activeCall?.isVideo == true)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
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
            Text("Private voice call", color = muted, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Text(timer, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(55.dp))
            CallControls(onHangup)
        }
    }
}
