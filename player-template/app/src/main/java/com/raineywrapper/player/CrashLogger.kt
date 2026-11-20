package com.raineywrapper.player

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Log

object CrashLogger {
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val v = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "rainey-crash-" + app.packageName + ".txt")
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                }
                val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                uri?.let {
                    app.contentResolver.openOutputStream(it)?.use { o ->
                        o.write(Log.getStackTraceString(e).toByteArray())
                    }
                }
            } catch (_: Throwable) {}
            old?.uncaughtException(t, e)
        }
    }
}
