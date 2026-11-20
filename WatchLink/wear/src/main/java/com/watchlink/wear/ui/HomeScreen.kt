package com.watchlink.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.AppCard
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.watchlink.shared.NotificationPayload
import com.watchlink.wear.NotificationActions
import com.watchlink.wear.NotificationStore
import com.watchlink.wear.PhoneMessenger
import kotlinx.coroutines.launch
import com.watchlink.wear.AppScope

@Composable
fun HomeScreen(
    onOpenNotification: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val notifications by NotificationStore.items.collectAsState()
    val connected by PhoneMessenger.connected.collectAsState()

    ScreenList {
        item {
            Chip(
                onClick = {
                    AppScope.scope.launch { PhoneMessenger.refreshConnection(context) }
                    PhoneMessenger.requestSync(context)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ChipDefaults.secondaryChipColors(),
                icon = {
                    Icon(
                        imageVector = if (connected) Icons.Default.Link else Icons.Default.LinkOff,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (connected) Color(0xFF66BB6A) else Color(0xFFEF5350),
                    )
                },
                label = {
                    Text(
                        text = if (connected) "Phone connected" else "Phone disconnected",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (notifications.isNotEmpty()) {
                    CompactChip(
                        onClick = { NotificationActions.clearAll(context) },
                        label = { Text("Clear all", maxLines = 1) },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
                CompactChip(
                    onClick = onOpenSettings,
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
        }

        if (notifications.isEmpty()) {
            item {
                Text(
                    text = "No notifications",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    style = MaterialTheme.typography.body1,
                )
            }
        } else {
            items(count = notifications.size, key = { notifications[it].key }) { index ->
                val notification = notifications[index]
                NotificationCard(
                    notification = notification,
                    onClick = { onOpenNotification(notification.key) },
                )
            }
        }
    }
}

@Composable
private fun NotificationCard(notification: NotificationPayload, onClick: () -> Unit) {
    val context = LocalContext.current
    val icon = rememberBase64Image(notification.appIconPng)
    val appImage: (@Composable () -> Unit)? = icon?.let { bitmap ->
        @Composable { CircleImage(bitmap = bitmap, size = 16.dp) }
    }

    AppCard(
        onClick = onClick,
        appName = {
            Text(notification.appName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        time = { Text(formatPostTime(context, notification.postTime)) },
        title = {
            Text(notification.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        modifier = Modifier.fillMaxWidth(),
        appImage = appImage,
    ) {
        Text(
            text = notification.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.body2,
        )
    }
}
