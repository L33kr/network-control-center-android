package io.github.l33kr.networkcontrolcenter.ui.v2

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
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.l33kr.networkcontrolcenter.BuildConfig
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiConfig
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiConfigStore
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiController
import io.github.l33kr.networkcontrolcenter.byedpi.CatalogStrategy
import io.github.l33kr.networkcontrolcenter.byedpi.Ipv6Mode
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiStrategyTester
import io.github.l33kr.networkcontrolcenter.byedpi.profile.DomainListModel
import io.github.l33kr.networkcontrolcenter.byedpi.profile.ProfileAction
import io.github.l33kr.networkcontrolcenter.byedpi.profile.ProfileCompiler
import io.github.l33kr.networkcontrolcenter.byedpi.profile.ProfileEngineBridge
import io.github.l33kr.networkcontrolcenter.byedpi.profile.ProfileProtocol
import io.github.l33kr.networkcontrolcenter.byedpi.profile.ProfileSetModel
import io.github.l33kr.networkcontrolcenter.byedpi.profile.ProfileStore
import io.github.l33kr.networkcontrolcenter.byedpi.profile.TrafficProfile
import io.github.l33kr.networkcontrolcenter.core.AndroidUnifiedEngineController
import io.github.l33kr.networkcontrolcenter.core.EngineStatus
import io.github.l33kr.networkcontrolcenter.tgws.TgWsController
import kotlinx.coroutines.launch

private enum class V2Section(val title: String, val icon: ImageVector) {
    HOME("Главная", Icons.Rounded.Home),
    SETS("Наборы", Icons.Rounded.Tune),
    PROFILES("Профили", Icons.Rounded.Settings),
    STRATEGIES("Стратегии", Icons.Rounded.Search),
    LISTS("Списки доменов", Icons.Rounded.Language),
    TELEGRAM("Telegram WS", Icons.Rounded.Send),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DpiControlApp(
    controller: AndroidUnifiedEngineController,
    onByeDpiChange: (Boolean) -> Unit,
    onApplyTelegramProxy: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val state by controller.state.collectAsStateWithLifecycle()
    val byeProfile by ByeDpiController.activeProfile.collectAsStateWithLifecycle()
    val byeNetwork by ByeDpiController.networkLabel.collectAsStateWithLifecycle()
    val byeIpv6 by ByeDpiController.ipv6Active.collectAsStateWithLifecycle()
    val byeError by ByeDpiController.lastError.collectAsStateWithLifecycle()
    val tgPort by TgWsController.activePort.collectAsStateWithLifecycle()
    val tgError by TgWsController.lastError.collectAsStateWithLifecycle()

    var sectionName by rememberSaveable { mutableStateOf(V2Section.HOME.name) }
    var revision by remember { mutableIntStateOf(0) }
    val section = runCatching { V2Section.valueOf(sectionName) }.getOrDefault(V2Section.HOME)

    val sets = remember(revision) { ProfileStore.loadSets(context) }
    val lists = remember(revision) { ProfileStore.loadLists(context) }
    val activeSet = remember(revision) { ProfileStore.activeSet(context) }
    val config = remember(revision) { ByeDpiConfigStore.load(context) }
    val compiled = remember(revision) { ProfileCompiler.compile(context, activeSet, config.sni) }
    val targets = remember(revision) { ByeDpiStrategyTester.collectTargets(context, activeSet) }
    val canEdit = state.byeDpi == EngineStatus.STOPPED || state.byeDpi == EngineStatus.FAILED

    fun refresh() {
        revision++
    }

    fun applyAndRefresh() {
        ProfileEngineBridge.applyActiveSet(context)
        refresh()
    }

    fun saveActiveSet(next: ProfileSetModel) {
        ProfileStore.saveSet(context, next)
        ProfileStore.setActiveSet(context, next.id)
        applyAndRefresh()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(20.dp))
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    Text("DPI Control", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        BuildConfig.VERSION_NAME,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Spacer(Modifier.height(16.dp))
                V2Section.entries.forEach { item ->
                    NavigationDrawerItem(
                        label = { Text(item.title) },
                        selected = item == section,
                        icon = { Icon(item.icon, contentDescription = null) },
                        onClick = {
                            sectionName = item.name
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(section.title, fontWeight = FontWeight.Bold)
                            Text(
                                activeSet.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Rounded.Menu, contentDescription = "Меню")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
        ) { padding ->
            when (section) {
                V2Section.HOME -> HomeV2Screen(
                    modifier = Modifier.padding(padding),
                    status = state.byeDpi,
                    activeSet = activeSet,
                    config = config,
                    enabledProfiles = compiled.enabledProfiles,
                    bypassProfiles = compiled.bypassProfiles,
                    domainCount = targets.size,
                    manualDomainCount = targets.count { it.manual },
                    activeRuntimeProfile = byeProfile,
                    network = byeNetwork,
                    ipv6 = byeIpv6,
                    error = byeError,
                    canEdit = canEdit,
                    onToggle = onByeDpiChange,
                    onSaveConfig = { next ->
                        ByeDpiConfigStore.save(context, next)
                        applyAndRefresh()
                    },
                    onOpenStrategies = { sectionName = V2Section.STRATEGIES.name },
                    onOpenLists = { sectionName = V2Section.LISTS.name },
                )

                V2Section.SETS -> SetsV2Screen(
                    modifier = Modifier.padding(padding),
                    sets = sets,
                    activeSet = activeSet,
                    enabled = canEdit,
                    onSelect = {
                        ProfileStore.setActiveSet(context, it.id)
                        applyAndRefresh()
                    },
                    onDuplicate = {
                        ProfileStore.duplicateActiveSet(context, "Мой набор")
                        applyAndRefresh()
                    },
                )

                V2Section.PROFILES -> ProfilesV2Screen(
                    modifier = Modifier.padding(padding),
                    activeSet = activeSet,
                    lists = lists,
                    enabled = canEdit,
                    onSaveSet = ::saveActiveSet,
                    onOpenStrategies = { sectionName = V2Section.STRATEGIES.name },
                )

                V2Section.STRATEGIES -> StrategyCenterScreen(
                    modifier = Modifier.padding(padding),
                    activeSet = activeSet,
                    domainLists = lists,
                    enabled = canEdit,
                    onApplyStrategy = { profileId, strategy ->
                        saveActiveSet(
                            activeSet.copy(
                                profiles = activeSet.profiles.map { profile ->
                                    if (profile.id == profileId) {
                                        profile.copy(
                                            strategyName = strategy.name,
                                            strategyCommand = strategy.command,
                                        )
                                    } else profile
                                },
                            ),
                        )
                    },
                )

                V2Section.LISTS -> ListsV2Screen(
                    modifier = Modifier.padding(padding),
                    lists = lists,
                    enabled = canEdit,
                    onSave = {
                        ProfileStore.saveDomainList(context, it)
                        applyAndRefresh()
                    },
                    onAdd = {
                        ProfileStore.createDomainList(context, "Мой список")
                        applyAndRefresh()
                    },
                )

                V2Section.TELEGRAM -> TelegramV2Screen(
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
}

@Composable
private fun HomeV2Screen(
    modifier: Modifier,
    status: EngineStatus,
    activeSet: ProfileSetModel,
    config: ByeDpiConfig,
    enabledProfiles: Int,
    bypassProfiles: Int,
    domainCount: Int,
    manualDomainCount: Int,
    activeRuntimeProfile: String?,
    network: String?,
    ipv6: Boolean,
    error: String?,
    canEdit: Boolean,
    onToggle: (Boolean) -> Unit,
    onSaveConfig: (ByeDpiConfig) -> Unit,
    onOpenStrategies: () -> Unit,
    onOpenLists: () -> Unit,
) {
    val running = status == EngineStatus.RUNNING || status == EngineStatus.STARTING
    var settingsOpen by remember { mutableStateOf(false) }

    if (settingsOpen) {
        NetworkSettingsDialog(
            config = config,
            enabled = canEdit,
            onDismiss = { settingsOpen = false },
            onSave = {
                onSaveConfig(it)
                settingsOpen = false
            },
        )
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (status == EngineStatus.RUNNING) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.fillMaxWidth(0.72f)) {
                        Text("Обход DPI", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(statusText(status), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Icon(
                            if (status == EngineStatus.RUNNING) Icons.Rounded.CheckCircle else Icons.Rounded.PowerSettingsNew,
                            contentDescription = null,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }

                if (status == EngineStatus.STARTING || status == EngineStatus.STOPPING) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (status == EngineStatus.RUNNING) {
                    Text(activeRuntimeProfile ?: activeSet.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        buildString {
                            network?.let { append(it) }
                            if (network != null) append(" · ")
                            append(if (ipv6) "IPv4 + IPv6" else "IPv4")
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (status == EngineStatus.FAILED && !error.isNullOrBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = status != EngineStatus.STARTING && status != EngineStatus.STOPPING,
                    onClick = { onToggle(!running) },
                ) {
                    Text(if (running) "Отключить" else "Запустить")
                }
            }
        }

        Text("Конфигурация", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Card(shape = RoundedCornerShape(22.dp)) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(activeSet.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(activeSet.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Профилей: $enabledProfiles · обход: $bypassProfiles")
                Text("Доменов: $domainCount · вручную: $manualDomainCount")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenStrategies) { Text("Стратегии") }
                    OutlinedButton(onClick = onOpenLists) { Text("Домены") }
                }
            }
        }

        Card(shape = RoundedCornerShape(22.dp)) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Сеть", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("DNS: ${config.dns}")
                Text("Fake SNI: ${config.sni}")
                Text("IPv6: ${config.ipv6Mode.name}")
                OutlinedButton(enabled = canEdit, onClick = { settingsOpen = true }) {
                    Icon(Icons.Rounded.Edit, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Изменить")
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SetsV2Screen(
    modifier: Modifier,
    sets: List<ProfileSetModel>,
    activeSet: ProfileSetModel,
    enabled: Boolean,
    onSelect: (ProfileSetModel) -> Unit,
    onDuplicate: () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Наборы", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Набор объединяет профили, их порядок, доменные списки и стратегии.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Button(modifier = Modifier.fillMaxWidth(), enabled = enabled, onClick = onDuplicate) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Создать копию активного набора")
            }
        }
        items(sets, key = { it.id }) { set ->
            val selected = set.id == activeSet.id
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                ),
                onClick = { if (enabled) onSelect(set) },
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(set.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(set.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${set.profiles.count { it.enabled }} активных профилей")
                    if (selected) Text("Активный", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ProfilesV2Screen(
    modifier: Modifier,
    activeSet: ProfileSetModel,
    lists: List<DomainListModel>,
    enabled: Boolean,
    onSaveSet: (ProfileSetModel) -> Unit,
    onOpenStrategies: () -> Unit,
) {
    var editing by remember { mutableStateOf<TrafficProfile?>(null) }

    editing?.let { profile ->
        ProfileEditDialog(
            profile = profile,
            lists = lists,
            enabled = enabled,
            onDismiss = { editing = null },
            onSave = { updated ->
                onSaveSet(
                    activeSet.copy(
                        profiles = activeSet.profiles.map { if (it.id == updated.id) updated else it },
                    ),
                )
                editing = null
            },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Профили", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Порядок важен: первое совпавшее правило выигрывает. PASS можно использовать для исключений.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(activeSet.profiles, key = { it.id }) { profile ->
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.fillMaxWidth(0.72f)) {
                            Text(profile.name, fontWeight = FontWeight.Bold)
                            Text(
                                "${profile.action.name} · ${profile.protocol.name}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        Switch(
                            checked = profile.enabled,
                            enabled = enabled,
                            onCheckedChange = { checked ->
                                onSaveSet(
                                    activeSet.copy(
                                        profiles = activeSet.profiles.map {
                                            if (it.id == profile.id) it.copy(enabled = checked) else it
                                        },
                                    ),
                                )
                            },
                        )
                    }
                    if (profile.action == ProfileAction.BYPASS) {
                        Text(
                            profile.strategyName ?: "Стратегия не выбрана",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    val listNames = lists.filter { it.id in profile.domainListIds }.joinToString(", ") { it.name }
                    if (listNames.isNotBlank()) {
                        Text(listNames, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(
                            enabled = enabled,
                            onClick = {
                                val mutable = activeSet.profiles.toMutableList()
                                val from = mutable.indexOfFirst { it.id == profile.id }
                                if (from > 0) {
                                    val item = mutable.removeAt(from)
                                    mutable.add(from - 1, item)
                                    onSaveSet(activeSet.copy(profiles = mutable))
                                }
                            },
                        ) { Icon(Icons.Rounded.ArrowUpward, contentDescription = "Выше") }
                        IconButton(
                            enabled = enabled,
                            onClick = {
                                val mutable = activeSet.profiles.toMutableList()
                                val from = mutable.indexOfFirst { it.id == profile.id }
                                if (from in 0 until mutable.lastIndex) {
                                    val item = mutable.removeAt(from)
                                    mutable.add(from + 1, item)
                                    onSaveSet(activeSet.copy(profiles = mutable))
                                }
                            },
                        ) { Icon(Icons.Rounded.ArrowDownward, contentDescription = "Ниже") }
                        OutlinedButton(enabled = enabled, onClick = { editing = profile }) {
                            Icon(Icons.Rounded.Edit, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Профиль")
                        }
                    }
                }
            }
        }
        item {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onOpenStrategies) {
                Icon(Icons.Rounded.Search, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Открыть каталог стратегий")
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ListsV2Screen(
    modifier: Modifier,
    lists: List<DomainListModel>,
    enabled: Boolean,
    onSave: (DomainListModel) -> Unit,
    onAdd: () -> Unit,
) {
    var editing by remember { mutableStateOf<DomainListModel?>(null) }
    editing?.let { list ->
        DomainListEditDialog(
            list = list,
            enabled = enabled,
            onDismiss = { editing = null },
            onSave = {
                onSave(it)
                editing = null
            },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Списки доменов", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Все домены отсюда, включая созданные вручную, автоматически попадают в проверку стратегий.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Button(modifier = Modifier.fillMaxWidth(), enabled = enabled, onClick = onAdd) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Новый список")
            }
        }
        items(lists, key = { it.id }) { list ->
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), onClick = { editing = list }) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(list.name, fontWeight = FontWeight.Bold)
                    Text("${list.domains.size} доменов", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (list.domains.isNotEmpty()) {
                        Text(
                            list.domains.take(4).joinToString(" · "),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun TelegramV2Screen(
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
        Card(shape = RoundedCornerShape(28.dp)) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Telegram WS Proxy", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(statusText(status), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (port > 0) Text("127.0.0.1:$port")
                if (status == EngineStatus.FAILED && !error.isNullOrBlank()) Text(error, color = MaterialTheme.colorScheme.error)
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
        Text(
            "TG WS остаётся независимым от VPN и может работать одновременно с профилями ByeDPI.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProfileEditDialog(
    profile: TrafficProfile,
    lists: List<DomainListModel>,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (TrafficProfile) -> Unit,
) {
    var draft by remember(profile) { mutableStateOf(profile) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Профиль") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    label = { Text("Название") },
                    singleLine = true,
                )
                Text("Действие", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileAction.entries.forEach { action ->
                        FilterChip(
                            selected = draft.action == action,
                            onClick = { draft = draft.copy(action = action) },
                            label = { Text(action.name) },
                        )
                    }
                }
                Text("Протокол", fontWeight = FontWeight.SemiBold)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ProfileProtocol.entries.forEach { protocol ->
                        FilterChip(
                            selected = draft.protocol == protocol,
                            onClick = { draft = draft.copy(protocol = protocol) },
                            label = { Text(protocol.name) },
                        )
                    }
                }
                Text("Списки доменов", fontWeight = FontWeight.SemiBold)
                lists.forEach { list ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = list.id in draft.domainListIds,
                            onCheckedChange = { checked ->
                                val next = draft.domainListIds.toMutableSet().apply {
                                    if (checked) add(list.id) else remove(list.id)
                                }
                                draft = draft.copy(domainListIds = next.toList())
                            },
                        )
                        Text("${list.name} (${list.domains.size})")
                    }
                }
                if (draft.action == ProfileAction.BYPASS) {
                    Text("Стратегия: ${draft.strategyName ?: "не выбрана"}")
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = draft.strategyCommand.orEmpty(),
                        onValueChange = { draft = draft.copy(strategyCommand = it, strategyName = "Custom") },
                        label = { Text("Аргументы ByeDPI") },
                        minLines = 2,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        },
        confirmButton = {
            Button(enabled = enabled, onClick = { onSave(draft.copy(name = draft.name.trim().ifBlank { "Профиль" })) }) {
                Text("Сохранить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun DomainListEditDialog(
    list: DomainListModel,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (DomainListModel) -> Unit,
) {
    var name by remember(list) { mutableStateOf(list.name) }
    var domains by remember(list) { mutableStateOf(list.domains.joinToString("\n")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Список доменов") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = domains,
                    onValueChange = { domains = it },
                    label = { Text("По одному домену на строку") },
                    minLines = 8,
                    maxLines = 14,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                Text(
                    "Поддомены можно указывать обычным доменом: example.com.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = enabled,
                onClick = {
                    onSave(
                        list.copy(
                            name = name.trim().ifBlank { "Список" },
                            domains = domains.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList(),
                        ),
                    )
                },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun NetworkSettingsDialog(
    config: ByeDpiConfig,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (ByeDpiConfig) -> Unit,
) {
    var dns by remember(config) { mutableStateOf(config.dns) }
    var sni by remember(config) { mutableStateOf(config.sni) }
    var ipv6 by remember(config) { mutableStateOf(config.ipv6Mode) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сетевые настройки") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = dns,
                    onValueChange = { dns = it },
                    label = { Text("DNS") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = sni,
                    onValueChange = { sni = it },
                    label = { Text("Fake SNI") },
                    singleLine = true,
                )
                Text("IPv6", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Ipv6Mode.entries.forEach { mode ->
                        FilterChip(
                            selected = ipv6 == mode,
                            onClick = { ipv6 = mode },
                            label = { Text(mode.name) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = enabled,
                onClick = {
                    onSave(
                        config.copy(
                            dns = dns.trim().ifBlank { "1.1.1.1" },
                            sni = sni.trim().ifBlank { "google.com" },
                            ipv6Mode = ipv6,
                        ),
                    )
                },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

private fun statusText(status: EngineStatus): String = when (status) {
    EngineStatus.STOPPED -> "Выключено"
    EngineStatus.STARTING -> "Запуск…"
    EngineStatus.RUNNING -> "Работает"
    EngineStatus.STOPPING -> "Остановка…"
    EngineStatus.FAILED -> "Ошибка"
}
