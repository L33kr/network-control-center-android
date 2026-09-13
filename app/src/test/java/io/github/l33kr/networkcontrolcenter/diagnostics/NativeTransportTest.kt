package io.github.l33kr.networkcontrolcenter.diagnostics

import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiConfig
import java.io.DataInputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real pinned Linux ByeDPI, no Internet or physical DPI claims. Required in CI. */
class NativeTransportTest {
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    private fun withEngine(config: ByeDpiConfig, block: (Int) -> Unit) {
        val executable = System.getenv("BYEDPI_TEST_BINARY")
        assumeTrue("Set BYEDPI_TEST_BINARY to the compiled pinned core", executable != null && File(executable).canExecute())
        val port = ServerSocket(0, 1, loopback).use { it.localPort }
        val log = File.createTempFile("byedpi-test-", ".log")
        val process = ProcessBuilder(listOf(executable!!) + config.copy(port = port).toArgs().drop(1))
            .redirectErrorStream(true).redirectOutput(log).start()
        try {
            var ready = false
            for (attempt in 0..100) {
                if (!process.isAlive) fail("ByeDPI rejected generated args, exit=${process.exitValue()}: ${log.readText().takeLast(2000)}")
                ready = runCatching {
                    Socket().use { socket ->
                        socket.soTimeout = 500
                        socket.connect(InetSocketAddress(loopback, port), 200)
                        socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                        val input = DataInputStream(socket.getInputStream())
                        input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0
                    }
                }.getOrDefault(false)
                if (ready) break
                Thread.sleep(20)
            }
            assertTrue("SOCKS5 did not become ready: ${log.readText().takeLast(2000)}", ready)
            block(port)
        } finally {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(2, TimeUnit.SECONDS)
            log.delete()
        }
    }

    @Test fun pinnedEngineAcceptsPure16AndTheExistingMetaGroups() {
        withEngine(ByeDpiConfig()) { }
        withEngine(ByeDpiConfig(metaCompatibility = true)) { }
    }

    @Test fun tcpAndUdpChecksUseTheRealSocksProxy() {
        // An empty strategy passes loopback test data unchanged; #16 syntax is checked above.
        withEngine(ByeDpiConfig(command = "")) { proxyPort ->
            val pool = Executors.newFixedThreadPool(2)
            try {
                ServerSocket(0, 1, loopback).use { server ->
                    server.soTimeout = 5000
                    val echoed = pool.submit {
                        server.accept().use { peer ->
                            peer.soTimeout = 5000
                            val request = DataInputStream(peer.getInputStream()).readInt()
                            assertEquals(0x12345678, request)
                            peer.getOutputStream().write(byteArrayOf(9, 8, 7, 6))
                        }
                    }
                    Socks5Transport("127.0.0.1", proxyPort).use { transport ->
                        transport.tcp(loopback, server.localPort) { socket ->
                            socket.getOutputStream().write(byteArrayOf(0x12, 0x34, 0x56, 0x78))
                            assertEquals(0x09080706, DataInputStream(socket.getInputStream()).readInt())
                        }
                    }
                    echoed.get(6, TimeUnit.SECONDS)
                }
                DatagramSocket(0, loopback).use { server ->
                    server.soTimeout = 5000
                    val echoed = pool.submit {
                        val packet = DatagramPacket(ByteArray(1024), 1024)
                        server.receive(packet)
                        server.send(packet)
                    }
                    Socks5Transport("127.0.0.1", proxyPort).use { transport ->
                        val payload = "UDP through ByeDPI".toByteArray()
                        assertArrayEquals(payload, transport.udp(loopback, server.localPort, payload))
                    }
                    echoed.get(6, TimeUnit.SECONDS)
                }
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test fun cancelClosesABlockedSocksHandshake() {
        ServerSocket(0, 1, loopback).use { server ->
            server.soTimeout = 3000
            val pool = Executors.newSingleThreadExecutor()
            val transport = Socks5Transport("127.0.0.1", server.localPort)
            try {
                val blocked = pool.submit<Boolean> {
                    runCatching { transport.tcp(loopback, 443) { } }.isFailure
                }
                server.accept().use {
                    transport.close()
                    assertTrue(blocked.get(1, TimeUnit.SECONDS))
                }
            } finally {
                transport.close()
                pool.shutdownNow()
            }
        }
    }
}
