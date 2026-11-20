package com.watchlink.mobile.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.watchlink.mobile.MainViewModel
import com.watchlink.mobile.WatchLinkNotificationService

private fun hasPermission(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
fun StatusScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val status by vm.watchStatus.collectAsState()
    val ping by vm.pingText.collectAsState()
    val testResult by vm.testText.collectAsState()
    val tick by vm.tick.collectAsState()

    val runtimePerms = remember {
        buildList<Pair<String, String>> {
            add(Manifest.permission.READ_PHONE_STATE to "Phone state")
            add(Manifest.permission.READ_CALL_LOG to "Call log (caller number)")
            add(Manifest.permission.READ_CONTACTS to "Contacts (caller name and photo)")
            add(Manifest.permission.ANSWER_PHONE_CALLS to "Answer / decline calls")
            add(Manifest.permission.SEND_SMS to "Send SMS (reply to caller)")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS to "Show notifications")
            }
        }
    }
    val granted = remember(tick) { runtimePerms.associate { it.first to hasPermission(context, it.first) } }
    val listenerEnabled = remember(tick) {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
    val batteryOk = remember(tick) {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refresh()
        WatchLinkNotificationService.instance?.registerCallMonitor()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("WatchLink", style = MaterialTheme.typography.headlineMedium)

        SectionCard("Watch connection") {
            when {
                status.checking -> Text("Checking...")
                status.connected.isEmpty() -> StatusRow("No watch connected", ok = false)
                else -> status.connected.forEach { StatusRow("Connected: $it", ok = true) }
            }
            if (!status.checking && status.connected.isNotEmpty()) {
                if (status.withWatchApp.isEmpty()) {
                    StatusRow("WatchLink is not running on the watch", ok = false)
                } else {
                    StatusRow("WatchLink watch app: ${status.withWatchApp.joinToString()}", ok = true)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.refresh() }) { Text("Refresh") }
                FilledTonalButton(onClick = { vm.sendTestNotification() }) { Text("Send test notification") }
                FilledTonalButton(onClick = { vm.ping() }) { Text("Ping") }
            }
            testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            ping?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        SectionCard("Permissions") {
            StatusRow("Notification access", listenerEnabled) {
                if (!listenerEnabled) {
                    TextButton(onClick = {
                        context.safeStartActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }) { Text("Open") }
                }
            }
            runtimePerms.forEach { (perm, label) ->
                StatusRow(label, granted[perm] == true)
            }
            val missing = runtimePerms.map { it.first }.filter { granted[it] != true }
            if (missing.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { permissionLauncher.launch(missing.toTypedArray()) }) {
                        Text("Grant permissions")
                    }
                    OutlinedButton(onClick = {
                        context.safeStartActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                        )
                    }) { Text("App settings") }
                }
            }
            StatusRow("Battery optimisation exemption", batteryOk) {
                if (!batteryOk) {
                    TextButton(onClick = {
                        context.safeStartActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }) { Text("Allow") }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Text(
                "Tip: In Galaxy Wearable › Watch settings › Notifications, turn off mirroring for apps " +
                    "you route through WatchLink to avoid duplicates.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
