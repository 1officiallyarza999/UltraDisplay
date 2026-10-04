package com.ultradisplay.app

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves the stack trace of any crash so the next launch can show it (and the user can send it). */
class UltraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}

object CrashReporter {
    private const val FILE = "last_crash.txt"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter(); error.printStackTrace(PrintWriter(sw))
                val text = buildString {
                    append("UltraDisplay ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
                    append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" · Android ").append(Build.VERSION.RELEASE)
                    append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
                    append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append(" · thread ").append(thread.name).append('\n')
                    append("mode=").append(Session.mode).append(" link=").append(Session.link).append(" streaming=").append(Session.streaming).append('\n')
                    append("--- log ---\n").append(Session.logText(15)).append("\n--- stack ---\n").append(sw.toString().take(6000))
                }
                File(app.filesDir, FILE).writeText(text)
            } catch (_: Throwable) {}
            previous?.uncaughtException(thread, error)
        }
    }

    fun pending(ctx: Context): String? = File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText()
    fun clear(ctx: Context) { File(ctx.filesDir, FILE).delete() }

    /** Run [block] and keep the app alive if it throws; the error goes to the on-screen log instead. */
    inline fun guard(where: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { Session.log("⚠ $where: ${t.javaClass.simpleName}: ${t.message}") }
    }
}
