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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiConfigStore
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiController
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiMode
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiStrategies
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiStrategyCatalog
import io.github.l33kr.networkcontrolcenter.core.AndroidUnifiedEngineController
import io.github.l33kr.networkcontrolcenter.core.EngineStatus
import io.github.l33kr.networkcontrolcenter.tgws.TgWsController
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val controller = remember {
                    AndroidUnifiedEngineController(applicationContext)
                }
                val scope = rememberCoroutineScope()
                val vpnPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        scope.launch { controller.startByeDpi() }
                    }
                }

                NetworkControlCenterScreen(
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
                        val opened = TgWsController.openTelegramProxy(applicationContext)
                        if (!opened) {
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

@Composable
private fun NetworkControlCenterScreen(
    controller: AndroidUnifiedEngineController,
    onByeDpiChange: (Boolean) -> Unit,
    onApplyTelegramProxy: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val tgWsPort by TgWsController.activePort.collectAsStateWithLifecycle()
    val tgWsError by TgWsController.lastError.collectAsStateWithLifecycle()
    val byeProfile by ByeDpiController.activeProfile.collectAsStateWithLifecycle()
    val byeNetwork by ByeDpiController.networkLabel.collectAsStateWithLifecycle()
    val byeIpv6 by ByeDpiController.ipv6Active.collectAsStateWithLifecycle()
    val byeError by ByeDpiController.lastError.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    var selectedModeName by rememberSaveable {
        mutableStateOf(ByeDpiController.selectedMode(context).name)
    }
    var selectedStrategyName by rememberSaveable {
        mutableStateOf(ByeDpiConfigStore.load(context).strategyName)
    }
    var showStrategyCatalog by rememberSaveable { mutableStateOf(false) }

    val selectedMode = runCatching { ByeDpiMode.valueOf(selectedModeName) }
        .getOrDefault(ByeDpiMode.AUTO)
    val byeCanConfigure = state.byeDpi == EngineStatus.STOPPED || state.byeDpi == EngineStatus.FAILED

    if (showStrategyCatalog) {
        ReadyStrategyDialog(
            canConfigure = byeCanConfigure,
            onDismiss = { showStrategyCatalog = false },
            onSelected = { name, command ->
                ByeDpiConfigStore.setCatalogStrategy(context, name, command)
                selectedModeName = ByeDpiMode.MANUAL.name
                selectedStrategyName = name
                showStrategyCatalog = false
            },
        )
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Network Control Center", style = MaterialTheme.typography.headlineMedium)
            Text(
                "ByeDPI для общего трафика и отдельный WS-маршрут для Telegram.",
                style = MaterialTheme.typography.bodyMedium,
            )

            EngineCard(
                title = "Интернет / ByeDPI",
                subtitle = when {
                    state.byeDpi == EngineStatus.RUNNING && byeProfile != null -> buildString {
                        append("$byeProfile")
                        byeNetwork?.let { append(" · $it") }
                        append(if (byeIpv6) " · IPv4+IPv6" else " · IPv4")
                    }
                    else -> "Android VPN → hev-socks5-tunnel → актуальное ядро ByeDPI"
                },
                status = state.byeDpi,
                checked = state.byeDpi == EngineStatus.RUNNING || state.byeDpi == EngineStatus.STARTING,
                switchEnabled = state.byeDpi != EngineStatus.STOPPING,
                detail = if (state.byeDpi == EngineStatus.FAILED) byeError else null,
                onEnabledChange = onByeDpiChange,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Режим обхода DPI", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (byeCanConfigure) {
                            "Можно выбрать простой профиль или одну из готовых стратегий ByeByeDPI."
                        } else {
                            "Чтобы сменить стратегию, сначала выключи ByeDPI."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )

                    selectedStrategyName?.let {
                        Text("Выбрано: $it", style = MaterialTheme.typography.labelLarge)
                    }

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = byeCanConfigure,
                        onClick = { showStrategyCatalog = true },
                    ) {
                        Text("Готовые стратегии ByeDPI")
                    }

                    ByeDpiStrategies.selectable
                        .filter { it.mode != ByeDpiMode.MANUAL }
                        .forEach { preset ->
                            ModeButton(
                                title = preset.title,
                                description = preset.description,
                                selected = selectedMode == preset.mode && selectedStrategyName == null,
                                enabled = byeCanConfigure,
                                onClick = {
                                    ByeDpiController.setMode(context, preset.mode)
                                    selectedModeName = preset.mode.name
                                    selectedStrategyName = null
                                },
                            )
                        }
                }
            }

            EngineCard(
                title = "Telegram WS Proxy",
                subtitle = when {
                    state.tgWs == EngineStatus.RUNNING && tgWsPort > 0 ->
                        "Локальный MTProto на 127.0.0.1:$tgWsPort → WSS / Cloudflare → Telegram DC"
                    else -> "Локальный MTProto → WSS / Cloudflare → Telegram DC"
                },
                status = state.tgWs,
                checked = state.tgWs == EngineStatus.RUNNING || state.tgWs == EngineStatus.STARTING,
                switchEnabled = state.tgWs != EngineStatus.STOPPING,
                detail = if (state.tgWs == EngineStatus.FAILED) tgWsError else null,
                onEnabledChange = { enabled ->
                    scope.launch {
                        if (enabled) controller.startTgWs() else controller.stopTgWs()
                    }
                },
            )

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = state.tgWs == EngineStatus.RUNNING,
                onClick = onApplyTelegramProxy,
            ) {
                Text("Применить прокси в Telegram")
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "Для YouTube на мобильной сети сначала попробуй несколько верхних готовых стратегий. Следующий этап — автоматический прогон этого каталога и выбор победителя.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ReadyStrategyDialog(
    canConfigure: Boolean,
    onDismiss: () -> Unit,
    onSelected: (String, String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val catalog = remember { ByeDpiStrategyCatalog.load(context) }
    var sni by rememberSaveable { mutableStateOf(ByeDpiConfigStore.load(context).sni) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Готовые стратегии") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Каталог из ByeByeDPI: ${catalog.size} вариантов. {sni} заменяется значением ниже.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = sni,
                    onValueChange = { sni = it.trim() },
                    label = { Text("Fake SNI") },
                    singleLine = true,
                    supportingText = { Text("По умолчанию: google.com") },
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(catalog, key = { it.index }) { strategy ->
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = canConfigure,
                            onClick = {
                                ByeDpiConfigStore.setSni(context, sni)
                                onSelected(strategy.name, strategy.command)
                            },
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(strategy.name)
                                Text(
                                    strategy.command,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 3,
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
private fun ModeButton(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            onClick = onClick,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("✓ $title")
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        }
    } else {
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            onClick = onClick,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(title)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun EngineCard(
    title: String,
    subtitle: String,
    status: EngineStatus,
    checked: Boolean,
    switchEnabled: Boolean,
    detail: String? = null,
    onEnabledChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Text(statusLabel(status), style = MaterialTheme.typography.labelMedium)
                detail?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            Switch(
                checked = checked,
                enabled = switchEnabled,
                onCheckedChange = onEnabledChange,
            )
        }
    }
}

private fun statusLabel(status: EngineStatus): String = when (status) {
    EngineStatus.STOPPED -> "Отключено"
    EngineStatus.STARTING -> "Запуск…"
    EngineStatus.RUNNING -> "Работает"
    EngineStatus.STOPPING -> "Остановка…"
    EngineStatus.FAILED -> "Ошибка"
}
