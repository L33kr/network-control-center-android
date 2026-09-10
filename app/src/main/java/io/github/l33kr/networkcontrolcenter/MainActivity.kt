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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiStrategies
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiStrategyCatalog
import io.github.l33kr.networkcontrolcenter.byedpi.CatalogStrategy
import io.github.l33kr.networkcontrolcenter.byedpi.Ipv6Mode
import io.github.l33kr.networkcontrolcenter.byedpi.profile.CompiledProfileSet
import io.github.l33kr.networkcontrolcenter.byedpi.profile.DomainListModel
import io.github.l33kr.networkcontrolcenter.byedpi.profile.ProfileAction
import io.github.l33kr.networkcontrolcenter.byedpi.profile.ProfileCompiler
import io.github.l33kr.networkcontrolcenter.byedpi.profile.ProfileEngineBridge
import io.github.l33kr.networkcontrolcenter.byedpi.profile.ProfileProtocol
import io.github.l33kr.networkcontrolcenter.byedpi.profile.ProfileSetModel
import io.github.l33kr.networkcontrolcenter.byedpi.profile.ProfileStore
import io.github.l33kr.networkcontrolcenter.byedpi.profile.TrafficProfile
import io.github.l33kr.networkcontrolcenter.core.AndroidUnifiedEngineController
import io.github.l33kr.networkcontrolcenter.core.EngineState
import io.github.l33kr.networkcontrolcenter.core.EngineStatus
import io.github.l33kr.networkcontrolcenter.tgws.TgWsController
import io.github.l33kr.networkcontrolcenter.ui.theme.DpiControlTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ProfileStore.ensureInitialized(applicationContext)
        ProfileEngineBridge.applyActiveSet(applicationContext)

        setContent {
            DpiControlTheme {
                val controller = remember { AndroidUnifiedEngineController(applicationContext) }
                val scope = rememberCoroutineScope()
                val vpnPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        ProfileEngineBridge.applyActiveSet(applicationContext)
                        scope.launch { controller.startByeDpi() }
                    }
                }

                DpiControlV2App(
                    controller = controller,
                    onByeDpiChange = { enabled ->
                        if (!enabled) {
                            scope.launch { controller.stopByeDpi() }
                        } else {
                            ProfileEngineBridge.applyActiveSet(applicationContext)
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
    SETS("Наборы", Icons.Rounded.Tune),
    PROFILES("Профили", Icons.Rounded.Settings),
    LISTS("Списки", Icons.Rounded.Language),
    TELEGRAM("Telegram", Icons.Rounded.Send),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DpiControlV2App(
    controller: AndroidUnifiedEngineController,
    onByeDpiChange: (Boolean) -> Unit,
    onApplyTelegramProxy: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsStateWithLifecycle()
    val byeProfile by ByeDpiController.activeProfile.collectAsStateWithLifecycle()
    val byeNetwork by ByeDpiController.networkLabel.collectAsStateWithLifecycle()
    val byeIpv6 by ByeDpiController.ipv6Active.collectAsStateWithLifecycle()
    val byeError by ByeDpiController.lastError.collectAsStateWithLifecycle()
    val tgPort by TgWsController.activePort.collectAsStateWithLifecycle()
    val tgError by TgWsController.lastError.collectAsStateWithLifecycle()

    var tabName by rememberSaveable { mutableStateOf(AppTab.HOME.name) }
    var revision by remember { mutableIntStateOf(0) }
    val sets = remember(revision) { ProfileStore.loadSets(context) }
    val lists = remember(revision) { ProfileStore.loadLists(context) }
    val activeSet = remember(revision) { ProfileStore.activeSet(context) }
    val config = remember(revision) { ByeDpiConfigStore.load(context) }
    val catalog = remember { ByeDpiStrategyCatalog.load(context) }
    val compiled = remember(revision) { ProfileCompiler.compile(context, activeSet, config.sni) }
    val selectedTab = runCatching { AppTab.valueOf(tabName) }.getOrDefault(AppTab.HOME)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("DPI Control", fontWeight = FontWeight.Bold)
                        Text(
                            "${activeSet.name} · ${BuildConfig.VERSION_NAME}",
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
                        onClick = { tabName = tab.name },
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
                activeSet = activeSet,
                compiled = compiled,
                config = config,
                activeRuntimeProfile = byeProfile,
                network = byeNetwork,
                ipv6 = byeIpv6,
                error = byeError,
                onToggle = onByeDpiChange,
                onOpenSets = { tabName = AppTab.SETS.name },
                onOpenProfiles = { tabName = AppTab.PROFILES.name },
                onOpenLists = { tabName = AppTab.LISTS.name },
                onSaveAdvanced = { next ->
                    ByeDpiConfigStore.save(context, next)
                    applyAndRefresh()
                },
                canEdit = canEdit,
            )

            AppTab.SETS -> SetsScreen(
                modifier = Modifier.padding(padding),
                sets = sets,
                activeSet = activeSet,
                enabled = canEdit,
                onSelect = { set ->
                    ProfileStore.setActiveSet(context, set.id)
                    applyAndRefresh()
                },
                onDuplicate = {
                    ProfileStore.duplicateActiveSet(context, "Мой набор")
                    applyAndRefresh()
                },
            )

            AppTab.PROFILES -> ProfilesScreen(
                modifier = Modifier.padding(padding),
                set = activeSet,
                lists = lists,
                catalog = catalog,
                enabled = canEdit,
                onSaveProfile = { updated ->
                    saveActiveSet(
                        activeSet.copy(
                            profiles = activeSet.profiles.map { current ->
                                if (current.id == updated.id) updated else current
                            },
                        ),
                    )
                },
                onToggle = { id, isEnabled ->
                    saveActiveSet(
                        activeSet.copy(
                            profiles = activeSet.profiles.map { profile ->
                                if (profile.id == id) profile.copy(enabled = isEnabled) else profile
                            },
                        ),
                    )
                },
                onMove = { id, delta ->
                    val mutable = activeSet.profiles.toMutableList()
                    val from = mutable.indexOfFirst { it.id == id }
                    if (from >= 0) {
                        val to = (from + delta).coerceIn(0, mutable.lastIndex)
                        if (from != to) {
                            val item = mutable.removeAt(from)
                            mutable.add(to, item)
                            saveActiveSet(activeSet.copy(profiles = mutable))
                        }
                    }
                },
            )

            AppTab.LISTS -> ListsScreen(
                modifier = Modifier.padding(padding),
                lists = lists,
                enabled = canEdit,
                onSave = { list ->
                    ProfileStore.saveDomainList(context, list)
                    applyAndRefresh()
                },
                onAdd = {
                    ProfileStore.createDomainList(context, "Мой список")
                    applyAndRefresh()
                },
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
    activeSet: ProfileSetModel,
    compiled: CompiledProfileSet,
    config: ByeDpiConfig,
    activeRuntimeProfile: String?,
    network: String?,
    ipv6: Boolean,
    error: String?,
    onToggle: (Boolean) -> Unit,
    onOpenSets: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenLists: () -> Unit,
    onSaveAdvanced: (ByeDpiConfig) -> Unit,
    canEdit: Boolean,
) {
    val running = state.byeDpi == EngineStatus.RUNNING || state.byeDpi == EngineStatus.STARTING
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    if (showAdvanced) {
        AdvancedSettingsDialog(
            config = config,
            enabled = canEdit,
            onDismiss = { showAdvanced = false },
            onSave = {
                onSaveAdvanced(it)
                showAdvanced = false
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
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (state.byeDpi == EngineStatus.RUNNING) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
            ),
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Icon(
                            imageVector = if (running) Icons.Rounded.CheckCircle else Icons.Rounded.PowerSettingsNew,
                            contentDescription = null,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                    Column(modifier = Modifier.fillMaxWidth(0.78f)) {
                        Text("Обход DPI", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(statusLabel(state.byeDpi), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (state.byeDpi == EngineStatus.STARTING || state.byeDpi == EngineStatus.STOPPING) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                if (state.byeDpi == EngineStatus.RUNNING) {
                    InfoPill(activeRuntimeProfile ?: activeSet.name)
                    network?.let { InfoPill(it) }
                    Text(
                        if (ipv6) "Маршрут: IPv4 + IPv6" else "Маршрут: IPv4",
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

        SectionTitle("Активный набор")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            onClick = onOpenSets,
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(activeSet.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(activeSet.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                InfoPill("${compiled.enabledProfiles} профилей · ${compiled.bypassProfiles} bypass · ${compiled.passProfiles} pass")
            }
        }

        SectionTitle("Маршрутизация")
        activeSet.profiles.filter { it.enabled }.take(5).forEachIndexed { index, profile ->
            RoutePreviewRow(index + 1, profile)
        }
        if (activeSet.profiles.count { it.enabled } > 5) {
            Text(
                "+ ещё ${activeSet.profiles.count { it.enabled } - 5}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = onOpenProfiles) {
            Text("Открыть профили")
        }
        FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = onOpenLists) {
            Text("Открыть доменные списки")
        }
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { showAdvanced = true }) {
            Icon(Icons.Rounded.Settings, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("DNS, IPv6 и Fake SNI")
        }
    }
}

@Composable
private fun SetsScreen(
    modifier: Modifier,
    sets: List<ProfileSetModel>,
    activeSet: ProfileSetModel,
    enabled: Boolean,
    onSelect: (ProfileSetModel) -> Unit,
    onDuplicate: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("Наборы")
        Text(
            "Набор задаёт порядок правил. Более точные профили должны находиться выше общего PASS.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        sets.forEach { set ->
            val selected = set.id == activeSet.id
            Card(
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                onClick = { onSelect(set) },
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(set.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (selected) Icon(Icons.Rounded.CheckCircle, contentDescription = "Активен")
                    }
                    Text(set.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${set.profiles.count { it.enabled }} активных профилей", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = enabled, onClick = onDuplicate) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Сделать редактируемую копию")
        }

        if (!enabled) DisabledHint()
    }
}

@Composable
private fun ProfilesScreen(
    modifier: Modifier,
    set: ProfileSetModel,
    lists: List<DomainListModel>,
    catalog: List<CatalogStrategy>,
    enabled: Boolean,
    onSaveProfile: (TrafficProfile) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onMove: (String, Int) -> Unit,
) {
    var editing by remember { mutableStateOf<TrafficProfile?>(null) }

    editing?.let { profile ->
        ProfileEditorDialog(
            profile = profile,
            lists = lists,
            catalog = catalog,
            enabled = enabled,
            onDismiss = { editing = null },
            onSave = {
                onSaveProfile(it)
                editing = null
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("${set.name} · профили")
        Text(
            "Первое совпавшее правило определяет обработку. Профили можно отключать и менять местами.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        set.profiles.forEachIndexed { index, profile ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                onClick = { if (enabled) editing = profile },
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.fillMaxWidth(0.73f)) {
                            Text("${index + 1}. ${profile.name}", fontWeight = FontWeight.SemiBold)
                            Text(
                                profileSummary(profile, lists),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Switch(
                            checked = profile.enabled,
                            enabled = enabled,
                            onCheckedChange = { onToggle(profile.id, it) },
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            InfoPill(if (profile.action == ProfileAction.PASS) "PASS" else "BYPASS")
                            InfoPill(protocolTitle(profile.protocol))
                        }
                        Row {
                            IconButton(enabled = enabled && index > 0, onClick = { onMove(profile.id, -1) }) {
                                Icon(Icons.Rounded.ArrowUpward, contentDescription = "Выше")
                            }
                            IconButton(enabled = enabled && index < set.profiles.lastIndex, onClick = { onMove(profile.id, 1) }) {
                                Icon(Icons.Rounded.ArrowDownward, contentDescription = "Ниже")
                            }
                            IconButton(enabled = enabled, onClick = { editing = profile }) {
                                Icon(Icons.Rounded.Edit, contentDescription = "Редактировать")
                            }
                        }
                    }
                }
            }
        }

        if (!enabled) DisabledHint()
    }
}

@Composable
private fun ListsScreen(
    modifier: Modifier,
    lists: List<DomainListModel>,
    enabled: Boolean,
    onSave: (DomainListModel) -> Unit,
    onAdd: () -> Unit,
) {
    var editing by remember { mutableStateOf<DomainListModel?>(null) }

    editing?.let { list ->
        DomainListEditorDialog(
            list = list,
            enabled = enabled,
            onDismiss = { editing = null },
            onSave = {
                onSave(it)
                editing = null
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("Доменные списки")
        Text(
            "Один список может использоваться несколькими профилями. Есть отдельный список PASS-исключений.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        lists.forEach { list ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                onClick = { editing = list },
                shape = RoundedCornerShape(20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.fillMaxWidth(0.8f)) {
                        Text(list.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${list.domains.size} доменов",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.Rounded.Edit, contentDescription = null)
                }
            }
        }

        OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = enabled, onClick = onAdd) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Новый список")
        }

        if (!enabled) DisabledHint()
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
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Text(statusLabel(status), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    if (status == EngineStatus.RUNNING && port > 0) {
                        "127.0.0.1:$port · MTProto → WSS"
                    } else {
                        "Отдельный локальный Telegram-прокси. Работает независимо от ByeDPI."
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
private fun ProfileEditorDialog(
    profile: TrafficProfile,
    lists: List<DomainListModel>,
    catalog: List<CatalogStrategy>,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (TrafficProfile) -> Unit,
) {
    var name by rememberSaveable(profile.id) { mutableStateOf(profile.name) }
    var actionName by rememberSaveable(profile.id) { mutableStateOf(profile.action.name) }
    var protocolName by rememberSaveable(profile.id) { mutableStateOf(profile.protocol.name) }
    var selectedLists by remember(profile.id) { mutableStateOf(profile.domainListIds.toSet()) }
    var strategyName by rememberSaveable(profile.id) { mutableStateOf(profile.strategyName ?: "ByeByeDPI Default") }
    var strategyCommand by rememberSaveable(profile.id) { mutableStateOf(profile.strategyCommand ?: ByeDpiStrategies.BALANCED.command) }
    var showCatalog by rememberSaveable { mutableStateOf(false) }

    val action = runCatching { ProfileAction.valueOf(actionName) }.getOrDefault(ProfileAction.BYPASS)
    val protocol = runCatching { ProfileProtocol.valueOf(protocolName) }.getOrDefault(ProfileProtocol.ANY)

    if (showCatalog) {
        StrategyPickerDialog(
            catalog = catalog,
            onDismiss = { showCatalog = false },
            onSelect = { item ->
                strategyName = item.name
                strategyCommand = item.command
                showCatalog = false
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Редактировать профиль") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = name,
                    enabled = enabled,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    singleLine = true,
                )

                Text("Действие", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = action == ProfileAction.BYPASS,
                        enabled = enabled,
                        onClick = { actionName = ProfileAction.BYPASS.name },
                        label = { Text("BYPASS") },
                    )
                    FilterChip(
                        selected = action == ProfileAction.PASS,
                        enabled = enabled,
                        onClick = { actionName = ProfileAction.PASS.name },
                        label = { Text("PASS") },
                    )
                }

                Text("Протокол", style = MaterialTheme.typography.labelLarge)
                ProfileProtocol.entries.forEach { item ->
                    FilterChip(
                        selected = protocol == item,
                        enabled = enabled,
                        onClick = { protocolName = item.name },
                        label = { Text(protocolTitle(item)) },
                    )
                }

                Text("Доменные списки", style = MaterialTheme.typography.labelLarge)
                lists.forEach { list ->
                    FilterChip(
                        selected = list.id in selectedLists,
                        enabled = enabled,
                        onClick = {
                            selectedLists = if (list.id in selectedLists) {
                                selectedLists - list.id
                            } else {
                                selectedLists + list.id
                            }
                        },
                        label = { Text("${list.name} · ${list.domains.size}") },
                    )
                }

                if (action == ProfileAction.BYPASS) {
                    HorizontalDivider()
                    Text("Стратегия", style = MaterialTheme.typography.labelLarge)
                    Text(strategyName, fontWeight = FontWeight.SemiBold)
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled,
                        onClick = { showCatalog = true },
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Выбрать из каталога")
                    }
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = strategyCommand,
                        enabled = enabled,
                        onValueChange = { strategyCommand = it },
                        label = { Text("Аргументы ByeDPI") },
                        minLines = 3,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = enabled && name.isNotBlank(),
                onClick = {
                    onSave(
                        profile.copy(
                            name = name.trim(),
                            action = action,
                            protocol = protocol,
                            domainListIds = selectedLists.toList(),
                            strategyName = if (action == ProfileAction.BYPASS) strategyName else null,
                            strategyCommand = if (action == ProfileAction.BYPASS) strategyCommand else null,
                        ),
                    )
                },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun StrategyPickerDialog(
    catalog: List<CatalogStrategy>,
    onDismiss: () -> Unit,
    onSelect: (CatalogStrategy) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(query, catalog) {
        val q = query.trim().lowercase()
        if (q.isBlank()) catalog else catalog.filter {
            it.name.lowercase().contains(q) || it.command.lowercase().contains(q)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Каталог стратегий") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    label = { Text("Поиск") },
                    singleLine = true,
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 430.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { it.index }) { strategy ->
                        Card(modifier = Modifier.fillMaxWidth(), onClick = { onSelect(strategy) }) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(strategy.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    strategy.command,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
private fun DomainListEditorDialog(
    list: DomainListModel,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (DomainListModel) -> Unit,
) {
    var name by rememberSaveable(list.id) { mutableStateOf(list.name) }
    var domains by rememberSaveable(list.id) { mutableStateOf(list.domains.joinToString("\n")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Доменный список") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = name,
                    enabled = enabled,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = domains,
                    enabled = enabled,
                    onValueChange = { domains = it },
                    label = { Text("Домены") },
                    placeholder = { Text("youtube.com\ngooglevideo.com") },
                    supportingText = { Text("По одному на строку или через пробел/запятую") },
                    minLines = 10,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = enabled && name.isNotBlank(),
                onClick = {
                    onSave(
                        list.copy(
                            name = name.trim(),
                            domains = domains.lineSequence().toList(),
                        ),
                    )
                },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun AdvancedSettingsDialog(
    config: ByeDpiConfig,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (ByeDpiConfig) -> Unit,
) {
    var dns by rememberSaveable { mutableStateOf(config.dns) }
    var sni by rememberSaveable { mutableStateOf(config.sni) }
    var ipv6Name by rememberSaveable { mutableStateOf(config.ipv6Mode.name) }
    val ipv6 = runCatching { Ipv6Mode.valueOf(ipv6Name) }.getOrDefault(Ipv6Mode.AUTO)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сетевые настройки") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = dns,
                    enabled = enabled,
                    onValueChange = { dns = it },
                    label = { Text("DNS") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = sni,
                    enabled = enabled,
                    onValueChange = { sni = it },
                    label = { Text("Fake SNI") },
                    singleLine = true,
                )
                Text("IPv6", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Ipv6Mode.entries.forEach { mode ->
                        FilterChip(
                            selected = ipv6 == mode,
                            enabled = enabled,
                            onClick = { ipv6Name = mode.name },
                            label = { Text(mode.name) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = enabled,
                onClick = {
                    onSave(
                        config.copy(
                            dns = dns.trim(),
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

@Composable
private fun RoutePreviewRow(index: Int, profile: TrafficProfile) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.fillMaxWidth(0.72f)) {
                Text("${index.toString().padStart(2, '0')} · ${profile.name}", fontWeight = FontWeight.Medium)
                Text(
                    if (profile.action == ProfileAction.PASS) "Без desync" else (profile.strategyName ?: "ByeDPI"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            InfoPill(if (profile.action == ProfileAction.PASS) "PASS" else "BYPASS")
        }
    }
}

@Composable
private fun InfoPill(text: String) {
    Surface(shape = RoundedCornerShape(100.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun DisabledHint() {
    Text(
        "Для изменения конфигурации сначала отключи ByeDPI.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun profileSummary(profile: TrafficProfile, lists: List<DomainListModel>): String {
    val byId = lists.associateBy { it.id }
    val listText = if (profile.domainListIds.isEmpty()) {
        "любой трафик"
    } else {
        profile.domainListIds.mapNotNull { byId[it]?.name }.joinToString(" + ").ifBlank { "без списка" }
    }
    val action = if (profile.action == ProfileAction.PASS) "PASS" else (profile.strategyName ?: "BYPASS")
    return "$listText · ${protocolTitle(profile.protocol)} · $action"
}

private fun protocolTitle(protocol: ProfileProtocol): String = when (protocol) {
    ProfileProtocol.ANY -> "ANY"
    ProfileProtocol.TCP_TLS -> "TLS/TCP"
    ProfileProtocol.UDP_QUIC -> "QUIC/UDP"
}

private fun statusLabel(status: EngineStatus): String = when (status) {
    EngineStatus.STOPPED -> "Выключено"
    EngineStatus.STARTING -> "Запуск…"
    EngineStatus.RUNNING -> "Работает"
    EngineStatus.STOPPING -> "Остановка…"
    EngineStatus.FAILED -> "Ошибка"
}
