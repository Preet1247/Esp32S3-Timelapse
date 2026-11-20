package com.watchlink.wear

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide scope for fire-and-forget work that must outlive any single screen. */
object AppScope {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

class WatchLinkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationStore.init(this)
        WatchSettingsStore.init(this)
        LocalNotifications.createChannels(this)
    }
}
