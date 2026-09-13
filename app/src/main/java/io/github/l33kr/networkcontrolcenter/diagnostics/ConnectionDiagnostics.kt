package io.github.l33kr.networkcontrolcenter.diagnostics

import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiConfig
import io.github.l33kr.networkcontrolcenter.byedpi.DpiDnsPolicy
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class CheckTarget(val name: String, val host: String, val udpPort: Int? = null)

data class CheckResult(
    val target: CheckTarget,
    val stage: String,
    val detail: String,
    val elapsedMs: Long,
    val resolver: String? = null,
    val address: String? = null,
    val httpCode: Int? = null,
    val reached: Boolean = false,
) {
    fun reportLine(): String = buildString {
        append("${target.name} (${target.host}): $stage — $detail; ${elapsedMs} мс")
        resolver?.let { append("; DNS=$it") }
        address?.let { append("; IP=$it") }
    }
}

/** BlockCheck-style tests against the currently running SOCKS5 engine, never a direct HTTP client. */
class ConnectionDiagnostics(private val config: ByeDpiConfig) : Closeable {
    private val transport = Socks5Transport(config.bindIp, config.port)
    private val random = SecureRandom()

    companion object {
        // ZapretGUI b5543b6, src/blockcheck/data_lists.py. WhatsApp Web is an Android-side addition.
        val targets = listOf(
            CheckTarget("YouTube", "www.youtube.com"),
            CheckTarget("Instagram", "www.instagram.com"),
            CheckTarget("WhatsApp Web", "web.whatsapp.com"),
            CheckTarget("Discord", "discord.com"),
            CheckTarget("UDP / STUN", "stun.l.google.com", 19302),
        )

        fun validDns(value: String): Boolean = runCatching {
            !Socks5Transport.numericAddress(value.trim()).isAnyLocalAddress
        }.getOrDefault(false)

        fun validSni(value: String): Boolean {
            val sni = value.trim()
            return sni.length in 1..253 && sni.split('.').all { label ->
                label.length in 1..63 && !label.startsWith('-') && !label.endsWith('-') &&
                    label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "-?*#" }
            }
        }

        internal fun validStunResponse(response: ByteArray, transaction: ByteArray): Boolean {
            if (response.size < 20 || transaction.size != 12) return false
            val buffer = ByteBuffer.wrap(response)
            val type = buffer.short.toInt() and 65535
            val length = buffer.short.toInt() and 65535
            return type == 0x0101 && length % 4 == 0 && length + 20 == response.size &&
                buffer.int == 0x2112a442 && response.copyOfRange(8, 20).contentEquals(transaction)
        }

        internal fun tlsParameters(host: String): SSLParameters = SSLParameters().apply {
            endpointIdentificationAlgorithm = "HTTPS"
            serverNames = listOf(SNIHostName(host))
        }
    }

    suspend fun check(target: CheckTarget): CheckResult = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val started = System.nanoTime()
        var stage = "DNS через ByeDPI"
        var resolver: String? = null
        var address: String? = null
        try {
            val resolved = resolve(target.host)
            resolver = resolved.resolver
            var failure: Exception? = null
            // A second address often belongs to another CDN edge. Keep attempts bounded.
            for (ip in resolved.addresses.take(2)) {
                currentCoroutineContext().ensureActive()
                address = ip.hostAddress
                try {
                    if (target.udpPort != null) {
                        stage = "UDP через ByeDPI"
                        val transaction = ByteArray(12).also(random::nextBytes)
                        val request = ByteBuffer.allocate(20)
                            .putShort(1.toShort()).putShort(0.toShort()).putInt(0x2112a442).put(transaction).array()
                        val response = transport.udp(ip, target.udpPort, request)
                        if (!validStunResponse(response, transaction)) throw IOException("Нет корректного ответа STUN")
                        return@withContext CheckResult(target, "UDP", "Ответ STUN получен", elapsed(started), resolver, address, reached = true)
                    }
                    stage = "TCP / SOCKS5"
                    val code = transport.tcp(ip, 443) { socket ->
                        stage = "TLS"
                        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                        (factory.createSocket(socket, target.host, 443, true) as SSLSocket).use { tls ->
                            tls.soTimeout = Socks5Transport.TIMEOUT_MS
                            tls.sslParameters = tlsParameters(target.host)
                            tls.startHandshake() // System trust store + hostname verification stay enabled.
                            stage = "HTTP"
                            tls.outputStream.write(
                                ("GET / HTTP/1.1\r\nHost: ${target.host}\r\n" +
                                    "User-Agent: DPI-Control-Check\r\nRange: bytes=0-0\r\nConnection: close\r\n\r\n")
                                    .toByteArray(Charsets.US_ASCII),
                            )
                            tls.outputStream.flush()
                            val status = StringBuilder()
                            while (status.length < 1024) {
                                val next = tls.inputStream.read()
                                if (next < 0 || next == 10) break
                                status.append(next.toChar())
                            }
                            Regex("^HTTP/1\\.[01] ([1-5][0-9]{2})(?: |\\r|$)")
                                .find(status.toString())?.groupValues?.get(1)?.toInt()
                                ?: throw IOException("TLS установлен, но нет корректного HTTP-ответа")
                        }
                    }
                    return@withContext CheckResult(
                        target, "HTTPS", "TLS подтверждён · HTTP $code", elapsed(started), resolver, address, code, reached = true,
                    )
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    failure = e
                }
            }
            throw failure ?: IOException("Нет адреса для проверки")
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            CheckResult(target, stage, e.message ?: e.javaClass.simpleName, elapsed(started), resolver, address)
        }
    }

    private fun resolve(host: String): Resolution {
        val errors = mutableListOf<String>()
        for (server in DpiDnsPolicy.servers(config.dns)) {
            try {
                val address = Socks5Transport.numericAddress(server)
                val id = random.nextInt(65536)
                val query = DnsPacket.query(host, id)
                val packet = transport.udp(address, 53, query)
                val addresses = try {
                    DnsPacket.addresses(packet, host, id)
                } catch (_: TruncatedDnsResponse) {
                    // DNS truncation requires retry over TCP to the same selected resolver.
                    val full = transport.tcp(address, 53) { socket ->
                        DataOutputStream(socket.getOutputStream()).apply {
                            writeShort(query.size)
                            write(query)
                            flush()
                        }
                        val input = DataInputStream(socket.getInputStream())
                        val size = input.readUnsignedShort()
                        if (size !in 12..65535) throw IOException("DNS: неверная длина TCP-ответа")
                        ByteArray(size).also { input.readFully(it) }
                    }
                    DnsPacket.addresses(full, host, id)
                }
                return Resolution(addresses, server)
            } catch (e: Exception) {
                errors += "$server: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw IOException(errors.joinToString("; "))
    }

    private fun elapsed(start: Long): Long = (System.nanoTime() - start) / 1_000_000
    override fun close() = transport.close()
    private data class Resolution(val addresses: List<InetAddress>, val resolver: String)
}
