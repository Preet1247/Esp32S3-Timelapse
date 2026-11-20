package com.watchlink.mobile

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.watchlink.shared.NotificationKey
import com.watchlink.shared.Paths
import com.watchlink.shared.WireJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

class WatchLinkNotificationService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastSignature = ConcurrentHashMap<String, Int>()
    private var callMonitor: CallMonitor? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        instanceRef = WeakReference(this)
        try {
            activeNotifications?.forEach { activeCache[it.key] = it }
        } catch (e: Exception) {
            Log.w(TAG, "seed active notifications failed", e)
        }
        registerCallMonitor()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        callMonitor?.stop()
        callMonitor = null
        if (instanceRef?.get() === this) instanceRef = null
        try {
            requestRebind(ComponentName(this, WatchLinkNotificationService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "requestRebind failed", e)
        }
    }

    override fun onDestroy() {
        callMonitor?.stop()
        callMonitor = null
        if (instanceRef?.get() === this) instanceRef = null
        scope.cancel()
        super.onDestroy()
    }

    /** Idempotent; also called from the UI after READ_PHONE_STATE is granted. */
    @Synchronized
    fun registerCallMonitor() {
        val monitor = callMonitor ?: CallMonitor(applicationContext, CallTracker.scope).also { callMonitor = it }
        monitor.start()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val posted = sbn ?: return
        activeCache[posted.key] = posted
        scope.launch {
            try {
                handlePosted(posted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "handlePosted failed", e)
            }
        }
    }

    private suspend fun handlePosted(sbn: StatusBarNotification) {
        val app = applicationContext
        if (sbn.packageName == app.packageName) return
        val n = sbn.notification ?: return

        if (n.category == Notification.CATEGORY_CALL) {
            val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            CallTracker.noteCallNotification(app, title)
            return
        }

        val all = try {
            activeNotifications?.toList().orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        if (!NotificationMapper.shouldForward(app, sbn, all)) return

        val base = NotificationMapper.build(app, sbn, silent = false)
        val signature = (base.title + "\u0000" + base.text + "\u0000" +
            base.messages.size + base.messages.lastOrNull()?.text).hashCode()
        val previous = lastSignature.put(sbn.key, signature)
        val payload = if (previous == signature) base.copy(silent = true) else base

        val bytes = NotificationMapper.encodeFitting(payload) ?: return
        WearMessenger.sendToWatches(app, Paths.NOTIF_POSTED, bytes)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val removed = sbn ?: return
        activeCache.remove(removed.key)
        lastSignature.remove(removed.key)
        if (removed.packageName == packageName) return
        if (removed.notification?.category == Notification.CATEGORY_CALL) return
        val app = applicationContext
        scope.launch {
            try {
                WearMessenger.sendToWatches(app, Paths.NOTIF_REMOVED, WireJson.encode(NotificationKey(removed.key)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "removed send failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "WatchLinkNLS"

        @Volatile
        private var instanceRef: WeakReference<WatchLinkNotificationService>? = null

        val instance: WatchLinkNotificationService?
            get() = instanceRef?.get()

        /** key -> StatusBarNotification, used to execute watch commands later. */
        val activeCache = ConcurrentHashMap<String, StatusBarNotification>()

        fun activeList(): List<StatusBarNotification> = try {
            instance?.activeNotifications?.toList().orEmpty()
        } catch (e: Exception) {
            emptyList()
        }

        fun findNotification(key: String): StatusBarNotification? =
            activeCache[key] ?: activeList().firstOrNull { it.key == key }
    }
}
