package com.example.mirroring

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import kotlin.concurrent.thread

object Discovery {
    const val PORT = 8766
    private const val PREFIX = "MIRRORING|"

    fun deviceName(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?: Build.MODEL

    fun broadcastLoop(name: String, isRunning: () -> Boolean) {
        DatagramSocket().use { s ->
            s.broadcast = true
            val data = (PREFIX + name).toByteArray()
            while (isRunning()) {
                for (addr in broadcastAddresses()) {
                    runCatching { s.send(DatagramPacket(data, data.size, addr, PORT)) }
                }
                Thread.sleep(1000)
            }
        }
    }

    private fun broadcastAddresses(): Set<InetAddress> {
        val set = mutableSetOf(InetAddress.getByName("255.255.255.255"))
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .forEach { ni -> ni.interfaceAddresses.mapNotNullTo(set) { it.broadcast } }
        }
        return set
    }

    class Listener(context: Context, private val onFound: (ip: String, name: String) -> Unit) {
        private val lock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("mirroring").apply { setReferenceCounted(false) }
        @Volatile
        private var socket: DatagramSocket? = null
        @Volatile
        private var active = false

        fun start() {
            active = true
            lock.acquire()
            thread {
                try {
                    val s = DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        bind(InetSocketAddress(PORT))
                    }
                    socket = s
                    if (!active) s.close()
                    val buf = ByteArray(256)
                    while (active) {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val msg = String(p.data, 0, p.length)
                        val ip = p.address.hostAddress ?: continue
                        if (msg.startsWith(PREFIX)) onFound(ip, msg.removePrefix(PREFIX))
                    }
                } catch (_: Exception) {
                }
            }
        }

        fun stop() {
            active = false
            runCatching { socket?.close() }
            socket = null
            if (lock.isHeld) lock.release()
        }
    }
}
