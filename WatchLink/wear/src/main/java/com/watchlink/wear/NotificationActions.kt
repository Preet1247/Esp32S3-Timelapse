package com.watchlink.wear

import android.content.Context
import com.watchlink.shared.NotificationKey
import com.watchlink.shared.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared "dismiss" logic used by the UI and the notification action receiver. */
object NotificationActions {
    suspend fun dismissNow(context: Context, key: String) {
        val app = context.applicationContext
        withContext(Dispatchers.IO) { NotificationStore.remove(key) }
        LocalNotifications.cancel(app, key)
        PhoneMessenger.sendJson(app, Paths.NOTIF_DISMISS, NotificationKey(key))
    }

    fun dismiss(context: Context, key: String) {
        val app = context.applicationContext
        AppScope.scope.launch { dismissNow(app, key) }
    }

    /** Dismisses every dismissable notification on the phone and clears the local list. */
    fun clearAll(context: Context) {
        val app = context.applicationContext
        AppScope.scope.launch {
            val current = NotificationStore.items.value
            withContext(Dispatchers.IO) { NotificationStore.clear() }
            current.forEach { LocalNotifications.cancel(app, it.key) }
            current.filterNot { it.isOngoing }.forEach {
                PhoneMessenger.sendJson(app, Paths.NOTIF_DISMISS, NotificationKey(it.key))
            }
        }
    }
}
