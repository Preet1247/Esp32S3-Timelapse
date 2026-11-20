package com.watchlink.wearfacepush

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.wear.watchfacepush.WatchFacePushManager
import androidx.wear.watchfacepush.WatchFacePushManagerFactory
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.watchlink.shared.Paths
import com.watchlink.shared.WatchFaceInfo
import com.watchlink.shared.WatchFaceList
import com.watchlink.shared.WatchFaceResult
import com.watchlink.shared.WatchFaceTransfer
import com.watchlink.shared.WireJson
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Watch-side bridge between the Data Layer (phone) and the Watch Face Push API.
 *
 * Call [onMessage] / [onChannelOpened] from the WearableListenerService in :wear.
 * Both return true when the event belonged to watch-face handling and was consumed.
 */
object WatchFacePushHandler {
    private const val TAG = "WatchFacePushHandler"
    private const val PREFS = "wearfacepush_meta"
    private const val SET_ACTIVE_PERMISSION = "com.google.wear.permission.SET_PUSHED_WATCH_FACE_AS_ACTIVE"
    private const val META_WAIT_MS = 5_000L

    private val transfers = ConcurrentHashMap<String, WatchFaceTransfer>()

    suspend fun onMessage(context: Context, event: MessageEvent): Boolean {
        val app = context.applicationContext
        return when (event.path) {
            Paths.WATCHFACE_TRANSFER_META -> {
                try {
                    val meta = WireJson.decode<WatchFaceTransfer>(event.data)
                    storeMeta(app, meta)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "Bad transfer meta", e)
                }
                true
            }
            Paths.WATCHFACE_LIST -> {
                val list = try {
                    buildList(app)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "List failed", e)
                    WatchFaceList(emptyList(), supported = false, freeSlots = 0)
                }
                send(app, event.sourceNodeId, Paths.WATCHFACE_LIST_RESULT, WireJson.encode(list))
                true
            }
            else -> false
        }
    }

    suspend fun onChannelOpened(context: Context, channel: ChannelClient.Channel): Boolean {
        val prefix = Paths.WATCHFACE_CHANNEL + "/"
        val path = channel.path
        if (!path.startsWith(prefix)) return false
        val app = context.applicationContext
        val transferId = path.removePrefix(prefix)
        val nodeId = channel.nodeId
        val result = try {
            handleTransfer(app, channel, transferId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Transfer $transferId failed", e)
            WatchFaceResult(transferId, false, "Watch face install failed: ${describe(e)}")
        }
        removeMeta(app, transferId)
        send(app, nodeId, Paths.WATCHFACE_RESULT, WireJson.encode(result))
        return true
    }

    // ---------------------------------------------------------------- transfer

    private suspend fun handleTransfer(
        context: Context,
        channel: ChannelClient.Channel,
        transferId: String,
    ): WatchFaceResult {
        val channelClient = Wearable.getChannelClient(context)
        val file = File(context.cacheDir, "wf_${transferId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }}.apk")
        try {
            // 1. Receive the APK bytes.
            try {
                withContext(Dispatchers.IO) {
                    channelClient.getInputStream(channel).await().use { input ->
                        file.outputStream().use { out -> input.copyTo(out) }
                    }
                }
            } finally {
                runCatching { channelClient.close(channel).await() }
            }

            val meta = awaitMeta(context, transferId)
                ?: return WatchFaceResult(transferId, false, "Received APK but no transfer metadata (token) arrived")

            if (meta.sizeBytes > 0 && file.length() != meta.sizeBytes) {
                return WatchFaceResult(
                    transferId, false,
                    "Incomplete transfer: got ${file.length()} of ${meta.sizeBytes} bytes",
                )
            }
            if (!WatchFacePushManagerFactory.isSupported()) {
                return WatchFaceResult(transferId, false, "Watch Face Push is not supported on this watch (needs Wear OS 6 / API 36)")
            }
            if (meta.validationToken.isBlank() || meta.validationToken == "VALIDATION_FAILED") {
                return WatchFaceResult(transferId, false, "No valid validation token for this watch face")
            }

            // 2. Install or update.
            val manager = WatchFacePushManagerFactory.createWatchFacePushManager(context)
            val packageName = readPackageName(context, file)
            val existing = packageName?.let { pkg ->
                manager.listWatchFaces().installedWatchFaceDetails.firstOrNull { it.packageName == pkg }
            }

            val details = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                if (existing != null) {
                    manager.updateWatchFace(existing.slotId, fd, meta.validationToken)
                } else {
                    manager.addWatchFace(fd, meta.validationToken)
                }
            }
            val verb = if (existing != null) "updated" else "installed"

            // 3. Optionally activate.
            if (!meta.setActive) {
                return WatchFaceResult(transferId, true, "Watch face $verb. Pick it from the watch face picker.", details.slotId)
            }
            val granted = ContextCompat.checkSelfPermission(context, SET_ACTIVE_PERMISSION) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                return WatchFaceResult(
                    transferId, true,
                    "Watch face $verb. Permission to activate it is not granted - set it from the watch face picker.",
                    details.slotId,
                )
            }
            return try {
                manager.setWatchFaceAsActive(details.slotId)
                WatchFaceResult(transferId, true, "Watch face $verb and set as active.", details.slotId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "setWatchFaceAsActive failed", e)
                WatchFaceResult(
                    transferId, true,
                    "Watch face $verb, but could not activate it (${describe(e)}). Set it from the watch face picker.",
                    details.slotId,
                )
            }
        } finally {
            file.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun readPackageName(context: Context, apk: File): String? = try {
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName
    } catch (e: Throwable) {
        Log.w(TAG, "Could not read APK package name", e)
        null
    }

    private suspend fun buildList(context: Context): WatchFaceList {
        if (!WatchFacePushManagerFactory.isSupported()) {
            return WatchFaceList(emptyList(), supported = false, freeSlots = 0)
        }
        val manager: WatchFacePushManager = WatchFacePushManagerFactory.createWatchFacePushManager(context)
        val response = manager.listWatchFaces()
        return WatchFaceList(
            faces = response.installedWatchFaceDetails.map { WatchFaceInfo(it.slotId, it.packageName, it.versionCode) },
            supported = true,
            freeSlots = response.remainingSlotCount,
        )
    }

    // ---------------------------------------------------------------- metadata store

    private fun storeMeta(context: Context, meta: WatchFaceTransfer) {
        transfers[meta.transferId] = meta
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(meta.transferId, String(WireJson.encode(meta), Charsets.UTF_8))
                .apply()
        }
    }

    private fun loadMeta(context: Context, transferId: String): WatchFaceTransfer? {
        transfers[transferId]?.let { return it }
        return runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(transferId, null)
                ?.let { WireJson.decode<WatchFaceTransfer>(it.toByteArray(Charsets.UTF_8)) }
        }.getOrNull()
    }

    private fun removeMeta(context: Context, transferId: String) {
        transfers.remove(transferId)
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(transferId).apply() }
    }

    /** The metadata message is sent first but delivered independently; wait briefly for it. */
    private suspend fun awaitMeta(context: Context, transferId: String): WatchFaceTransfer? {
        var waited = 0L
        while (true) {
            loadMeta(context, transferId)?.let { return it }
            if (waited >= META_WAIT_MS) return null
            delay(250)
            waited += 250
        }
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun send(context: Context, nodeId: String, path: String, data: ByteArray) {
        try {
            Wearable.getMessageClient(context).sendMessage(nodeId, path, data).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to send $path to $nodeId", e)
        }
    }

    private fun describe(e: Throwable): String {
        val msg = e.message?.takeIf { it.isNotBlank() }
        return if (msg != null) "${e.javaClass.simpleName}: $msg" else e.javaClass.simpleName
    }
}
