package io.github.l33kr.networkcontrolcenter.byedpi

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class StrategyTestResult(
    val strategy: CatalogStrategy,
    val successCount: Int,
    val totalCount: Int,
) {
    val percent: Int
        get() = if (totalCount == 0) 0 else successCount * 100 / totalCount
}

object ByeDpiStrategyTester {
    /**
     * A broader service coverage set than a single test domain.
     *
     * The list intentionally mixes YouTube page/API/media endpoints with Discord
     * web/gateway/CDN endpoints. A strategy that opens only youtube.com but breaks
     * googlevideo/ytimg should no longer be reported as fully working.
     */
    private val testHosts = listOf(
        // YouTube / Google media
        "www.youtube.com",
        "youtubei.googleapis.com",
        "youtube.googleapis.com",
        "i.ytimg.com",
        "yt3.ggpht.com",
        "redirector.googlevideo.com",

        // Discord
        "discord.com",
        "discord.gg",
        "gateway.discord.gg",
        "cdn.discordapp.com",
    )

    suspend fun run(
        context: Context,
        strategies: List<CatalogStrategy>,
        sni: String,
        onProgress: suspend (index: Int, total: Int, current: StrategyTestResult, best: StrategyTestResult?) -> Unit,
    ): List<StrategyTestResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<StrategyTestResult>()
        val baseConfig = ByeDpiConfigStore.load(context)

        for ((index, strategy) in strategies.withIndex()) {
            val port = findFreePort()
            val proxy = ByeDpiProxy()
            val config = baseConfig.copy(
                bindIp = "127.0.0.1",
                port = port,
                mode = ByeDpiMode.MANUAL,
                command = strategy.command,
                strategyName = strategy.name,
                sni = sni,
                // Measure the strategy itself. A user's include/exclude list is applied
                // only when the real VPN starts and must not skew coverage test results.
                domainFilterMode = DomainFilterMode.ALL,
                domains = "",
            )

            val result = coroutineScope {
                val proxyJob = async(Dispatchers.IO) {
                    runCatching { proxy.start(config) }.getOrDefault(-999)
                }

                val ready = waitForPort(port)
                var successes = 0

                if (ready) {
                    for (host in testHosts) {
                        if (testTlsThroughSocks(host, port)) successes++
                    }
                }

                runCatching { proxy.stop() }
                val finished = withTimeoutOrNull(1500) {
                    proxyJob.await()
                    true
                } ?: false
                if (!finished) runCatching { proxy.forceClose() }

                StrategyTestResult(
                    strategy = strategy,
                    successCount = successes,
                    totalCount = testHosts.size,
                )
            }

            results += result
            val best = results.sortedWith(
                compareByDescending<StrategyTestResult> { it.successCount }
                    .thenBy { it.strategy.index },
            ).firstOrNull()
            onProgress(index + 1, strategies.size, result, best)
            delay(150)
        }

        results.sortedWith(
            compareByDescending<StrategyTestResult> { it.successCount }
                .thenBy { it.strategy.index },
        )
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    private suspend fun waitForPort(port: Int): Boolean {
        repeat(25) {
            val ready = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 100)
                }
                true
            }.getOrDefault(false)
            if (ready) return true
            delay(80)
        }
        return false
    }

    private fun testTlsThroughSocks(host: String, port: Int): Boolean = runCatching {
        val socks = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        val raw = Socket(socks)
        raw.soTimeout = 3000
        raw.connect(InetSocketAddress.createUnresolved(host, 443), 3000)

        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = factory.createSocket(raw, host, 443, true) as SSLSocket
        ssl.soTimeout = 3000
        ssl.sslParameters = ssl.sslParameters.apply {
            serverNames = listOf(SNIHostName(host))
        }
        ssl.startHandshake()
        ssl.close()
        true
    }.getOrDefault(false)
}
