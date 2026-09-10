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
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
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

object ByeDpiStrategyTester {
    private const val MAX_PARALLEL_HOSTS = 4
    private const val MAX_SAMPLE_HOSTS = 12
    private const val FINALISTS = 3

    /**
     * Build the coverage list from the user's real configuration, not from a
     * hard-coded YouTube list. Every non-PASS named list participates, including
     * custom lists and the legacy manual domain field from 0.4.x.
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
            // The built-in ignore list is an explicit PASS list; it is shown only
            // if the same host also appears in another testable list.
            if (list.id == ProfileStore.LIST_IGNORE) return@forEach
            list.domains.forEach { host ->
                listNamesByDomain.getOrPut(host) { linkedSetOf() } += list.name
                if (list.id == ProfileStore.LIST_USER || list.id.startsWith("list-")) {
                    manualDomains += host
                }
            }
        }

        // Preserve manually entered domains from the pre-v2 screen as well.
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
     * Low-load strategy finder:
     *  1) recommended/adapted candidates are tested on a representative sample;
     *  2) only the three best candidates are verified against every configured host.
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

        val sample = representativeSample(eligible)
        val preliminary = mutableListOf<StrategyTestResult>()

        candidates.forEachIndexed { index, strategy ->
            onProgress(
                StrategySearchProgress(
                    phase = "Быстрый отбор",
                    strategyIndex = index + 1,
                    strategyTotal = candidates.size,
                    strategyName = strategy.name,
                    currentSuccess = 0,
                    currentTotal = sample.size,
                ),
            )
            val result = testStrategy(context, strategy, sni, sample, preliminary = true)
            preliminary += result
            onProgress(
                StrategySearchProgress(
                    phase = "Быстрый отбор",
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
                    phase = "Проверка всех доменов",
                    strategyIndex = index + 1,
                    strategyTotal = finalists.size,
                    strategyName = strategy.name,
                    currentSuccess = 0,
                    currentTotal = eligible.size,
                ),
            )
            val result = testStrategy(context, strategy, sni, eligible, preliminary = false)
            verified += result
            onProgress(
                StrategySearchProgress(
                    phase = "Проверка всех доменов",
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
        testStrategy(context, strategy, sni, eligible, preliminary = false)
    }

    private fun representativeSample(targets: List<StrategyTestTarget>): List<StrategyTestTarget> {
        if (targets.size <= MAX_SAMPLE_HOSTS) return targets

        val selected = linkedMapOf<String, StrategyTestTarget>()

        // Manual entries are never silently ignored by the fast stage.
        targets.filter { it.manual }.take(4).forEach { selected[it.host] = it }

        // Then take up to two hosts from each named list/service.
        targets
            .flatMap { target -> target.listNames.map { it to target } }
            .groupBy({ it.first }, { it.second })
            .values
            .forEach { group ->
                group.distinctBy { it.host }.take(2).forEach { selected[it.host] = it }
            }

        // Fill the remaining sample deterministically.
        targets.forEach { target ->
            if (selected.size < MAX_SAMPLE_HOSTS) selected.putIfAbsent(target.host, target)
        }
        return selected.values.take(MAX_SAMPLE_HOSTS)
    }

    private suspend fun testStrategy(
        context: Context,
        strategy: CatalogStrategy,
        sni: String,
        targets: List<StrategyTestTarget>,
        preliminary: Boolean,
    ): StrategyTestResult = coroutineScope {
        if (targets.isEmpty()) return@coroutineScope StrategyTestResult(strategy, emptyList(), preliminary)

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
                    semaphore.withPermit { probeTls(target, port) }
                }
            }.awaitAll()
        } else {
            targets.map { DomainProbeResult(it, success = false, latencyMs = null) }
        }

        runCatching { proxy.stop() }
        val finished = withTimeoutOrNull(1500) {
            proxyJob.await()
            true
        } ?: false
        if (!finished) runCatching { proxy.forceClose() }
        delay(80)

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

    private fun probeTls(target: StrategyTestTarget, port: Int): DomainProbeResult {
        var success = false
        val elapsed = measureTimeMillis {
            success = testTlsThroughSocks(target.host, port)
        }
        return DomainProbeResult(
            target = target,
            success = success,
            latencyMs = elapsed.takeIf { success },
        )
    }

    private fun testTlsThroughSocks(host: String, port: Int): Boolean = runCatching {
        val socks = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        val raw = Socket(socks)
        raw.soTimeout = 2500
        raw.connect(InetSocketAddress.createUnresolved(host, 443), 2500)

        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = factory.createSocket(raw, host, 443, true) as SSLSocket
        ssl.soTimeout = 2500
        ssl.sslParameters = ssl.sslParameters.apply {
            serverNames = listOf(SNIHostName(host))
        }
        ssl.startHandshake()
        ssl.close()
        true
    }.getOrDefault(false)
}
