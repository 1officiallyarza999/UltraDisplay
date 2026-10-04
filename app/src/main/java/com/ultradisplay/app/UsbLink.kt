package com.ultradisplay.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.*
import android.os.SystemClock
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Symmetric USB link: whichever device Android made the USB host switches the other one into
 * Android Open Accessory mode; the other device opens the accessory. The user-facing role
 * (send / show) is independent of which side ended up as USB host.
 */
class UsbLink(private val context: Context) {
    companion object {
        const val PERMISSION = "com.ultradisplay.app.USB_PERMISSION"
        const val MANUFACTURER = "UltraDisplay"
        const val MODEL = "UltraDisplayHost"
        private const val GOOGLE_VID = 0x18d1
        fun isAoa(d: UsbDevice) = d.vendorId == GOOGLE_VID && d.productId in 0x2d00..0x2d05
    }

    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var busy = false
    @Volatile private var permissionAskedAt = 0L
    private var lastScan = ""

    private fun permissionIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(PERMISSION).setPackage(context.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    fun connect() {
        if (busy || Session.wire != null) return
        busy = true
        worker.execute {
            try { scan() }
            catch (e: Exception) { Session.set(Session.Link.ERROR, e.message ?: tr("שגיאת USB", "USB error")) }
            finally { busy = false }
        }
    }

    fun onPermissionResult(granted: Boolean) {
        permissionAskedAt = 0L
        if (granted) { Session.log(tr("הרשאת USB אושרה", "USB permission granted")); connect() }
        else Session.set(Session.Link.ERROR, tr("הרשאת USB נדחתה — לחץ \"חבר מחדש\" ואשר", "USB permission denied — tap Reconnect and allow"))
    }

    fun reset() { permissionAskedAt = 0L; lastScan = "" }

    /** Returns true if a permission request is (still) pending, so the caller should stop for now. */
    private fun ask(request: () -> Unit) {
        val now = SystemClock.uptimeMillis()
        if (permissionAskedAt != 0L && now - permissionAskedAt < 20_000) {
            Session.set(Session.Link.CONNECTING, tr("אשר את בקשת הגישה ל-USB שעל המסך", "Allow the USB access request on screen"))
            return
        }
        permissionAskedAt = now
        Session.set(Session.Link.CONNECTING, tr("אשר את בקשת הגישה ל-USB שעל המסך", "Allow the USB access request on screen"))
        Session.log(tr("מבקש הרשאת USB", "Requesting USB permission"))
        request()
    }

    private fun scan() {
        if (Session.wire != null) return

        // 1) This device is the USB peripheral and the other side already switched us to accessory mode.
        val accessory = manager.accessoryList?.firstOrNull { it.manufacturer == MANUFACTURER && it.model == MODEL }
        if (accessory != null) {
            if (!manager.hasPermission(accessory)) { ask { manager.requestPermission(accessory, permissionIntent()) }; return }
            openAccessory(accessory); return
        }

        // 2) This device is the USB host.
        val devices = manager.deviceList.values.filter { it.deviceClass != UsbConstants.USB_CLASS_HUB }
        val summary = if (devices.isEmpty()) tr("אין התקן במצב מארח", "no device in host mode")
            else devices.joinToString { "%04x:%04x %s".format(it.vendorId, it.productId, it.productName ?: "") }
        if (summary != lastScan) { lastScan = summary; Session.log(tr("סריקת USB: ", "USB scan: ") + summary) }

        val aoa = devices.firstOrNull { isAoa(it) }
        if (aoa != null) {
            if (!manager.hasPermission(aoa)) { ask { manager.requestPermission(aoa, permissionIntent()) }; return }
            openAoa(aoa); return
        }
        val other = devices.firstOrNull()
        if (other == null) {
            if (Session.link != Session.Link.ERROR && Session.link != Session.Link.CONNECTING)
                Session.set(Session.Link.SEARCHING, tr("ממתין לחיבור כבל", "Waiting for cable"))
            return
        }
        if (!manager.hasPermission(other)) { ask { manager.requestPermission(other, permissionIntent()) }; return }
        switchToAccessory(other)
    }

    private fun switchToAccessory(dev: UsbDevice) {
        Session.set(Session.Link.CONNECTING, tr("מעביר את המכשיר השני למצב UltraDisplay…", "Switching the other device to UltraDisplay mode…"))
        val c = manager.openDevice(dev) ?: throw IOException(tr("לא ניתן לפתוח את התקן ה-USB", "Cannot open the USB device"))
        try {
            val v = ByteArray(2)
            val n = c.controlTransfer(0xC0, 51, 0, 0, v, 2, 1000)
            val version = if (n == 2) (v[0].toInt() and 0xff) or ((v[1].toInt() and 0xff) shl 8) else 0
            if (version < 1) throw IOException(tr("המכשיר השני לא עונה ל-AOA (n=$n). ודא שהכבל תומך בנתונים", "The other device does not answer AOA (n=$n). Make sure the cable supports data"))
            Session.log("AOA v$version")
            val labels = arrayOf(MANUFACTURER, MODEL, "UltraDisplay wired screen", "0.2",
                "https://github.com/1officiallyarza999/UltraDisplay", "UD-0002")
            labels.forEachIndexed { index, label ->
                val bytes = (label + "\u0000").toByteArray(Charsets.UTF_8)
                if (c.controlTransfer(0x40, 52, 0, index, bytes, bytes.size, 1000) < 0) throw IOException("AOA: מחרוזת $index נדחתה")
            }
            if (c.controlTransfer(0x40, 53, 0, 0, null, 0, 1000) < 0) throw IOException("AOA: פקודת ההתחלה נדחתה")
        } finally { c.close() }

        Session.log(tr("המכשיר השני מתחבר מחדש במצב AOA…", "Other device re-enumerating in AOA mode…"))
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(300)
            val aoa = manager.deviceList.values.firstOrNull { isAoa(it) } ?: continue
            if (!manager.hasPermission(aoa)) { permissionAskedAt = 0L; ask { manager.requestPermission(aoa, permissionIntent()) }; return }
            openAoa(aoa); return
        }
        throw IOException(tr("המכשיר השני לא חזר במצב AOA. ודא ש-UltraDisplay מותקן ופתוח בו", "The other device did not return in AOA mode. Make sure UltraDisplay is installed and open on it"))
    }

    private fun openAoa(dev: UsbDevice) {
        val c = manager.openDevice(dev) ?: throw IOException("לא ניתן לפתוח את התקן ה-AOA")
        val iface = (0 until dev.interfaceCount).map { dev.getInterface(it) }.firstOrNull { intf ->
            (0 until intf.endpointCount).count { intf.getEndpoint(it).type == UsbConstants.USB_ENDPOINT_XFER_BULK } >= 2
        }
        if (iface == null) { c.close(); throw IOException("לא נמצא ממשק AOA") }
        if (!c.claimInterface(iface, true)) { c.close(); throw IOException("לא ניתן לתפוס את ממשק ה-USB") }
        var input: UsbEndpoint? = null; var output: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) input = ep else output = ep
        }
        val inEp = input; val outEp = output
        if (inEp == null || outEp == null) { c.close(); throw IOException("חסרים ערוצי AOA") }
        val bulkIn = UsbBulkInput(c, inEp)
        val wire = Wire(bulkIn, UsbBulkOutput(c, outEp)) {
            bulkIn.closed = true
            try { c.releaseInterface(iface) } catch (_: Exception) {}
            c.close()
        }
        Session.attach(wire, tr("מארח USB", "USB host"))
    }

    private fun openAccessory(accessory: UsbAccessory) {
        val pfd = manager.openAccessory(accessory) ?: throw IOException("openAccessory נכשל")
        val fd = pfd.fileDescriptor
        val wire = Wire(FileInputStream(fd), FileOutputStream(fd)) { pfd.close() }
        Session.attach(wire, tr("אביזר USB", "USB accessory"))
    }

    fun close() { worker.shutdownNow() }
}

private class UsbBulkInput(private val conn: UsbDeviceConnection, private val ep: UsbEndpoint) : java.io.InputStream() {
    @Volatile var closed = false
    private val buf = ByteArray(16384)
    private var offset = 0; private var available = 0
    override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 255 }
    override fun read(dst: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (offset == available) {
            var fastFailures = 0
            while (true) {
                if (closed) throw IOException("USB closed")
                val started = SystemClock.uptimeMillis()
                val n = conn.bulkTransfer(ep, buf, buf.size, 1000)
                if (n > 0) { offset = 0; available = n; break }
                // n == 0: zero-length packet. n < 0 after ~1s: idle timeout. n < 0 instantly: device gone.
                if (n < 0 && SystemClock.uptimeMillis() - started < 100) {
                    if (++fastFailures > 5) throw IOException("USB read failed")
                    Thread.sleep(20)
                }
            }
        }
        val n = minOf(len, available - offset)
        System.arraycopy(buf, offset, dst, off, n); offset += n; return n
    }
}

private class UsbBulkOutput(private val conn: UsbDeviceConnection, private val ep: UsbEndpoint) : java.io.OutputStream() {
    private var sinceFlush = 0L
    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
    override fun write(bytes: ByteArray, off: Int, len: Int) {
        var index = off
        while (index < off + len) {
            val part = minOf(16384, off + len - index)
            val chunk = if (index == 0 && part == bytes.size) bytes else bytes.copyOfRange(index, index + part)
            val n = conn.bulkTransfer(ep, chunk, part, 2000)
            if (n <= 0) throw IOException("USB write failed")
            index += n; sinceFlush += n
        }
    }
    /** End each packet with a short transfer so the accessory side's read returns immediately. */
    override fun flush() {
        val max = ep.maxPacketSize.coerceAtLeast(1)
        if (sinceFlush > 0 && sinceFlush % max == 0L && sinceFlush % 16384 != 0L) conn.bulkTransfer(ep, ByteArray(0), 0, 500)
        sinceFlush = 0
    }
}
