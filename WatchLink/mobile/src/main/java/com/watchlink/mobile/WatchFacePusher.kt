package com.watchlink.mobile

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.google.android.gms.wearable.Wearable
import com.watchlink.shared.Paths
import com.watchlink.shared.WatchFaceTransfer
import com.watchlink.shared.WireJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import java.util.UUID
import com.watchlink.mobile.WatchFaceRepository.Stage
import com.watchlink.mobile.WatchFaceRepository.Transfer

/** Phone side of Watch Face Push: meta message, then APK bytes over ChannelClient. */
class WatchFacePusher(context: Context) {
    private val app = context.applicationContext

    fun sampleAvailable(): Boolean = try {
        val names = app.assets.list(ASSET_DIR)?.toList().orEmpty()
        names.contains(SAMPLE_APK) && names.contains(SAMPLE_TOKEN)
    } catch (e: Exception) {
        false
    }

    suspend fun pushSample(): Boolean {
        val (bytes, token) = withContext(Dispatchers.IO) {
            try {
                val apk = app.assets.open("$ASSET_DIR/$SAMPLE_APK").use { it.readBytes() }
                val tok = app.assets.open("$ASSET_DIR/$SAMPLE_TOKEN").use { it.readBytes() }
                    .toString(Charsets.UTF_8).trim()
                Pair(apk, tok)
            } catch (e: Exception) {
                Pair(null, "")
            }
        }
        if (bytes == null) {
            fail("sample", SAMPLE_APK, "Sample watch face is not bundled in this build")
            return false
        }
        return push(bytes, SAMPLE_APK, token)
    }

    suspend fun pushFromUri(uri: Uri, fileName: String, token: String): Boolean {
        val bytes = withContext(Dispatchers.IO) {
            try {
                app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                null
            }
        }
        if (bytes == null) {
            fail("local", fileName, "Could not read the selected file")
            return false
        }
        return push(bytes, fileName, token)
    }

    private fun fail(id: String, fileName: String, message: String) {
        WatchFaceRepository.setTransfer(Transfer(id, fileName, 0, 0, Stage.FAILED, message))
    }

    suspend fun push(bytes: ByteArray, fileName: String, token: String): Boolean {
        val transferId = UUID.randomUUID().toString()
        val total = bytes.size.toLong()
        if (token.isBlank()) {
            fail(transferId, fileName, "Validation token is empty")
            return false
        }
        WatchFaceRepository.setTransfer(Transfer(transferId, fileName, 0, total, Stage.PREPARING))
        try {
            val nodes = WearMessenger.watchNodes(app)
            val node = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
            if (node == null) {
                fail(transferId, fileName, "No watch connected")
                return false
            }

            val meta = WatchFaceTransfer(
                transferId = transferId,
                fileName = fileName,
                sizeBytes = total,
                validationToken = token.trim(),
            )
            Wearable.getMessageClient(app)
                .sendMessage(node.id, Paths.WATCHFACE_TRANSFER_META, WireJson.encode(meta))
                .await()

            val channelClient = Wearable.getChannelClient(app)
            val channel = channelClient.openChannel(node.id, "${Paths.WATCHFACE_CHANNEL}/$transferId").await()
            WatchFaceRepository.updateTransfer { it.copy(stage = Stage.SENDING) }
            try {
                val out = channelClient.getOutputStream(channel).await()
                withContext(Dispatchers.IO) {
                    out.use { stream ->
                        var offset = 0
                        while (offset < bytes.size) {
                            val len = minOf(CHUNK, bytes.size - offset)
                            stream.write(bytes, offset, len)
                            offset += len
                            val sent = offset.toLong()
                            WatchFaceRepository.updateTransfer { it.copy(sentBytes = sent) }
                        }
                        stream.flush()
                    }
                }
            } finally {
                try {
                    channelClient.close(channel).await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "channel close failed", e)
                }
            }
            WatchFaceRepository.updateTransfer { it.copy(stage = Stage.WAITING_FOR_WATCH) }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "push failed", e)
            fail(transferId, fileName, "Transfer failed: ${e.message}")
            return false
        }
    }

    suspend fun requestList(): Boolean {
        val sent = WearMessenger.sendToWatches(app, Paths.WATCHFACE_LIST)
        return sent > 0
    }

    companion object {
        private const val TAG = "WatchFacePusher"
        private const val CHUNK = 8 * 1024
        const val ASSET_DIR = "watchfaces"
        const val SAMPLE_APK = "sample.apk"
        const val SAMPLE_TOKEN = "sample.token"

        fun displayName(context: Context, uri: Uri): String {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c ->
                        if (c.moveToFirst()) {
                            val n = c.getString(0)
                            if (!n.isNullOrBlank()) return n
                        }
                    }
            } catch (e: Exception) {
                // ignore
            }
            return uri.lastPathSegment ?: "watchface.apk"
        }
    }
}
