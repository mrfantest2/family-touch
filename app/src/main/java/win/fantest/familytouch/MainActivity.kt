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
import androidx.compose.ui.res.painterResource
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
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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

private val bg = Color(0xFF0B141A)
private val surface = Color(0xFF202C33)
private val accent = Color(0xFF00A884)
private val ink = Color(0xFFE9EDEF)
private val muted = Color(0xFF8696A0)

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("open_peer_id")?.let { id ->
            if (FamilyStore.find(id) != null || GroupStore.find(id) != null) FamilyStore.selectedPeer = id
        }
        handleCallNotificationAction(intent)
    }
    override fun onStart() { super.onStart(); FamilyStore.appVisible = true }
    override fun onStop() { FamilyStore.appVisible = false; FamilyPin.lock(); super.onStop() }

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    private var pendingCallAction: (() -> Unit)? = null
    private val requestCallGrants = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val action = pendingCallAction
        pendingCallAction = null
        if (permissions.values.all { it }) action?.invoke()
        else FamilyStore.lastError = "Allow microphone / camera permission to answer or start the call"
    }
    private fun withCallPermissions(video: Boolean, whenGranted: () -> Unit) {
        val permissions = if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        else arrayOf(Manifest.permission.RECORD_AUDIO)
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) whenGranted()
        else {
            pendingCallAction = whenGranted
            requestCallGrants.launch(missing.toTypedArray())
        }
    }
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            askPermissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }
    private fun handleCallNotificationAction(incomingIntent: Intent) {
        val action = incomingIntent.action
        if (action != NotificationIds.ACTION_ANSWER_CALL && action != NotificationIds.ACTION_DECLINE_CALL) return
        incomingIntent.action = null
        val session = incomingIntent.getStringExtra(NotificationIds.EXTRA_CALL_SESSION) ?: return
        incomingIntent.removeExtra(NotificationIds.EXTRA_CALL_SESSION)
        val direct = FamilyStore.incomingCall
        val group = GroupCallController.incoming
        if (direct?.sessionId == session) {
            if (action == NotificationIds.ACTION_DECLINE_CALL) CallController.hangup()
            else withCallPermissions(direct.isVideo) {
                if (FamilyStore.incomingCall?.sessionId == session) CallController.accept()
            }
        } else if (group?.sessionId == session) {
            if (action == NotificationIds.ACTION_DECLINE_CALL) GroupCallController.hangup()
            else withCallPermissions(group.video) {
                if (GroupCallController.incoming?.sessionId == session) GroupCallController.accept()
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.ADMIN_APP) {
            // Native Admin uses a white top bar; ensure Android system icons remain visible.
            window.statusBarColor = android.graphics.Color.WHITE
            window.navigationBarColor = android.graphics.Color.WHITE
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
                .isAppearanceLightStatusBars = true
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
                .isAppearanceLightNavigationBars = true
            setContent { Box { FamilyTouchAdminUI(); FamilyUpdatePrompt() } }
            return
        }
        // Standard app: light/white header uses dark Android system icons.
        // Admin's separate branch above retains its existing system-bar behavior.
        window.statusBarColor = android.graphics.Color.rgb(32,44,51)
        window.navigationBarColor = android.graphics.Color.rgb(11,20,26)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        FamilyStore.init(this)
        GroupStore.init(this)
        intent.getStringExtra("open_peer_id")?.let { id ->
            if (FamilyStore.find(id) != null || GroupStore.find(id) != null) FamilyStore.selectedPeer = id
        }
        Tailnet.init(this)
        PeerOutbox.init(this)
        MessageReactions.init(this)
        resumeFamilyNetwork(this)
        CallController.init(this)
        GroupCallController.init(this)
        CallRecordingEngine.init(this)
        handleCallNotificationAction(intent)
        try { ContextCompat.startForegroundService(this, Intent(this, PeerService::class.java)) }
        catch (ex: Exception) { FamilyStore.lastError = "Service: " + ex.message }
        if (!FamilyStore.needsNicknamePrompt) requestNotificationPermission()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = accent, onPrimary = Color(0xFF0B141A), background = bg, surface = surface, onBackground = ink, onSurface = ink,
                secondary = accent, secondaryContainer = Color(0xFF173E37),
                onSecondaryContainer = ink, surfaceContainer = surface, surfaceVariant = Color(0xFF2A3942)
            )) { Surface(modifier = Modifier.fillMaxSize(), color = bg, contentColor = ink) { Box { FamilyPinGate {
                FamilyTouchUI(requestCallPermissions = { video, callback -> withCallPermissions(video, callback) },
                    onNicknameFinished = { requestNotificationPermission() })
                FamilyUpdatePrompt()
            } } } }
        }
    }
}

@Composable
private fun FamilyTouchUI(requestCallPermissions: (Boolean, () -> Unit) -> Unit, onNicknameFinished: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var recordingsLibrary by remember { mutableStateOf(false) }
    var chatMenuExpanded by remember { mutableStateOf(false) }
    var chatMenuAction by remember { mutableStateOf<String?>(null) }
    var showContactDetails by remember { mutableStateOf(false) }
    var showChatSecurityConfig by remember { mutableStateOf(false) }
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
    val launchSelectedCall: (Boolean) -> Unit = { video ->
        selectedPeer?.let { target ->
            requestCallPermissions(video) {
                try {
                    val group = GroupStore.find(target.id)
                    if (group != null) GroupCallController.start(group, video) else CallController.start(target, video)
                } catch (e: Exception) { FamilyStore.lastError = e.message ?: "Cannot start call" }
            }
        }
    }
    Column(Modifier.fillMaxSize().background(bg).navigationBarsPadding()) {
        Surface(color = surface, shadowElevation = 1.dp) {
            Row(Modifier.fillMaxWidth().statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selectedPeer != null && active == null && tab == 0) {
                    IconButton(onClick = { FamilyStore.selectedPeer = null }) {
                        Icon(Icons.Outlined.ArrowBack, "Back", tint = ink)
                    }
                    Box(Modifier.clickable { showContactDetails = true }) {
                        Avatar(selectedPeer.name, FamilyStore.peerPhotos[selectedPeer.id], 39.dp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f).clickable { showContactDetails = true }) {
                        Text(selectedPeer.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                            color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(FamilyStore.presenceLabel(selectedPeer.id), color = muted, fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { launchSelectedCall(true) }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.Videocam, "Video call", tint = ink)
                    }
                    IconButton(onClick = { launchSelectedCall(false) }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.Call, "Voice call", tint = ink)
                    }
                    Box {
                        IconButton(onClick = { chatMenuExpanded = true }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Outlined.MoreVert, "Conversation menu", tint = ink)
                        }
                        DropdownMenu(expanded = chatMenuExpanded, onDismissRequest = { chatMenuExpanded = false },
                            shape = RoundedCornerShape(16.dp)) {
                            DropdownMenuItem(text = { Text("View contact profile") }, onClick = {
                                chatMenuExpanded = false; showContactDetails = true
                            })
                            DropdownMenuItem(text = { Text("Clear chat on this device") }, onClick = {
                                chatMenuExpanded = false; chatMenuAction = "clear"
                            })
                            DropdownMenuItem(text = { Text("Search conversation") }, onClick = {
                                chatMenuExpanded = false; chatMenuAction = "search"
                            })
                            DropdownMenuItem(text = { Text("Shared media") }, onClick = {
                                chatMenuExpanded = false; chatMenuAction = "media"
                            })
                            DropdownMenuItem(text = { Text("Lock this conversation") }, onClick = {
                                chatMenuExpanded = false; showChatSecurityConfig = true
                            })
                        }
                    }
                } else {
                    SurfaceLogo()
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Family Touch", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = ink)
                        Text(networkStatus.label, fontSize = 12.sp, color = if (networkStatus.connected) accent else muted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (networkStatus.connected) Icon(Icons.Outlined.Wifi, "Private network connected", tint = accent)
                }
            }
        }
        HorizontalDivider(color = Color(0xFFE5ECE7), thickness = 0.5.dp)
        if (recordingsLibrary) {
            RecordingLibraryScreen { recordingsLibrary = false }
        } else if (groupCall != null) {
            GroupCallScreen(groupCall)
        } else if (active != null) {
            CallPage(active, onHangup = { CallController.hangup() })
        } else if (selectedPeer != null && tab == 0) {
            ChatSecurityGate(selectedPeer.id) {
                Conversation(peer = selectedPeer, menuAction = chatMenuAction,
                    onMenuHandled = { chatMenuAction = null })
            }
        } else {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> ChatsPage(contacts, onOpen = { FamilyStore.markChatViewed(it); FamilyStore.selectedPeer = it; tab = 0 })
                    1 -> CallsPage(contacts) { peer, video ->
                        requestCallPermissions(video) { CallController.start(peer, video) }
                    }
                    2 -> ContactsPage(contacts, onOpen = { FamilyStore.selectedPeer = it; tab = 0 },
                        onConnectionSettings = { tab = 3 })
                    else -> SettingsPage(networkStatus, onRecordings = { recordingsLibrary = true })
                }
            }
            NavigationBar(containerColor = surface, tonalElevation = 0.dp) {
                val navItems = listOf(Triple("Chats", Icons.Outlined.ChatBubbleOutline, 0),
                    Triple("Calls", Icons.Outlined.Call, 1),
                    Triple("Contacts", Icons.Outlined.Contacts, 2),
                    Triple("Settings", Icons.Outlined.Settings, 3))
                navItems.forEach { (label, icon, idx) ->
                    NavigationBarItem(selected = tab == idx, onClick = { tab = idx; FamilyStore.selectedPeer = null },
                        icon = {
                            if (idx == 0 && FamilyStore.unreadTotal() > 0) {
                                BadgedBox(badge = {
                                    Badge(modifier = Modifier.offset(x = (-5).dp, y = 3.dp),
                                        containerColor = Color(0xFFE85B64), contentColor = Color.White) {
                                        Text(FamilyStore.unreadTotal().coerceAtMost(99).toString())
                                    }
                                }) { Icon(icon, label) }
                            } else Icon(icon, label)
                        }, label = { Text(label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = accent, selectedTextColor = accent,
                            indicatorColor = Color(0xFFDEF3E8),
                            unselectedIconColor = muted, unselectedTextColor = muted))
                }
            }
        }
    }

    if (showChatSecurityConfig && selectedPeer != null)
        ChatSecuritySettingsDialog(selectedPeer.id) { showChatSecurityConfig = false }
    if (showContactDetails && selectedPeer != null) AlertDialog(
        onDismissRequest = { showContactDetails = false },
        title = { Text("Contact profile") },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(selectedPeer.name, FamilyStore.peerPhotos[selectedPeer.id], 80.dp)
                Text(selectedPeer.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(FamilyStore.presenceLabel(selectedPeer.id), color = muted)
                Text(if (GroupStore.find(selectedPeer.id) != null) "Private family group" else
                    "Paired private family device", fontSize = 12.sp, color = muted)
                Text("Shared media and messages are stored on your devices.",
                    color = muted, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { showContactDetails = false }) { Text("Close") } },
        dismissButton = { TextButton(onClick = {
            showContactDetails = false; chatMenuAction = "media"
        }) { Text("Shared media") } }
    )
    if (groupIncoming != null) AlertDialog(onDismissRequest = {}, title = { Text("Incoming group call") },
        text = { Text(GroupStore.find(groupIncoming.groupId)?.title ?: "Family group") },
        confirmButton = { Button(onClick = {
            requestCallPermissions(groupIncoming.video) {
                if (GroupCallController.incoming != null) GroupCallController.accept()
            }
        }) { Text("Join") } }, dismissButton = {
            TextButton(onClick = { GroupCallController.hangup() }) { Text("Decline") }
        })
    if (!FamilyStore.needsNicknamePrompt && !networkStatus.connected && incoming == null && active == null && groupCall == null) {
        AlertDialog(onDismissRequest = {}, title = { Text("Turn on your private connection") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Allow the built-in VPN for private family calls and messages. Registered devices appear after connecting.")
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
                Button(onClick = { requestCallPermissions(incoming.isVideo) {
                    if (FamilyStore.incomingCall != null) CallController.accept()
                } }) { Text("Answer") }
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
    Image(painter = painterResource(R.drawable.ic_family_touch),
        contentDescription = "Family Touch", modifier = Modifier.size(44.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatsPage(peers: List<Peer>, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var newGroup by remember { mutableStateOf(false) }
    fun refresh() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            try {
                if (EmbeddedTailnet.status.connected) FamilyDirectory.refresh(context)
                else FamilyStore.updateDirectoryStatus("Connect private network to refresh chats")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { FamilyStore.lastError = "Chat refresh: " + (e.message ?: "Network unavailable") }
            finally { refreshing = false }
        }
    }
    if (newGroup) GroupEditor(onDismiss = { newGroup = false }, onSaved = { newGroup = false; onOpen(it.id) })
    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refresh() }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 86.dp, top = 8.dp)) {
            if (peers.isEmpty() && GroupStore.groups.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 85.dp, start = 30.dp, end = 30.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Welcome to Family Touch",fontWeight=FontWeight.Bold,fontSize=20.sp)
                    Text("Pull down to refresh conversations.",color=muted)
                }
            }
            items(GroupStore.groups.toList(), key = { it.id }) { group ->
                PeerRow(Peer(group.id, group.title, "", ""),
                    "Group · " + group.members.size + " devices",
                    onClick = { onOpen(group.id) }, unread = FamilyStore.unreadFor(group.id))
            }
            items(peers, key = { it.id }) { peer ->
                val message = FamilyStore.messages.lastOrNull { it.peerId == peer.id }
                PeerRow(peer, message?.text?.let { ChatPreviewFormatter.forContact(it) } ?: "Start a conversation",
                    onClick = { onOpen(peer.id) }, unread = FamilyStore.unreadFor(peer.id))
            }
        }
        }
        FloatingActionButton(onClick = { newGroup = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
            containerColor = accent, contentColor = bg, shape = CircleShape) {
            Icon(Icons.Outlined.Add, "Create group")
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
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 17.dp),
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
            HorizontalDivider(color = Color(0xFFE5ECE7), thickness = 0.5.dp)
        }
    }
}

private fun durationText(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1000L
    return "%02d:%02d:%02d".format(seconds / 3600L, (seconds / 60L) % 60L, seconds % 60L)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactsPage(peers: List<Peer>, onOpen: (String) -> Unit, onConnectionSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connected = EmbeddedTailnet.status.connected
    var refreshing by remember { mutableStateOf(false) }
    var quickPeer by remember { mutableStateOf<Peer?>(null) }
    var confirmHelp by remember { mutableStateOf<Peer?>(null) }
    var pingResult by remember { mutableStateOf("") }
    quickPeer?.let { selected ->
        ModalBottomSheet(onDismissRequest = { quickPeer = null },
            containerColor = surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(selected.name, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { quickPeer = null; onOpen(selected.id) }) {
                    Text("Open conversation")
                }
                TextButton(onClick = {
                    quickPeer = null
                    pingResult = "Checking " + selected.name + "…"
                    scope.launch {
                        try {
                            val reply = Tailnet.send(selected, "presence")
                            pingResult = selected.name + " is " +
                                reply.optString("status", "reachable")
                        } catch (_: Exception) { pingResult = selected.name + " is unreachable" }
                    }
                }) { Text("Ping this family device") }
                TextButton(onClick = { quickPeer = null; confirmHelp = selected }) {
                    Text("SOS — ask for help")
                }
                TextButton(onClick = {
                    quickPeer = null
                    try {
                        PeerOutbox.queue(selected, "Could you help me with Family Touch or KDE Connect? Please contact me so I can approve a support session.")
                        pingResult = "Technical support request queued for " + selected.name
                    } catch (e: Exception) { pingResult = e.message ?: "Unable to request help" }
                }) { Text("Request technical support") }
                Text("Remote commands require the device owner's explicit approval. No unattended access.",
                    fontSize = 12.sp, color = muted)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    confirmHelp?.let { chosen ->
        AlertDialog(onDismissRequest = { confirmHelp = null },
            title = { Text("Ask " + chosen.name + " for help?") },
            text = { Text("Send an SOS chat message to your paired family member. This does not call emergency services.") },
            confirmButton = { Button(onClick = {
                try {
                    PeerOutbox.queue(chosen, "SOS: I need help. Please contact me as soon as you can.")
                    pingResult = "SOS message queued for " + chosen.name
                } catch (e: Exception) { pingResult = e.message ?: "SOS request failed" }
                confirmHelp = null
            }) { Text("Send SOS") } },
            dismissButton = { TextButton(onClick = { confirmHelp = null }) { Text("Cancel") } })
    }
    fun doRefresh() {
        if (refreshing) return
        if (!connected) {
            onConnectionSettings()
            return
        }
        refreshing = true
        scope.launch {
            try {
                FamilyDirectory.refresh(context)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                FamilyStore.lastError = "Contacts refresh failed: " + (e.message ?: "connection unavailable")
            } finally {
                refreshing = false
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Registered family devices", fontWeight = FontWeight.Bold)
                val statusText = if (connected) FamilyStore.directoryStatus else
                    "Private network disconnected - connect before discovery"
                val warning = statusText.contains("unavailable", true) ||
                    statusText.contains("retrying", true) ||
                    statusText.contains("disconnected", true) ||
                    statusText.contains("blocked", true)
                Row(Modifier.padding(top = 5.dp)
                    .background(if (warning) Color(0xFF4E3D23) else Color(0xFF183B31),
                        RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (warning) {
                        Icon(Icons.Outlined.WarningAmber, null, Modifier.size(15.dp), tint = Color(0xFFFFD68B))
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(statusText, color = if (warning) Color(0xFFFFD68B) else Color(0xFFB1E2CF),
                        fontSize = 11.sp, maxLines = 3)
                }
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { doRefresh() }, enabled = !refreshing) {
                Text(if (refreshing) "Refreshing…" else if (connected) "Refresh" else "Connect")
            }
        }
        if (pingResult.isNotBlank()) Text(pingResult, color = accent, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { doRefresh() },
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                if (peers.isEmpty()) item {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 92.dp, start = 24.dp, end = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Outlined.Contacts, null, Modifier.size(52.dp), tint = accent)
                        Text("Private family circle", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        Text("Pull down to refresh or connect to discover registered family devices.",
                            color = muted, fontSize = 13.sp)
                    }
                }
                items(peers, key = { it.id }) { peer ->
                    PeerRow(peer, peer.ip, onClick = { onOpen(peer.id) },
                        onLongPress = { quickPeer = peer })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PeerRow(peer: Peer, subtitle: String, onClick: () -> Unit, unread: Int = 0,
                    onLongPress: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick,
        onLongClick = { onLongPress?.invoke() }).padding(horizontal = 19.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Avatar(peer.name, FamilyStore.peerPhotos[peer.id]); Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(peer.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val state = FamilyStore.peerPresence[peer.id]
                val presenceColor = when (state) {
                    "available" -> Color(0xFF12835A)
                    "on_call" -> Color(0xFF287CC7)
                    "away" -> Color(0xFFD39A27)
                    else -> Color(0xFF98A4A0)
                }
                Box(Modifier.size(8.dp).background(presenceColor, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(FamilyStore.presenceLabel(peer.id) + " · " + subtitle, color = muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            }
        }
        if (unread > 0) {
            Badge(containerColor = accent) {
                Text(if (unread > 99) "99+" else unread.toString(), color = Color.White)
            }
        }
    }
    HorizontalDivider(color = Color(0xFFE5ECE7), thickness = 0.5.dp)
}

@Composable
internal fun Avatar(name: String, photo: String? = null, avatarSize: androidx.compose.ui.unit.Dp = 48.dp) {
    val decoded = remember(photo) {
        try {
            if (photo.isNullOrEmpty()) null else {
                val bytes = Base64.decode(photo, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        } catch (_: Exception) { null }
    }
    Box(Modifier.size(avatarSize).clip(CircleShape).background(Color(0xFF536A63)),
        contentAlignment = Alignment.Center) {
        if (decoded != null) Image(decoded, "Profile image", Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit)
        else Text(name.take(1).uppercase(), fontWeight = FontWeight.Bold,
            fontSize = 21.sp, color = Color(0xFF086940))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Conversation(peer: Peer, menuAction: String?, onMenuHandled: () -> Unit) {
    val group = GroupStore.find(peer.id)
    val scope = rememberCoroutineScope()
    var draft by remember(peer.id) { mutableStateOf("") }
    var emojiPickerOpen by remember(peer.id) { mutableStateOf(false) }
    var replyTo by remember(peer.id) { mutableStateOf<ChatItem?>(null) }
    var actionMessage by remember(peer.id) { mutableStateOf<ChatItem?>(null) }
    var attachmentSheet by remember(peer.id) { mutableStateOf(false) }
    var editMessage by remember(peer.id) { mutableStateOf<ChatItem?>(null) }
    var editDraft by remember(peer.id) { mutableStateOf("") }
    var deleteChoice by remember(peer.id) { mutableStateOf<Pair<ChatItem, Boolean>?>(null) }
    var clearConfirmation by remember(peer.id) { mutableStateOf(false) }
    var gallery by remember(peer.id) { mutableStateOf(false) }
    var searching by remember(peer.id) { mutableStateOf(false) }
    var query by remember(peer.id) { mutableStateOf("") }
    LaunchedEffect(menuAction) {
        when (menuAction) {
            "search" -> searching = true
            "media" -> gallery = true
            "clear" -> clearConfirmation = true
        }
        if (menuAction != null) onMenuHandled()
    }
    if (gallery) MediaGallery(peer.id) { gallery = false }
    actionMessage?.let { item ->
        ModalBottomSheet(onDismissRequest = { actionMessage = null },
            containerColor = surface,
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)) {
            Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 23.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text((AttachmentTransport.parse(item.text)?.name ?: item.text).take(130),
                    maxLines = 2, overflow = TextOverflow.Ellipsis, color = ink, fontSize = 14.sp)
                Row(Modifier.fillMaxWidth().background(Color(0xFF2A3942), RoundedCornerShape(30.dp)),
                    horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    ReactionPolicy.choices.forEach { emoji ->
                        Box(Modifier.size(45.dp).clickable {
                            try { MessageReactions.react(item, emoji); actionMessage = null }
                            catch (_: Exception) { FamilyStore.lastError = "Reaction unavailable. Retry after reconnecting" }
                        }, contentAlignment = Alignment.Center) { Text(emoji, fontSize = 23.sp) }
                    }
                }
                TextButton(onClick = { replyTo = item; actionMessage = null }) {
                    Text("Reply", color = accent)
                }
                if (MessageMutations.editable(item) && group == null) {
                    TextButton(onClick = {
                        editMessage = item; editDraft = item.text; actionMessage = null
                    }) { Text("Edit message (15 minutes)", color = accent) }
                }
                TextButton(onClick = {
                    deleteChoice = item to false; actionMessage = null
                }) { Text("Delete for me", color = ink) }
                if (group == null && MessageMutations.deletableForEveryone(item)) {
                    TextButton(onClick = {
                        deleteChoice = item to true; actionMessage = null
                    }) { Text("Delete for everyone", color = Color(0xFFFF8888)) }
                }
                TextButton(onClick = {
                    try { MessageReactions.react(item, ""); actionMessage = null }
                    catch (_: Exception) { FamilyStore.lastError = "Reaction unavailable. Retry after reconnecting" }
                }) { Text("Remove my reaction", color = muted) }
            }
        }
    }
    val context = LocalContext.current
    if (clearConfirmation) AlertDialog(onDismissRequest = { clearConfirmation = false },
        title = { Text("Clear this chat?") },
        text = { Text("Messages will be removed from this device only. This does not remove messages on other family devices.") },
        confirmButton = { Button(onClick = {
            FamilyStore.clearConversation(peer.id); clearConfirmation = false
        }) { Text("Clear locally") } },
        dismissButton = { TextButton(onClick = { clearConfirmation = false }) { Text("Cancel") } })
    editMessage?.let { target ->
        AlertDialog(onDismissRequest = { editMessage = null }, title = { Text("Edit sent message") },
            text = {
                OutlinedTextField(value = editDraft, onValueChange = { editDraft = it.take(6000) },
                    label = { Text("Message") }, modifier = Modifier.fillMaxWidth(),
                    minLines = 2, maxLines = 5)
            },
            confirmButton = { Button(enabled = editDraft.isNotBlank(), onClick = {
                try { MessageMutations.edit(target, editDraft); editMessage = null }
                catch (e: Exception) { FamilyStore.lastError = e.message ?: "Unable to edit message" }
            }) { Text("Save edit") } },
            dismissButton = { TextButton(onClick = { editMessage = null }) { Text("Cancel") } })
    }
    deleteChoice?.let { (target, everyone) ->
        AlertDialog(onDismissRequest = { deleteChoice = null },
            title = { Text(if (everyone) "Delete for everyone?" else "Delete for me?") },
            text = { Text(if (everyone)
                "The delete request will retry when connected. Other devices must support this update; saved copies and screenshots cannot be recalled."
                else "This only removes the message from this device.") },
            confirmButton = { Button(onClick = {
                try {
                    if (everyone) MessageMutations.deleteForEveryone(target)
                    else FamilyStore.deleteForMe(target.id)
                    deleteChoice = null
                } catch (e: Exception) {
                    FamilyStore.lastError = e.message ?: "Unable to delete message"
                }
            }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteChoice = null }) { Text("Cancel") } })
    }
    var mapCenter by remember(peer.id) { mutableStateOf<Pair<Double,Double>?>(null) }
    var showMapPicker by remember(peer.id) { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
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
    val sendVoiceNote: (Uri, (Boolean) -> Unit) -> Unit = { uri, done ->
        scope.launch {
            try {
                if (group != null) GroupStore.sendMedia(group, listOf(uri))
                else AttachmentTransport.send(context, peer, listOf(uri)) { FamilyStore.addChat(it) }
                done(true)
            } catch (e: Exception) {
                done(false)
                FamilyStore.lastError = "Voice message failed: " + (e.message ?: "Retry")
            }
        }
    }
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

    var captureUri by remember(peer.id) { mutableStateOf<Uri?>(null) }
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val uri = captureUri
        captureUri = null
        if (taken && uri != null) {
            sendingMedia = true
            scope.launch {
                try {
                    if (group != null) GroupStore.sendMedia(group, listOf(uri))
                    else AttachmentTransport.send(context, peer, listOf(uri)) { FamilyStore.addChat(it) }
                } catch (e: Exception) { FamilyStore.lastError = "Photo transfer failed: " + e.message }
                finally { sendingMedia = false }
            }
        }
    }
    val launchCamera: () -> Unit = {
        try {
            val dir = java.io.File(context.cacheDir, "family-camera").apply { mkdirs() }
            val temp = java.io.File(dir, "capture-" + UUID.randomUUID() + ".jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(context,
                context.packageName + ".files", temp)
            captureUri = uri
            capture.launch(uri)
        } catch (e: Exception) { FamilyStore.lastError = "Camera unavailable: " + e.message }
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
    val submitDraft: () -> Unit = {
        val quoted = replyTo?.let { (AttachmentTransport.parse(it.text)?.name ?: it.text).replace('\n', ' ').take(180) }
        val text = if (quoted != null) "↪ $quoted\n\n${draft.trim()}" else draft.trim()
        if (sendChat(text)) {
            draft = ""
            replyTo = null
            keyboardController?.hide()
            focusManager.clearFocus()
            ChatSendSound.play()
        }
    }
    val requestLocation: () -> Unit = {
        locationBusy = true
        locationJob?.cancel()
        locationJob = scope.launch {
            try {
                val loc = LocationShareController.request(context)
                mapCenter = loc.latitude to loc.longitude
                showMapPicker = true
                locationDetails = "Location accuracy about " + loc.accuracy.toInt() + " m"
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

    if (attachmentSheet) ModalBottomSheet(onDismissRequest = { attachmentSheet = false },
        containerColor = surface, shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Attach", fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
                modifier = Modifier.padding(bottom = 7.dp))
            @Composable fun AttachmentChoice(name: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
                                              select: () -> Unit) {
                Row(Modifier.fillMaxWidth().clickable { attachmentSheet = false; select() }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, name, tint = accent, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(19.dp))
                    Text(name, color = ink, fontSize = 16.sp)
                }
            }
            AttachmentChoice("Photos", Icons.Outlined.Photo) { selectMedia.launch(arrayOf("image/*")) }
            AttachmentChoice("Videos", Icons.Outlined.Videocam) { selectMedia.launch(arrayOf("video/*")) }
            AttachmentChoice("Documents and files", Icons.Outlined.InsertDriveFile) {
                selectMedia.launch(arrayOf("*/*"))
            }
            AttachmentChoice("Location: map pin or live", Icons.Outlined.LocationOn) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED) requestLocation()
                else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION))
            }
            AttachmentChoice("Camera", Icons.Outlined.CameraAlt) { launchCamera() }
            Spacer(Modifier.height(20.dp))
        }
    }
    val chosenCenter = mapCenter
    if (showMapPicker && chosenCenter != null) {
        LocationPinPicker(chosenCenter.first, chosenCenter.second, peer.name,
            direct = group == null,
            onDismiss = { showMapPicker = false; mapCenter = null },
            onSendPin = { lat, lon ->
                val map = String.format(Locale.US,
                    "https://www.google.com/maps?q=%.6f,%.6f", lat,lon)
                if (sendChat("Location: " + map)) {
                    showMapPicker = false; mapCenter = null
                }
            },
            onStartLive = { duration ->
                try {
                    LiveLocationSharing.begin(context, peer, duration)
                    showMapPicker = false
                    mapCenter = null
                } catch (ex: Exception) {
                    FamilyStore.lastError = "Live location failed: " + (ex.message ?: "Unavailable")
                }
            })
    }
    Column(Modifier.fillMaxSize().background(bg)) {
        if (searching) Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it.take(200) }, Modifier.weight(1f),
                placeholder = { Text("Search this conversation") }, singleLine = true)
            TextButton(onClick = { searching = false; query = "" }) { Text("Close") }
        }
        if (group != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = {
                if (group.owner == FamilyStore.deviceId) editGroup = true
                else scope.launch {
                    try { GroupStore.leave(group); FamilyStore.selectedPeer = null }
                    catch (_: Exception) { FamilyStore.lastError = "Creator unavailable; try leaving again later" }
                }
            }) { Text(if (group.owner == FamilyStore.deviceId) "Members" else "Leave group", color = accent) }
        }
        val history = FamilyStore.messages.filter { it.peerId == peer.id && (query.isBlank() || it.text.contains(query, ignoreCase = true)) }
        val chatScroll = rememberLazyListState()
        var didInitialScroll by remember(peer.id) { mutableStateOf(false) }
        LaunchedEffect(peer.id, history.size) {
            if (history.isNotEmpty()) {
                val nearBottom = chatScroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let {
                    it >= history.size - 2
                } ?: false
                if (!didInitialScroll || nearBottom) chatScroll.scrollToItem(history.size)
                didInitialScroll = true
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            DarkChatWallpaper(Modifier.matchParentSize())
            LazyColumn(Modifier.fillMaxSize(), state = chatScroll, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                item(key = "chat-security") { DarkChatSecurityNotice() }
                items(history, key = { it.id }) { msg ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.outgoing) Arrangement.End else Arrangement.Start) {
                    val textColor = ink
                    val secondaryColor = muted
                    val actionColor = accent
                    val bubbleColor = if (msg.outgoing) Color(0xFF005C4B) else surface
                    if (!msg.outgoing) DarkChatTail(false, bubbleColor)
                    Column(Modifier.widthIn(max = 310.dp)
                        .pointerInput(msg.id) { detectTapGestures(onLongPress = { actionMessage = msg }) }
                        .background(bubbleColor, RoundedCornerShape(
                            topStart = if (msg.outgoing) 12.dp else 3.dp,
                            topEnd = if (msg.outgoing) 3.dp else 12.dp,
                            bottomStart = 12.dp, bottomEnd = 12.dp
                        )).padding(horizontal = 11.dp, vertical = 8.dp)) {
                        val attachment = if (msg.deletedForEveryone) null else AttachmentTransport.parse(msg.text)
                        val quoted = if (attachment == null && msg.text.startsWith("↪ ") && msg.text.contains("\n\n"))
                            msg.text.substringAfter("↪ ").substringBefore("\n\n") else null
                        if (quoted != null) {
                            Row(Modifier.fillMaxWidth().background(
                                Color(0xFF17262B).copy(alpha = .77f), RoundedCornerShape(6.dp))
                                .padding(vertical = 6.dp)) {
                                Box(Modifier.width(3.dp).height(38.dp).background(Color(0xFFB388EB)))
                                Column(Modifier.padding(horizontal = 8.dp)) {
                                    Text(if (msg.outgoing) "Reply" else peer.name, color = Color(0xFFCEB1F3),
                                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    Text(quoted, color = muted, maxLines = 2, fontSize = 12.sp,
                                        overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Spacer(Modifier.height(5.dp))
                        }
                        if (group != null && !msg.outgoing) Text(FamilyStore.find(msg.senderId)?.name ?: "Family member", fontSize = 11.sp, color = secondaryColor)
                        if (attachment != null) {
                            if (attachment.mime.startsWith("audio/")) {
                                VoiceMessagePlayer(attachment, textColor,
                                    senderName = if (msg.outgoing) FamilyStore.nickname else peer.name,
                                    senderPhoto = if (msg.outgoing) FamilyStore.profilePhoto else FamilyStore.peerPhotos[peer.id])
                            } else {
                                if (attachment.mime.startsWith("image/")) {
                                    ChatImageThumbnail(attachment, modifier = Modifier.clickable {
                                        viewedMedia = attachment
                                    })
                                } else if (attachment.mime.startsWith("video/")) {
                                    Box(Modifier.size(width = 210.dp, height = 110.dp)
                                        .background(Color(0xFF2A3942), RoundedCornerShape(8.dp))
                                        .clickable { viewedMedia = attachment },
                                        contentAlignment = Alignment.Center) {
                                        Icon(Icons.Outlined.Videocam, "Play video", tint = accent)
                                    }
                                } else {
                                    Row(Modifier.widthIn(max = 250.dp).clickable {
                                        try { AttachmentTransport.open(context, attachment) }
                                        catch (e: Exception) {
                                            FamilyStore.lastError = "Cannot open file: " + (e.message ?: "No compatible app")
                                        }
                                    }.padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.InsertDriveFile, "Open document",
                                            tint = accent, modifier = Modifier.size(38.dp))
                                        Spacer(Modifier.width(12.dp))
                                        Text("Open document", color = textColor, fontSize = 13.sp)
                                    }
                                }
                                Text(attachment.name, color = textColor, fontSize = 12.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                Text(((attachment.size + 1023) / 1024).toString() + " KB",
                                    color = muted, fontSize = 10.sp)
                            }
                        } else if (msg.deletedForEveryone) {
                            Text("This message was deleted", color = muted, fontSize = 14.sp,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                        } else if (LiveLocationProtocol.parse(msg.text) != null) {
                            LiveLocationBubble(requireNotNull(LiveLocationProtocol.parse(msg.text)),
                                msg.outgoing) { LiveLocationSharing.stop(context) }
                        } else if (msg.text.startsWith("Location: https://www.google.com/maps?q=")) {
                            ChatLocationPreview(msg.text.removePrefix("Location: ")) {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW,
                                        Uri.parse(msg.text.removePrefix("Location: "))))
                                } catch (_: Exception) {
                                    FamilyStore.lastError = "No maps app or browser available"
                                }
                            }
                        } else Text(if (quoted != null) msg.text.substringAfter("\n\n") else msg.text,
                            color = textColor, fontSize = 15.sp, lineHeight = 20.sp)
                        if (msg.editedAt > 0 && !msg.deletedForEveryone)
                            Text("edited", color = muted, fontSize = 10.sp)
                        MessageMutations.pendingLabels[msg.id]?.let { sync ->
                            Text(sync, color = muted, fontSize = 10.sp)
                        }
                        val reactions = MessageReactions.summary(msg.id)
                        if (reactions.isNotBlank()) Text(reactions, color = textColor)
                        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(msg.time)),
                                color = secondaryColor, fontSize = 10.sp)
                            if (msg.outgoing) {
                                Spacer(Modifier.width(4.dp))
                                DarkChatReceipt(msg.sent, msg.delivered, msg.read)
                            }
                        }
                    }
                    if (msg.outgoing) DarkChatTail(true, bubbleColor)
                }
            }
            }
        }
        replyTo?.let { item -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
            .background(surface, RoundedCornerShape(9.dp)).padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val quotedAttachment = AttachmentTransport.parse(item.text)
            if (quotedAttachment?.mime?.startsWith("image/") == true) {
                ChatImageThumbnail(quotedAttachment, 42.dp, Modifier.padding(end = 8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("Reply", color = Color(0xFFCEB1F3), fontSize = 12.sp)
                Text((quotedAttachment?.name ?: item.text).take(140), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = ink, fontSize = 12.sp)
            }
            TextButton(onClick = { replyTo = null }) { Text("✕", color = muted) }
        } }
        LiveLocationSharing.active?.let { share ->
            if (share.peerId == peer.id) {
                Row(Modifier.fillMaxWidth().background(Color(0xFF26423C))
                    .padding(horizontal = 12.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Live location is active", Modifier.weight(1f),
                        color = Color(0xFF8FFFD5), fontSize = 12.sp)
                    TextButton(onClick = { LiveLocationSharing.stop(context) }) {
                        Text("Stop now", color = Color(0xFFFFB8B8))
                    }
                }
            }
        }
        if (voiceNote) {
            InlineVoiceComposer(onDismiss = { voiceNote = false }, onSend = sendVoiceNote)
        } else Row(Modifier.fillMaxWidth().background(bg).padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom) {
            Row(Modifier.weight(1f).background(Color(0xFF2A3942), RoundedCornerShape(27.dp))
                .heightIn(min = 50.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(modifier = Modifier.size(40.dp), onClick = { emojiPickerOpen = true }) {
                        Icon(Icons.Outlined.EmojiEmotions, "Insert emoji", tint = muted)
                    }
                    DropdownMenu(expanded = emojiPickerOpen, onDismissRequest = { emojiPickerOpen = false }) {
                        listOf("🙂", "❤️", "👍", "😂", "🙏", "🎉").forEach { choice ->
                            DropdownMenuItem(text = { Text(choice, fontSize = 19.sp) }, onClick = {
                                draft = (draft + choice).take(6000); emojiPickerOpen = false
                            })
                        }
                    }
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = draft, onValueChange = { draft = it.take(6000) },
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(color = ink, fontSize = 15.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(accent),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submitDraft() }),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (draft.isBlank()) Text("Message", color = muted, fontSize = 15.sp)
                            inner()
                        }
                    }
                )
                IconButton(modifier = Modifier.size(40.dp),
                    enabled = !sendingMedia, onClick = { attachmentSheet = true }) {
                    Icon(Icons.Outlined.AttachFile, "Choose attachment type", tint = muted)
                }
                IconButton(modifier = Modifier.size(40.dp), enabled = !sendingMedia,
                    onClick = { launchCamera() }) {
                    Icon(Icons.Outlined.CameraAlt, "Take photo", tint = muted)
                }
            }
            Spacer(Modifier.width(7.dp))
            FilledIconButton(modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = bg),
                enabled = !sendingMedia,
                onClick = {
                    if (draft.isNotBlank()) submitDraft()
                    else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                        voiceNote = true else voicePermission.launch(Manifest.permission.RECORD_AUDIO)
                }) {
                Icon(if (draft.isNotBlank()) Icons.Outlined.Send else Icons.Outlined.Mic,
                    if (draft.isNotBlank()) "Send message" else "Record voice note",
                    modifier = Modifier.size(24.dp))
            }
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
        FamilyPinSettings()
        OutlinedButton(onClick = {
            try {
                context.startActivity(Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, "familytouch_status_silent_v3"))
            } catch (_: Exception) {
                context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        }) { Text("Connection notification settings") }
        Text("The private connection uses an Android foreground service. Its notification is minimized and silent; Android may still show a service indicator.",
            color = muted, fontSize = 12.sp)
        ChatBackupPanel()
        OutlinedButton(onClick = onRecordings) { Text("Local call recordings") }
        Text("App version", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = ink)
        Text(FamilyVersionLabels.format(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong()),
            color = accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text("Installed release: " + BuildConfig.VERSION_NAME, color = muted, fontSize = 12.sp)
        Button(onClick = { FamilyUpdates.checkNow() }, colors = ButtonDefaults.buttonColors(
            containerColor = accent, contentColor = bg)) { Text("Check for updates") }
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
