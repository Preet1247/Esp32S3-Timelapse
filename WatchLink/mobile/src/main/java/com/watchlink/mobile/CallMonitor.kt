package com.watchlink.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.watchlink.shared.CallPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Registers a telephony call-state listener (TelephonyCallback on 31+, PhoneStateListener before). */
class CallMonitor(private val context: Context, private val scope: CoroutineScope) {
    private var modernCallback: Any? = null
    private var legacyListener: Any? = null
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = ModernCallCallback { state -> dispatch(state, null) }
                tm.registerTelephonyCallback(ContextCompat.getMainExecutor(context), cb)
                modernCallback = cb
            } else {
                @Suppress("DEPRECATION")
                val l = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        dispatch(state, phoneNumber)
                    }
                }
                @Suppress("DEPRECATION")
                tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
                legacyListener = l
            }
            started = true
        } catch (e: SecurityException) {
            Log.w(TAG, "Call monitor not permitted", e)
        } catch (e: Exception) {
            Log.w(TAG, "Call monitor failed", e)
        }
    }

    @Synchronized
    fun stop() {
        if (!started) return
        val tm = context.getSystemService(TelephonyManager::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (modernCallback as? TelephonyCallback)?.let { tm?.unregisterTelephonyCallback(it) }
            } else {
                @Suppress("DEPRECATION")
                (legacyListener as? PhoneStateListener)?.let { tm?.listen(it, PhoneStateListener.LISTEN_NONE) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Call monitor stop failed", e)
        }
        modernCallback = null
        legacyListener = null
        started = false
    }

    private fun dispatch(state: Int, number: String?) {
        val phase = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> CallPhase.RINGING
            TelephonyManager.CALL_STATE_OFFHOOK -> CallPhase.OFFHOOK
            else -> CallPhase.IDLE
        }
        scope.launch { CallTracker.update(context, phase, number) }
    }

    companion object {
        private const val TAG = "CallMonitor"
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private class ModernCallCallback(
    private val onState: (Int) -> Unit,
) : TelephonyCallback(), TelephonyCallback.CallStateListener {
    override fun onCallStateChanged(state: Int) {
        onState(state)
    }
}
