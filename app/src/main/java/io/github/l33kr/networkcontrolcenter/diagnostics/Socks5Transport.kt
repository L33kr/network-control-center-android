package io.github.l33kr.networkcontrolcenter.diagnostics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Explicit SOCKS5 for every diagnostic packet, including UDP DNS and STUN. */
internal class Socks5Transport(host: String, private val port: Int) : Closeable {
    private val proxyAddress = numericAddress(when (host) {
        "0.0.0.0" -> "127.0.0.1"
        "::" -> "::1"
        else -> host
    })
    private val resources = mutableSetOf<Closeable>()
    @Volatile private var closed = false

    private fun <T : Closeable> track(value: T): T = synchronized(resources) {
        if (closed) {
            value.close()
            throw IOException("Проверка отменена")
        }
        resources += value
        value
    }

    private fun release(value: Closeable) {
        synchronized(resources) { resources.remove(value) }
        runCatching { value.close() }
    }

    private fun openControl(): Socket {
        val socket = track(Socket())
        try {
            socket.soTimeout = TIMEOUT_MS
            socket.connect(InetSocketAddress(proxyAddress, port), TIMEOUT_MS)
            socket.getOutputStream().write(byteArrayOf(5, 1, 0))
            val input = DataInputStream(socket.getInputStream())
            if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 0) {
                throw IOException("SOCKS5: ядро не приняло соединение")
            }
            return socket
        } catch (t: Throwable) {
            release(socket)
            throw t
        }
    }

    private fun request(socket: Socket, command: Int, address: InetAddress, port: Int): InetSocketAddress {
        val out = DataOutputStream(socket.getOutputStream())
        out.write(byteArrayOf(5, command.toByte(), 0))
        writeAddress(out, address, port)
        out.flush()
        val input = DataInputStream(socket.getInputStream())
        if (input.readUnsignedByte() != 5) throw IOException("SOCKS5: неверная версия")
        val reply = input.readUnsignedByte()
        if (reply != 0) throw IOException("SOCKS5: ошибка соединения $reply")
        if (input.readUnsignedByte() != 0) throw IOException("SOCKS5: неверный ответ")
        return readAddress(input)
    }

    fun <T> tcp(address: InetAddress, port: Int, block: (Socket) -> T): T {
        val socket = openControl()
        try {
            request(socket, 1, address, port)
            return block(socket)
        } finally {
            release(socket)
        }
    }

    fun udp(address: InetAddress, port: Int, payload: ByteArray): ByteArray {
        val control = openControl()
        try {
            // Keep this TCP control connection open for the lifetime of the UDP association.
            val bound = request(control, 3, InetAddress.getByAddress(ByteArray(4)), 0)
            val relay = InetSocketAddress(if (bound.address.isAnyLocalAddress) proxyAddress else bound.address, bound.port)
            val socket = track(DatagramSocket())
            try {
                socket.soTimeout = TIMEOUT_MS
                socket.connect(relay)
                val bytes = ByteArrayOutputStream()
                DataOutputStream(bytes).use { out ->
                    out.write(byteArrayOf(0, 0, 0))
                    writeAddress(out, address, port)
                    out.write(payload)
                }
                val encoded = bytes.toByteArray()
                socket.send(DatagramPacket(encoded, encoded.size))
                val packet = DatagramPacket(ByteArray(65507), 65507)
                socket.receive(packet)
                val input = DataInputStream(ByteArrayInputStream(packet.data, 0, packet.length))
                if (input.readUnsignedShort() != 0 || input.readUnsignedByte() != 0) {
                    throw IOException("SOCKS5 UDP: повреждённый или фрагментированный ответ")
                }
                val source = readAddress(input)
                if (source.address != address || source.port != port) {
                    throw IOException("SOCKS5 UDP: ответ от другого сервера")
                }
                return ByteArray(input.available()).also { input.readFully(it) }
            } finally {
                release(socket)
            }
        } finally {
            release(control)
        }
    }

    override fun close() {
        val open = synchronized(resources) {
            closed = true
            resources.toList().also { resources.clear() }
        }
        open.forEach { runCatching { it.close() } }
    }

    companion object {
        const val TIMEOUT_MS = 3500

        fun numericAddress(value: String): InetAddress {
            // Restrict input to numeric addresses before getByName; no system DNS lookup.
            val validV4 = value.split('.').let { parts ->
                parts.size == 4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) && (it.toIntOrNull() ?: -1) in 0..255 }
            }
            val validV6 = ':' in value && value.all { it in "0123456789abcdefABCDEF:." }
            if (!validV4 && !validV6) throw IOException("Нужен IP-адрес DNS, без https:// и имени сайта")
            return InetAddress.getByName(value)
        }

        private fun writeAddress(out: DataOutputStream, address: InetAddress, port: Int) {
            out.writeByte(if (address.address.size == 4) 1 else 4)
            out.write(address.address)
            out.writeShort(port)
        }

        private fun readAddress(input: DataInputStream): InetSocketAddress {
            val size = when (input.readUnsignedByte()) {
                1 -> 4
                4 -> 16
                else -> throw IOException("SOCKS5: ожидался числовой адрес")
            }
            val bytes = ByteArray(size).also { input.readFully(it) }
            return InetSocketAddress(InetAddress.getByAddress(bytes), input.readUnsignedShort())
        }
    }
}
