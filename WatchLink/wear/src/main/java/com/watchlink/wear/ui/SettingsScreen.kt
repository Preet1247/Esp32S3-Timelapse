package com.watchlink.wear.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Watch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.watchlink.wear.PhoneMessenger
import com.watchlink.wear.WatchSettingsStore

private const val SET_ACTIVE_PERMISSION = "com.google.wear.permission.SET_PUSHED_WATCH_FACE_AS_ACTIVE"

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val localVibrate by WatchSettingsStore.localVibrate.collectAsState()
    val phoneSettings by WatchSettingsStore.settings.collectAsState()
    val connected by PhoneMessenger.connected.collectAsState()
    val phoneName by PhoneMessenger.phoneName.collectAsState()

    var syncRequested by remember { mutableStateOf(false) }
    var faceGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, SET_ACTIVE_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val faceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> faceGranted = granted }

    @Suppress("DEPRECATION")
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "?"
    }

    ScreenList {
        item { ListHeader { Text("Settings") } }
        item {
            Chip(
                onClick = { WatchSettingsStore.setLocalVibrate(!localVibrate) },
                modifier = Modifier.fillMaxWidth(),
                colors = ChipDefaults.secondaryChipColors(),
                icon = { Icon(Icons.Default.Vibration, contentDescription = null, modifier = Modifier.size(20.dp)) },
                label = { Text("Vibrate on alerts") },
                secondaryLabel = {
                    Text(
                        when {
                            !phoneSettings.vibrate -> "Off (phone setting)"
                            localVibrate -> "On"
                            else -> "Off"
                        },
                    )
                },
            )
        }
        item {
            Chip(
                onClick = {
                    PhoneMessenger.requestSync(context)
                    syncRequested = true
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ChipDefaults.secondaryChipColors(),
                icon = { Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(20.dp)) },
                label = { Text("Sync now") },
                secondaryLabel = if (syncRequested) {
                    @androidx.compose.runtime.Composable { Text("Requested") }
                } else {
                    null
                },
            )
        }
        item {
            Chip(
                onClick = {
                    if (!faceGranted) faceLauncher.launch(SET_ACTIVE_PERMISSION)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ChipDefaults.secondaryChipColors(),
                icon = {
                    Icon(
                        imageVector = if (faceGranted) Icons.Default.Check else Icons.Default.Watch,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                label = { Text("Allow setting watch faces active", maxLines = 2) },
                secondaryLabel = { Text(if (faceGranted) "Allowed" else "Tap to allow") },
            )
        }
        item {
            Text(
                text = if (connected) "Phone: ${phoneName ?: "connected"}" else "Phone: not connected",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Text(
                text = "WatchLink $version",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
