package win.fantest.familytouch

import android.os.Build
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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

internal data class DeviceRecord(
    val id: String, val name: String, val model: String,
    val approved: Boolean, val revoked: Boolean
)
internal data class Dashboard(
    val devices: List<DeviceRecord>, val deleted: Int, val auto: Boolean
)

@Composable
internal fun FamilyTouchAdminUI() {
    val s25 = Build.MANUFACTURER.equals("samsung", true) && Build.MODEL == "SM-S938B"
    var loggedIn by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(3) }
    var section by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    var dashboard by remember { mutableStateOf(Dashboard(emptyList(), 0, false)) }
    var pending by remember { mutableStateOf<Pair<DeviceRecord, String>?>(null) }
    var selectedContact by remember { mutableStateOf<DeviceRecord?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        while (!loggedIn && s25) {
            busy = true
            try {
                fingerprint = DeviceAdminApi.fingerprint()
                if (DeviceAdminApi.register()) {
                    dashboard = DeviceAdminApi.load()
                    loggedIn = true
                    error = ""
                } else {
                    error = ""
                }
            } catch (e: Exception) {
                error = e.message ?: "Connection unavailable"
            } finally { busy = false }
            if (!loggedIn) delay(15000)
        }
    }
    fun reload() {
        busy = true
        scope.launch {
            try { dashboard = DeviceAdminApi.load(); error = "" }
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
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(26.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Security, null, Modifier.size(72.dp), tint = green)
                    Spacer(Modifier.height(18.dp))
                    Text("Secure S25 access", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Family Touch Admin opens automatically after one-time MASTER-PC device authorization.",
                        color = muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(17.dp))
                    Surface(color = mint, shape = RoundedCornerShape(15.dp)) {
                        Column(Modifier.padding(14.dp), horizontalAlignment=Alignment.CenterHorizontally) {
                            Text("Device fingerprint", color=green, fontSize=12.sp)
                            Text(fingerprint.take(16), color=green, fontWeight=FontWeight.Bold)
                            Text("No username or password required", color=green, fontSize=12.sp)
                        }
                    }
                    if(busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=15.dp))
                    if(error.isNotBlank()) Text(error, color=MaterialTheme.colorScheme.error,
                        modifier=Modifier.padding(top=13.dp))
                    Text("Checking access automatically every 15 seconds",
                        fontSize=12.sp, color=muted, modifier=Modifier.padding(top=14.dp))
                }
            } else {
                Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                    Surface(color=Color.White,shadowElevation=2.dp) {
                        Column(Modifier.fillMaxWidth().background(Color.White).statusBarsPadding()) {
                            Row(Modifier.fillMaxWidth().height(67.dp).padding(horizontal=16.dp),
                                verticalAlignment=Alignment.CenterVertically) {
                                Box(Modifier.size(43.dp).background(mint,CircleShape),
                                    contentAlignment=Alignment.Center) {
                                    Icon(Icons.Outlined.Security, null, tint=green)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Family Touch", fontWeight=FontWeight.Bold, fontSize=20.sp)
                                    Text("ADMIN  •  S25  •  Private HTTPS", color=green, fontSize=11.sp)
                                }
                                IconButton(onClick={ FamilyUpdates.checkNow() }) {
                                    Icon(Icons.Outlined.SystemUpdate,contentDescription="Check updates",tint=green)
                                }
                                IconButton(onClick={ reload() },enabled=!busy) {
                                    Icon(Icons.Outlined.Refresh,contentDescription="Refresh",tint=green)
                                }
                            }
                        }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Box(Modifier.weight(1f)) {
                        when (tab) {
                            3 -> Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text("Family management", fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { reload() }, enabled = !busy) { Text("Refresh") }
                                }
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected=section==0,onClick={section=0},
                                        label={Text("Users")})
                                    FilterChip(selected=section==1,onClick={section=1},
                                        label={Text("Devices")})
                                }
                                Text(if(section==0) "Family users & approvals"
                                    else "Registered devices & connection recovery",
                                    color=muted,fontSize=12.sp)

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
                                                        Text(if(section==0) "Family user · " + device.model
                                                            else "Device model · " + device.model,
                                                            fontSize = 12.sp, color = muted)
                                                    }
                                                    Text(if (device.revoked) "Revoked" else
                                                        if (device.approved) "Approved" else "Pending",
                                                        color = if (device.approved) green else muted, fontSize = 12.sp)
                                                }
                                                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                                    if (!device.approved) OutlinedButton(onClick = {
                                                        pending = device to "approve"
                                                    }, enabled = !busy) { Text("Approve") }
                                                    if (device.approved && !device.revoked) OutlinedButton(onClick = {
                                                        pending = device to "reauthorize"
                                                    }, enabled = !busy) { Text("Retry VPN") }
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
                            2 -> Column(Modifier.fillMaxSize()) {
                                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp),
                                    verticalAlignment=Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Registered family devices",fontWeight=FontWeight.Bold)
                                        Text("Your approved family circle", color=muted,fontSize=12.sp)
                                    }
                                    OutlinedButton(onClick={reload()},enabled=!busy) { Text("Refresh") }
                                }
                                val contacts=dashboard.devices.filter{it.approved && !it.revoked}
                                if(contacts.isEmpty()) {
                                    Text("Approved family contacts will appear here.",
                                        Modifier.padding(22.dp),color=muted)
                                } else LazyColumn {
                                    items(contacts,key={it.id}) { device ->
                                        Row(Modifier.fillMaxWidth().clickable{selectedContact=device}
                                            .padding(horizontal=19.dp,vertical=15.dp),
                                            verticalAlignment=Alignment.CenterVertically) {
                                            Box(Modifier.size(48.dp).background(mint,CircleShape),
                                                contentAlignment=Alignment.Center) {
                                                Text(device.name.take(1).uppercase(),color=green,
                                                    fontWeight=FontWeight.Bold,fontSize=21.sp)
                                            }
                                            Spacer(Modifier.width(14.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(device.name,fontWeight=FontWeight.SemiBold,
                                                    fontSize=16.sp)
                                                Text("Approved · "+device.model,color=muted,
                                                    fontSize=13.sp)
                                            }
                                            Icon(Icons.Outlined.ChevronRight,null,tint=muted)
                                        }
                                        HorizontalDivider(color=Color(0xFFE5ECE7),
                                            thickness=0.5.dp)
                                    }
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
        selectedContact?.let { contact ->
            AlertDialog(onDismissRequest={selectedContact=null},
                title={Text(contact.name)},
                text={Text("Registered family member · "+contact.model+
                    "\nVPN-free chats/calls are not available in Admin yet.")},
                confirmButton={TextButton(onClick={selectedContact=null}){Text("Close")}})
        }
        pending?.let { (device, action) ->
            AlertDialog(onDismissRequest = { pending = null },
                title = { Text(action.replaceFirstChar { it.uppercase() } + " " + device.name + "?") },
                text = { Text(when (action) {
                    "delete" -> "Remove this registration? Re-enrollment will be required. This cannot be undone."
                    "revoke" -> "Revoke this device's access to future private credentials?"
                    "reauthorize" -> "Invalidate this device's previous short-lived VPN credential and allow a new one on its next signed registration attempt? The device must remain approved."
                    else -> "Approve this device for family access?"
                }) },
                confirmButton = {
                    TextButton(enabled = !busy, onClick = {
                        pending = null; busy = true
                        scope.launch {
                            try {
                                DeviceAdminApi.update(device, action)
                                dashboard = DeviceAdminApi.load()
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
