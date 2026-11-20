package com.watchlink.wear.ui

import android.app.Activity
import android.app.RemoteInput
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.input.RemoteInputIntentHelper
import com.watchlink.shared.Paths
import com.watchlink.shared.ReplyCommand
import com.watchlink.wear.AppScope
import com.watchlink.wear.NotificationStore
import com.watchlink.wear.PhoneMessenger
import com.watchlink.wear.ReplyStatus
import com.watchlink.wear.ReplyStatusStore
import com.watchlink.wear.WatchSettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val REPLY_RESULT_KEY = "reply"

@Composable
fun ReplyScreen(
    notificationKey: String?,
    onCancel: () -> Unit,
    onSent: () -> Unit,
) {
    val context = LocalContext.current
    val all by NotificationStore.items.collectAsState()
    val settings by WatchSettingsStore.settings.collectAsState()
    val status by ReplyStatusStore.status.collectAsState()
    val n = all.firstOrNull { it.key == notificationKey }
    val replyAction = n?.actions?.firstOrNull { it.isReply }

    var draft by rememberSaveable { mutableStateOf<String?>(null) }
    var submitted by rememberSaveable { mutableStateOf(false) }

    val inputLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val text = RemoteInput.getResultsFromIntent(data)
                ?.getCharSequence(REPLY_RESULT_KEY)
                ?.toString()
            if (!text.isNullOrBlank()) draft = text
        }
    }

    if (n == null || replyAction == null) {
        LaunchedEffect(Unit) {
            delay(400)
            onCancel()
        }
        ScreenList {
            item { Text("Reply not available", textAlign = TextAlign.Center) }
        }
        return
    }

    LaunchedEffect(Unit) {
        if (!submitted) ReplyStatusStore.reset()
    }

    fun send(text: String) {
        val key = n.key
        submitted = true
        ReplyStatusStore.sending(key)
        AppScope.scope.launch {
            val ok = PhoneMessenger.sendJson(
                context,
                Paths.NOTIF_REPLY,
                ReplyCommand(key, replyAction.index, text),
            )
            if (!ok) ReplyStatusStore.finished(key, false, "Phone not connected")
        }
    }

    if (submitted) {
        val current = status
        LaunchedEffect(Unit) {
            delay(15_000)
            ReplyStatusStore.timeout(n.key)
        }
        LaunchedEffect(current) {
            if (current is ReplyStatus.Finished && current.key == n.key && current.ok) {
                delay(1000)
                ReplyStatusStore.reset()
                onSent()
            }
        }
        ScreenList {
            when {
                current is ReplyStatus.Finished && current.key == n.key && current.ok -> {
                    item {
                        Text(
                            text = "Sent ✓",
                            style = MaterialTheme.typography.title2,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                current is ReplyStatus.Finished && current.key == n.key -> {
                    item {
                        Text(
                            text = "Failed" + (current.message?.let { ": $it" } ?: ""),
                            color = MaterialTheme.colors.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                    item {
                        Chip(
                            onClick = {
                                ReplyStatusStore.reset()
                                submitted = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ChipDefaults.primaryChipColors(),
                            label = { Text("Try again") },
                        )
                    }
                    item {
                        Chip(
                            onClick = onCancel,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ChipDefaults.secondaryChipColors(),
                            label = { Text("Cancel") },
                        )
                    }
                }
                else -> {
                    item {
                        Text(
                            text = "Sending…",
                            style = MaterialTheme.typography.title3,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        return
    }

    val pendingText = draft
    if (pendingText != null) {
        ScreenList {
            item { ListHeader { Text("Send this reply?") } }
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colors.surface, RoundedCornerShape(14.dp))
                        .padding(10.dp),
                ) {
                    Text(text = pendingText, style = MaterialTheme.typography.body1)
                }
            }
            item {
                Chip(
                    onClick = { send(pendingText) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.primaryChipColors(),
                    icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    label = { Text("Send") },
                )
            }
            item {
                Chip(
                    onClick = { draft = null },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.secondaryChipColors(),
                    icon = { Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    label = { Text("Edit / cancel") },
                )
            }
        }
        return
    }

    val quickReplies = (replyAction.choices + settings.quickReplies)
        .filter { it.isNotBlank() }
        .distinct()

    ScreenList {
        item { ListHeader { Text("Reply to ${n.title}", maxLines = 2, overflow = TextOverflow.Ellipsis) } }
        item {
            Chip(
                onClick = {
                    val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
                    val remoteInput = RemoteInput.Builder(REPLY_RESULT_KEY).setLabel("Reply").build()
                    RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
                    inputLauncher.launch(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ChipDefaults.primaryChipColors(),
                icon = { Icon(Icons.Default.Keyboard, contentDescription = null, modifier = Modifier.size(20.dp)) },
                label = { Text("Voice / keyboard / emoji", maxLines = 2) },
            )
        }
        quickReplies.forEach { reply ->
            item {
                Chip(
                    onClick = { send(reply) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.secondaryChipColors(),
                    label = { Text(reply, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}
