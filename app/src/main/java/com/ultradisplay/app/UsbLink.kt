package com.ultradisplay.app

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors

/** Tab A8 must be USB host. It uses standard USB control requests to put S25 into AOA mode. */
class UsbLink(private val context: Context, private val onReady: (Wire, String) -> Unit) {
    companion object { const val PERMISSION = "com.ultradisplay.app.USB_PERMISSION" }
    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val worker = Executors.newSingleThreadExecutor()
    private var connection: UsbDeviceConnection? = null
    private var accessoryFd: ParcelFileDescriptor? = null
    private fun permissionIntent() = PendingIntent.getBroadcast(context, 0, Intent(PERMISSION).setPackage(context.packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    fun attachPhone() {
        val accessory = manager.accessoryList?.firstOrNull {
            it.manufacturer == "UltraDisplay" && it.model == "UltraDisplayHost"
        }
        if (accessory == null) { Session.update("Phone: waiting for AOA connection from tablet"); return }
        if (!manager.hasPermission(accessory)) { manager.requestPermission(accessory, permissionIntent()); return }
        worker.execute {
            try {
                accessoryFd?.close()
                val fd = manager.openAccessory(accessory) ?: error("openAccessory returned null")
                accessoryFd = fd
                onReady(Wire(FileInputStream(fd.fileDescriptor), FileOutputStream(fd.fileDescriptor)), "PHONE")
            } catch (e: Exception) { Session.update("Phone USB error: ${e.message}") }
        }
    }
    fun attachTablet() {
        val devices = manager.deviceList.values.toList()
        val aoa = devices.firstOrNull { it.vendorId == 0x18d1 && (it.productId == 0x2d00 || it.productId == 0x2d01) }
        val candidate = aoa ?: devices.firstOrNull { it.interfaceCount > 0 && it.deviceClass != UsbConstants.USB_CLASS_HUB }
        if (candidate == null) { Session.update("Tablet: no USB device. Set tablet as USB host."); return }
        if (!manager.hasPermission(candidate)) { manager.requestPermission(candidate, permissionIntent()); return }
        worker.execute {
            try {
                if (candidate === aoa) openAoA(candidate) else initiateAoA(candidate)
            } catch (e: Exception) { Session.update("USB: ${e.message}") }
        }
    }
    private fun initiateAoA(dev: UsbDevice) {
        val c = manager.openDevice(dev) ?: error("Cannot open USB device")
        try {
            val version = ByteArray(2)
            val count = c.controlTransfer(0xC0, 51, 0, 0, version, 2, 2000)
            if (count != 2 || version[0].toInt() == 0) error("S25 does not expose AOA in this USB role")
            val labels = arrayOf("UltraDisplay", "UltraDisplayHost", "S25 to Tab A8 wired display", "0.1", "https://example.invalid", "UD-DEV")
            labels.forEachIndexed { index, label ->
                val bytes = (label + "\u0000").toByteArray(Charsets.UTF_8)
                if (c.controlTransfer(0x40, 52, 0, index, bytes, bytes.size, 2000) < 0) error("AOA string $index rejected")
            }
            if (c.controlTransfer(0x40, 53, 0, 0, null, 0, 2000) < 0) error("AOA start failed")
        } finally {
            c.close()
        }
        Session.update("AOA requested. Waiting for USB re-enumeration…")
        Thread.sleep(1200)
        // The ACTION_USB_DEVICE_ATTACHED broadcast also triggers a fresh scan.
        android.os.Handler(android.os.Looper.getMainLooper()).post { attachTablet() }
    }
    private fun openAoA(dev: UsbDevice) {
        val c = manager.openDevice(dev) ?: error("Cannot open AOA device")
        val iface = (0 until dev.interfaceCount).map { dev.getInterface(it) }.firstOrNull { intf ->
            (0 until intf.endpointCount).count { intf.getEndpoint(it).type == UsbConstants.USB_ENDPOINT_XFER_BULK } >= 2
        } ?: error("No AOA bulk interface")
        if (!c.claimInterface(iface, true)) { c.close(); error("Claim interface failed") }
        var input: UsbEndpoint? = null; var output: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) input = ep else output = ep
        }
        val inEp = input ?: error("AOA IN missing")
        val outEp = output ?: error("AOA OUT missing")
        connection = c
        // stream adapters avoid a mandatory TCP/IP stack over the USB cable.
        onReady(Wire(UsbBulkInput(c, inEp), UsbBulkOutput(c, outEp)), "TABLET")
    }
    fun close() { try { accessoryFd?.close(); connection?.close() } catch (_: Exception) {}; worker.shutdownNow() }
}

private class UsbBulkInput(private val conn: UsbDeviceConnection, private val ep: UsbEndpoint) : java.io.InputStream() {
    private val buf = ByteArray(16384)
    private var offset = 0; private var available = 0
    override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 255 }
    override fun read(dst: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (offset == available) {
            // timeouts let disconnect close the USB connection instead of an infinite block
            var n: Int
            do { n = conn.bulkTransfer(ep, buf, buf.size, 1500) } while (n == 0)
            if (n < 0) throw java.io.IOException("USB read failed or timed out")
            offset = 0; available = n
        }
        val n = minOf(len, available - offset)
        System.arraycopy(buf, offset, dst, off, n); offset += n; return n
    }
}
private class UsbBulkOutput(private val conn: UsbDeviceConnection, private val ep: UsbEndpoint) : java.io.OutputStream() {
    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
    override fun write(bytes: ByteArray, off: Int, len: Int) {
        var index = off
        while (index < off + len) {
            val part = minOf(16384, off + len - index)
            val copied = bytes.copyOfRange(index, index + part)
            val n = conn.bulkTransfer(ep, copied, copied.size, 1500)
            if (n <= 0) throw java.io.IOException("USB write failed")
            index += n
        }
    }
}
