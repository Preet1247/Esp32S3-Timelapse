package com.watchlink.mobile

import android.os.SystemClock
import android.util.Log
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.watchlink.shared.ActionCommand
import com.watchlink.shared.CallCommand
import com.watchlink.shared.Capabilities
import com.watchlink.shared.CommandResult
import com.watchlink.shared.NotificationKey
import com.watchlink.shared.Paths
import com.watchlink.shared.ReplyCommand
import com.watchlink.shared.WatchFaceList
import com.watchlink.shared.WatchFaceResult
import com.watchlink.shared.WireJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle

class PhoneWearListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        val path = event.path
        val source = event.sourceNodeId
        val data = event.data ?: ByteArray(0)
        runBlocking(Dispatchers.IO) {
            try {
                handle(path, source, data)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "handling $path failed", e)
            }
        }
    }

    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        if (capabilityInfo.name != Capabilities.WATCH) return
        val nodes = capabilityInfo.nodes.toList()
        if (nodes.isEmpty()) return
        runBlocking(Dispatchers.IO) {
            try {
                WearMessenger.sendToNodes(
                    this@PhoneWearListenerService,
                    nodes,
                    Paths.SETTINGS,
                    WireJson.encode(WatchLinkPrefs.watchSettings(this@PhoneWearListenerService)),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "settings push on connect failed", e)
            }
        }
    }

    private suspend fun handle(path: String, source: String, data: ByteArray) {
        when (path) {
            Paths.NOTIF_REPLY -> handleReply(source, WireJson.decode<ReplyCommand>(data))
            Paths.NOTIF_ACTION -> handleAction(source, WireJson.decode<ActionCommand>(data))
            Paths.NOTIF_DISMISS -> handleDismiss(source, WireJson.decode<NotificationKey>(data))
            Paths.NOTIF_OPEN_ON_PHONE -> handleOpen(source, WireJson.decode<NotificationKey>(data))
            Paths.SYNC_REQUEST -> handleSync(source)
            Paths.CALL_COMMAND -> {
                val result = CallController.handle(this, WireJson.decode<CallCommand>(data))
                sendResult(source, result)
            }
            Paths.PING -> WearMessenger.send(this, source, Paths.PONG, data)
            Paths.PONG -> LinkEvents.pongs.tryEmit(SystemClock.elapsedRealtime())
            Paths.WATCHFACE_RESULT -> WatchFaceRepository.onResult(WireJson.decode<WatchFaceResult>(data))
            Paths.WATCHFACE_LIST_RESULT -> WatchFaceRepository.onList(WireJson.decode<WatchFaceList>(data))
            else -> Log.d(TAG, "ignored path $path")
        }
    }

    private suspend fun sendResult(nodeId: String, result: CommandResult) {
        WearMessenger.send(this, nodeId, Paths.REPLY_RESULT, WireJson.encode(result))
    }

    private suspend fun handleReply(source: String, cmd: ReplyCommand) {
        val sbn = WatchLinkNotificationService.findNotification(cmd.key)
        val action = sbn?.notification?.actions?.getOrNull(cmd.actionIndex)
        val remoteInputs = action?.remoteInputs
        if (action == null || remoteInputs == null || remoteInputs.isEmpty()) {
            sendResult(source, CommandResult(cmd.key, false, "Notification or reply action no longer available"))
            return
        }
        try {
            val freeForm = remoteInputs.filter { it.allowFreeFormInput }
            val targets = if (freeForm.isNotEmpty()) freeForm else remoteInputs.toList()
            val bundle = Bundle()
            for (ri in targets) bundle.putCharSequence(ri.resultKey, cmd.text)
            val fillIn = Intent()
            RemoteInput.addResultsToIntent(remoteInputs, fillIn, bundle)
            RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT)
            PendingIntentSender.send(this, action.actionIntent, fillIn)
            sendResult(source, CommandResult(cmd.key, true, "Reply sent"))
        } catch (e: Exception) {
            Log.w(TAG, "reply failed", e)
            sendResult(source, CommandResult(cmd.key, false, e.message ?: "Reply failed"))
        }
    }

    private suspend fun handleAction(source: String, cmd: ActionCommand) {
        val sbn = WatchLinkNotificationService.findNotification(cmd.key)
        val action = sbn?.notification?.actions?.getOrNull(cmd.actionIndex)
        if (action == null) {
            sendResult(source, CommandResult(cmd.key, false, "Action no longer available"))
            return
        }
        try {
            PendingIntentSender.send(this, action.actionIntent)
            sendResult(source, CommandResult(cmd.key, true, "Action sent"))
        } catch (e: Exception) {
            Log.w(TAG, "action failed", e)
            sendResult(source, CommandResult(cmd.key, false, e.message ?: "Action failed"))
        }
    }

    private suspend fun handleDismiss(source: String, cmd: NotificationKey) {
        val service = WatchLinkNotificationService.instance
        if (service == null) {
            sendResult(source, CommandResult(cmd.key, false, "Notification access is not enabled"))
            return
        }
        try {
            service.cancelNotification(cmd.key)
        } catch (e: Exception) {
            Log.w(TAG, "dismiss failed", e)
            sendResult(source, CommandResult(cmd.key, false, e.message ?: "Dismiss failed"))
        }
    }

    private suspend fun handleOpen(source: String, cmd: NotificationKey) {
        val sbn = WatchLinkNotificationService.findNotification(cmd.key)
        val content = sbn?.notification?.contentIntent
        if (content == null) {
            sendResult(source, CommandResult(cmd.key, false, "Notification cannot be opened"))
            return
        }
        try {
            PendingIntentSender.send(this, content)
            sendResult(source, CommandResult(cmd.key, true, "Opened on phone"))
        } catch (e: Exception) {
            Log.w(TAG, "open failed", e)
            sendResult(source, CommandResult(cmd.key, false, e.message ?: "Open failed"))
        }
    }

    private suspend fun handleSync(source: String) {
        val all = WatchLinkNotificationService.activeList()
        val payloads = all
            .filter { NotificationMapper.shouldForward(this, it, all) }
            .sortedByDescending { it.postTime }
            .take(NotificationMapper.MAX_SNAPSHOT)
            .map { NotificationMapper.build(this, it, silent = true) }
        WearMessenger.send(this, source, Paths.NOTIF_SNAPSHOT, NotificationMapper.encodeSnapshot(payloads))
        WearMessenger.send(this, source, Paths.SETTINGS, WireJson.encode(WatchLinkPrefs.watchSettings(this)))
        WearMessenger.send(this, source, Paths.CALL_STATE, WireJson.encode(CallTracker.snapshot(this)))
    }

    companion object {
        private const val TAG = "PhoneWearListener"
    }
}
