package io.github.l33kr.networkcontrolcenter.ui.nativealpha

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.l33kr.networkcontrolcenter.BuildConfig
import io.github.l33kr.networkcontrolcenter.byedpi.profile.DomainListModel
import io.github.l33kr.networkcontrolcenter.byedpi.profile.ProfileAction
import io.github.l33kr.networkcontrolcenter.byedpi.profile.ProfileProtocol
import io.github.l33kr.networkcontrolcenter.byedpi.profile.ProfileSetModel
import io.github.l33kr.networkcontrolcenter.byedpi.profile.ProfileStore
import io.github.l33kr.networkcontrolcenter.core.AndroidUnifiedEngineController
import io.github.l33kr.networkcontrolcenter.core.EngineStatus
import io.github.l33kr.networkcontrolcenter.nativecore.NativeRuntime
import io.github.l33kr.networkcontrolcenter.nativecore.NativeStrategies
import io.github.l33kr.networkcontrolcenter.nativecore.NativeStrategyPreset
import io.github.l33kr.networkcontrolcenter.tgws.TgWsController

private enum class NativeTab(val title: String, val icon: ImageVector) {
    HOME("Главная", Icons.Rounded.Home),
    DOMAINS("Домены", Icons.Rounded.Language),
    STRATEGIES("Стратегии", Icons.Rounded.Tune),
    DIAGNOSTICS("Диагностика", Icons.Rounded.Speed),
    TELEGRAM("Telegram", Icons.Rounded.Send),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeControlApp(
    controller: AndroidUnifiedEngineController,
    onVpnChange: (Boolean) -> Unit,
    onTelegramChange: (Boolean) -> Unit,
    onApplyTelegramProxy: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val engineState by controller.state.collectAsStateWithLifecycle()
    val runtime by NativeRuntime.snapshot.collectAsStateWithLifecycle()
    val tgPort by TgWsController.activePort.collectAsStateWithLifecycle()
    val tgError by TgWsController.lastError.collectAsStateWithLifecycle()

    var selectedName by rememberSaveable { mutableStateOf(NativeTab.HOME.name) }
    var revision by remember { mutableIntStateOf(0) }
    val selected = runCatching { NativeTab.valueOf(selectedName) }.getOrDefault(NativeTab.HOME)
    val lists = remember(revision) { ProfileStore.loadLists(context) }
    val sets = remember(revision) { ProfileStore.loadSets(context) }
    val activeSet = remember(revision) { ProfileStore.activeSet(context) }
    val vpnEditable = engineState.byeDpi == EngineStatus.STOPPED || engineState.byeDpi == EngineStatus.FAILED

    fun refresh() {
        revision++
    }

    fun saveSet(set: ProfileSetModel) {
        ProfileStore.saveSet(context, set)
        ProfileStore.setActiveSet(context, set.id)
        refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("DPI Control", fontWeight = FontWeight.Bold)
                        Text(
                            "Native Engine · ${BuildConfig.VERSION_NAME}",
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
                NativeTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selected == tab,
                        onClick = { selectedName = tab.name },
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = {
                            Text(
                                when (tab) {
                                    NativeTab.DIAGNOSTICS -> "Статус"
                                    NativeTab.STRATEGIES -> "Методы"
                                    else -> tab.title
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        },
    ) { padding ->
        when (selected) {
            NativeTab.HOME -> HomeScreen(
                modifier = Modifier.padding(padding),
                status = engineState.byeDpi,
                activeSet = activeSet,
                sets = sets,
                domainCount = lists.sumOf { it.domains.size },
                transformed = runtime.transformedFlows,
                activeFlows = runtime.activeFlows,
                onToggle = onVpnChange,
                onSelectSet = { set ->
                    ProfileStore.setActiveSet(context, set.id)
                    refresh()
                },
                onOpenDomains = { selectedName = NativeTab.DOMAINS.name },
                onOpenStrategies = { selectedName = NativeTab.STRATEGIES.name },
            )

            NativeTab.DOMAINS -> DomainsScreen(
                modifier = Modifier.padding(padding),
                lists = lists,
                editable = vpnEditable,
                onSave = { list ->
                    ProfileStore.saveDomainList(context, list)
                    refresh()
                },
                onCreate = {
                    ProfileStore.createDomainList(context, "Мои домены")
                    refresh()
                },
            )

            NativeTab.STRATEGIES -> StrategiesScreen(
                modifier = Modifier.padding(padding),
                activeSet = activeSet,
                lists = lists,
                editable = vpnEditable,
                onSaveSet = ::saveSet,
            )

            NativeTab.DIAGNOSTICS -> DiagnosticsScreen(
                modifier = Modifier.padding(padding),
                status = engineState.byeDpi,
                activeSet = activeSet,
                domainCount = lists.sumOf { it.domains.size },
                runtime = runtime,
            )

            NativeTab.TELEGRAM -> TelegramScreen(
                modifier = Modifier.padding(padding),
                status = engineState.tgWs,
                port = tgPort,
                error = tgError,
                onToggle = onTelegramChange,
                onApply = onApplyTelegramProxy,
            )
        }
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier,
    status: EngineStatus,
    activeSet: ProfileSetModel,
    sets: List<ProfileSetModel>,
    domainCount: Int,
    transformed: Int,
    activeFlows: Int,
    onToggle: (Boolean) -> Unit,
    onSelectSet: (ProfileSetModel) -> Unit,
    onOpenDomains: () -> Unit,
    onOpenStrategies: () -> Unit,
) {
    val running = status == EngineStatus.RUNNING || status == EngineStatus.STARTING
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(30.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (status == EngineStatus.RUNNING) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
            ),
        ) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.fillMaxWidth(0.75f)) {
                        Text("Обход DPI", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(
                            when (status) {
                                EngineStatus.RUNNING -> "Активен · без root"
                                EngineStatus.STARTING -> "Запускается…"
                                EngineStatus.STOPPING -> "Останавливается…"
                                EngineStatus.FAILED -> "Ошибка запуска"
                                EngineStatus.STOPPED -> "Выключен · без root"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Icon(
                            if (status == EngineStatus.RUNNING) Icons.Rounded.CheckCircle else Icons.Rounded.PowerSettingsNew,
                            contentDescription = null,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }

                if (status == EngineStatus.STARTING || status == EngineStatus.STOPPING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                if (status == EngineStatus.RUNNING) {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        MiniStat("Потоки", activeFlows.toString())
                        MiniStat("Изменено", transformed.toString())
                        MiniStat("Домены", domainCount.toString())
                    }
                }

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = status != EngineStatus.STARTING && status != EngineStatus.STOPPING,
                    onClick = { onToggle(!running) },
                ) {
                    Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (running) "Отключить" else "Запустить")
                }
            }
        }

        Text("Конфигурация", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(activeSet.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(activeSet.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (sets.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        sets.take(3).forEach { set ->
                            FilledTonalButton(onClick = { onSelectSet(set) }) {
                                Text(set.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(modifier = Modifier.weight(1f), onClick = onOpenDomains) {
                Icon(Icons.Rounded.Dns, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Домены")
            }
            OutlinedButton(modifier = Modifier.weight(1f), onClick = onOpenStrategies) {
                Icon(Icons.Rounded.Bolt, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Методы")
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DomainsScreen(
    modifier: Modifier,
    lists: List<DomainListModel>,
    editable: Boolean,
    onSave: (DomainListModel) -> Unit,
    onCreate: () -> Unit,
) {
    var editing by remember { mutableStateOf<DomainListModel?>(null) }

    editing?.let { list ->
        DomainEditorDialog(
            list = list,
            onDismiss = { editing = null },
            onSave = {
                onSave(it)
                editing = null
            },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("Списки доменов", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Движок обрабатывает только правила активного набора. Поддомены совпадают автоматически.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
        }

        items(lists, key = { it.id }) { list ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                onClick = { if (editable) editing = list },
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(list.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text("${list.domains.size}", modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                        }
                    }
                    Text(
                        list.domains.take(3).joinToString(" · ").ifBlank { "Пустой список" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        item {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = editable,
                onClick = onCreate,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Новый список")
            }
            if (!editable) {
                Text(
                    "Отключите VPN, чтобы менять правила.",
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun DomainEditorDialog(
    list: DomainListModel,
    onDismiss: () -> Unit,
    onSave: (DomainListModel) -> Unit,
) {
    var name by remember(list.id) { mutableStateOf(list.name) }
    var domains by remember(list.id) { mutableStateOf(list.domains.joinToString("\n")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Редактировать список") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = domains,
                    onValueChange = { domains = it },
                    label = { Text("Домены · по одному на строку") },
                    minLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsed = domains
                        .lineSequence()
                        .flatMap { it.split(' ', ',', ';').asSequence() }
                        .map { it.trim().lowercase() }
                        .filter { it.isNotBlank() }
                        .toList()
                    onSave(list.copy(name = name.trim().ifBlank { list.name }, domains = parsed))
                },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun StrategiesScreen(
    modifier: Modifier,
    activeSet: ProfileSetModel,
    lists: List<DomainListModel>,
    editable: Boolean,
    onSaveSet: (ProfileSetModel) -> Unit,
) {
    var targetProfileId by remember { mutableStateOf<String?>(null) }
    val bypassProfiles = activeSet.profiles.filter {
        it.enabled && it.action == ProfileAction.BYPASS && it.protocol != ProfileProtocol.UDP_QUIC
    }

    targetProfileId?.let { id ->
        val profile = bypassProfiles.firstOrNull { it.id == id }
        if (profile != null) {
            StrategyPickerDialog(
                profileName = profile.name,
                current = NativeStrategies.fromCommand(profile.strategyCommand) ?: NativeStrategies.AUTO,
                onDismiss = { targetProfileId = null },
                onSelect = { preset ->
                    onSaveSet(
                        activeSet.copy(
                            profiles = activeSet.profiles.map { item ->
                                if (item.id == id) item.copy(strategyName = preset.title, strategyCommand = preset.command) else item
                            },
                        ),
                    )
                    targetProfileId = null
                },
            )
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("Методы обхода", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Это собственные методы DPI Control. Никаких команд ByeDPI здесь не исполняется.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            FilledTonalButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = editable && bypassProfiles.isNotEmpty(),
                onClick = {
                    val preset = NativeStrategies.AUTO
                    onSaveSet(
                        activeSet.copy(
                            profiles = activeSet.profiles.map { profile ->
                                if (profile.enabled && profile.action == ProfileAction.BYPASS && profile.protocol != ProfileProtocol.UDP_QUIC) {
                                    profile.copy(strategyName = preset.title, strategyCommand = preset.command)
                                } else profile
                            },
                        ),
                    )
                },
            ) {
                Icon(Icons.Rounded.Bolt, contentDescription = null)
                Spacer(Modifier.width(7.dp))
                Text("Auto для всех TCP-профилей")
            }
        }

        items(bypassProfiles, key = { it.id }) { profile ->
            val current = NativeStrategies.fromCommand(profile.strategyCommand) ?: NativeStrategies.AUTO
            val listNames = lists.filter { it.id in profile.domainListIds }.map { it.name }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                onClick = { if (editable) targetProfileId = profile.id },
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        listNames.joinToString(" · ").ifBlank { "Все домены профиля" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Memory, contentDescription = null)
                            Spacer(Modifier.width(7.dp))
                            Text(current.title, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        item {
            Text("Доступные методы", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        items(NativeStrategies.all, key = { it.id }) { preset ->
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(preset.title, fontWeight = FontWeight.Bold)
                    Text(preset.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Spacer(Modifier.height(18.dp)) }
    }
}

@Composable
private fun StrategyPickerDialog(
    profileName: String,
    current: NativeStrategyPreset,
    onDismiss: () -> Unit,
    onSelect: (NativeStrategyPreset) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(profileName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NativeStrategies.all.forEach { preset ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (preset.id == current.id) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        onClick = { onSelect(preset) },
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(preset.title, fontWeight = FontWeight.Bold)
                            Text(preset.description, style = MaterialTheme.typography.bodySmall)
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
private fun DiagnosticsScreen(
    modifier: Modifier,
    status: EngineStatus,
    activeSet: ProfileSetModel,
    domainCount: Int,
    runtime: io.github.l33kr.networkcontrolcenter.nativecore.NativeRuntimeSnapshot,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Диагностика", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Счётчики обновляются локально и не создают фоновых сетевых проверок.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DiagnosticRow("VPN", statusLabel(status))
                DiagnosticRow("Движок", "Native Engine α")
                DiagnosticRow("Набор", activeSet.name)
                DiagnosticRow("Доменов", domainCount.toString())
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Трафик", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                DiagnosticRow("Активных потоков", runtime.activeFlows.toString())
                DiagnosticRow("Всего TCP-потоков", runtime.totalFlows.toString())
                DiagnosticRow("TLS ClientHello", runtime.tlsFlows.toString())
                DiagnosticRow("Преобразовано", runtime.transformedFlows.toString())
                DiagnosticRow("UDP пакетов", runtime.udpPackets.toString())
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Последнее событие", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                DiagnosticRow("Хост", runtime.lastHost ?: "—")
                DiagnosticRow("Метод", runtime.lastTechnique ?: "—")
                if (!runtime.lastError.isNullOrBlank()) {
                    Text(runtime.lastError, color = MaterialTheme.colorScheme.error)
                }
                OutlinedButton(onClick = { NativeRuntime.reset() }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Сбросить счётчики")
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Send, contentDescription = null)
                    Spacer(Modifier.width(9.dp))
                    Text("Telegram WS", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                Text(statusLabel(status), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (port in 1..65535) Text("Локальный порт: $port")
                if (!error.isNullOrBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = status != EngineStatus.STARTING && status != EngineStatus.STOPPING,
                    onClick = { onToggle(!running) },
                ) {
                    Text(if (running) "Остановить" else "Запустить")
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

private fun statusLabel(status: EngineStatus): String = when (status) {
    EngineStatus.STOPPED -> "Остановлен"
    EngineStatus.STARTING -> "Запускается"
    EngineStatus.RUNNING -> "Работает"
    EngineStatus.STOPPING -> "Останавливается"
    EngineStatus.FAILED -> "Ошибка"
}
