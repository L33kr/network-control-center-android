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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiConfig
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiConfigStore
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiController
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiStableProfile
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiStrategyCatalog
import io.github.l33kr.networkcontrolcenter.byedpi.CatalogStrategy
import io.github.l33kr.networkcontrolcenter.byedpi.VpnAppEntry
import io.github.l33kr.networkcontrolcenter.byedpi.VpnAppFilterStore
import io.github.l33kr.networkcontrolcenter.core.AndroidUnifiedEngineController
import io.github.l33kr.networkcontrolcenter.core.EngineState
import io.github.l33kr.networkcontrolcenter.core.EngineStatus
import io.github.l33kr.networkcontrolcenter.tgws.TgWsController
import io.github.l33kr.networkcontrolcenter.ui.theme.DpiControlTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ByeDpiConfigStore.ensureStableRebuildBaseline(applicationContext)

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
    APPS("Приложения", Icons.Rounded.Apps),
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
    val tgPort by TgWsController.activePort.collectAsStateWithLifecycle()
    val tgError by TgWsController.lastError.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var selectedTabName by rememberSaveable { mutableStateOf(AppTab.HOME.name) }
    var configRevision by remember { mutableIntStateOf(0) }
    val config = remember(configRevision) { ByeDpiConfigStore.load(context) }
    val catalog = remember { ByeDpiStrategyCatalog.load(context) }
    val selectedTab = runCatching { AppTab.valueOf(selectedTabName) }.getOrDefault(AppTab.HOME)
    val vpnEditable = state.byeDpi == EngineStatus.STOPPED || state.byeDpi == EngineStatus.FAILED

    fun refreshConfig() {
        configRevision++
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("DPI Control", fontWeight = FontWeight.Bold)
                        Text(
                            BuildConfig.VERSION_NAME,
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
                        label = { Text(tab.title, maxLines = 1) },
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
                catalog = catalog,
                editable = vpnEditable,
                onToggle = onByeDpiChange,
                onSelectStrategy = { strategy ->
                    ByeDpiConfigStore.setCatalogStrategy(context, strategy.name, strategy.command)
                    refreshConfig()
                },
                onUseStable16 = {
                    ByeDpiConfigStore.setCatalogStrategy(
                        context,
                        ByeDpiStableProfile.NAME,
                        ByeDpiStableProfile.COMMAND,
                    )
                    refreshConfig()
                },
                onSniSave = { sni ->
                    ByeDpiConfigStore.setSni(context, sni)
                    refreshConfig()
                },
            )

            AppTab.APPS -> AppFilterScreen(
                modifier = Modifier.padding(padding),
                editable = vpnEditable,
            )

            AppTab.TELEGRAM -> TelegramScreen(
                modifier = Modifier.padding(padding),
                status = state.tgWs,
                port = tgPort,
                error = tgError,
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
    catalog: List<CatalogStrategy>,
    editable: Boolean,
    onToggle: (Boolean) -> Unit,
    onSelectStrategy: (CatalogStrategy) -> Unit,
    onUseStable16: () -> Unit,
    onSniSave: (String) -> Unit,
) {
    val running = state.byeDpi == EngineStatus.RUNNING || state.byeDpi == EngineStatus.STARTING
    var showStrategies by rememberSaveable { mutableStateOf(false) }
    var sni by rememberSaveable(config.sni) { mutableStateOf(config.sni) }

    if (showStrategies) {
        StrategyDialog(
            catalog = catalog,
            currentCommand = config.command,
            onDismiss = { showStrategies = false },
            onSelect = {
                onSelectStrategy(it)
                showStrategies = false
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (state.byeDpi == EngineStatus.RUNNING) {
                    MaterialTheme.colorScheme.primaryContainer
                } else MaterialTheme.colorScheme.surface
            ),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Icon(
                            if (running) Icons.Rounded.CheckCircle else Icons.Rounded.PowerSettingsNew,
                            contentDescription = null,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Обход DPI", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(statusLabel(state.byeDpi), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (state.byeDpi == EngineStatus.STARTING || state.byeDpi == EngineStatus.STOPPING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                if (state.byeDpi == EngineStatus.RUNNING) {
                    Text(
                        listOfNotNull(activeProfile, network).joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (ipv6) "IPv4 + IPv6" else "IPv4",
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
                    onClick = { onToggle(!running) },
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

        Text("Стратегия", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    config.strategyName ?: "Пользовательская",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (config.command == ByeDpiStableProfile.COMMAND) {
                        "Проверенная стратегия №16 из рабочей 0.4"
                    } else {
                        "Пользовательская стратегия ByeDPI"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(enabled = editable, onClick = onUseStable16) {
                        Text("№16")
                    }
                    OutlinedButton(enabled = editable, onClick = { showStrategies = true }) {
                        Icon(Icons.Rounded.Tune, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Другая")
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Fake SNI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = sni,
                    onValueChange = { sni = it },
                    enabled = editable,
                    singleLine = true,
                    label = { Text("SNI для fake-пакета") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = editable && sni.trim().isNotBlank() && sni.trim() != config.sni,
                    onClick = { onSniSave(sni.trim()) },
                ) {
                    Text("Сохранить SNI")
                }
            }
        }

        Text(
            "Приложения, которые должны идти напрямую без VPN, выбираются во вкладке «Приложения». Изменять список нужно при выключенном VPN.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun StrategyDialog(
    catalog: List<CatalogStrategy>,
    currentCommand: String,
    onDismiss: () -> Unit,
    onSelect: (CatalogStrategy) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(query, catalog) {
        if (query.isBlank()) catalog
        else catalog.filter { strategy ->
            strategy.name.contains(query, ignoreCase = true) ||
                (strategy.index + 1).toString() == query.trim()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Стратегии ByeDPI") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    label = { Text("Номер стратегии") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    items(filtered, key = { it.index }) { strategy ->
                        val selected = strategy.command == currentCommand
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            onClick = { onSelect(strategy) },
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    "Стратегия ${strategy.index + 1}",
                                    fontWeight = FontWeight.Bold,
                                )
                                if (strategy.index + 1 == 16) {
                                    Text("Рабочая на проверенной сети", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
private fun AppFilterScreen(
    modifier: Modifier,
    editable: Boolean,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val apps = remember { VpnAppFilterStore.launchableApps(context) }
    var revision by remember { mutableIntStateOf(0) }
    val excluded = remember(revision) { VpnAppFilterStore.excludedPackages(context) }
    var query by rememberSaveable { mutableStateOf("") }

    val filteredApps = remember(query, apps) {
        val q = query.trim()
        if (q.isBlank()) apps else apps.filter {
            it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("Исключения VPN", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Отмеченные приложения идут напрямую через обычную сеть и вообще не попадают в VpnService/ByeDPI.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!editable) {
                Text(
                    "Отключи VPN, чтобы изменить список.",
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                label = { Text("Поиск приложения") },
            )
            Text(
                "Исключено: ${excluded.size}",
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }

        items(filteredApps, key = { it.packageName }) { app ->
            AppFilterRow(
                app = app,
                checked = app.packageName in excluded,
                enabled = editable,
                onChecked = { checked ->
                    VpnAppFilterStore.setExcluded(context, app.packageName, checked)
                    revision++
                },
            )
        }

        item { Spacer(Modifier.height(18.dp)) }
    }
}

@Composable
private fun AppFilterRow(
    app: VpnAppEntry,
    checked: Boolean,
    enabled: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(app.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Checkbox(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onChecked,
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
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Send, contentDescription = null)
                    Spacer(Modifier.width(9.dp))
                    Text("Telegram Proxy", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                Text(statusLabel(status), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (port in 1..65535) Text("127.0.0.1:$port")
                if (!error.isNullOrBlank()) Text(error, color = MaterialTheme.colorScheme.error)

                if (status == EngineStatus.STARTING || status == EngineStatus.STOPPING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
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
                    Text("Подключить в Telegram")
                }
            }
        }

        Text(
            "В этой версии сервис держит wake-lock всё время работы и автоматически восстанавливается Android после выгрузки процесса.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun statusLabel(status: EngineStatus): String = when (status) {
    EngineStatus.STOPPED -> "Остановлен"
    EngineStatus.STARTING -> "Запускается…"
    EngineStatus.RUNNING -> "Работает"
    EngineStatus.STOPPING -> "Останавливается…"
    EngineStatus.FAILED -> "Ошибка"
}
