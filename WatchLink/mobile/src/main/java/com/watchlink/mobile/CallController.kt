package com.watchlink.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.watchlink.shared.CallAction
import com.watchlink.shared.CallCommand
import com.watchlink.shared.CommandResult

/** Executes watch call commands via TelecomManager, falling back to the call notification's actions. */
object CallController {
    private const val TAG = "CallController"
    const val RESULT_KEY = "call"

    private val ANSWER_WORDS = listOf("answer", "accept")
    private val DECLINE_WORDS = listOf("decline", "reject", "hang up", "hangup", "end call", "dismiss")
    private val SILENCE_WORDS = listOf("silence", "mute")

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun ok(msg: String) = CommandResult(RESULT_KEY, true, msg)
    private fun fail(msg: String) = CommandResult(RESULT_KEY, false, msg)

    fun handle(context: Context, cmd: CallCommand): CommandResult = when (cmd.action) {
        CallAction.ANSWER -> execute(context, true, ANSWER_WORDS, "Answered") { tm ->
            tm.acceptRingingCall()
            true
        }
        CallAction.DECLINE, CallAction.END -> execute(context, true, DECLINE_WORDS, "Call ended") { tm -> endCall(tm) }
        CallAction.SILENCE -> execute(context, false, SILENCE_WORDS, "Ringer silenced") { tm ->
            tm.silenceRinger()
            true
        }
        CallAction.REPLY_SMS -> replySms(context, cmd.smsText)
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun endCall(tm: TelecomManager): Boolean = tm.endCall()

    @SuppressLint("MissingPermission")
    private fun execute(
        context: Context,
        needsAnswerPermission: Boolean,
        words: List<String>,
        okMessage: String,
        block: (TelecomManager) -> Boolean,
    ): CommandResult {
        var error: String? = null
        val permitted = !needsAnswerPermission || granted(context, Manifest.permission.ANSWER_PHONE_CALLS)
        if (permitted) {
            try {
                val tm = context.getSystemService(TelecomManager::class.java)
                if (tm != null && block(tm)) return ok(okMessage)
                error = "Telecom could not perform the action"
            } catch (e: SecurityException) {
                error = "Permission denied: ${e.message}"
            } catch (e: Exception) {
                Log.w(TAG, "telecom action failed", e)
                error = e.message ?: "Telecom error"
            }
        } else {
            error = "ANSWER_PHONE_CALLS permission not granted"
        }
        if (fireNotificationAction(context, words)) return ok("$okMessage (via notification action)")
        return fail(error ?: "Failed")
    }

    private fun fireNotificationAction(context: Context, words: List<String>): Boolean {
        for (sbn in WatchLinkNotificationService.activeList()) {
            val n = sbn.notification ?: continue
            if (n.category != Notification.CATEGORY_CALL) continue
            val actions = n.actions ?: continue
            for (action in actions) {
                val title = action.title?.toString()?.lowercase().orEmpty()
                if (words.any { title.contains(it) }) {
                    try {
                        PendingIntentSender.send(context, action.actionIntent)
                        return true
                    } catch (e: Exception) {
                        Log.w(TAG, "call notification action failed", e)
                    }
                }
            }
        }
        return false
    }

    private fun replySms(context: Context, text: String?): CommandResult {
        val number = CallTracker.number ?: CallTracker.lastNumber
            ?: return fail("Caller number unknown (grant Call log permission)")
        val body = text?.takeIf { it.isNotBlank() } ?: return fail("Empty SMS text")
        if (!granted(context, Manifest.permission.SEND_SMS)) return fail("SEND_SMS permission not granted")
        // Hang up first (best effort), then text the caller.
        execute(context, true, DECLINE_WORDS, "Call ended") { tm -> endCall(tm) }
        return try {
            @Suppress("DEPRECATION")
            val sms: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }
            val parts = sms.divideMessage(body)
            if (parts.size > 1) {
                sms.sendMultipartTextMessage(number, null, parts, null, null)
            } else {
                sms.sendTextMessage(number, null, body, null, null)
            }
            ok("SMS sent")
        } catch (e: SecurityException) {
            fail("Permission denied: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "sms failed", e)
            fail(e.message ?: "SMS failed")
        }
    }
}
