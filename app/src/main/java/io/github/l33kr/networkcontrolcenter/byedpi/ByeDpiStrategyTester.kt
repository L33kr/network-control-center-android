package io.github.l33kr.networkcontrolcenter.byedpi

import android.content.Context
import io.github.l33kr.networkcontrolcenter.byedpi.profile.ProfileAction
import io.github.l33kr.networkcontrolcenter.byedpi.profile.ProfileSetModel
import io.github.l33kr.networkcontrolcenter.byedpi.profile.ProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.system.measureTimeMillis

data class StrategyTestTarget(
    val host: String,
    val listNames: List<String>,
    val profileNames: List<String>,
    val manual: Boolean,
    val passOnly: Boolean,
)

data class DomainProbeResult(
    val target: StrategyTestTarget,
    val success: Boolean,
    val latencyMs: Long?,
    val httpCode: Int? = null,
    val bytesRead: Long = 0,
    val error: String? = null,
)

data class StrategyTestResult(
    val strategy: CatalogStrategy,
    val probes: List<DomainProbeResult>,
    val preliminary: Boolean = false,
) {
    val testedCount: Int get() = probes.size
    val successCount: Int get() = probes.count { it.success }
    val totalCount: Int get() = probes.size
    val percent: Int get() = if (totalCount == 0) 0 else successCount * 100 / totalCount
    val averageLatencyMs: Long?
        get() = probes.mapNotNull { it.latencyMs }.takeIf { it.isNotEmpty() }?.average()?.toLong()
}

data class StrategySearchProgress(
    val phase: String,
    val strategyIndex: Int,
    val strategyTotal: Int,
    val strategyName: String,
    val currentSuccess: Int,
    val currentTotal: Int,
)

data class StrategySearchReport(
    val targets: List<StrategyTestTarget>,
    val preliminary: List<StrategyTestResult>,
    val verified: List<StrategyTestResult>,
) {
    val best: StrategyTestResult? get() = verified.firstOrNull()
}

private data class HttpProbeOutcome(
    val success: Boolean,
    val httpCode: Int? = null,
    val bytesRead: Long = 0,
    val error: String? = null,
)

object ByeDpiStrategyTester {
    private const val MAX_PARALLEL_HOSTS = 3
    private const val MAX_SAMPLE_HOSTS = 10
    private const val LAB_SAMPLE_HOSTS = 4
    private const val NORMAL_TIMEOUT_MS = 4500
    private const val LAB_PRELIMINARY_TIMEOUT_MS = 2500
    private const val FINALISTS = 3
    private const val MAX_BODY_BYTES = 64L * 1024L
    private const val MIN_LARGE_BODY_BYTES = 8L * 1024L

    /**
     * Build coverage from the real configuration. Every named list participates,
     * including user-created lists, PASS/ignore lists and the legacy 0.4 field.
     * PASS-only hosts stay visible in the report but do not reduce BYPASS score.
     */
    fun collectTargets(
        context: Context,
        set: ProfileSetModel = ProfileStore.activeSet(context),
    ): List<StrategyTestTarget> {
        val lists = ProfileStore.loadLists(context)
        val enabledProfiles = set.profiles.filter { it.enabled }
        val bypassProfiles = enabledProfiles.filter { it.action == ProfileAction.BYPASS }
        val passProfiles = enabledProfiles.filter { it.action == ProfileAction.PASS }

        val passDomains = passProfiles
            .flatMap { ProfileStore.resolveDomains(context, it.domainListIds) }
            .toSet()

        val profileNamesByDomain = linkedMapOf<String, MutableSet<String>>()
        bypassProfiles.forEach { profile ->
            ProfileStore.resolveDomains(context, profile.domainListIds).forEach { host ->
                profileNamesByDomain.getOrPut(host) { linkedSetOf() } += profile.name
            }
        }

        val listNamesByDomain = linkedMapOf<String, MutableSet<String>>()
        val manualDomains = linkedSetOf<String>()

        lists.forEach { list ->
            list.domains.forEach { host ->
                listNamesByDomain.getOrPut(host) { linkedSetOf() } += list.name
                if (
                    list.id == ProfileStore.LIST_USER ||
                    list.id == ProfileStore.LIST_IGNORE ||
                    list.id.startsWith("list-")
                ) {
                    manualDomains += host
                }
            }
        }

        ByeDpiConfigStore.load(context).normalizedDomains().forEach { host ->
            listNamesByDomain.getOrPut(host) { linkedSetOf() } += "Ручные домены (0.4)"
            manualDomains += host
        }

        return listNamesByDomain.keys
            .map { host ->
                val profiles = profileNamesByDomain[host].orEmpty().toList()
                StrategyTestTarget(
                    host = host,
                    listNames = listNamesByDomain[host].orEmpty().toList(),
                    profileNames = profiles,
                    manual = host in manualDomains,
                    passOnly = host in passDomains && profiles.isEmpty(),
                )
            }
            .sortedWith(
                compareByDescending<StrategyTestTarget> { it.manual }
                    .thenBy { it.listNames.firstOrNull().orEmpty() }
                    .thenBy { it.host },
            )
    }

    /**
     * Low-load search similar to ByeByeDPI's proxy tester, but adapted for the
     * phone: first rank candidates on a small sample, then verify only three
     * finalists against every configured BYPASS host.
     *
     * Unlike the old tester, success now requires an actual HTTPS request through
     * the local SOCKS proxy. Merely completing TLS is no longer enough.
     */
    suspend fun findBestAdaptive(
        context: Context,
        sni: String = ByeDpiConfigStore.load(context).sni,
        set: ProfileSetModel = ProfileStore.activeSet(context),
        candidates: List<CatalogStrategy> = ByeDpiStrategyCatalog.quickCandidates(context),
        onProgress: suspend (StrategySearchProgress) -> Unit = {},
    ): StrategySearchReport = withContext(Dispatchers.IO) {
        val targets = collectTargets(context, set)
        val eligible = targets.filterNot { it.passOnly }
        if (eligible.isEmpty() || candidates.isEmpty()) {
            return@withContext StrategySearchReport(targets, emptyList(), emptyList())
        }

        val nativeLabSearch = candidates.any { it.category == "Android" || it.category == "Generator" }
        val sampleLimit = if (nativeLabSearch) LAB_SAMPLE_HOSTS else MAX_SAMPLE_HOSTS
        val preliminaryTimeout = if (nativeLabSearch) LAB_PRELIMINARY_TIMEOUT_MS else NORMAL_TIMEOUT_MS
        val sample = representativeSample(eligible, sampleLimit)
        val preliminary = mutableListOf<StrategyTestResult>()

        candidates.forEachIndexed { index, strategy ->
            val phase = if (nativeLabSearch) "ByeDPI · быстрый отбор" else "Быстрый отбор"
            onProgress(
                StrategySearchProgress(
                    phase = phase,
                    strategyIndex = index + 1,
                    strategyTotal = candidates.size,
                    strategyName = strategy.name,
                    currentSuccess = 0,
                    currentTotal = sample.size,
                ),
            )
            val result = testStrategy(
                context = context,
                strategy = strategy,
                sni = sni,
                targets = sample,
                preliminary = true,
                timeoutMs = preliminaryTimeout,
            )
            preliminary += result
            onProgress(
                StrategySearchProgress(
                    phase = phase,
                    strategyIndex = index + 1,
                    strategyTotal = candidates.size,
                    strategyName = strategy.name,
                    currentSuccess = result.successCount,
                    currentTotal = result.totalCount,
                ),
            )
        }

        val finalists = sortResults(preliminary).take(FINALISTS).map { it.strategy }
        val verified = mutableListOf<StrategyTestResult>()

        finalists.forEachIndexed { index, strategy ->
            onProgress(
                StrategySearchProgress(
                    phase = "HTTPS · все домены",
                    strategyIndex = index + 1,
                    strategyTotal = finalists.size,
                    strategyName = strategy.name,
                    currentSuccess = 0,
                    currentTotal = eligible.size,
                ),
            )
            val result = testStrategy(
                context = context,
                strategy = strategy,
                sni = sni,
                targets = eligible,
                preliminary = false,
                timeoutMs = NORMAL_TIMEOUT_MS,
            )
            verified += result
            onProgress(
                StrategySearchProgress(
                    phase = "HTTPS · все домены",
                    strategyIndex = index + 1,
                    strategyTotal = finalists.size,
                    strategyName = strategy.name,
                    currentSuccess = result.successCount,
                    currentTotal = result.totalCount,
                ),
            )
        }

        StrategySearchReport(
            targets = targets,
            preliminary = sortResults(preliminary),
            verified = sortResults(verified),
        )
    }

    suspend fun testStrategyAgainstAllDomains(
        context: Context,
        strategy: CatalogStrategy,
        sni: String = ByeDpiConfigStore.load(context).sni,
        set: ProfileSetModel = ProfileStore.activeSet(context),
    ): StrategyTestResult = withContext(Dispatchers.IO) {
        val eligible = collectTargets(context, set).filterNot { it.passOnly }
        testStrategy(
            context = context,
            strategy = strategy,
            sni = sni,
            targets = eligible,
            preliminary = false,
            timeoutMs = NORMAL_TIMEOUT_MS,
        )
    }

    private fun representativeSample(
        targets: List<StrategyTestTarget>,
        maxHosts: Int,
    ): List<StrategyTestTarget> {
        if (targets.size <= maxHosts) return targets

        val selected = linkedMapOf<String, StrategyTestTarget>()
        targets.filter { it.manual }.take((maxHosts / 2).coerceAtLeast(1)).forEach {
            selected[it.host] = it
        }

        targets
            .flatMap { target -> target.listNames.map { it to target } }
            .groupBy({ it.first }, { it.second })
            .values
            .forEach { group ->
                if (selected.size < maxHosts) {
                    group.distinctBy { it.host }.firstOrNull()?.let { selected[it.host] = it }
                }
            }

        targets.forEach { target ->
            if (selected.size < maxHosts) selected.putIfAbsent(target.host, target)
        }
        return selected.values.take(maxHosts)
    }

    private suspend fun testStrategy(
        context: Context,
        strategy: CatalogStrategy,
        sni: String,
        targets: List<StrategyTestTarget>,
        preliminary: Boolean,
        timeoutMs: Int,
    ): StrategyTestResult = coroutineScope {
        if (targets.isEmpty()) return@coroutineScope StrategyTestResult(strategy, emptyList(), preliminary)

        // Strategy tests deliberately bypass ProfileCompiler. This is the same
        // raw-command model used by ByeByeDPI's command-mode tester and lets us
        // distinguish a bad strategy from a bad profile compilation.
        val baseConfig = ByeDpiConfigStore.load(context)
        val port = findFreePort()
        val proxy = ByeDpiProxy()
        val config = baseConfig.copy(
            bindIp = "127.0.0.1",
            port = port,
            mode = ByeDpiMode.MANUAL,
            command = strategy.command,
            strategyName = strategy.name,
            sni = sni,
            domainFilterMode = DomainFilterMode.ALL,
            domains = "",
        )

        val proxyJob = async(Dispatchers.IO) {
            runCatching { proxy.start(config) }.getOrDefault(-999)
        }

        val ready = waitForPort(port)
        val probes = if (ready) {
            val semaphore = Semaphore(MAX_PARALLEL_HOSTS)
            targets.map { target ->
                async(Dispatchers.IO) {
                    semaphore.withPermit { probeHttps(target, port, timeoutMs) }
                }
            }.awaitAll()
        } else {
            targets.map {
                DomainProbeResult(
                    target = it,
                    success = false,
                    latencyMs = null,
                    error = "ByeDPI SOCKS не запустился",
                )
            }
        }

        runCatching { proxy.stop() }
        val finished = withTimeoutOrNull(1800) {
            proxyJob.await()
            true
        } ?: false
        if (!finished) runCatching { proxy.forceClose() }
        delay(100)

        StrategyTestResult(strategy, probes, preliminary)
    }

    private fun sortResults(results: List<StrategyTestResult>): List<StrategyTestResult> = results.sortedWith(
        compareByDescending<StrategyTestResult> { it.percent }
            .thenByDescending { it.successCount }
            .thenBy { it.averageLatencyMs ?: Long.MAX_VALUE }
            .thenBy { it.strategy.index },
    )

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    private suspend fun waitForPort(port: Int): Boolean {
        repeat(30) {
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

    private fun probeHttps(target: StrategyTestTarget, port: Int, timeoutMs: Int): DomainProbeResult {
        var outcome = HttpProbeOutcome(success = false, error = "Нет ответа")
        val elapsed = measureTimeMillis {
            outcome = testHttpsThroughSocks(target.host, port, timeoutMs)
        }
        return DomainProbeResult(
            target = target,
            success = outcome.success,
            latencyMs = elapsed.takeIf { outcome.success },
            httpCode = outcome.httpCode,
            bytesRead = outcome.bytesRead,
            error = outcome.error,
        )
    }

    /**
     * Mirrors ByeByeDPI's SiteCheckUtils approach: open a real HTTPS URL through
     * SOCKS, obtain the HTTP response and consume enough of the body to detect a
     * connection that survives ClientHello but is reset immediately afterwards.
     * Large pages are capped to keep the test light on mobile data.
     */
    private fun testHttpsThroughSocks(host: String, port: Int, timeoutMs: Int): HttpProbeOutcome {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        var connection: HttpURLConnection? = null

        return try {
            connection = URL("https://$host/").openConnection(proxy) as HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("Connection", "close")
            connection.setRequestProperty("User-Agent", "DPI-Control/0.5 ByeDPI-check")

            val code = connection.responseCode
            val declaredLength = connection.contentLengthLong
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            var actualLength = 0L
            var readFailed = false

            if (stream != null) {
                try {
                    stream.use { input ->
                        val buffer = ByteArray(8192)
                        val readLimit = when {
                            declaredLength in 1..MAX_BODY_BYTES -> declaredLength
                            else -> MAX_BODY_BYTES
                        }
                        while (actualLength < readLimit) {
                            val remaining = readLimit - actualLength
                            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (read == -1) break
                            actualLength += read
                        }
                    }
                } catch (_: Exception) {
                    readFailed = true
                }
            }

            val bodyLooksComplete = when {
                declaredLength <= 0L -> !readFailed || code in 100..599
                declaredLength <= MAX_BODY_BYTES -> !readFailed && actualLength >= declaredLength
                else -> !readFailed && actualLength >= MIN_LARGE_BODY_BYTES
            }
            val success = code in 100..599 && bodyLooksComplete

            HttpProbeOutcome(
                success = success,
                httpCode = code,
                bytesRead = actualLength,
                error = if (success) null else "HTTP $code, получено $actualLength/${declaredLength.coerceAtLeast(0)} байт",
            )
        } catch (e: Exception) {
            HttpProbeOutcome(
                success = false,
                error = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""),
            )
        } finally {
            connection?.disconnect()
        }
    }
}
