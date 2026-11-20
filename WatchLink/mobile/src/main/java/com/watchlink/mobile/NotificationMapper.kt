package com.watchlink.mobile

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.os.BundleCompat
import com.watchlink.shared.ChatMessage
import com.watchlink.shared.MAX_MESSAGE_BYTES
import com.watchlink.shared.NotifAction
import com.watchlink.shared.NotificationPayload
import com.watchlink.shared.NotificationSnapshot
import com.watchlink.shared.WireJson

/** Filtering + conversion of StatusBarNotification to wire payloads. */
object NotificationMapper {
    const val MAX_MESSAGES = 6
    const val MAX_SNAPSHOT = 25
    private const val EXTRA_APP_INFO = "android.appInfo"

    private fun hasFlag(n: Notification, flag: Int) = (n.flags and flag) != 0

    private fun hasChildren(sbn: StatusBarNotification, all: Collection<StatusBarNotification>): Boolean {
        val group = sbn.notification.group ?: return false
        return all.any {
            it.key != sbn.key &&
                it.packageName == sbn.packageName &&
                it.notification.group == group &&
                !hasFlag(it.notification, Notification.FLAG_GROUP_SUMMARY)
        }
    }

    /** Whether this notification belongs in the normal notification stream. */
    fun shouldForward(
        context: Context,
        sbn: StatusBarNotification,
        all: Collection<StatusBarNotification> = emptyList(),
    ): Boolean {
        if (sbn.packageName == context.packageName) return false
        val n = sbn.notification ?: return false
        if (n.category == Notification.CATEGORY_CALL) return false // dedicated call screen instead
        if (hasFlag(n, Notification.FLAG_GROUP_SUMMARY) && hasChildren(sbn, all)) return false
        if (hasFlag(n, Notification.FLAG_ONGOING_EVENT) || hasFlag(n, Notification.FLAG_FOREGROUND_SERVICE)) return false
        if (WatchLinkPrefs.isMuted(context, sbn.packageName)) return false
        val e = n.extras
        if (e != null) {
            val empty = e.getCharSequence(Notification.EXTRA_TITLE).isNullOrEmpty() &&
                e.getCharSequence(Notification.EXTRA_TEXT).isNullOrEmpty() &&
                e.getCharSequence(Notification.EXTRA_BIG_TEXT).isNullOrEmpty() &&
                !e.containsKey(Notification.EXTRA_MESSAGES)
            if (empty) return false
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun resolveAppInfo(context: Context, sbn: StatusBarNotification, extras: Bundle?): ApplicationInfo? {
        try {
            return context.packageManager.getApplicationInfo(sbn.packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            // fall through to the info embedded in the notification
        } catch (e: Exception) {
            // ignore
        }
        return try {
            extras?.let { BundleCompat.getParcelable(it, EXTRA_APP_INFO, ApplicationInfo::class.java) }
        } catch (e: Exception) {
            null
        }
    }

    fun build(
        context: Context,
        sbn: StatusBarNotification,
        silent: Boolean,
        includeImages: Boolean = true,
    ): NotificationPayload {
        val n = sbn.notification
        val extras: Bundle = n.extras ?: Bundle()
        val pm = context.packageManager

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().take(2000)
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        val style = try {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        } catch (e: Exception) {
            null
        }
        val messages = style?.messages.orEmpty().takeLast(MAX_MESSAGES).map { m ->
            ChatMessage(
                sender = m.person?.name?.toString() ?: style?.user?.name?.toString().orEmpty(),
                text = m.text?.toString().orEmpty().take(1000),
                time = m.timestamp,
            )
        }

        val appInfo = resolveAppInfo(context, sbn, extras)
        val appName = try {
            appInfo?.let { pm.getApplicationLabel(it).toString() } ?: sbn.packageName
        } catch (e: Exception) {
            sbn.packageName
        }

        var appIcon: String? = null
        var largeIcon: String? = null
        if (includeImages) {
            appIcon = try {
                ImageUtil.drawableToBase64(appInfo?.let { pm.getApplicationIcon(it) }, 96)
            } catch (e: Exception) {
                null
            }
            largeIcon = try {
                ImageUtil.drawableToBase64(n.getLargeIcon()?.loadDrawable(context), 128)
            } catch (e: Exception) {
                null
            }
        }

        val actions = n.actions?.mapIndexed { i, a ->
            val reply = a.remoteInputs?.firstOrNull { it.allowFreeFormInput || !it.choices.isNullOrEmpty() }
            NotifAction(
                index = i,
                title = a.title?.toString().orEmpty(),
                isReply = reply != null,
                choices = reply?.choices?.map { it.toString() }.orEmpty(),
            )
        }.orEmpty()

        return NotificationPayload(
            key = sbn.key,
            packageName = sbn.packageName,
            appName = appName,
            title = title,
            text = text,
            subText = subText,
            postTime = sbn.postTime,
            category = n.category,
            isMessaging = style != null,
            messages = messages,
            conversationTitle = style?.conversationTitle?.toString(),
            appIconPng = appIcon,
            largeIconPng = largeIcon,
            actions = actions,
            isOngoing = hasFlag(n, Notification.FLAG_ONGOING_EVENT),
            silent = silent,
        )
    }

    /** Encodes the payload, shedding images / text until it fits MAX_MESSAGE_BYTES. Null if impossible. */
    fun encodeFitting(payload: NotificationPayload): ByteArray? {
        var bytes = WireJson.encode(payload)
        if (bytes.size <= MAX_MESSAGE_BYTES) return bytes
        val noImages = payload.copy(appIconPng = null, largeIconPng = null)
        bytes = WireJson.encode(noImages)
        if (bytes.size <= MAX_MESSAGE_BYTES) return bytes
        val trimmed = noImages.copy(
            text = noImages.text.take(1500),
            messages = noImages.messages.takeLast(3).map { it.copy(text = it.text.take(300)) },
        )
        bytes = WireJson.encode(trimmed)
        return if (bytes.size <= MAX_MESSAGE_BYTES) bytes else null
    }

    /** Snapshot encoding: drop large icons, then all images, then oldest entries until it fits. */
    fun encodeSnapshot(list: List<NotificationPayload>): ByteArray {
        var items = list
        var bytes = WireJson.encode(NotificationSnapshot(items))
        if (bytes.size <= MAX_MESSAGE_BYTES) return bytes
        items = items.map { it.copy(largeIconPng = null) }
        bytes = WireJson.encode(NotificationSnapshot(items))
        if (bytes.size <= MAX_MESSAGE_BYTES) return bytes
        items = items.map { it.copy(appIconPng = null) }
        bytes = WireJson.encode(NotificationSnapshot(items))
        while (bytes.size > MAX_MESSAGE_BYTES && items.isNotEmpty()) {
            items = items.dropLast(1)
            bytes = WireJson.encode(NotificationSnapshot(items))
        }
        return bytes
    }
}
