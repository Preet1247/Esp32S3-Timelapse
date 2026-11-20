package com.watchlink.mobile

import android.content.Context
import com.watchlink.shared.DEFAULT_QUICK_REPLIES
import com.watchlink.shared.WatchSettings

/** SharedPreferences-backed user settings (mute list, quick replies, vibrate). */
object WatchLinkPrefs {
    private const val FILE = "watchlink_prefs"
    private const val KEY_MUTED = "muted_packages"
    private const val KEY_VIBRATE = "vibrate"
    private const val KEY_REPLIES = "quick_replies"
    private const val SEP = "\u001F"

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun mutedPackages(context: Context): Set<String> =
        sp(context).getStringSet(KEY_MUTED, null)?.toSet() ?: emptySet()

    fun isMuted(context: Context, packageName: String): Boolean =
        mutedPackages(context).contains(packageName)

    fun setMuted(context: Context, packageName: String, muted: Boolean) {
        val set = mutedPackages(context).toMutableSet()
        if (muted) set.add(packageName) else set.remove(packageName)
        sp(context).edit().putStringSet(KEY_MUTED, set).apply()
    }

    fun vibrate(context: Context): Boolean = sp(context).getBoolean(KEY_VIBRATE, true)

    fun setVibrate(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_VIBRATE, value).apply()
    }

    fun quickReplies(context: Context): List<String> {
        val raw = sp(context).getString(KEY_REPLIES, null) ?: return DEFAULT_QUICK_REPLIES
        return raw.split(SEP).filter { it.isNotEmpty() }
    }

    fun setQuickReplies(context: Context, replies: List<String>) {
        sp(context).edit().putString(KEY_REPLIES, replies.joinToString(SEP)).apply()
    }

    fun watchSettings(context: Context): WatchSettings =
        WatchSettings(vibrate = vibrate(context), quickReplies = quickReplies(context))
}
