package com.watchlink.wear

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.MaterialTheme
import com.watchlink.wear.ui.WatchApp

/** Deep-link request from a notification: open [key]'s detail, optionally straight to reply. */
data class NavRequest(val key: String, val reply: Boolean, val nonce: Long = System.nanoTime())

class MainActivity : ComponentActivity() {

    private var navRequest by mutableStateOf<NavRequest?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            navRequest = parseNavRequest(intent)
            requestNotificationPermissionOnce()
            PhoneMessenger.requestSync(this)
        }

        setContent {
            MaterialTheme {
                WatchApp(
                    navRequest = navRequest,
                    onNavHandled = { navRequest = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        navRequest = parseNavRequest(intent)
    }

    override fun onStart() {
        super.onStart()
        PhoneMessenger.startMonitoring(this)
    }

    override fun onStop() {
        PhoneMessenger.stopMonitoring()
        super.onStop()
    }

    private fun parseNavRequest(intent: Intent): NavRequest? {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return null
        return NavRequest(key, intent.getBooleanExtra(EXTRA_REPLY, false))
    }

    private fun requestNotificationPermissionOnce() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        val prefs = getSharedPreferences("watchlink_ui", Context.MODE_PRIVATE)
        if (prefs.getBoolean("asked_notification_permission", false)) return
        prefs.edit().putBoolean("asked_notification_permission", true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val EXTRA_KEY = "notification_key"
        const val EXTRA_REPLY = "open_reply"

        fun createIntent(context: Context, key: String, reply: Boolean): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
                .putExtra(EXTRA_KEY, key)
                .putExtra(EXTRA_REPLY, reply)
    }
}
