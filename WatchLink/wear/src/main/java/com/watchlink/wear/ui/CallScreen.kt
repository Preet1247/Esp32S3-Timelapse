package com.watchlink.wear.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Sms
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.watchlink.shared.CallAction
import com.watchlink.shared.CallCommand
import com.watchlink.shared.CallPhase
import com.watchlink.shared.CallState
import com.watchlink.shared.Paths
import com.watchlink.wear.CallStore
import com.watchlink.wear.CallVibrator
import com.watchlink.wear.PhoneMessenger
import com.watchlink.wear.WatchSettingsStore
import kotlinx.coroutines.delay

private val AnswerGreen = Color(0xFF2E7D32)
private val DeclineRed = Color(0xFFC62828)

private fun sendCall(context: Context, action: CallAction, sms: String? = null) {
    PhoneMessenger.post(context, Paths.CALL_COMMAND, CallCommand(action, sms))
}

@Composable
fun CallScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    val call by CallStore.state.collectAsState()
    val settings by WatchSettingsStore.settings.collectAsState()

    LaunchedEffect(call.phase) {
        if (call.phase == CallPhase.IDLE) onFinish()
    }

    var showReplies by remember { mutableStateOf(false) }
    var smsSent by remember { mutableStateOf(false) }
    val photo = rememberBase64Image(call.callerPhotoPng)

    ScreenList {
        item {
            CallerHeader(call = call, photo = photo)
        }
        when (call.phase) {
            CallPhase.RINGING -> {
                if (showReplies) {
                    settings.quickReplies.filter { it.isNotBlank() }.forEach { reply ->
                        item {
                            Chip(
                                onClick = {
                                    CallVibrator.stop(context)
                                    sendCall(context, CallAction.REPLY_SMS, reply)
                                    smsSent = true
                                    showReplies = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ChipDefaults.secondaryChipColors(),
                                label = { Text(reply, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                    item {
                        Chip(
                            onClick = { showReplies = false },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ChipDefaults.primaryChipColors(),
                            label = { Text("Back") },
                        )
                    }
                } else {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RoundCallButton(
                                color = DeclineRed,
                                label = "Decline",
                                icon = { Icon(Icons.Default.CallEnd, contentDescription = "Decline") },
                                onClick = {
                                    CallVibrator.stop(context)
                                    sendCall(context, CallAction.DECLINE)
                                },
                            )
                            RoundCallButton(
                                color = AnswerGreen,
                                label = "Answer",
                                icon = { Icon(Icons.Default.Call, contentDescription = "Answer") },
                                onClick = {
                                    CallVibrator.stop(context)
                                    sendCall(context, CallAction.ANSWER)
                                },
                            )
                        }
                    }
                    item {
                        Chip(
                            onClick = {
                                CallVibrator.stop(context)
                                sendCall(context, CallAction.SILENCE)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ChipDefaults.secondaryChipColors(),
                            icon = { Icon(Icons.AutoMirrored.Filled.VolumeOff, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            label = { Text("Silence") },
                        )
                    }
                    item {
                        Chip(
                            onClick = { showReplies = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ChipDefaults.secondaryChipColors(),
                            icon = { Icon(Icons.Default.Sms, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            label = { Text(if (smsSent) "Message sent ✓" else "Reply with text") },
                        )
                    }
                }
            }
            CallPhase.OFFHOOK -> {
                item { CallTimer(call) }
                item {
                    RoundCallButton(
                        color = DeclineRed,
                        label = "End call",
                        icon = { Icon(Icons.Default.CallEnd, contentDescription = "End call") },
                        onClick = { sendCall(context, CallAction.END) },
                    )
                }
            }
            CallPhase.IDLE -> Unit
        }
    }
}

@Composable
private fun CallerHeader(call: CallState, photo: androidx.compose.ui.graphics.ImageBitmap?) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (photo != null) {
            CircleImage(bitmap = photo, size = 48.dp)
        }
        val name = call.callerName?.takeIf { it.isNotBlank() } ?: call.number ?: "Unknown caller"
        Text(
            text = name,
            style = MaterialTheme.typography.title3,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!call.callerName.isNullOrBlank() && !call.number.isNullOrBlank()) {
            Text(
                text = call.number.orEmpty(),
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurfaceVariant,
            )
        }
        if (call.phase == CallPhase.RINGING) {
            Text(
                text = "Incoming call",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CallTimer(call: CallState) {
    val startedAt = remember(call.startedAt) {
        if (call.startedAt > 0) call.startedAt else System.currentTimeMillis()
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Text(
        text = formatDuration((now - startedAt) / 1000),
        style = MaterialTheme.typography.display2,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun RoundCallButton(
    color: Color,
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(56.dp),
        colors = ButtonDefaults.buttonColors(backgroundColor = color, contentColor = Color.White),
    ) {
        icon()
    }
}
