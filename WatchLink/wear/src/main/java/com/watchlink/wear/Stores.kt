package com.watchlink.wear

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.watchlink.shared.CallPhase
import com.watchlink.shared.CallState
import com.watchlink.shared.NotificationPayload
import com.watchlink.shared.NotificationSnapshot
import com.watchlink.shared.WatchSettings
import com.watchlink.shared.WireJson
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Newest-first list of phone notifications, persisted to a JSON file so it survives
 * process death. Mutators write synchronously; call them off the main thread.
 */
object NotificationStore {
    private const val TAG = "NotificationStore"
    private const val FILE_NAME = "notifications.json"
    private const val MAX_ITEMS = 50

    private val lock = Any()
    private val _items = MutableStateFlow<List<NotificationPayload>>(emptyList())
    val items: StateFlow<List<NotificationPayload>> = _items.asStateFlow()

    private var file: File? = null

    fun init(context: Context) {
        synchronized(lock) {
            if (file != null) return
            val f = File(context.applicationContext.filesDir, FILE_NAME)
            file = f
            _items.value = readFrom(f)
        }
    }

    fun get(key: String): NotificationPayload? = _items.value.firstOrNull { it.key == key }

    fun upsert(payload: NotificationPayload) = mutate { current ->
        (listOf(payload) + current.filterNot { it.key == payload.key })
            .sortedByDescending { it.postTime }
            .take(MAX_ITEMS)
    }

    fun remove(key: String) = mutate { current -> current.filterNot { it.key == key } }

    fun replaceAll(list: List<NotificationPayload>) = mutate {
        list.distinctBy { it.key }.sortedByDescending { it.postTime }.take(MAX_ITEMS)
    }

    fun clear() = mutate { emptyList() }

    private fun mutate(transform: (List<NotificationPayload>) -> List<NotificationPayload>) {
        synchronized(lock) {
            val next = transform(_items.value)
            _items.value = next
            file?.let { writeTo(it, next) }
        }
    }

    private fun readFrom(f: File): List<NotificationPayload> {
        if (!f.exists()) return emptyList()
        return try {
            WireJson.decode<NotificationSnapshot>(f.readBytes()).notifications.take(MAX_ITEMS)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read persisted notifications", e)
            emptyList()
        }
    }

    private fun writeTo(f: File, list: List<NotificationPayload>) {
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(WireJson.encode(NotificationSnapshot(list)))
            if (!tmp.renameTo(f)) {
                f.writeBytes(tmp.readBytes())
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not persist notifications", e)
        }
    }
}

object CallStore {
    private val _state = MutableStateFlow(CallState(CallPhase.IDLE))
    val state: StateFlow<CallState> = _state.asStateFlow()

    fun set(state: CallState) {
        _state.value = state
    }
}

/** Phone-pushed [WatchSettings] plus a watch-local vibration switch. */
object WatchSettingsStore {
    private const val PREFS = "watchlink_settings"
    private const val KEY_PHONE_SETTINGS = "phone_settings"
    private const val KEY_LOCAL_VIBRATE = "local_vibrate"
    private const val TAG = "WatchSettingsStore"

    private var prefs: SharedPreferences? = null
    private val _settings = MutableStateFlow(WatchSettings())
    private val _localVibrate = MutableStateFlow(true)

    val settings: StateFlow<WatchSettings> = _settings.asStateFlow()
    val localVibrate: StateFlow<Boolean> = _localVibrate.asStateFlow()

    /** Vibration is on only if both the phone-side setting and the local switch allow it. */
    val shouldVibrate: Boolean get() = _settings.value.vibrate && _localVibrate.value

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _localVibrate.value = p.getBoolean(KEY_LOCAL_VIBRATE, true)
        p.getString(KEY_PHONE_SETTINGS, null)?.let { json ->
            try {
                _settings.value = WireJson.decode<WatchSettings>(json.toByteArray(Charsets.UTF_8))
            } catch (e: Exception) {
                Log.w(TAG, "Bad stored settings", e)
            }
        }
    }

    fun update(settings: WatchSettings) {
        _settings.value = settings
        prefs?.edit()
            ?.putString(KEY_PHONE_SETTINGS, WireJson.encode(settings).toString(Charsets.UTF_8))
            ?.apply()
    }

    fun setLocalVibrate(enabled: Boolean) {
        _localVibrate.value = enabled
        prefs?.edit()?.putBoolean(KEY_LOCAL_VIBRATE, enabled)?.apply()
    }
}

sealed interface ReplyStatus {
    data object Idle : ReplyStatus
    data class Sending(val key: String) : ReplyStatus
    data class Finished(val key: String, val ok: Boolean, val message: String?) : ReplyStatus
}

object ReplyStatusStore {
    private val _status = MutableStateFlow<ReplyStatus>(ReplyStatus.Idle)
    val status: StateFlow<ReplyStatus> = _status.asStateFlow()

    fun sending(key: String) {
        _status.value = ReplyStatus.Sending(key)
    }

    fun finished(key: String, ok: Boolean, message: String?) {
        _status.value = ReplyStatus.Finished(key, ok, message)
    }

    fun timeout(key: String) {
        _status.update {
            if (it is ReplyStatus.Sending && it.key == key) {
                ReplyStatus.Finished(key, false, "No response from phone")
            } else {
                it
            }
        }
    }

    fun reset() {
        _status.value = ReplyStatus.Idle
    }
}
