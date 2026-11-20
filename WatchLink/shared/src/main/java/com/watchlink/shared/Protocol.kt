package com.watchlink.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Wire contract between the phone app (:mobile) and the watch app (:wear).
 *
 * Transport: Google Wearable Data Layer (play-services-wearable).
 *  - Small payloads go over MessageClient as UTF-8 JSON (see [WireJson]).
 *  - Watch-face APKs go over ChannelClient (see [Paths.WATCHFACE_CHANNEL]).
 *
 * Both apps MUST share the same applicationId and signing key, otherwise the
 * Data Layer silently refuses to deliver messages between them.
 */
object Paths {
    // phone -> watch
    const val NOTIF_POSTED = "/notif/posted"          // NotificationPayload
    const val NOTIF_REMOVED = "/notif/removed"        // NotificationKey
    const val NOTIF_SNAPSHOT = "/notif/snapshot"      // NotificationSnapshot (answer to SYNC_REQUEST)
    const val CALL_STATE = "/call/state"              // CallState
    const val REPLY_RESULT = "/notif/reply/result"    // CommandResult
    const val WATCHFACE_RESULT = "/watchface/result"  // WatchFaceResult
    const val SETTINGS = "/settings"                  // WatchSettings

    // watch -> phone
    const val NOTIF_ACTION = "/notif/action"          // ActionCommand
    const val NOTIF_REPLY = "/notif/reply"            // ReplyCommand
    const val NOTIF_DISMISS = "/notif/dismiss"        // NotificationKey
    const val NOTIF_OPEN_ON_PHONE = "/notif/open"     // NotificationKey
    const val CALL_COMMAND = "/call/command"          // CallCommand
    const val SYNC_REQUEST = "/sync/request"          // empty body

    // phone -> watch (watch faces)
    const val WATCHFACE_LIST = "/watchface/list"      // phone -> watch, empty body; watch answers WATCHFACE_LIST_RESULT
    const val WATCHFACE_LIST_RESULT = "/watchface/list/result" // WatchFaceList (watch -> phone)
    /** ChannelClient path prefix; full path is "$WATCHFACE_CHANNEL/<WatchFaceTransfer.transferId>". */
    const val WATCHFACE_CHANNEL = "/watchface/apk"
    const val WATCHFACE_TRANSFER_META = "/watchface/meta" // WatchFaceTransfer, sent BEFORE opening channel

    // both directions
    const val PING = "/ping"
    const val PONG = "/pong"
}

/** Declared in res/values/wear.xml of each app (android_wear_capabilities). */
object Capabilities {
    const val PHONE = "watchlink_phone"
    const val WATCH = "watchlink_watch"
}

@Serializable
data class NotifAction(
    val index: Int,               // index into Notification.actions on the phone
    val title: String,
    val isReply: Boolean,         // has a RemoteInput -> watch shows reply UI
    val choices: List<String> = emptyList(), // RemoteInput.choices (smart replies), if any
)

@Serializable
data class NotificationPayload(
    val key: String,              // StatusBarNotification.key (stable id on both sides)
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val subText: String? = null,
    val postTime: Long,
    val category: String? = null, // Notification.category (msg, call, email, ...)
    val isMessaging: Boolean = false,
    val messages: List<ChatMessage> = emptyList(), // MessagingStyle history (last N)
    val conversationTitle: String? = null,
    val appIconPng: String? = null,   // base64 PNG, <= 96x96
    val largeIconPng: String? = null, // base64 PNG, <= 128x128 (contact photo etc.)
    val actions: List<NotifAction> = emptyList(),
    val isOngoing: Boolean = false,
    val silent: Boolean = false,  // true when phone app wants watch NOT to vibrate (updates)
)

@Serializable
data class ChatMessage(val sender: String, val text: String, val time: Long)

@Serializable
data class NotificationKey(val key: String)

@Serializable
data class NotificationSnapshot(val notifications: List<NotificationPayload>)

@Serializable
data class ActionCommand(val key: String, val actionIndex: Int)

@Serializable
data class ReplyCommand(val key: String, val actionIndex: Int, val text: String)

@Serializable
data class CommandResult(val key: String, val ok: Boolean, val message: String? = null)

@Serializable
enum class CallPhase { IDLE, RINGING, OFFHOOK }

@Serializable
data class CallState(
    val phase: CallPhase,
    val number: String? = null,
    val callerName: String? = null,
    val callerPhotoPng: String? = null, // base64
    val startedAt: Long = 0L,
)

@Serializable
enum class CallAction { ANSWER, DECLINE, END, SILENCE, REPLY_SMS }

@Serializable
data class CallCommand(val action: CallAction, val smsText: String? = null)

@Serializable
data class WatchFaceTransfer(
    val transferId: String,
    val fileName: String,
    val sizeBytes: Long,
    /** Token produced by Google's Watch Face Push validator for this exact APK. */
    val validationToken: String,
    val setActive: Boolean = true,
)

@Serializable
data class WatchFaceResult(
    val transferId: String,
    val ok: Boolean,
    val message: String,
    val slotId: String? = null,
)

@Serializable
data class WatchFaceInfo(val slotId: String, val packageName: String, val versionCode: Long)

@Serializable
data class WatchFaceList(val faces: List<WatchFaceInfo>, val supported: Boolean, val freeSlots: Int = 0)

@Serializable
data class WatchSettings(
    val vibrate: Boolean = true,
    val quickReplies: List<String> = DEFAULT_QUICK_REPLIES,
)

val DEFAULT_QUICK_REPLIES = listOf(
    "OK", "Yes", "No", "On my way", "Can't talk now, I'll call you later", "Thanks!", "👍",
)

object WireJson {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    inline fun <reified T> encode(value: T): ByteArray = json.encodeToString(value).toByteArray(Charsets.UTF_8)
    inline fun <reified T> decode(bytes: ByteArray): T = json.decodeFromString(bytes.toString(Charsets.UTF_8))
}

/** Data Layer MessageClient practical limit is ~100 KB; keep payloads under this. */
const val MAX_MESSAGE_BYTES = 90_000
