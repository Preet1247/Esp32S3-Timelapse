package com.watchlink.mobile.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.watchlink.mobile.MainViewModel
import com.watchlink.mobile.WatchFaceRepository

@Composable
fun WatchFacesScreen(vm: MainViewModel) {
    val apkName by vm.apkName.collectAsState()
    val token by vm.token.collectAsState()
    val pushing by vm.pushing.collectAsState()
    val sampleAvailable by vm.sampleAvailable.collectAsState()
    val status by vm.faceStatus.collectAsState()
    val transfer by WatchFaceRepository.transfer.collectAsState()
    val result by WatchFaceRepository.lastResult.collectAsState()
    val faces by WatchFaceRepository.faceList.collectAsState()

    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.onApkPicked(uri)
    }
    val tokenPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.onTokenFilePicked(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Watch faces", style = MaterialTheme.typography.headlineMedium)

        SectionCard("Push a watch face") {
            Text(
                "Select a watch face APK and its validation token (from Google's Watch Face Push validator).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { apkPicker.launch(arrayOf("application/vnd.android.package-archive", "*/*")) }) {
                Text(apkName ?: "Pick watch face APK")
            }
            OutlinedTextField(
                value = token,
                onValueChange = { vm.token.value = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Validation token") },
                minLines = 2,
                maxLines = 4,
            )
            OutlinedButton(onClick = { tokenPicker.launch(arrayOf("text/plain", "*/*")) }) {
                Text("Pick token .txt file")
            }
            Button(
                onClick = { vm.pushSelected() },
                enabled = !pushing && apkName != null && token.isNotBlank(),
            ) { Text("Push to watch") }
            if (sampleAvailable) {
                FilledTonalButton(onClick = { vm.pushSample() }, enabled = !pushing) {
                    Text("Install sample watch face")
                }
            }

            transfer?.let { t ->
                Text(
                    "${t.fileName}: ${stageLabel(t.stage)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                when (t.stage) {
                    WatchFaceRepository.Stage.SENDING -> {
                        val fraction = if (t.totalBytes > 0) t.sentBytes.toFloat() / t.totalBytes else 0f
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        Text("${t.sentBytes / 1024} / ${t.totalBytes / 1024} KB", style = MaterialTheme.typography.bodySmall)
                    }
                    WatchFaceRepository.Stage.PREPARING,
                    WatchFaceRepository.Stage.WAITING_FOR_WATCH,
                    -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    else -> Unit
                }
                t.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            result?.let { r ->
                Text(
                    (if (r.ok) "Watch: OK - " else "Watch: failed - ") + r.message +
                        (r.slotId?.let { " (slot $it)" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (r.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        SectionCard("Installed on watch") {
            OutlinedButton(onClick = { vm.refreshFaces() }) { Text("Refresh installed faces") }
            val list = faces
            when {
                list == null -> Text("No data yet", style = MaterialTheme.typography.bodySmall)
                !list.supported -> Text("Watch Face Push requires Wear OS 6", style = MaterialTheme.typography.bodyMedium)
                else -> {
                    Text("Free slots: ${list.freeSlots}", style = MaterialTheme.typography.bodySmall)
                    if (list.faces.isEmpty()) {
                        Text("No pushed watch faces", style = MaterialTheme.typography.bodyMedium)
                    }
                    list.faces.forEach { f ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Column {
                                Text(f.packageName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "slot ${f.slotId}, version ${f.versionCode}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun stageLabel(stage: WatchFaceRepository.Stage): String = when (stage) {
    WatchFaceRepository.Stage.PREPARING -> "preparing"
    WatchFaceRepository.Stage.SENDING -> "sending"
    WatchFaceRepository.Stage.WAITING_FOR_WATCH -> "waiting for watch"
    WatchFaceRepository.Stage.DONE -> "done"
    WatchFaceRepository.Stage.FAILED -> "failed"
}
