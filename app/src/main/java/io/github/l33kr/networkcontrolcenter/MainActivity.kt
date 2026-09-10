package io.github.l33kr.networkcontrolcenter

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiConfig
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiConfigStore
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiController
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiMode
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiStrategies
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiStrategyCatalog
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiStrategyTester
import io.github.l33kr.networkcontrolcenter.byedpi.CatalogStrategy
import io.github.l33kr.networkcontrolcenter.byedpi.DomainFilterMode
import io.github.l33kr.networkcontrolcenter.byedpi.DomainPresets
import io.github.l33kr.networkcontrolcenter.core.AndroidUnifiedEngineController
import io.github.l33kr.networkcontrolcenter.core.EngineState
import io.github.l33kr.networkcontrolcenter.core.EngineStatus
import io.github.l33kr.networkcontrolcenter.tgws.TgWsController
import io.github.l33kr.networkcontrolcenter.ui.theme.DpiControlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DpiControlTheme {
                val controller = remember { AndroidUnifiedEngineController(applicationContext) }
                val scope = rememberCoroutineScope()
                val vpnPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        scope.launch { controller.startByeDpi() }
                    }
                }

                DpiControlApp(
                    controller = controller,
                    onByeDpiChange = { enabled ->
                        if (!enabled) {
                            scope.launch { controller.stopByeDpi() }
                        } else {
                            val permissionIntent = VpnService.prepare(this@MainActivity)
                            if (permissionIntent == null) {
                                scope.launch { controller.startByeDpi() }
                            } else {
                                vpnPermissionLauncher.launch(permissionIntent)
                            }
                        }
                    },
                    onApplyTelegramProxy = {
                        if (!TgWsController.openTelegramProxy(applicationContext)) {
                            Toast.makeText(
                                this,
                                "Не удалось открыть Telegram-клиент",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
            }
        }
    }
}

private enum class AppTab(val title: String, val icon: ImageVector) {
    HOME("Главная", Icons.Rounded.Home),
    STRATEGIES("Стратегии", Icons.Rounded.Tune),
    DOMAINS("Домены", Icons.Rounded.Language),
    TELEGRAM("Telegram", Icons.Rounded.Send),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DpiControlApp(
    controller: AndroidUnifiedEngineController,
    onByeDpiChange: (Boolean) -> Unit,
    onApplyTelegramProxy: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by controller.state.collectAsStateWithLifecycle()
    val byeProfile by ByeDpiController.activeProfile.collectAsStateWithLifecycle()
    val byeNetwork by ByeDpiController.networkLabel.collectAsStateWithLifecycle()
    val byeIpv6 by ByeDpiController.ipv6Active.collectAsStateWithLifecycle()
    val byeError by ByeDpiController.lastError.collectAsStateWithLifecycle()
    val tgWsPort by TgWsController.activePort.collectAsStateWithLifecycle()
    val tgWsError by TgWsController.lastError.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var selectedTabName by rememberSaveable { mutableStateOf(AppTab.HOME.name) }
    var configRevision by remember { mutableIntStateOf(0) }
    val config = remember(configRevision) { ByeDpiConfigStore.load(context) }
    val catalog = remember { ByeDpiStrategyCatalog.load(context) }
    var testRunning by rememberSaveable { mutableStateOf(false) }
    var testProgress by rememberSaveable { mutableStateOf<String?>(null) }

    val selectedTab = runCatching { AppTab.valueOf(selectedTabName) }.getOrDefault(AppTab.HOME)
    val canConfigure = (state.byeDpi == EngineStatus.STOPPED || state.byeDpi == EngineStatus.FAILED) && !testRunning

    fun refreshConfig() {
        configRevision++
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("DPI Control", fontWeight = FontWeight.SemiBold)
                        Text(
                            "ByeDPI · Android",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTabName = tab.name },
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title) },
                    )
                }
            }
        },
    ) { padding ->
        when (selectedTab) {
            AppTab.HOME -> HomeScreen(
                modifier = Modifier.padding(padding),
                state = state,
                config = config,
                activeProfile = byeProfile,
                network = byeNetwork,
                ipv6 = byeIpv6,
                error = byeError,
                onByeDpiChange = onByeDpiChange,
                onOpenStrategies = { selectedTabName = AppTab.STRATEGIES.name },
                onOpenDomains = { selectedTabName = AppTab.DOMAINS.name },
            )

            AppTab.STRATEGIES -> StrategiesScreen(
                modifier = Modifier.padding(padding),
                config = config,
                catalog = catalog,
                canConfigure = canConfigure,
                testRunning = testRunning,
                testProgress = testProgress,
                onPreset = { mode ->
                    ByeDpiController.setMode(context, mode)
                    testProgress = null
                    refreshConfig()
                },
                onCatalogStrategy = { strategy, sni ->
                    ByeDpiConfigStore.setSni(context, sni)
                    ByeDpiConfigStore.setCatalogStrategy(context, strategy.name, strategy.command)
                    testProgress = null
                    refreshConfig()
                },
                onManualStrategy = { command, sni ->
                    ByeDpiConfigStore.setSni(context, sni)
                    ByeDpiConfigStore.setManualStrategy(context, command)
                    testProgress = null
                    refreshConfig()
                },
                onRunTest = {
                    testRunning = true
                    testProgress = "Подготовка автотеста…"
                    scope.launch {
                        try {
                            val current = ByeDpiConfigStore.load(context)
                            val results = ByeDpiStrategyTester.run(
                                context = context,
                                strategies = catalog,
                                sni = current.sni,
                            ) { index, total, currentResult, best ->
                                withContext(Dispatchers.Main) {
                                    testProgress = buildString {
                                        append("$index/$total · #${currentResult.strategy.index + 1}: ${currentResult.successCount}/${currentResult.totalCount}")
                                        best?.let {
                                            append(" · лучшая #${it.strategy.index + 1}: ${it.successCount}/${it.totalCount}")
                                        }
                                    }
                                }
                            }
                            val best = results.firstOrNull()
                            if (best != null && best.successCount > 0) {
                                val name = "Автотест · стратегия ${best.strategy.index + 1}"
                                ByeDpiConfigStore.setCatalogStrategy(context, name, best.strategy.command)
                                refreshConfig()
                                testProgress = "Готово · стратегия ${best.strategy.index + 1} · ${best.successCount}/${best.totalCount}"
                            } else {
                                testProgress = "Ни одна стратегия не прошла TLS-проверку YouTube"
                            }
                        } catch (t: Throwable) {
                            testProgress = "Ошибка: ${t.message ?: t.javaClass.simpleName}"
                        } finally {
                            testRunning = false
                        }
                    }
                },
            )

            AppTab.DOMAINS -> DomainsScreen(
                modifier = Modifier.padding(padding),
                config = config,
                canConfigure = canConfigure,
                onSave = { mode, domains ->
                    ByeDpiConfigStore.setDomainFilter(context, mode, domains)
                    refreshConfig()
                },
            )

            AppTab.TELEGRAM -> TelegramScreen(
                modifier = Modifier.padding(padding),
                status = state.tgWs,
                port = tgWsPort,
                error = tgWsError,
                onToggle = { enabled ->
                    scope.launch {
                        if (enabled) controller.startTgWs() else controller.stopTgWs()
                    }
                },
                onApply = onApplyTelegramProxy,
            )
        }
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier,
    state: EngineState,
    config: ByeDpiConfig,
    activeProfile: String?,
    network: String?,
    ipv6: Boolean,
    error: String?,
    onByeDpiChange: (Boolean) -> Unit,
    onOpenStrategies: () -> Unit,
    onOpenDomains: () -> Unit,
) {
    val running = state.byeDpi == EngineStatus.RUNNING || state.byeDpi == EngineStatus.STARTING

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (state.byeDpi == EngineStatus.RUNNING) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ) {
                        Icon(
                            imageVector = if (running) Icons.Rounded.CheckCircle else Icons.Rounded.PowerSettingsNew,
                            contentDescription = null,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Защищённый маршрут", style = MaterialTheme.typography.titleLarge)
                        Text(
                            statusLabel(state.byeDpi),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (state.byeDpi == EngineStatus.STARTING || state.byeDpi == EngineStatus.STOPPING) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                if (state.byeDpi == EngineStatus.RUNNING) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InfoPill(activeProfile ?: "ByeDPI")
                        network?.let { InfoPill(it) }
                    }
                    Text(
                        if (ipv6) "Маршрутизация: IPv4 + IPv6" else "Маршрутизация: IPv4",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (state.byeDpi == EngineStatus.FAILED && !error.isNullOrBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.byeDpi != EngineStatus.STARTING && state.byeDpi != EngineStatus.STOPPING,
                    onClick = { onByeDpiChange(!running) },
                ) {
                    Icon(
                        if (running) Icons.Rounded.PowerSettingsNew else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (running) "Отключить" else "Запустить")
                }
            }
        }

        SectionTitle("Текущая конфигурация")
        SettingsRow(
            title = "Стратегия",
            value = config.strategyName ?: strategyModeTitle(config.mode),
            onClick = onOpenStrategies,
        )
        SettingsRow(
            title = "Домены",
            value = domainModeTitle(config.domainFilterMode, config.normalizedDomains().size),
            onClick = onOpenDomains,
        )
        SettingsRow(
            title = "DNS / IPv6",
            value = "${config.dns} · ${config.ipv6Mode.name}",
            onClick = null,
        )
    }
}

@Composable
private fun StrategiesScreen(
    modifier: Modifier,
    config: ByeDpiConfig,
    catalog: List<CatalogStrategy>,
    canConfigure: Boolean,
    testRunning: Boolean,
    testProgress: String?,
    onPreset: (ByeDpiMode) -> Unit,
    onCatalogStrategy: (CatalogStrategy, String) -> Unit,
    onManualStrategy: (String, String) -> Unit,
    onRunTest: () -> Unit,
) {
    var showCatalog by rememberSaveable { mutableStateOf(false) }
    var manualCommand by rememberSaveable(config.command) { mutableStateOf(config.command) }
    var sni by rememberSaveable(config.sni) { mutableStateOf(config.sni) }

    if (showCatalog) {
        StrategyCatalogDialog(
            catalog = catalog,
            initialSni = sni,
            enabled = canConfigure,
            onDismiss = { showCatalog = false },
            onSelect = { strategy, selectedSni ->
                sni = selectedSni
                onCatalogStrategy(strategy, selectedSni)
                showCatalog = false
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionTitle("Стратегии ByeDPI")
        Text(
            "Выбери готовый вариант как в zapret/ByeDPI или вставь собственную команду. Для смены стратегии VPN должен быть выключен.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Сейчас выбрано", style = MaterialTheme.typography.labelMedium)
                Text(
                    config.strategyName ?: strategyModeTitle(config.mode),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = canConfigure,
            onClick = { showCatalog = true },
        ) {
            Icon(Icons.Rounded.Search, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Каталог готовых стратегий · ${catalog.size}")
        }

        FilledTonalButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = canConfigure,
            onClick = onRunTest,
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (testRunning) "Идёт тест…" else "Автотест YouTube")
        }
        testProgress?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionTitle("Быстрые профили")
        ByeDpiStrategies.selectable
            .filter { it.mode != ByeDpiMode.MANUAL }
            .forEach { preset ->
                val selected = config.strategyName == null && config.mode == preset.mode
                StrategyPresetCard(
                    title = preset.title,
                    description = preset.description,
                    selected = selected,
                    enabled = canConfigure,
                    onClick = { onPreset(preset.mode) },
                )
            }

        SectionTitle("Ручная команда")
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = sni,
            enabled = canConfigure,
            onValueChange = { sni = it },
            label = { Text("Fake SNI") },
            singleLine = true,
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = manualCommand,
            enabled = canConfigure,
            onValueChange = { manualCommand = it },
            label = { Text("Аргументы ByeDPI") },
            minLines = 5,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = canConfigure && manualCommand.isNotBlank(),
            onClick = { onManualStrategy(manualCommand, sni) },
        ) {
            Text("Сохранить ручную стратегию")
        }
    }
}

@Composable
private fun DomainsScreen(
    modifier: Modifier,
    config: ByeDpiConfig,
    canConfigure: Boolean,
    onSave: (DomainFilterMode, String) -> Unit,
) {
    var modeName by rememberSaveable(config.domainFilterMode.name) {
        mutableStateOf(config.domainFilterMode.name)
    }
    var domains by rememberSaveable(config.domains) { mutableStateOf(config.domains) }
    val mode = runCatching { DomainFilterMode.valueOf(modeName) }.getOrDefault(DomainFilterMode.ALL)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionTitle("Фильтрация по доменам")
        Text(
            "Аналог hostlist/exclude-list: можно обрабатывать весь трафик, только свой список или всё кроме списка исключений.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        DomainModeCard(
            title = "Весь трафик",
            description = "Стратегия применяется без ограничения по доменам",
            selected = mode == DomainFilterMode.ALL,
            enabled = canConfigure,
            onClick = { modeName = DomainFilterMode.ALL.name },
        )
        DomainModeCard(
            title = "Только указанные",
            description = "ByeDPI обрабатывает только домены из списка ниже",
            selected = mode == DomainFilterMode.ONLY_LISTED,
            enabled = canConfigure,
            onClick = { modeName = DomainFilterMode.ONLY_LISTED.name },
        )
        DomainModeCard(
            title = "Игнорировать указанные",
            description = "Эти домены идут без desync, остальные — через выбранную стратегию",
            selected = mode == DomainFilterMode.EXCLUDE_LISTED,
            enabled = canConfigure,
            onClick = { modeName = DomainFilterMode.EXCLUDE_LISTED.name },
        )

        SectionTitle("Быстро добавить")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(DomainPresets.all, key = { it.title }) { preset ->
                OutlinedButton(
                    enabled = canConfigure,
                    onClick = { domains = DomainPresets.merge(domains, preset) },
                ) {
                    Text("+ ${preset.title}")
                }
            }
        }

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = domains,
            enabled = canConfigure && mode != DomainFilterMode.ALL,
            onValueChange = { domains = it },
            label = { Text("Домены") },
            placeholder = { Text("youtube.com\ngooglevideo.com\ndiscord.com") },
            supportingText = { Text("Один домен на строку. Поддомены учитываются ядром ByeDPI.") },
            minLines = 9,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = canConfigure,
            onClick = { onSave(mode, domains) },
        ) {
            Text("Сохранить список")
        }

        if (!canConfigure) {
            Text(
                "Чтобы изменить фильтрацию, сначала отключи ByeDPI.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TelegramScreen(
    modifier: Modifier,
    status: EngineStatus,
    port: Int,
    error: String?,
    onToggle: (Boolean) -> Unit,
    onApply: () -> Unit,
) {
    val running = status == EngineStatus.RUNNING || status == EngineStatus.STARTING

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionTitle("Telegram WS Proxy")
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(statusLabel(status), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (status == EngineStatus.RUNNING && port > 0) {
                        "127.0.0.1:$port · MTProto → WSS"
                    } else {
                        "Локальный MTProto через WebSocket-маршрут"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (status == EngineStatus.STARTING || status == EngineStatus.STOPPING) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (status == EngineStatus.FAILED && !error.isNullOrBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = status != EngineStatus.STARTING && status != EngineStatus.STOPPING,
                    onClick = { onToggle(!running) },
                ) {
                    Text(if (running) "Остановить прокси" else "Запустить прокси")
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = status == EngineStatus.RUNNING,
                    onClick = onApply,
                ) {
                    Text("Применить в Telegram")
                }
            }
        }
    }
}

@Composable
private fun StrategyCatalogDialog(
    catalog: List<CatalogStrategy>,
    initialSni: String,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onSelect: (CatalogStrategy, String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var sni by rememberSaveable(initialSni) { mutableStateOf(initialSni) }
    val filtered = remember(query, catalog) {
        val q = query.trim().lowercase()
        if (q.isBlank()) catalog else catalog.filter {
            it.name.lowercase().contains(q) || it.command.lowercase().contains(q)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Готовые стратегии") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Поиск") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = sni,
                    onValueChange = { sni = it },
                    label = { Text("Fake SNI") },
                    singleLine = true,
                )
                Text(
                    "Найдено: ${filtered.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 430.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { it.index }) { strategy ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { if (enabled) onSelect(strategy, sni.trim().ifBlank { "google.com" }) },
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(strategy.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    strategy.command,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}

@Composable
private fun StrategyPresetCard(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) Icon(Icons.Rounded.CheckCircle, contentDescription = "Выбрано")
        }
    }
}

@Composable
private fun DomainModeCard(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) Icon(Icons.Rounded.CheckCircle, contentDescription = "Выбрано")
        }
    }
}

@Composable
private fun SettingsRow(title: String, value: String, onClick: (() -> Unit)?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        enabled = onClick != null,
        onClick = { onClick?.invoke() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(3.dp))
                Text(value, fontWeight = FontWeight.Medium)
            }
            if (onClick != null) Text("›", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun InfoPill(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
    }
}

private fun statusLabel(status: EngineStatus): String = when (status) {
    EngineStatus.STOPPED -> "Отключено"
    EngineStatus.STARTING -> "Запуск…"
    EngineStatus.RUNNING -> "Работает"
    EngineStatus.STOPPING -> "Остановка…"
    EngineStatus.FAILED -> "Ошибка"
}

private fun strategyModeTitle(mode: ByeDpiMode): String = when (mode) {
    ByeDpiMode.AUTO -> "Auto"
    ByeDpiMode.MOBILE_RU -> "Mobile RU"
    ByeDpiMode.BALANCED -> "ByeByeDPI Default"
    ByeDpiMode.STRONG_FAKE -> "Strong / Fake"
    ByeDpiMode.MANUAL -> "Ручная стратегия"
}

private fun domainModeTitle(mode: DomainFilterMode, count: Int): String = when (mode) {
    DomainFilterMode.ALL -> "Весь трафик"
    DomainFilterMode.ONLY_LISTED -> "Только список · $count"
    DomainFilterMode.EXCLUDE_LISTED -> "Исключения · $count"
}
