package io.github.l33kr.networkcontrolcenter

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
                NetworkControlCenterScreen(
                    controller = controller,
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
    onApplyTelegramProxy: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
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
                "Два независимых движка в одном приложении.",
                style = MaterialTheme.typography.bodyMedium,
            )

            EngineCard(
                title = "Интернет / ByeDPI",
                subtitle = "Системный трафик через Android VPN и ByeDPI · следующий этап",
                status = state.byeDpi,
                checked = false,
                switchEnabled = false,
                onEnabledChange = {},
            )

            EngineCard(
                title = "Telegram WS Proxy",
                subtitle = "Локальный MTProto → WSS / Cloudflare → Telegram DC",
                status = state.tgWs,
                checked = state.tgWs == EngineStatus.RUNNING || state.tgWs == EngineStatus.STARTING,
                switchEnabled = state.tgWs != EngineStatus.STOPPING,
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
                when (state.tgWs) {
                    EngineStatus.STOPPED -> "Telegram WS выключен"
                    EngineStatus.STARTING -> "Telegram WS запускается…"
                    EngineStatus.RUNNING -> "Telegram WS работает на 127.0.0.1:1443"
                    EngineStatus.STOPPING -> "Telegram WS останавливается…"
                    EngineStatus.FAILED -> "Telegram WS: ошибка запуска"
                },
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
