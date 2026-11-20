package com.watchlink.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.watchlink.mobile.MainViewModel

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val vibrate by vm.vibrate.collectAsState()
    val replies by vm.quickReplies.collectAsState()
    var newReply by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        SectionCard("Watch behaviour") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Vibrate on notifications", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Sent to the watch whenever it changes or connects.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = vibrate, onCheckedChange = { vm.setVibrate(it) })
            }
        }

        SectionCard("Quick replies") {
            if (replies.isEmpty()) {
                Text("No quick replies", style = MaterialTheme.typography.bodyMedium)
            }
            replies.forEachIndexed { index, reply ->
                if (index > 0) HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(reply, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = { vm.removeQuickReply(reply) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove")
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newReply,
                    onValueChange = { newReply = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("New quick reply") },
                )
                IconButton(
                    onClick = {
                        vm.addQuickReply(newReply)
                        newReply = ""
                    },
                    enabled = newReply.isNotBlank(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add")
                }
            }
            OutlinedButton(onClick = { vm.resetQuickReplies() }) { Text("Reset to defaults") }
        }
    }
}
