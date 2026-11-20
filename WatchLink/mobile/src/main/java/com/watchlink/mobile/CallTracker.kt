package com.watchlink.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.watchlink.shared.CallPhase
import com.watchlink.shared.CallState
import com.watchlink.shared.Paths
import com.watchlink.shared.WireJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-wide call state; de-duplicates updates from the telephony callback and the broadcast receiver. */
object CallTracker {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile
    var phase: CallPhase = CallPhase.IDLE
        private set

    @Volatile
    var number: String? = null
        private set

    /** Last known call number; survives the IDLE transition (used for SMS-reply after hang-up). */
    @Volatile
    var lastNumber: String? = null
        private set

    private var hintName: String? = null
    private var startedAt = 0L
    private var cacheNumber: String? = null
    private var cacheName: String? = null
    private var cachePhoto: String? = null

    suspend fun update(context: Context, newPhase: CallPhase, rawNumber: String?) {
        val app = context.applicationContext
        val state = mutex.withLock {
            val cleaned = rawNumber?.takeIf { it.isNotBlank() }
            val newNumber = if (newPhase == CallPhase.IDLE) null else (cleaned ?: number)
            if (newPhase == phase && newNumber == number) return@withLock null
            if (newPhase != phase) {
                startedAt = if (newPhase == CallPhase.IDLE) 0L else System.currentTimeMillis()
                if (newPhase == CallPhase.IDLE) hintName = null
            }
            phase = newPhase
            number = newNumber
            if (newNumber != null) lastNumber = newNumber
            buildState(app)
        } ?: return
        WearMessenger.sendToWatches(app, Paths.CALL_STATE, WireJson.encode(state))
    }

    /** The dialer's CATEGORY_CALL notification title is a fallback caller name. */
    suspend fun noteCallNotification(context: Context, title: String?) {
        val t = title?.takeIf { it.isNotBlank() } ?: return
        val app = context.applicationContext
        val state = mutex.withLock {
            if (hintName == t) return@withLock null
            hintName = t
            if (phase == CallPhase.IDLE) null else buildState(app)
        } ?: return
        WearMessenger.sendToWatches(app, Paths.CALL_STATE, WireJson.encode(state))
    }

    suspend fun snapshot(context: Context): CallState {
        val app = context.applicationContext
        return mutex.withLock { buildState(app) }
    }

    private fun buildState(context: Context): CallState {
        val n = number
        if (phase == CallPhase.IDLE || n == null) {
            return CallState(
                phase = phase,
                number = n,
                callerName = if (phase == CallPhase.IDLE) null else hintName,
                startedAt = startedAt,
            )
        }
        if (cacheNumber != n) {
            val (name, photo) = ContactLookup.lookup(context, n)
            cacheNumber = n
            cacheName = name
            cachePhoto = photo
        }
        return CallState(
            phase = phase,
            number = n,
            callerName = cacheName ?: hintName,
            callerPhotoPng = cachePhoto,
            startedAt = startedAt,
        )
    }
}

object ContactLookup {
    /** Returns (displayName, base64 photo PNG); both null if unknown or no permission. */
    fun lookup(context: Context, number: String): Pair<String?, String?> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) return Pair(null, null)
        var name: String? = null
        var photoUri: String? = null
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number),
            )
            val projection = arrayOf(
                ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.PHOTO_URI,
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    photoUri = c.getString(1)
                }
            }
        } catch (e: Exception) {
            // ignore: contact lookup is best effort
        }
        var photo: String? = null
        val pu = photoUri
        if (pu != null) {
            try {
                context.contentResolver.openInputStream(Uri.parse(pu))?.use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    if (bmp != null) photo = ImageUtil.bitmapToBase64Scaled(bmp, 128)
                }
            } catch (e: Exception) {
                // ignore
            }
        }
        return Pair(name, photo)
    }
}
