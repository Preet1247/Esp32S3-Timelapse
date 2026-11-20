package com.watchlink.mobile

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle

object PendingIntentSender {
    /** Sends [pi], allowing background activity start on Android 14+. Throws on failure. */
    fun send(context: Context, pi: PendingIntent?, fillIn: Intent? = null) {
        if (pi == null) throw IllegalStateException("No PendingIntent available")
        val options: Bundle? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val o = ActivityOptions.makeBasic()
            o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            o.toBundle()
        } else {
            null
        }
        pi.send(context, 0, fillIn, null, null, null, options)
    }
}
