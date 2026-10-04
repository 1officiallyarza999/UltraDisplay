package com.ultradisplay.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/**
 * Owns the USB connection for the whole app, independent of any screen. Keeps looking for the
 * other device, reconnects by itself after a cable wiggle, and (on the phone) can start
 * streaming automatically as soon as the tablet is connected.
 */
class LinkService : Service() {
    companion object {
        const val ACTION_CONNECT = "ultra.link.CONNECT"
        const val ACTION_STOP = "ultra.link.STOP"
        private const val CHANNEL = "ultradisplay_link"
        private const val CHANNEL_ALERT = "ultradisplay_ready"
        @Volatile var instance: LinkService? = null; private set

        fun start(ctx: Context, action: String = ACTION_CONNECT) {
            // A plain start is allowed while the app is on screen and can never hit the "did not call
            // startForeground in time" crash; the service promotes itself to foreground in onCreate.
            val i = Intent(ctx, LinkService::class.java).setAction(action)
            try { ctx.startService(i) } catch (e: Exception) {
                ErrorLog.record(ErrorLog.Kind.ERROR, "LinkService start refused: ${e.javaClass.simpleName}", e)
            }
        }
        fun connectNow(ctx: Context) { instance?.link?.connect() ?: start(ctx) }
        fun reset() { instance?.link?.reset() }
    }

    lateinit var link: UsbLink; private set
    private val handler = Handler(Looper.getMainLooper())
    private var lastConnected = false
    private var autoStarted = false
    private var announcedStream = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbLink.PERMISSION -> link.onPermissionResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                    Session.log(tr("זוהה חיבור USB", "USB connection detected"))
                    handler.postDelayed({ link.connect() }, 400)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED, UsbManager.ACTION_USB_ACCESSORY_DETACHED ->
                    if (Session.wire != null) Session.detach(tr("הכבל נותק", "Cable disconnected"))
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            CrashReporter.guard("link tick") {
                if (Session.wire == null) link.connect()
                onState()
            }
            handler.postDelayed(this, 1500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Lang.load(this)
        Session.init(this); TouchInjector.init(this); ShizukuBridge.init(this)
        link = UsbLink(applicationContext)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("חיבור בכבל", "Cable connection"), NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, tr("מוכן לצפייה", "Ready to view"), NotificationManager.IMPORTANCE_HIGH))
        foreground()
        val filter = IntentFilter().apply {
            addAction(UsbLink.PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED); addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, filter)
        handler.post(tick)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).setAction(if (Session.mode == Session.Mode.SEND) MainActivity.ACTION_AUTO_START else Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun foreground() {
        val text = when {
            Session.streaming -> tr("משדר עכשיו", "Streaming now")
            Session.wire != null -> tr("מחובר ל-", "Connected to ") + Session.peerName.ifBlank { tr("המכשיר השני", "the other device") }
            else -> tr("ממתין לכבל — יתחבר אוטומטית", "Waiting for cable — connects automatically")
        }
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("UltraDisplay").setContentText(text).setOngoing(true).setContentIntent(openApp())
            .build()
        if (Build.VERSION.SDK_INT < 29) { startForeground(11, n); return }
        try { startForeground(11, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE); return }
        catch (e: Exception) { if (!fgsWarned) ErrorLog.record(ErrorLog.Kind.ERROR, "FGS connectedDevice refused", e) }
        if (Build.VERSION.SDK_INT >= 34) {
            try { startForeground(11, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE); return }
            catch (e: Exception) { if (!fgsWarned) ErrorLog.record(ErrorLog.Kind.ERROR, "FGS specialUse refused", e) }
        }
        // Keep running as a normal service while the app is open rather than crashing.
        if (!fgsWarned) Session.log(tr("⚠ אנדרואיד לא אישר חיבור ברקע — יעבוד כשהאפליקציה פתוחה", "⚠ Android refused background mode — works while the app is open"))
        fgsWarned = true
    }
    private var fgsWarned = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            else -> link.connect()
        }
        return START_STICKY
    }

    /** Runs every tick: refresh the notification and handle automatic actions on (dis)connect. */
    private fun onState() {
        val connected = Session.wire != null
        if (connected != lastConnected) {
            lastConnected = connected
            autoStarted = false; announcedStream = false
            foreground()
        }
        if (!connected) return
        val prefs = Prefs.of(this)

        // Phone: start streaming by itself once the tablet is there.
        if (Session.mode == Session.Mode.SEND && !Session.streaming && !autoStarted && prefs.autoStream &&
            Session.peerMode == Session.Mode.RECEIVE) {
            autoStarted = true
            if (prefs.tabletMode && ShizukuBridge.ready && Session.peerW > 0) {
                Session.log(tr("מתחיל מסך טאבלט אוטומטית", "Starting tablet screen automatically"))
                try {
                    startService(Intent(this, CaptureService::class.java)
                        .setAction(CaptureService.ACTION_START_TABLET).putExtra(CaptureService.EXTRA_QUALITY, prefs.preset))
                } catch (e: Exception) {
                    // Android may refuse to start it while the app is in the background: ask the user to tap instead.
                    Session.log(tr("אנדרואיד חסם התחלה ברקע — הקש על ההתראה", "Android blocked a background start — tap the notification"))
                    notifyReady(tr("הטאבלט מחובר", "Tablet connected"), tr("הקש כדי להפעיל את מסך הטאבלט", "Tap to start the tablet screen"))
                }
            } else {
                // Screen mirroring always needs the system's share-screen approval, so open the app to ask for it.
                try {
                    startActivity(Intent(this, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_AUTO_START).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                } catch (_: Exception) {}
                notifyReady(tr("הטאבלט מחובר", "Tablet connected"), tr("הקש כדי להתחיל לשדר", "Tap to start streaming"))
            }
        }

        // Tablet: tell the user the picture is ready if the viewer is not open.
        if (Session.mode == Session.Mode.RECEIVE && Session.lastConfig != null && !announcedStream && !MainActivity.viewerOpen) {
            announcedStream = true
            try { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) } catch (_: Exception) {}
            notifyReady(tr("הטלפון משדר", "The phone is streaming"), tr("הקש כדי לצפות", "Tap to view"))
        }
        if (Session.lastConfig == null) announcedStream = false
    }

    private fun notifyReady(title: String, text: String) {
        val n = Notification.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle(title).setContentText(text)
            .setContentIntent(openApp()).setAutoCancel(true).setTimeoutAfter(30_000).build()
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(12, n) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        if (Session.streaming) try { startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)) } catch (_: Exception) {}
        Session.detach(tr("החיבור נסגר", "Connection closed"))
        link.close()
        instance = null
        super.onDestroy()
    }
}

/** Starts the connection service after a reboot when automatic connection is enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val p = Prefs.of(ctx)
            if (p.autoConnect && p.onboarded) try { LinkService.start(ctx) } catch (_: Exception) {}
        }
    }
}
