package com.watchlink.wear

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.watchlink.shared.CallPhase
import com.watchlink.shared.CallState
import com.watchlink.shared.CommandResult
import com.watchlink.shared.NotificationKey
import com.watchlink.shared.NotificationPayload
import com.watchlink.shared.NotificationSnapshot
import com.watchlink.shared.Paths
import com.watchlink.shared.WatchSettings
import com.watchlink.shared.WireJson
import com.watchlink.wearfacepush.WatchFacePushHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await

/**
 * Receives everything the phone sends. Callbacks arrive on a background thread, so the work is
 * done with runBlocking: that keeps the service (and process) alive until we are finished.
 */
class WatchListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        runBlocking {
            try {
                dispatch(event)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed handling ${event.path}", e)
            }
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (!channel.path.startsWith(Paths.WATCHFACE_CHANNEL)) return
        runBlocking {
            try {
                WatchFacePushHandler.onChannelOpened(applicationContext, channel)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Watch face channel failed", e)
            }
        }
    }

    private suspend fun dispatch(event: MessageEvent) {
        val ctx = applicationContext
        when (val path = event.path) {
            Paths.NOTIF_POSTED -> {
                val payload = WireJson.decode<NotificationPayload>(event.data)
                NotificationStore.upsert(payload)
                LocalNotifications.post(ctx, payload)
            }
            Paths.NOTIF_REMOVED -> {
                val key = WireJson.decode<NotificationKey>(event.data).key
                NotificationStore.remove(key)
                LocalNotifications.cancel(ctx, key)
            }
            Paths.NOTIF_SNAPSHOT -> {
                val snapshot = WireJson.decode<NotificationSnapshot>(event.data)
                val newKeys = snapshot.notifications.mapTo(HashSet()) { it.key }
                val stale = NotificationStore.items.value.map { it.key }.filterNot { it in newKeys }
                NotificationStore.replaceAll(snapshot.notifications)
                stale.forEach { LocalNotifications.cancel(ctx, it) }
            }
            Paths.CALL_STATE -> onCallState(WireJson.decode<CallState>(event.data))
            Paths.SETTINGS -> WatchSettingsStore.update(WireJson.decode<WatchSettings>(event.data))
            Paths.REPLY_RESULT -> {
                val result = WireJson.decode<CommandResult>(event.data)
                ReplyStatusStore.finished(result.key, result.ok, result.message)
            }
            Paths.PING -> {
                Wearable.getMessageClient(ctx)
                    .sendMessage(event.sourceNodeId, Paths.PONG, ByteArray(0))
                    .await()
            }
            Paths.PONG -> Unit
            else -> {
                if (path.startsWith("/watchface")) {
                    WatchFacePushHandler.onMessage(ctx, event)
                } else {
                    Log.d(TAG, "Unhandled path $path")
                }
            }
        }
    }

    private fun onCallState(call: CallState) {
        val ctx = applicationContext
        val previous = CallStore.state.value.phase
        CallStore.set(call)
        when (call.phase) {
            CallPhase.RINGING -> {
                LocalNotifications.showIncomingCall(ctx, call)
                if (previous != CallPhase.RINGING) {
                    CallVibrator.start(ctx)
                    // Works only while we are allowed to start activities; the notification's
                    // full-screen intent is the reliable path.
                    try {
                        startActivity(
                            Intent(ctx, IncomingCallActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        )
                    } catch (e: Exception) {
                        Log.d(TAG, "Direct call-screen launch not permitted", e)
                    }
                }
            }
            CallPhase.OFFHOOK -> {
                CallVibrator.stop(ctx)
                LocalNotifications.showOngoingCall(ctx, call)
            }
            CallPhase.IDLE -> {
                CallVibrator.stop(ctx)
                LocalNotifications.cancelCallNotifications(ctx)
            }
        }
    }

    private companion object {
        const val TAG = "WatchListenerService"
    }
}
