package io.github.l33kr.networkcontrolcenter.nativecore

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DPI Control's own rootless stream engine.
 *
 * It speaks SOCKS5 to hev-socks5-tunnel, owns all TCP/UDP upstream sockets and
 * performs protocol-aware transformations itself. ByeDPI is not involved in
 * this code path.
 */
class NativeDpiProxy(private val context: Context) {
    private val running = AtomicBoolean(false)
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val policy = NativePolicyResolver(context)

    @Volatile
    private var serverSocket: ServerSocket? = null

    fun start(bindIp: String, port: Int): Int {
        if (!running.compareAndSet(false, true)) return -2
        NativeRuntime.reset()

        return try {
            ServerSocket().use { server ->
                server.reuseAddress = true
                server.bind(InetSocketAddress(bindIp, port), 128)
                serverSocket = server
                Log.i(TAG, "Native SOCKS5 listening on $bindIp:$port")

                while (running.get()) {
                    val client = try {
                        server.accept()
                    } catch (e: SocketException) {
                        if (!running.get()) break else throw e
                    }
                    clients += client
                    workerScope.launch { handleClient(client) }
                }
            }
            0
        } catch (t: Throwable) {
            if (running.get()) {
                Log.e(TAG, "Native proxy failed", t)
                NativeRuntime.error(t.message ?: t.javaClass.simpleName)
                -1
            } else {
                0
            }
        } finally {
            serverSocket = null
            running.set(false)
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        clients.toList().forEach { socket -> runCatching { socket.close() } }
        clients.clear()
        workerScope.cancel()
    }

    private suspend fun handleClient(client: Socket) {
        client.tcpNoDelay = true
        client.keepAlive = true

        val input = BufferedInputStream(client.getInputStream(), 16 * 1024)
        val output = BufferedOutputStream(client.getOutputStream(), 16 * 1024)

        try {
            negotiate(input, output)
            val request = readRequest(input)
            when (request.command) {
                CMD_CONNECT -> handleConnect(client, input, output, request.target)
                CMD_UDP_ASSOCIATE -> handleUdpAssociate(client, input, output)
                else -> sendReply(output, REP_COMMAND_NOT_SUPPORTED, null, 0)
            }
        } catch (t: Throwable) {
            if (running.get() && t !is EOFException && t !is SocketException) {
                Log.d(TAG, "SOCKS client ended: ${t.message}")
            }
        } finally {
            clients -= client
            runCatching { client.close() }
        }
    }

    private fun negotiate(input: InputStream, output: OutputStream) {
        if (readU8(input) != SOCKS_VERSION) throw SocksException("Unsupported SOCKS version")
        val methodCount = readU8(input)
        var supportsNoAuth = false
        repeat(methodCount) {
            if (readU8(input) == AUTH_NONE) supportsNoAuth = true
        }
        if (!supportsNoAuth) {
            output.write(byteArrayOf(SOCKS_VERSION.toByte(), AUTH_UNACCEPTABLE.toByte()))
            output.flush()
            throw SocksException("SOCKS client requires authentication")
        }
        output.write(byteArrayOf(SOCKS_VERSION.toByte(), AUTH_NONE.toByte()))
        output.flush()
    }

    private fun readRequest(input: InputStream): SocksRequest {
        if (readU8(input) != SOCKS_VERSION) throw SocksException("Invalid request version")
        val command = readU8(input)
        readU8(input) // RSV
        val target = readTarget(input)
        return SocksRequest(command, target)
    }

    private suspend fun handleConnect(
        client: Socket,
        clientInput: InputStream,
        clientOutput: OutputStream,
        target: SocksTarget,
    ) {
        val remote = Socket()
        remote.tcpNoDelay = true
        remote.keepAlive = true

        try {
            remote.connect(target.socketAddress(), CONNECT_TIMEOUT_MS)
            sendReply(
                clientOutput,
                REP_SUCCEEDED,
                remote.localAddress,
                remote.localPort,
            )

            NativeRuntime.flowOpened(target.displayHost)
            relayBidirectional(client, clientInput, clientOutput, remote, target)
        } catch (t: Throwable) {
            if (!remote.isConnected) {
                runCatching { sendReply(clientOutput, mapConnectError(t), null, 0) }
            }
            throw t
        } finally {
            NativeRuntime.flowClosed()
            runCatching { remote.close() }
        }
    }

    private suspend fun relayBidirectional(
        client: Socket,
        clientInput: InputStream,
        clientOutput: OutputStream,
        remote: Socket,
        target: SocksTarget,
    ) = coroutineScope {
        val upstream = launch(Dispatchers.IO) {
            try {
                relayClientToRemote(client, clientInput, remote.getOutputStream(), target)
            } finally {
                runCatching { remote.shutdownOutput() }
            }
        }
        val downstream = launch(Dispatchers.IO) {
            try {
                remote.getInputStream().copyTo(clientOutput, COPY_BUFFER_SIZE)
                clientOutput.flush()
            } finally {
                runCatching { client.shutdownOutput() }
            }
        }

        upstream.invokeOnCompletion {
            if (it != null) {
                runCatching { remote.close() }
                runCatching { client.close() }
            }
        }
        downstream.invokeOnCompletion {
            runCatching { remote.close() }
            runCatching { client.close() }
        }
        joinAll(upstream, downstream)
    }

    private fun relayClientToRemote(
        client: Socket,
        input: InputStream,
        remoteOutput: OutputStream,
        target: SocksTarget,
    ) {
        val initial = ByteArrayOutputStream(16 * 1024)
        val readBuffer = ByteArray(8 * 1024)
        var handled = false

        try {
            client.soTimeout = INITIAL_READ_TIMEOUT_MS
            while (initial.size() < MAX_INITIAL_BYTES && !handled) {
                val read = try {
                    input.read(readBuffer)
                } catch (_: SocketTimeoutException) {
                    break
                }
                if (read < 0) return
                if (read == 0) continue
                initial.write(readBuffer, 0, read)

                val bytes = initial.toByteArray()
                when (val parsed = TlsClientHelloParser.parse(bytes)) {
                    is TlsParseResult.NeedMore -> {
                        if (parsed.minimumBytes > MAX_INITIAL_BYTES) handled = true
                    }
                    TlsParseResult.NotTls -> {
                        val http = parseHttpHost(bytes)
                        if (http != null || !looksLikeHttp(bytes)) {
                            applyInitialPayload(bytes, null, http, target, remoteOutput)
                            initial.reset()
                            handled = true
                        }
                    }
                    is TlsParseResult.ClientHello -> {
                        NativeRuntime.tlsSeen(parsed.info.sni ?: target.displayHost)
                        applyInitialPayload(bytes, parsed.info, null, target, remoteOutput)
                        initial.reset()
                        handled = true
                    }
                }
            }
        } finally {
            runCatching { client.soTimeout = 0 }
        }

        if (initial.size() > 0) {
            val bytes = initial.toByteArray()
            applyInitialPayload(bytes, null, parseHttpHost(bytes), target, remoteOutput)
        }

        input.copyTo(remoteOutput, COPY_BUFFER_SIZE)
        remoteOutput.flush()
    }

    private fun applyInitialPayload(
        bytes: ByteArray,
        tls: TlsClientHelloInfo?,
        http: HttpHostInfo?,
        target: SocksTarget,
        output: OutputStream,
    ) {
        if (bytes.isEmpty()) return

        val host = tls?.sni ?: http?.host ?: target.displayHost
        val decision = policy.resolve(host, NativeTransport.TCP)
        if (decision.technique == NativeTechnique.PASS) {
            output.write(bytes)
            output.flush()
            return
        }

        when {
            tls != null && tls.sniStart != null && tls.sniEndExclusive != null -> {
                when (decision.technique) {
                    NativeTechnique.TLS_RECORD_SPLIT -> writeTlsRecordSplit(output, bytes, tls, delayMs = 0)
                    NativeTechnique.MULTI_SPLIT -> writeMultiSplit(output, bytes, tls.sniStart, tls.sniEndExclusive)
                    NativeTechnique.HYBRID -> writeTlsRecordSplit(output, bytes, tls, delayMs = HYBRID_DELAY_MS)
                    NativeTechnique.PASS -> Unit
                }
                NativeRuntime.transformed(host, decision.technique)
            }
            http != null -> {
                writeMultiSplit(output, bytes, http.hostStart, http.hostEndExclusive)
                NativeRuntime.transformed(host, NativeTechnique.MULTI_SPLIT)
            }
            else -> {
                output.write(bytes)
                output.flush()
            }
        }
    }

    /**
     * Re-encodes one TLS handshake record into two valid records, splitting in
     * the middle of SNI. The handshake length itself is unchanged and therefore
     * remains a protocol-valid ClientHello spanning two TLS records.
     */
    private fun writeTlsRecordSplit(
        output: OutputStream,
        data: ByteArray,
        info: TlsClientHelloInfo,
        delayMs: Long,
    ) {
        val sniStart = info.sniStart ?: return output.write(data)
        val sniEnd = info.sniEndExclusive ?: return output.write(data)
        val split = ((sniStart + sniEnd) / 2)
            .coerceIn(info.recordPayloadStart + 1, info.recordPayloadEndExclusive - 1)
        val firstLength = split - info.recordPayloadStart
        val secondLength = info.recordPayloadEndExclusive - split
        if (firstLength <= 0 || secondLength <= 0) {
            output.write(data)
            output.flush()
            return
        }

        val firstHeader = tlsHeader(data, firstLength)
        val secondHeader = tlsHeader(data, secondLength)

        output.write(firstHeader)
        output.write(data, info.recordPayloadStart, firstLength)
        output.flush()
        if (delayMs > 0) Thread.sleep(delayMs)

        output.write(secondHeader)
        output.write(data, split, secondLength)
        if (info.recordPayloadEndExclusive < data.size) {
            output.write(
                data,
                info.recordPayloadEndExclusive,
                data.size - info.recordPayloadEndExclusive,
            )
        }
        output.flush()
    }

    private fun tlsHeader(original: ByteArray, payloadLength: Int): ByteArray = byteArrayOf(
        original[0],
        original[1],
        original[2],
        ((payloadLength ushr 8) and 0xff).toByte(),
        (payloadLength and 0xff).toByte(),
    )

    private fun writeMultiSplit(
        output: OutputStream,
        data: ByteArray,
        markerStart: Int,
        markerEndExclusive: Int,
    ) {
        val middle = (markerStart + markerEndExclusive) / 2
        val positions = listOf(markerStart + 1, middle, markerEndExclusive - 1)
            .map { it.coerceIn(1, data.size - 1) }
            .distinct()
            .sorted()

        var offset = 0
        positions.forEach { position ->
            if (position <= offset) return@forEach
            output.write(data, offset, position - offset)
            output.flush()
            Thread.sleep(SPLIT_DELAY_MS)
            offset = position
        }
        if (offset < data.size) output.write(data, offset, data.size - offset)
        output.flush()
    }

    private fun looksLikeHttp(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        val prefix = data.copyOfRange(0, minOf(data.size, 12))
            .toString(StandardCharsets.US_ASCII)
            .uppercase()
        return HTTP_METHODS.any { prefix.startsWith(it) }
    }

    private fun parseHttpHost(data: ByteArray): HttpHostInfo? {
        if (!looksLikeHttp(data)) return null
        val text = data.toString(StandardCharsets.ISO_8859_1)
        val headerEnd = text.indexOf("\r\n\r\n")
        if (headerEnd < 0 && data.size < MAX_HTTP_HEADER_BYTES) return null

        var searchFrom = 0
        while (true) {
            val index = text.indexOf("\r\nHost:", searchFrom, ignoreCase = true)
            if (index < 0) return null
            var start = index + 7
            while (start < text.length && (text[start] == ' ' || text[start] == '\t')) start++
            var end = text.indexOf("\r\n", start)
            if (end < 0) end = text.length
            val raw = text.substring(start, end).trim()
            val host = raw.substringBefore(':').trim().lowercase()
            if (host.isNotBlank()) {
                val hostOffset = text.indexOf(host, start, ignoreCase = true)
                if (hostOffset >= 0) return HttpHostInfo(host, hostOffset, hostOffset + host.length)
            }
            searchFrom = end
        }
    }

    private suspend fun handleUdpAssociate(
        control: Socket,
        controlInput: InputStream,
        controlOutput: OutputStream,
    ) = coroutineScope {
        val relay = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(0))
            soTimeout = UDP_POLL_TIMEOUT_MS
        }

        sendReply(
            controlOutput,
            REP_SUCCEEDED,
            InetAddress.getByName("127.0.0.1"),
            relay.localPort,
        )

        val monitor = launch(Dispatchers.IO) {
            try {
                while (running.get() && controlInput.read() >= 0) Unit
            } catch (_: Throwable) {
                // Control connection closing ends the UDP association.
            } finally {
                runCatching { relay.close() }
            }
        }

        try {
            udpRelayLoop(relay)
        } finally {
            runCatching { relay.close() }
            monitor.cancel()
        }
    }

    private fun udpRelayLoop(relay: DatagramSocket) {
        val buffer = ByteArray(MAX_UDP_PACKET)
        var clientEndpoint: SocketAddress? = null

        while (running.get() && !relay.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                relay.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: SocketException) {
                break
            }

            val source = packet.socketAddress
            val bytes = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
            val fromClient = source == clientEndpoint || (clientEndpoint == null && looksLikeSocksUdp(bytes))

            if (fromClient) {
                if (clientEndpoint == null) clientEndpoint = source
                val datagram = runCatching { parseUdpDatagram(bytes) }.getOrNull() ?: continue
                if (datagram.fragment != 0) continue
                val destination = datagram.target.socketAddress()
                NativeRuntime.udpPacket(datagram.target.displayHost)
                relay.send(
                    DatagramPacket(
                        datagram.payload,
                        datagram.payload.size,
                        destination.address,
                        destination.port,
                    ),
                )
            } else {
                val client = clientEndpoint ?: continue
                val remote = source as? InetSocketAddress ?: continue
                val wrapped = encodeUdpResponse(remote.address, remote.port, bytes)
                relay.send(DatagramPacket(wrapped, wrapped.size, client))
            }
        }
    }

    private fun looksLikeSocksUdp(data: ByteArray): Boolean =
        data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
            (data[3].toInt() and 0xff) in setOf(ATYP_IPV4, ATYP_DOMAIN, ATYP_IPV6)

    private fun parseUdpDatagram(data: ByteArray): SocksUdpDatagram {
        if (data.size < 4 || data[0] != 0.toByte() || data[1] != 0.toByte()) {
            throw SocksException("Invalid UDP header")
        }
        val fragment = data[2].toInt() and 0xff
        var offset = 3
        val target = readTargetFromBytes(data, offset)
        offset = target.second
        return SocksUdpDatagram(fragment, target.first, data.copyOfRange(offset, data.size))
    }

    private fun readTargetFromBytes(data: ByteArray, startOffset: Int): Pair<SocksTarget, Int> {
        var offset = startOffset
        if (offset >= data.size) throw SocksException("Missing UDP ATYP")
        val atyp = data[offset++].toInt() and 0xff
        val host: String?
        val address: InetAddress?

        when (atyp) {
            ATYP_IPV4 -> {
                requireRemaining(data, offset, 4 + 2)
                address = InetAddress.getByAddress(data.copyOfRange(offset, offset + 4))
                host = null
                offset += 4
            }
            ATYP_IPV6 -> {
                requireRemaining(data, offset, 16 + 2)
                address = InetAddress.getByAddress(data.copyOfRange(offset, offset + 16))
                host = null
                offset += 16
            }
            ATYP_DOMAIN -> {
                requireRemaining(data, offset, 1)
                val length = data[offset++].toInt() and 0xff
                requireRemaining(data, offset, length + 2)
                host = data.copyOfRange(offset, offset + length).toString(StandardCharsets.US_ASCII)
                address = null
                offset += length
            }
            else -> throw SocksException("Unsupported UDP ATYP $atyp")
        }

        val port = ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
        offset += 2
        return SocksTarget(host, address, port) to offset
    }

    private fun encodeUdpResponse(address: InetAddress, port: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 32)
        out.write(0)
        out.write(0)
        out.write(0)
        when (address) {
            is Inet4Address -> {
                out.write(ATYP_IPV4)
                out.write(address.address)
            }
            is Inet6Address -> {
                out.write(ATYP_IPV6)
                out.write(address.address)
            }
            else -> throw SocksException("Unknown IP family")
        }
        out.write((port ushr 8) and 0xff)
        out.write(port and 0xff)
        out.write(payload)
        return out.toByteArray()
    }

    private fun readTarget(input: InputStream): SocksTarget {
        return when (val atyp = readU8(input)) {
            ATYP_IPV4 -> SocksTarget(null, InetAddress.getByAddress(readExact(input, 4)), readPort(input))
            ATYP_IPV6 -> SocksTarget(null, InetAddress.getByAddress(readExact(input, 16)), readPort(input))
            ATYP_DOMAIN -> {
                val length = readU8(input)
                val host = readExact(input, length).toString(StandardCharsets.US_ASCII)
                SocksTarget(host, null, readPort(input))
            }
            else -> throw SocksException("Unsupported ATYP $atyp")
        }
    }

    private fun sendReply(output: OutputStream, reply: Int, address: InetAddress?, port: Int) {
        val resolved = address ?: InetAddress.getByName("0.0.0.0")
        output.write(SOCKS_VERSION)
        output.write(reply)
        output.write(0)
        when (resolved) {
            is Inet6Address -> {
                output.write(ATYP_IPV6)
                output.write(resolved.address)
            }
            else -> {
                output.write(ATYP_IPV4)
                output.write(resolved.address.takeLast(4).toByteArray())
            }
        }
        output.write((port ushr 8) and 0xff)
        output.write(port and 0xff)
        output.flush()
    }

    private fun readPort(input: InputStream): Int = (readU8(input) shl 8) or readU8(input)

    private fun readU8(input: InputStream): Int {
        val value = input.read()
        if (value < 0) throw EOFException()
        return value
    }

    private fun readExact(input: InputStream, length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(result, offset, length - offset)
            if (read < 0) throw EOFException()
            offset += read
        }
        return result
    }

    private fun requireRemaining(data: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset + length > data.size) {
            throw SocksException("Truncated SOCKS UDP packet")
        }
    }

    private fun mapConnectError(t: Throwable): Int = when (t) {
        is java.net.ConnectException -> REP_CONNECTION_REFUSED
        is java.net.NoRouteToHostException -> REP_NETWORK_UNREACHABLE
        is java.net.SocketTimeoutException -> REP_TTL_EXPIRED
        else -> REP_GENERAL_FAILURE
    }

    private data class SocksRequest(val command: Int, val target: SocksTarget)

    private data class SocksTarget(
        val host: String?,
        val address: InetAddress?,
        val port: Int,
    ) {
        val displayHost: String get() = host?.lowercase() ?: address?.hostAddress.orEmpty()
        fun socketAddress(): InetSocketAddress = if (host != null) {
            InetSocketAddress(host, port)
        } else {
            InetSocketAddress(address ?: error("Missing address"), port)
        }
    }

    private data class SocksUdpDatagram(
        val fragment: Int,
        val target: SocksTarget,
        val payload: ByteArray,
    )

    private data class HttpHostInfo(
        val host: String,
        val hostStart: Int,
        val hostEndExclusive: Int,
    )

    private class SocksException(message: String) : Exception(message)

    companion object {
        private const val TAG = "NativeDpiProxy"
        private const val SOCKS_VERSION = 5
        private const val AUTH_NONE = 0
        private const val AUTH_UNACCEPTABLE = 0xff
        private const val CMD_CONNECT = 1
        private const val CMD_UDP_ASSOCIATE = 3
        private const val ATYP_IPV4 = 1
        private const val ATYP_DOMAIN = 3
        private const val ATYP_IPV6 = 4

        private const val REP_SUCCEEDED = 0
        private const val REP_GENERAL_FAILURE = 1
        private const val REP_NETWORK_UNREACHABLE = 3
        private const val REP_CONNECTION_REFUSED = 5
        private const val REP_TTL_EXPIRED = 6
        private const val REP_COMMAND_NOT_SUPPORTED = 7

        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val INITIAL_READ_TIMEOUT_MS = 1_200
        private const val UDP_POLL_TIMEOUT_MS = 1_000
        private const val MAX_INITIAL_BYTES = 64 * 1024
        private const val MAX_HTTP_HEADER_BYTES = 32 * 1024
        private const val MAX_UDP_PACKET = 65_507
        private const val COPY_BUFFER_SIZE = 32 * 1024
        private const val SPLIT_DELAY_MS = 5L
        private const val HYBRID_DELAY_MS = 8L

        private val HTTP_METHODS = listOf(
            "GET ", "POST ", "HEAD ", "PUT ", "DELETE ", "OPTIONS ", "PATCH ", "CONNECT ",
        )
    }
}
