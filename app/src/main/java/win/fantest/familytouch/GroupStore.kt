package win.fantest.familytouch

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class GroupMembership(val owner: String, val members: Set<String>, val revision: Long) {
    fun canUpdate(sender: String, nextOwner: String, nextMembers: Set<String>, nextRevision: Long) =
        sender == owner && nextOwner == owner && nextRevision > revision &&
            nextMembers.size in 1..32 && owner in nextMembers && nextMembers.none { it.isBlank() }
    fun canSend(sender: String, messageRevision: Long) = sender in members && messageRevision == revision
}
internal data class FamilyGroup(val id: String, val title: String, val owner: String,
    val members: Set<String>, val revision: Long) {
    val membership get() = GroupMembership(owner, members, revision)
    fun json() = JSONObject().put("groupId", id).put("title", title).put("owner", owner)
        .put("members", JSONArray(members.toList())).put("revision", revision)
}
internal object GroupStore {
    private lateinit var context: Context
    val groups = mutableStateListOf<FamilyGroup>()
    private val pending = mutableListOf<JSONObject>()
    private val updates = mutableListOf<JSONObject>()
    private val tombstones = mutableMapOf<String, FamilyGroup>()
    private val deliveryLabels = mutableStateMapOf<String, String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushMutex = Mutex()
    @Synchronized fun init(ctx: Context) {
        if (::context.isInitialized) return
        context = ctx.applicationContext
        val prefs = context.getSharedPreferences("groups_v1", Context.MODE_PRIVATE)
        runCatching {
            val array = JSONArray(prefs.getString("groups", "[]"))
            for (i in 0 until array.length()) groups.add(decode(array.getJSONObject(i)))
            val queue = JSONArray(prefs.getString("pending", "[]"))
            val changes = JSONArray(prefs.getString("updates", "[]"))
            for (i in 0 until changes.length()) updates.add(changes.getJSONObject(i))
            val removed = JSONArray(prefs.getString("tombstones", "[]"))
            for (i in 0 until removed.length()) decode(removed.getJSONObject(i)).let { tombstones[it.id] = it }
            for (i in 0 until minOf(queue.length(), 500)) pending.add(queue.getJSONObject(i))
            pending.forEach { deliveryLabels[it.getString("messageId")] = "Pending ${it.getJSONArray("remaining").length()} recipient(s) · retries automatically" }
        }.onFailure { FamilyStore.lastError = "Some group data could not be loaded" }
        scope.launch { while (isActive) { runCatching { flush() }; delay(15000) } }
    }
    @Synchronized fun find(id: String?) = groups.firstOrNull { it.id == id } ?: tombstones[id]
    @Synchronized fun snapshot(sender: String, data: JSONObject): JSONObject {
        val group = requireNotNull(find(data.getString("groupId")))
        require(group.owner == FamilyStore.deviceId && (sender in group.members || sender in FamilyStore.contacts.map { it.id }))
        return group.json()
    }
    private fun decode(data: JSONObject): FamilyGroup {
        val id = data.getString("groupId")
        require(id.startsWith("group:")); UUID.fromString(id.removePrefix("group:"))
        val memberArray = data.getJSONArray("members")
        require(memberArray.length() in 1..32)
        val members = (0 until memberArray.length()).map { memberArray.getString(it) }.toSet()
        val title = data.getString("title").trim()
        val owner = data.getString("owner")
        val revision = data.getLong("revision")
        require(members.size == memberArray.length() && owner in members && members.none { it.isBlank() } &&
            title.length in 1..60 && revision > 0)
        return FamilyGroup(id, title, owner, members, revision)
    }
    @Synchronized private fun save() {
        context.getSharedPreferences("groups_v1", Context.MODE_PRIVATE).edit()
            .putString("groups", JSONArray(groups.map { it.json() }).toString())
            .putString("pending", JSONArray(pending).toString()).apply()
        context.getSharedPreferences("groups_v1", Context.MODE_PRIVATE).edit()
            .putString("updates", JSONArray(updates).toString())
            .putString("tombstones", JSONArray(tombstones.values.map { it.json() }).toString()).apply()
    }
    @Synchronized fun create(title: String, selected: Set<String>): FamilyGroup {
        require(groups.size < 64 && title.trim().length in 1..60 && selected.size in 1..31)
        require(selected.all { FamilyStore.find(it) != null }) { "Select registered family devices" }
        val group = FamilyGroup("group:" + UUID.randomUUID(), title.trim(), FamilyStore.deviceId,
            selected + FamilyStore.deviceId, 1)
        groups.add(group); scheduleUpdate(group, group.members); save(); scope.launch { flush() }; return group
    }
    @Synchronized fun receiveUpdate(sender: String, data: JSONObject) {
        val next = decode(data)
        require(sender == next.owner) { "Only the group creator can update membership" }
        val old = find(next.id)
        if (old == next) return
        if (old == null) require(FamilyStore.deviceId in next.members && groups.size < 64)
        else require(old.membership.canUpdate(sender, next.owner, next.members, next.revision)) { "Invalid group revision" }
        if (old != null && GroupCallController.session?.groupId == next.id) GroupCallController.hangup()
        groups.removeAll { it.id == next.id }
        if (FamilyStore.deviceId in next.members) { groups.add(next); tombstones.remove(next.id) }
        else tombstones[next.id] = next
        save()
    }
    @Synchronized fun validateSender(sender: String, data: JSONObject): FamilyGroup {
        val group = requireNotNull(find(data.getString("groupId"))) { "Group invitation not received yet" }
        require(FamilyStore.deviceId in group.members && group.membership.canSend(sender, data.getLong("revision"))) {
            "Not a current group member"
        }
        return group
    }
    @Synchronized fun changeMembers(group: FamilyGroup, members: Set<String>) {
        require(group.owner == FamilyStore.deviceId && group.membership.canUpdate(group.owner, group.owner, members, group.revision + 1))
        require(members.all { it == FamilyStore.deviceId || FamilyStore.find(it) != null })
        val next = group.copy(members = members, revision = group.revision + 1)
        if (GroupCallController.session?.groupId == group.id) GroupCallController.hangup()
        groups[groups.indexOfFirst { it.id == group.id }] = next
        scheduleUpdate(next, group.members + members); save()
        scope.launch { flush() }
    }
    suspend fun leave(group: FamilyGroup) {
        require(group.owner != FamilyStore.deviceId) { "The creator manages membership; remove other members instead" }
        val owner = requireNotNull(FamilyStore.find(group.owner))
        Tailnet.send(owner, "group_leave", JSONObject().put("groupId", group.id))
        val latest = Tailnet.send(owner, "group_snapshot", JSONObject().put("groupId", group.id))
        withContext(Dispatchers.Main) { receiveUpdate(owner.id, latest) }
    }
    fun receiveLeave(sender: String, data: JSONObject) {
        val group = requireNotNull(find(data.getString("groupId")))
        require(group.owner == FamilyStore.deviceId && sender in group.members && sender != group.owner)
        changeMembers(group, group.members - sender)
    }
    @Synchronized private fun scheduleUpdate(group: FamilyGroup, targets: Set<String>) {
        val all = targets.toMutableSet()
        updates.filter { it.getString("groupId") == group.id }.forEach { item ->
            val ids = item.getJSONArray("remaining")
            for (i in 0 until ids.length()) all.add(ids.getString(i))
        }
        updates.removeAll { it.getString("groupId") == group.id }
        updates.add(group.json().put("remaining", JSONArray((all - FamilyStore.deviceId).toList())))
    }
    @Synchronized fun queue(group: FamilyGroup, text: String, attachment: AttachmentTransport.Info? = null) {
        require(FamilyStore.deviceId in group.members && pending.size < 500)
        val id = attachment?.id ?: UUID.randomUUID().toString()
        val body = attachment?.let { AttachmentTransport.message(it.id, it.name, it.mime, it.size) } ?: text.trim().take(6000)
        require(body.isNotEmpty())
        FamilyStore.addChat(ChatItem(id, group.id, true, body, System.currentTimeMillis(), false, FamilyStore.deviceId))
        pending.add(JSONObject().put("groupId", group.id).put("messageId", id).put("text", body)
            .put("remaining", JSONArray(group.members.filter { it != FamilyStore.deviceId })))
        deliveryLabels[id] = "Pending ${group.members.size - 1} recipient(s) · retries automatically"
        save(); scope.launch { flush() }
    }
    suspend fun sendMedia(group: FamilyGroup, uris: List<Uri>) = withContext(Dispatchers.IO) {
        require(uris.size in 1..AttachmentTransport.MAX_BATCH)
        uris.forEach { uri ->
            val info = AttachmentTransport.prepare(context, uri)
            withContext(Dispatchers.Main) { queue(group, "", info) }
        }
    }
    suspend fun flush() = flushMutex.withLock {
        if (!EmbeddedTailnet.status.connected) return@withLock
        for (update in synchronized(this) { updates.toList() }) {
            val array = update.getJSONArray("remaining")
            val remaining = (0 until array.length()).map { array.getString(it) }.toMutableSet()
            for (id in remaining.toList()) {
                val peer = FamilyStore.find(id) ?: continue
                try {
                    require(Tailnet.send(peer, "presence").optInt("protocol", 1) >= 4) { "Group recipient needs an update" }
                    Tailnet.send(peer, "group_update", update); remaining.remove(id)
                }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { }
            }
            synchronized(this) {
                update.put("remaining", JSONArray(remaining.toList()))
                if (remaining.isEmpty()) updates.remove(update)
                save()
            }
        }
        val snapshot = synchronized(this) { pending.toList() }
        for (item in snapshot) {
            var group = find(item.getString("groupId")) ?: continue
            if (group.owner != FamilyStore.deviceId) {
                try {
                    val owner = requireNotNull(FamilyStore.find(group.owner))
                    val latest = Tailnet.send(owner, "group_snapshot", JSONObject().put("groupId", group.id))
                    withContext(Dispatchers.Main) { receiveUpdate(owner.id, latest) }
                    group = requireNotNull(find(group.id))
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { continue }
            }
            if (FamilyStore.deviceId !in group.members) continue
            val remaining = synchronized(this) {
                val array = item.getJSONArray("remaining")
                (0 until array.length()).map { array.getString(it) }.filter { it in group.members }.toMutableSet()
            }
            for (id in remaining.toList()) {
                val peer = FamilyStore.find(id) ?: continue
                try {
                    val capabilities = Tailnet.send(peer, "presence")
                    require(capabilities.optInt("protocol", 1) >= 4) { "Group recipient needs a Family Touch update" }
                    if (group.owner == FamilyStore.deviceId) Tailnet.send(peer, "group_update", group.json())
                    val attachment = AttachmentTransport.parse(item.getString("text"))
                    if (attachment != null) AttachmentTransport.sendPrepared(context, peer, attachment, group)
                    else Tailnet.send(peer, "group_message", group.json().put("messageId", item.getString("messageId"))
                        .put("text", item.getString("text")))
                    remaining.remove(id)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Keep this recipient pending, not falsely delivered. */ }
            }
            withContext(Dispatchers.Main) { synchronized(this@GroupStore) {
                item.put("remaining", JSONArray(remaining.toList()))
                deliveryLabels[item.getString("messageId")] = "Pending ${remaining.size} recipient(s) · retries automatically"
                if (remaining.isEmpty()) { pending.remove(item); deliveryLabels.remove(item.getString("messageId")); FamilyStore.delivered(item.getString("messageId")) }
                save()
            } }
        }
    }
    @Synchronized fun delivery(messageId: String): String? {
        return deliveryLabels[messageId]
    }
}
