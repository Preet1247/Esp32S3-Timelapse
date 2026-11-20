package com.watchlink.wear

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import com.watchlink.shared.CallAction
import com.watchlink.shared.CallState
import com.watchlink.shared.NotificationPayload

/** Builds and posts the watch-local Android notifications. */
object LocalNotifications {
    const val CHANNEL_NOTIFICATIONS = "phone_notifications"
    const val CHANNEL_INCOMING_CALL = "incoming_call"
    const val CHANNEL_ONGOING_CALL = "ongoing_call"

    private const val ID_INCOMING_CALL = 0x7001
    private const val ID_ONGOING_CALL = 0x7002

    private const val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun notificationId(key: String): Int = key.hashCode()

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NOTIFICATIONS,
                context.getString(R.string.channel_phone_notifications),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.channel_phone_notifications_desc)
                enableVibration(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_INCOMING_CALL,
                context.getString(R.string.channel_incoming_call),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                // Vibration is driven by CallVibrator so it can repeat and be cancelled.
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ONGOING_CALL,
                context.getString(R.string.channel_ongoing_call),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                enableVibration(false)
                setSound(null, null)
            },
        )
    }

    // region phone notifications

    @SuppressLint("MissingPermission")
    fun post(context: Context, p: NotificationPayload) {
        if (p.isOngoing) return // kept in the in-app list only; never alert for ongoing items
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return

        val id = notificationId(p.key)
        val largeIcon = decodeBase64Bitmap(p.largeIconPng) ?: decodeBase64Bitmap(p.appIconPng)
        val quiet = p.silent || !WatchSettingsStore.shouldVibrate

        val builder = NotificationCompat.Builder(context, CHANNEL_NOTIFICATIONS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${p.appName} · ${p.title}")
            .setContentText(p.text)
            .setLargeIcon(largeIcon)
            .setWhen(p.postTime)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(p.silent)
            .setSilent(quiet)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    id,
                    MainActivity.createIntent(context, p.key, reply = false),
                    PI_FLAGS,
                ),
            )

        if (p.isMessaging) builder.setCategory(NotificationCompat.CATEGORY_MESSAGE)

        if (p.isMessaging && p.messages.isNotEmpty()) {
            val me = Person.Builder().setName("Me").build()
            val style = NotificationCompat.MessagingStyle(me)
            p.conversationTitle?.let {
                style.setConversationTitle(it)
                style.setGroupConversation(true)
            }
            p.messages.forEach { m ->
                val sender = Person.Builder().setName(m.sender).build()
                style.addMessage(m.text, m.time, sender)
            }
            builder.setStyle(style)
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(p.text))
        }

        if (p.actions.any { it.isReply }) {
            val replyPi = PendingIntent.getActivity(
                context,
                (p.key + "#reply").hashCode(),
                MainActivity.createIntent(context, p.key, reply = true),
                PI_FLAGS,
            )
            builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_notification,
                    context.getString(R.string.action_reply),
                    replyPi,
                ).build(),
            )
        }

        val dismissPi = PendingIntent.getBroadcast(
            context,
            (p.key + "#dismiss").hashCode(),
            NotificationActionReceiver.dismissIntent(context, p.key),
            PI_FLAGS,
        )
        builder.addAction(
            NotificationCompat.Action.Builder(
                R.drawable.ic_notification,
                context.getString(R.string.action_dismiss),
                dismissPi,
            ).build(),
        )

        nm.notify(id, builder.build())
    }

    fun cancel(context: Context, key: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(key))
    }

    // endregion

    // region calls

    private fun callActivityPendingIntent(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, IncomingCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PI_FLAGS,
        )

    private fun callActionPendingIntent(context: Context, action: CallAction): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            "call#${action.name}".hashCode(),
            NotificationActionReceiver.callIntent(context, action),
            PI_FLAGS,
        )

    private fun callerLabel(context: Context, call: CallState): String =
        call.callerName?.takeIf { it.isNotBlank() }
            ?: call.number?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.call_unknown_caller)

    @SuppressLint("MissingPermission")
    fun showIncomingCall(context: Context, call: CallState) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val fullScreen = callActivityPendingIntent(context, ID_INCOMING_CALL)

        val notification = NotificationCompat.Builder(context, CHANNEL_INCOMING_CALL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(callerLabel(context, call))
            .setContentText(context.getString(R.string.call_incoming))
            .setLargeIcon(decodeBase64Bitmap(call.callerPhotoPng))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_notification,
                    context.getString(R.string.action_answer),
                    callActionPendingIntent(context, CallAction.ANSWER),
                ).build(),
            )
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_notification,
                    context.getString(R.string.action_decline),
                    callActionPendingIntent(context, CallAction.DECLINE),
                ).build(),
            )
            .build()
        nm.notify(ID_INCOMING_CALL, notification)
    }

    @SuppressLint("MissingPermission")
    fun showOngoingCall(context: Context, call: CallState) {
        val nm = NotificationManagerCompat.from(context)
        nm.cancel(ID_INCOMING_CALL)
        if (!nm.areNotificationsEnabled()) return

        val notification = NotificationCompat.Builder(context, CHANNEL_ONGOING_CALL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(callerLabel(context, call))
            .setContentText(context.getString(R.string.call_ongoing))
            .setLargeIcon(decodeBase64Bitmap(call.callerPhotoPng))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(if (call.startedAt > 0) call.startedAt else System.currentTimeMillis())
            .setContentIntent(callActivityPendingIntent(context, ID_ONGOING_CALL))
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_notification,
                    context.getString(R.string.action_end_call),
                    callActionPendingIntent(context, CallAction.END),
                ).build(),
            )
            .build()
        nm.notify(ID_ONGOING_CALL, notification)
    }

    fun cancelCallNotifications(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        nm.cancel(ID_INCOMING_CALL)
        nm.cancel(ID_ONGOING_CALL)
    }

    // endregion
}

/** Strong ring-style vibration for incoming calls; stops itself after about a minute. */
object CallVibrator {
    private const val RINGS = 40
    private const val ON_MS = 800L
    private const val OFF_MS = 700L

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    @Suppress("DEPRECATION")
    fun start(context: Context) {
        if (!WatchSettingsStore.shouldVibrate) return
        val v = vibrator(context) ?: return
        if (!v.hasVibrator()) return
        val timings = buildList {
            add(0L)
            repeat(RINGS) {
                add(ON_MS)
                add(OFF_MS)
            }
        }.toLongArray()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        v.vibrate(VibrationEffect.createWaveform(timings, -1), attrs)
    }

    fun stop(context: Context) {
        vibrator(context)?.cancel()
    }
}
