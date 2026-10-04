package com.ultradisplay.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Installs the crash catcher as early as possible, before any screen or service starts. */
class UltraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ErrorLog.init(this)
        CrashReporter.install(this)
    }
}

/**
 * Persistent log of everything that went wrong: crashes, caught errors and connection failures.
 * Every entry gets a short code like `UD8-Capture318-ISE` (version · file+line · exception) that is
 * enough to find the exact spot in the source.
 */
object ErrorLog {
    enum class Kind { CRASH, ERROR, PEER_CRASH }
    data class Entry(val time: String, val kind: Kind, val code: String, val title: String, val detail: String)

    private const val FILE = "errors.log"
    private const val SEP = "\u001E\n"
    private const val MAX_BYTES = 256 * 1024
    private var dir: File? = null
    private val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private var lastKey = ""; private var lastAt = 0L

    fun init(ctx: Context) { dir = ctx.applicationContext.filesDir }

    /** Short, stable code for an exception: UD<versionCode>-<File><line>-<Exception initials>. */
    fun code(t: Throwable): String {
        var root: Throwable = t
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val frame = (root.stackTrace + t.stackTrace).firstOrNull { it.className.startsWith("com.ultradisplay") }
        val where = frame?.let { (it.fileName ?: "X").removeSuffix(".kt").removeSuffix("Activity").removeSuffix("Service").take(10) + it.lineNumber } ?: "sys"
        val ex = root.javaClass.simpleName.filter { it.isUpperCase() }.ifEmpty { "E" }
        return "UD${BuildConfig.VERSION_CODE}-$where-$ex"
    }

    fun codeFor(message: String): String = "UD${BuildConfig.VERSION_CODE}-msg-" + Integer.toHexString(message.hashCode() and 0xffff).uppercase()

    fun stack(t: Throwable): String = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()

    fun device(): String = "UltraDisplay ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${Build.MANUFACTURER} ${Build.MODEL} · " +
        "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

    @Synchronized fun record(kind: Kind, title: String, error: Throwable? = null, detail: String = "") {
        val d = dir ?: return
        val code = error?.let { code(it) } ?: codeFor(title)
        // Collapse the same error repeating in a tight loop (e.g. every tick) into one entry.
        val now = System.currentTimeMillis()
        if (kind != Kind.CRASH && code == lastKey && now - lastAt < 30_000) return
        lastKey = code; lastAt = now
        val body = buildString {
            append(detail)
            if (error != null) { if (isNotEmpty()) append("\n"); append(stack(error).take(6000)) }
        }
        val line = listOf(clock.format(Date(now)), kind.name, code, title.replace('\n', ' ').take(300), body.replace(SEP, " "))
            .joinToString("\u001F") + SEP
        try {
            val f = File(d, FILE)
            if (f.length() > MAX_BYTES) f.writeText(f.readText().takeLast(MAX_BYTES / 2).substringAfter(SEP))
            f.appendText(line)
        } catch (_: Exception) {}
    }

    fun entries(): List<Entry> {
        val f = dir?.let { File(it, FILE) } ?: return emptyList()
        if (!f.exists()) return emptyList()
        return f.readText().split(SEP).filter { it.isNotBlank() }.mapNotNull { rec ->
            val p = rec.split("\u001F")
            if (p.size < 5) null else Entry(p[0], runCatching { Kind.valueOf(p[1]) }.getOrDefault(Kind.ERROR), p[2], p[3], p[4])
        }.reversed()
    }

    fun clear() { dir?.let { File(it, FILE).delete() } }

    fun format(list: List<Entry>, withDetail: Boolean = true): String = buildString {
        append(device()).append("\n\n")
        list.forEach { e ->
            append("[").append(e.time).append("] ").append(e.kind).append(" ").append(e.code).append("\n").append(e.title).append("\n")
            if (withDetail && e.detail.isNotBlank()) append(e.detail.trim()).append("\n")
            append("\n")
        }
    }

    /** Opens a pre-filled GitHub issue in the browser. The app itself has no internet permission. */
    fun reportOnGitHub(ctx: Context, entries: List<Entry>) {
        val first = entries.firstOrNull()
        val title = if (first != null) "${first.kind} ${first.code}: ${first.title.take(80)}" else "UltraDisplay report"
        val body = "```\n" + format(entries.take(5)).take(6000) + "\n```\n\n" + Session.logText(20).take(1500)
        val url = "https://github.com/1officiallyarza999/UltraDisplay/issues/new?title=" +
            URLEncoder.encode(title, "UTF-8") + "&body=" + URLEncoder.encode(body, "UTF-8")
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }
}

object CrashReporter {
    private const val FILE = "last_crash.txt"
    private const val SENT = "last_crash.sent"

    private fun isCrashProcess(): Boolean =
        android.os.Build.VERSION.SDK_INT >= 28 && Application.getProcessName().endsWith(":crash")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (isCrashProcess()) return // never loop: the crash screen itself uses the default handler
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val code = ErrorLog.code(error)
                val text = buildString {
                    append("CODE ").append(code).append('\n')
                    append(ErrorLog.device()).append('\n')
                    append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append(" · thread ").append(thread.name).append('\n')
                    append("mode=").append(Session.mode).append(" link=").append(Session.link).append(" streaming=").append(Session.streaming).append('\n')
                    append("--- log ---\n").append(Session.logText(15)).append("\n--- stack ---\n").append(ErrorLog.stack(error).take(6000))
                }
                File(app.filesDir, FILE).writeText(text)
                File(app.filesDir, SENT).delete()
                ErrorLog.record(ErrorLog.Kind.CRASH, "${error.javaClass.simpleName}: ${error.message ?: ""}", error,
                    "thread ${thread.name}\n--- log ---\n" + Session.logText(15))
            } catch (_: Throwable) {}
            // Show our own crash screen instead of the system "keeps stopping" dialog.
            try {
                app.startActivity(Intent(app, CrashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            } catch (_: Throwable) {}
            previous?.uncaughtException(thread, error)
        }
    }

    fun pending(ctx: Context): String? = File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText()
    fun pendingCode(ctx: Context): String? = pending(ctx)?.lineSequence()?.firstOrNull()?.removePrefix("CODE ")
    fun clear(ctx: Context) { File(ctx.filesDir, FILE).delete() }

    /** Send the last crash to the other device over the cable once, so it is visible on both screens. */
    fun sendToPeer(ctx: Context) {
        val text = pending(ctx) ?: return
        val sent = File(ctx.filesDir, SENT)
        if (sent.exists()) return
        Session.sendAsync(Wire.Packet(Wire.CRASH_REPORT, 0, 0, 0, text.take(60_000).toByteArray(Charsets.UTF_8)))
        try { sent.writeText("1") } catch (_: Exception) {}
    }

    /** Run [block] and keep the app alive if it throws; the error is logged with a code instead. */
    inline fun guard(where: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) {
            Session.log("⚠ $where: ${t.javaClass.simpleName}: ${t.message} [${ErrorLog.code(t)}]")
            ErrorLog.record(ErrorLog.Kind.ERROR, "$where: ${t.javaClass.simpleName}: ${t.message ?: ""}", t)
        }
    }
}
