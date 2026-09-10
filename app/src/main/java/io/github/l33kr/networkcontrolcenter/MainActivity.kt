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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val scope = rememberCoroutineScope()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                subtitle = "Android VPN → hev-socks5-tunnel → актуальное ядро ByeDPI",
                status = state.byeDpi,
                checked = state.byeDpi == EngineStatus.RUNNING || state.byeDpi == EngineStatus.STARTING,
                switchEnabled = state.byeDpi != EngineStatus.STOPPING,
                onEnabledChange = onByeDpiChange,
            )

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
                "Оба движка независимы: Telegram WS можно использовать как вместе с ByeDPI, так и отдельно.",
                style = MaterialTheme.typography.bodySmall,
            )
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
