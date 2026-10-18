package win.fantest.familytouch

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun GroupEditor(group: FamilyGroup? = null, onDismiss: () -> Unit, onSaved: (FamilyGroup) -> Unit) {
    var name by remember(group?.id) { mutableStateOf(group?.title ?: "") }
    var selected by remember(group?.id) { mutableStateOf(group?.members.orEmpty() - FamilyStore.deviceId) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (group == null) "Create family group" else "Group members") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, label = { Text("Group name") }, enabled = group == null)
                Text("Select family devices. Group calls support up to four devices including you.", Modifier.padding(vertical = 12.dp))
                FamilyStore.contacts.forEach { peer ->
                    Row(Modifier.fillMaxWidth()) {
                        Checkbox(checked = peer.id in selected, onCheckedChange = {
                            selected = if (it) selected + peer.id else selected - peer.id
                        })
                        Text(peer.name, Modifier.padding(top = 14.dp))
                    }
                }
                if (error.isNotEmpty()) Text(error)
            }
        }, confirmButton = {
            Button(enabled = name.isNotBlank() && selected.size in 1..31, onClick = {
                try {
                    val saved = if (group == null) GroupStore.create(name, selected) else {
                        GroupStore.changeMembers(group, selected + FamilyStore.deviceId)
                        requireNotNull(GroupStore.find(group.id))
                    }
                    onSaved(saved)
                } catch (e: Exception) { error = e.message ?: "Unable to save group" }
            }) { Text("Save group") }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
