package com.watchlink.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.watchlink.shared.ActionCommand
import com.watchlink.shared.NotificationKey
import com.watchlink.shared.Paths
import com.watchlink.wear.NotificationActions
import com.watchlink.wear.NotificationStore
import com.watchlink.wear.PhoneMessenger
import kotlinx.coroutines.delay

@Composable
fun DetailScreen(
    notificationKey: String?,
    onReply: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val all by NotificationStore.items.collectAsState()
    val n = all.firstOrNull { it.key == notificationKey }

    if (n == null) {
        LaunchedEffect(Unit) {
            delay(400)
            onClose()
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Notification removed", textAlign = TextAlign.Center)
        }
        return
    }

    var sentActions by remember { mutableStateOf(setOf<Int>()) }
    var openedOnPhone by remember { mutableStateOf(false) }
    val appIcon = rememberBase64Image(n.appIconPng)
    val largeIcon = rememberBase64Image(n.largeIconPng)

    ScreenList {
        item {
            Text(
                text = n.appName,
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val avatar = largeIcon ?: appIcon
        if (avatar != null) {
            item { CircleImage(bitmap = avatar, size = 44.dp) }
        }
        item {
            Text(
                text = n.title,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.title3,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
        n.subText?.takeIf { it.isNotBlank() }?.let { sub ->
            item {
                Text(
                    text = sub,
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (n.isMessaging && n.messages.isNotEmpty()) {
            n.messages.forEach { message ->
                item {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                        Text(
                            text = message.sender,
                            style = MaterialTheme.typography.caption2,
                            color = MaterialTheme.colors.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
                        )
                        Box(
                            Modifier
                                .background(MaterialTheme.colors.surface, RoundedCornerShape(14.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(text = message.text, style = MaterialTheme.typography.body2)
                        }
                    }
                }
            }
        } else {
            item {
                Text(
                    text = n.text,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (n.actions.any { it.isReply }) {
            item {
                Chip(
                    onClick = onReply,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.primaryChipColors(),
                    icon = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    label = { Text("Reply") },
                )
            }
        }

        n.actions.filterNot { it.isReply }.forEach { action ->
            item {
                val sent = action.index in sentActions
                Chip(
                    onClick = {
                        PhoneMessenger.post(context, Paths.NOTIF_ACTION, ActionCommand(n.key, action.index))
                        sentActions = sentActions + action.index
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.secondaryChipColors(),
                    icon = if (sent) {
                        @Composable { Icon(Icons.Default.Done, contentDescription = null, modifier = Modifier.size(20.dp)) }
                    } else {
                        null
                    },
                    label = { Text(action.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                )
            }
        }

        item {
            Chip(
                onClick = {
                    PhoneMessenger.post(context, Paths.NOTIF_OPEN_ON_PHONE, NotificationKey(n.key))
                    openedOnPhone = true
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ChipDefaults.secondaryChipColors(),
                icon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(20.dp)) },
                label = { Text("Open on phone") },
                secondaryLabel = if (openedOnPhone) {
                    @Composable { Text("Sent") }
                } else {
                    null
                },
            )
        }
        item {
            Chip(
                onClick = { NotificationActions.dismiss(context, n.key) },
                modifier = Modifier.fillMaxWidth(),
                colors = ChipDefaults.chipColors(
                    backgroundColor = Color(0xFF5C2B29),
                    contentColor = Color(0xFFFFDAD6),
                ),
                icon = { Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(20.dp)) },
                label = { Text("Dismiss") },
            )
        }
    }
}
