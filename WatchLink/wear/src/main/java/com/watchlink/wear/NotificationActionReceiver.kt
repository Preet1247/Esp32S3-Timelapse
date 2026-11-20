package com.watchlink.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.watchlink.shared.CallAction
import com.watchlink.shared.CallCommand
import com.watchlink.shared.Paths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Handles action buttons of the local notifications (Dismiss, call Answer/Decline/End). */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ACTION_DISMISS -> {
                val key = intent.getStringExtra(EXTRA_KEY) ?: return
                runAsync { NotificationActions.dismissNow(app, key) }
            }
            ACTION_CALL -> {
                val action = intent.getStringExtra(EXTRA_CALL_ACTION)
                    ?.let { runCatching { CallAction.valueOf(it) }.getOrNull() }
                    ?: return
                if (action != CallAction.END) CallVibrator.stop(app)
                runAsync { PhoneMessenger.sendJson(app, Paths.CALL_COMMAND, CallCommand(action)) }
            }
        }
    }

    private fun runAsync(block: suspend () -> Unit) {
        val pending = goAsync()
        AppScope.scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Action failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ActionReceiver"
        const val ACTION_DISMISS = "com.watchlink.wear.action.DISMISS"
        const val ACTION_CALL = "com.watchlink.wear.action.CALL"
        const val EXTRA_KEY = "key"
        const val EXTRA_CALL_ACTION = "call_action"

        fun dismissIntent(context: Context, key: String): Intent =
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(ACTION_DISMISS)
                .putExtra(EXTRA_KEY, key)

        fun callIntent(context: Context, action: CallAction): Intent =
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(ACTION_CALL)
                .putExtra(EXTRA_CALL_ACTION, action.name)
    }
}
