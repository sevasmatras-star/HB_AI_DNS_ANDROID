package ai.hb.smartdns

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.Socket
import java.net.HttpURLConnection
import java.net.URL
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** DNS-only IPv4/UDP forwarder. */
class DnsVpnService : VpnService() {
    companion object {
        const val EXTRA_PRIMARY = "primary"
        const val EXTRA_SECONDARY = "secondary"
        const val EXTRA_PROVIDER = "provider"
        private const val VPN_DNS = "10.111.0.2"
        private const val VPN_DNS_V6 = "fd00:111::2"
        private const val CHANNEL = "hb_smart_dns"
        private const val NOTIFICATION_ID = 71
        @Volatile var isRunning: Boolean = false
            private set
    }

    private var tun: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    @Volatile private var primary = "94.250.253.55"
    @Volatile private var secondary = "212.109.195.93"
    @Volatile private var providerName = "Smart DNS"

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "HB AI Smart DNS", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        primary = intent?.getStringExtra(EXTRA_PRIMARY) ?: primary
        secondary = intent?.getStringExtra(EXTRA_SECONDARY) ?: secondary
        providerName = intent?.getStringExtra(EXTRA_PROVIDER) ?: providerName
        stopTunnel()
        try {
            val descriptor = Builder()
                .setSession("HB AI · $providerName")
                .addAddress("10.111.0.1", 30)
                .addAddress("fd00:111::1", 64)
                .addDnsServer(VPN_DNS)
                .addDnsServer(VPN_DNS_V6)
                .addRoute(VPN_DNS, 32)
                .addRoute(VPN_DNS_V6, 128)
                .setMtu(1500)
                .establish() ?: run { stopSelf(); return START_NOT_STICKY }
            tun = descriptor
            startForeground(NOTIFICATION_ID, buildNotification())
            running.set(true)
            isRunning = true
            thread(name = "HB-DNS-TUN", isDaemon = true) { loop(descriptor) }
        } catch (_: Exception) {
            stopTunnel()
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun loop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val packet = ByteArray(32767)
        while (running.get()) {
            try {
                val n = input.read(packet)
                if (n < 28) continue
                when ((packet[0].toInt() ushr 4) and 0xF) {
                    4 -> handleIpv4Udp(packet, n, output)
                    6 -> handleIpv6Udp(packet, n, output)
                    else -> continue
                }
            } catch (_: Exception) {
                if (!running.get()) break
            }
        }
        try { input.close() } catch (_: Exception) {}
        try { output.close() } catch (_: Exception) {}
    }

    private fun handleIpv4Udp(packet: ByteArray, length: Int, output: FileOutputStream) {
        val ihl = (packet[0].toInt() and 0xF) * 4
        if (ihl < 20 || length < ihl + 8 || (packet[9].toInt() and 0xFF) != 17) return
        if (u16(packet, ihl + 2) != 53) return
        val udpLength = u16(packet, ihl + 4)
        val dnsLength = udpLength - 8
        if (dnsLength < 12 || ihl + udpLength > length) return
        val query = packet.copyOfRange(ihl + 8, ihl + 8 + dnsLength)
        val response = queryUpstream(query) ?: return
        val clientAddress = packet.copyOfRange(12, 16)
        val virtualDnsAddress = packet.copyOfRange(16, 20)
        val clientPort = u16(packet, ihl)
        output.write(buildIpv4Udp(virtualDnsAddress, clientAddress, 53, clientPort, response))
    }

    private fun handleIpv6Udp(packet: ByteArray, length: Int, output: FileOutputStream) {
        if (length < 48 || (packet[6].toInt() and 0xFF) != 17) return // IPv6 extension headers are not supported.
        val udpOffset = 40
        if (u16(packet, udpOffset + 2) != 53) return
        val udpLength = u16(packet, udpOffset + 4)
        val dnsLength = udpLength - 8
        if (dnsLength < 12 || udpOffset + udpLength > length) return
        val query = packet.copyOfRange(udpOffset + 8, udpOffset + 8 + dnsLength)
        val response = queryUpstream(query) ?: return
        val clientAddress = packet.copyOfRange(8, 24)
        val virtualDnsAddress = packet.copyOfRange(24, 40)
        val clientPort = u16(packet, udpOffset)
        output.write(buildIpv6Udp(virtualDnsAddress, clientAddress, 53, clientPort, response))
    }

    private fun buildIpv6Udp(src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLength = 8 + payload.size
        val out = ByteArray(40 + udpLength)
        out[0] = 0x60
        put16(out, 4, udpLength)
        out[6] = 17
        out[7] = 64
        System.arraycopy(src, 0, out, 8, 16)
        System.arraycopy(dst, 0, out, 24, 16)
        put16(out, 40, srcPort)
        put16(out, 42, dstPort)
        put16(out, 44, udpLength)
        System.arraycopy(payload, 0, out, 48, payload.size)
        // IPv6 requires a non-zero UDP checksum.
        put16(out, 46, ipv6UdpChecksum(src, dst, out, 40, udpLength))
        return out
    }

    private fun ipv6UdpChecksum(src: ByteArray, dst: ByteArray, packet: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        fun add(bytes: ByteArray, start: Int, count: Int) {
            var i = start
            while (i + 1 < start + count) { sum += u16(bytes, i).toLong(); i += 2 }
            if (i < start + count) sum += ((bytes[i].toInt() and 0xFF) shl 8).toLong()
        }
        add(src, 0, 16); add(dst, 0, 16)
        sum += ((length ushr 16) and 0xFFFF).toLong()
        sum += (length and 0xFFFF).toLong()
        sum += 17L
        add(packet, offset, length)
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        val result = sum.inv().toInt() and 0xFFFF
        return if (result == 0) 0xFFFF else result
    }

    private fun queryUpstream(query: ByteArray): ByteArray? {
        for (server in listOf(primary, secondary).distinct()) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                if (!protect(socket)) return null
                socket.soTimeout = 2500
                socket.send(DatagramPacket(query, query.size, InetAddress.getByName(server), 53))
                val buffer = ByteArray(4096)
                val reply = DatagramPacket(buffer, buffer.size)
                socket.receive(reply)
                if (reply.length >= 12 && buffer[0] == query[0] && buffer[1] == query[1]) {
                    val udpResponse = buffer.copyOf(reply.length)
                    // Retry a truncated UDP answer over TCP to the upstream DNS server.
                    if ((udpResponse[2].toInt() and 0x02) != 0) {
                        val tcpResponse = queryUpstreamTcp(server, query)
                        if (tcpResponse != null) return tcpResponse
                    }
                    return udpResponse
                }
            } catch (_: SocketTimeoutException) {
                // Try secondary resolver.
            } catch (_: Exception) {
                // Try secondary resolver.
            } finally {
                socket?.close()
            }
        }
        // If UDP/TCP DNS is unavailable, retry through public DNS-over-HTTPS.
        // The DoH fallback is protected by the Android network stack; it does not
        // carry ordinary application traffic through a remote VPN server.
        return queryDoh(query)
    }

    private fun queryDoh(query: ByteArray): ByteArray? {
        val endpoints = listOf(
            "https://cloudflare-dns.com/dns-query",
            "https://dns.google/dns-query"
        )
        for (endpoint in endpoints) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 3000
                    readTimeout = 3000
                    setRequestProperty("Accept", "application/dns-message")
                    setRequestProperty("Content-Type", "application/dns-message")
                }
                connection.outputStream.use { it.write(query) }
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.use { it.readBytes() }
                    if (response.size >= 12 && response[0] == query[0] && response[1] == query[1]) {
                        return response
                    }
                }
            } catch (_: Exception) {
                // Try the second DoH endpoint.
            } finally {
                connection?.disconnect()
            }
        }
        return null
    }

    private fun queryUpstreamTcp(server: String, query: ByteArray): ByteArray? {
        return try {
            Socket().use { tcp ->
                if (!protect(tcp)) return null
                tcp.connect(java.net.InetSocketAddress(server, 53), 2500)
                tcp.soTimeout = 3000
                val output = DataOutputStream(tcp.getOutputStream())
                output.writeShort(query.size)
                output.write(query)
                output.flush()
                val input = DataInputStream(tcp.getInputStream())
                val length = input.readUnsignedShort()
                if (length < 12 || length > 4096) return null
                val response = ByteArray(length)
                input.readFully(response)
                if (response[0] == query[0] && response[1] == query[1]) response else null
            }
        } catch (_: Exception) { null }
    }

    private fun buildIpv4Udp(src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLength = 8 + payload.size
        val out = ByteArray(20 + udpLength)
        out[0] = 0x45
        put16(out, 2, out.size)
        put16(out, 4, (System.nanoTime() and 0xFFFF).toInt())
        out[6] = 0x40
        out[8] = 64
        out[9] = 17
        System.arraycopy(src, 0, out, 12, 4)
        System.arraycopy(dst, 0, out, 16, 4)
        put16(out, 10, checksum(out, 0, 20))
        put16(out, 20, srcPort)
        put16(out, 22, dstPort)
        put16(out, 24, udpLength)
        System.arraycopy(payload, 0, out, 28, payload.size)
        return out
    }

    private fun checksum(bytes: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        while (i < offset + length - 1) { sum += u16(bytes, i).toLong(); i += 2 }
        if (i < offset + length) sum += ((bytes[i].toInt() and 0xFF) shl 8).toLong()
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv().toInt() and 0xFFFF
    }

    private fun u16(bytes: ByteArray, index: Int) =
        ((bytes[index].toInt() and 0xFF) shl 8) or (bytes[index + 1].toInt() and 0xFF)

    private fun put16(bytes: ByteArray, index: Int, value: Int) {
        bytes[index] = (value ushr 8).toByte()
        bytes[index + 1] = value.toByte()
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("HB AI Smart DNS подключён")
            .setContentText("$providerName · DNS $primary")
            .setOngoing(true)
            .build()
    }

    private fun stopTunnel() {
        running.set(false)
        isRunning = false
        try { tun?.close() } catch (_: Exception) {}
        tun = null
    }

    override fun onDestroy() {
        stopTunnel()
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        super.onDestroy()
    }

    override fun onRevoke() {
        stopTunnel()
        stopSelf()
        super.onRevoke()
    }
}
