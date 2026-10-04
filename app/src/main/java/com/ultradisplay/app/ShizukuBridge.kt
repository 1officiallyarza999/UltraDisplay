package com.ultradisplay.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import rikka.shizuku.Shizuku

/** App-side connection to Shizuku and our [ShellService]. Everything here is optional. */
object ShizukuBridge {
    enum class State { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, CONNECTING, READY, ERROR }

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 4242

    @Volatile var service: IUltraShell? = null; private set
    @Volatile var state: State = State.NOT_RUNNING; private set
    @Volatile var lastError: String = ""; private set
    private var app: Context? = null
    @Volatile private var bound = false

    val ready: Boolean get() = service != null

    fun init(context: Context) {
        if (app != null) { refresh(); return }
        app = context.applicationContext
        try {
            Shizuku.addBinderReceivedListenerSticky { refresh() }
            Shizuku.addBinderDeadListener { service = null; bound = false; refresh() }
            Shizuku.addRequestPermissionResultListener { _, result ->
                if (result == PackageManager.PERMISSION_GRANTED) { Session.log("Shizuku: הגישה אושרה"); refresh() }
                else { lastError = "הגישה ל-Shizuku נדחתה"; refresh() }
            }
        } catch (e: Throwable) { lastError = e.message ?: "Shizuku error" }
        refresh()
    }

    fun refresh() {
        val ctx = app ?: return
        state = try {
            when {
                !installed(ctx) -> State.NOT_INSTALLED
                !Shizuku.pingBinder() -> State.NOT_RUNNING
                Shizuku.isPreV11() -> { lastError = "עדכן את Shizuku לגרסה חדשה"; State.ERROR }
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> State.NO_PERMISSION
                service != null -> State.READY
                else -> { bind(ctx); State.CONNECTING }
            }
        } catch (e: Throwable) { lastError = e.message ?: "Shizuku error"; State.ERROR }
    }

    fun requestPermission() {
        try { if (Shizuku.pingBinder()) Shizuku.requestPermission(REQUEST_CODE) } catch (e: Throwable) { lastError = e.message ?: "" }
    }

    fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    fun openShizuku(ctx: Context) {
        val launch = ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (launch != null) { ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PACKAGE")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(market) }
        catch (_: Exception) {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PACKAGE")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) { bound = false; return }
            val s = IUltraShell.Stub.asInterface(binder)
            service = s
            val uid = try { s.uid() } catch (_: Exception) { -1 }
            Session.log("Shizuku מחובר (uid $uid) — מגע מלא זמין")
            // If the app was killed while the phone was matched to the tablet, put the phone back now.
            app?.let { ctx -> if (!Session.streaming && Prefs.of(ctx).restoreCmd != null) Thread { DisplayMatch.restore(ctx) }.start() }
            refresh()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; bound = false; refresh() }
    }

    private fun bind(ctx: Context) {
        if (bound) return
        bound = true
        try {
            val args = Shizuku.UserServiceArgs(ComponentName(ctx.packageName, ShellService::class.java.name))
                .daemon(false).processNameSuffix("shell").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
            Shizuku.bindUserService(args, connection)
        } catch (e: Throwable) { bound = false; lastError = e.message ?: "bind failed" }
    }

    /** Start an activity on a given display using shell privileges. Returns the `am` output. */
    fun launch(component: ComponentName, displayId: Int, clearTask: Boolean = false): String {
        val s = service ?: return "no shizuku"
        val flags = if (clearTask) "0x10008000" else "0x10000000"
        return try { s.exec("am start --display $displayId -f $flags -n ${component.flattenToString()}") }
        catch (e: Exception) { "error: ${e.message}" }
    }

    fun launchHome(displayId: Int) {
        val ctx = app ?: return
        launch(ComponentName(ctx.packageName, LauncherActivity::class.java.name), displayId)
    }
}
