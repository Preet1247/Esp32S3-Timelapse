package com.watchlink.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.watchlink.shared.CallPhase
import kotlinx.coroutines.launch

/** Supplies the caller number (needs READ_CALL_LOG + READ_PHONE_STATE) to [CallTracker]. */
class PhoneStateReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val phase = when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> CallPhase.RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> CallPhase.OFFHOOK
            TelephonyManager.EXTRA_STATE_IDLE -> CallPhase.IDLE
            else -> return
        }
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        val pending = goAsync()
        val app = context.applicationContext
        CallTracker.scope.launch {
            try {
                CallTracker.update(app, phase, number)
            } finally {
                pending.finish()
            }
        }
    }
}
