package com.watchlink.mobile

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.watchlink.shared.DEFAULT_QUICK_REPLIES
import com.watchlink.shared.NotifAction
import com.watchlink.shared.NotificationPayload
import com.watchlink.shared.Paths
import com.watchlink.shared.WireJson
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class WatchStatus(
    val checking: Boolean = true,
    val connected: List<String> = emptyList(),
    val withWatchApp: List<String> = emptyList(),
)

data class AppEntry(val label: String, val packageName: String, val icon: ImageBitmap?)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app: Application = application
    private val pusher = WatchFacePusher(application)

    /** Bumped on every resume so the UI re-evaluates permission state. */
    val tick = MutableStateFlow(0)
    val watchStatus = MutableStateFlow(WatchStatus())
    val pingText = MutableStateFlow<String?>(null)
    val testText = MutableStateFlow<String?>(null)

    val apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val appsLoading = MutableStateFlow(true)
    val muted = MutableStateFlow(WatchLinkPrefs.mutedPackages(application))

    val vibrate = MutableStateFlow(WatchLinkPrefs.vibrate(application))
    val quickReplies = MutableStateFlow(WatchLinkPrefs.quickReplies(application))

    val apkUri = MutableStateFlow<Uri?>(null)
    val apkName = MutableStateFlow<String?>(null)
    val token = MutableStateFlow("")
    val pushing = MutableStateFlow(false)
    val sampleAvailable = MutableStateFlow(false)
    val faceStatus = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            sampleAvailable.value = pusher.sampleAvailable()
            loadApps()
        }
        refresh()
    }

    // ---- status -------------------------------------------------------------------------

    fun refresh() {
        tick.value = tick.value + 1
        viewModelScope.launch {
            watchStatus.value = watchStatus.value.copy(checking = true)
            val connected = WearMessenger.connectedNodes(app)
            val withApp = WearMessenger.capabilityNodes(app)
            watchStatus.value = WatchStatus(
                checking = false,
                connected = connected.map { it.displayName },
                withWatchApp = withApp.map { it.displayName },
            )
        }
    }

    fun sendTestNotification() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val payload = NotificationPayload(
                key = "watchlink-test-$now",
                packageName = app.packageName,
                appName = "WatchLink",
                title = "Test notification",
                text = "If you can read this on your watch, the link works.",
                postTime = now,
                category = "msg",
                actions = listOf(
                    NotifAction(0, "Reply", isReply = true, choices = listOf("OK", "Thanks!")),
                ),
            )
            val sent = WearMessenger.sendToWatches(app, Paths.NOTIF_POSTED, WireJson.encode(payload))
            testText.value = if (sent > 0) "Sent to $sent watch(es)" else "No reachable watch found"
        }
    }

    fun ping() {
        viewModelScope.launch {
            pingText.value = "Pinging..."
            val result: String = coroutineScope {
                val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeoutOrNull(5000L) { LinkEvents.pongs.first() }
                }
                val t0 = SystemClock.elapsedRealtime()
                val sent = WearMessenger.sendToWatches(app, Paths.PING)
                if (sent == 0) {
                    waiter.cancel()
                    "No reachable watch found"
                } else {
                    val got = waiter.await()
                    if (got == null) "No reply within 5 s" else "Round-trip ${got - t0} ms"
                }
            }
            pingText.value = result
        }
    }

    // ---- apps ---------------------------------------------------------------------------

    private suspend fun loadApps() = withContext(Dispatchers.IO) {
        try {
            val pm = app.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                pm.queryIntentActivities(intent, 0)
            }
            val list = infos
                .map { it.activityInfo.packageName to it }
                .distinctBy { it.first }
                .filter { it.first != app.packageName }
                .map { (pkg, ri) ->
                    val icon = try {
                        ri.loadIcon(pm).toBitmap(72, 72).asImageBitmap()
                    } catch (e: Exception) {
                        null
                    }
                    AppEntry(ri.loadLabel(pm).toString(), pkg, icon)
                }
                .sortedBy { it.label.lowercase() }
            apps.value = list
        } finally {
            appsLoading.value = false
        }
    }

    fun setForwarding(packageName: String, enabled: Boolean) {
        WatchLinkPrefs.setMuted(app, packageName, !enabled)
        muted.value = WatchLinkPrefs.mutedPackages(app)
    }

    // ---- settings -----------------------------------------------------------------------

    private fun pushSettings() {
        viewModelScope.launch { WearMessenger.sendSettings(app) }
    }

    fun setVibrate(value: Boolean) {
        WatchLinkPrefs.setVibrate(app, value)
        vibrate.value = value
        pushSettings()
    }

    private fun saveReplies(list: List<String>) {
        WatchLinkPrefs.setQuickReplies(app, list)
        quickReplies.value = list
        pushSettings()
    }

    fun addQuickReply(text: String) {
        val t = text.trim()
        if (t.isEmpty() || quickReplies.value.contains(t)) return
        saveReplies(quickReplies.value + t)
    }

    fun removeQuickReply(text: String) = saveReplies(quickReplies.value - text)

    fun resetQuickReplies() = saveReplies(DEFAULT_QUICK_REPLIES)

    // ---- watch faces --------------------------------------------------------------------

    fun onApkPicked(uri: Uri) {
        apkUri.value = uri
        apkName.value = WatchFacePusher.displayName(app, uri)
    }

    fun onTokenFilePicked(uri: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    app.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8).trim() }
                } catch (e: Exception) {
                    null
                }
            }
            if (text != null) token.value = text else faceStatus.value = "Could not read token file"
        }
    }

    fun pushSelected() {
        val uri = apkUri.value ?: return
        val name = apkName.value ?: "watchface.apk"
        runPush { pusher.pushFromUri(uri, name, token.value) }
    }

    fun pushSample() = runPush { pusher.pushSample() }

    private fun runPush(block: suspend () -> Boolean) {
        if (pushing.value) return
        viewModelScope.launch {
            pushing.value = true
            faceStatus.value = null
            try {
                block()
            } finally {
                pushing.value = false
            }
        }
    }

    fun refreshFaces() {
        viewModelScope.launch {
            val sent = pusher.requestList()
            faceStatus.value = if (sent) "Requested list from watch" else "No reachable watch found"
        }
    }
}
