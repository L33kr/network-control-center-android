package io.github.l33kr.networkcontrolcenter.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.l33kr.networkcontrolcenter.BuildConfig
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiConfig
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiStableProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun DiagnosticsDialog(activeConfig: ByeDpiConfig?, network: String?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var results by remember { mutableStateOf<List<CheckResult>>(emptyList()) }
    var client by remember { mutableStateOf<ConnectionDiagnostics?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var checking by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reportHeader by remember { mutableStateOf("") }

    fun stopCheck() {
        job?.cancel()
        client?.close()
        client = null
        checking = null
    }

    DisposableEffect(Unit) { onDispose { stopCheck() } }
    LaunchedEffect(activeConfig) {
        stopCheck()
        results = emptyList()
        error = null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Проверка соединения") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "DNS, HTTPS и UDP проверяются через активный ByeDPI. Проверка использует IPv4-адреса сайтов и не проверяет весь VPN-туннель.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Ответ сервера не подтверждает работу ленты, видео или звонков. Их проверь отдельно в приложениях.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (activeConfig == null) Text("Для проверки сначала запусти VPN.")
                checking?.let {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Проверяем: $it", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    items(results, key = { it.target.name }) { result ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(result.target.name, fontWeight = FontWeight.Bold)
                                Text(
                                    "${result.stage}: ${result.detail}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when {
                                        !result.reached -> MaterialTheme.colorScheme.error
                                        result.httpCode != null && result.httpCode >= 400 -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                Text("${result.elapsedMs} мс", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                if (results.isNotEmpty()) {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(
                            reportHeader + "\n" + results.joinToString("\n", transform = CheckResult::reportLine) +
                                "\nПроверок завершено: ${results.size}/${ConnectionDiagnostics.targets.size}." +
                                "\nТест SOCKS5 (IPv4), не полная проверка TUN, медиа и звонков.",
                        ))
                    }) { Text("Скопировать отчёт") }
                }
            }
        },
        confirmButton = {
            if (checking != null) {
                TextButton(onClick = { stopCheck() }) { Text("Остановить") }
            } else {
                TextButton(
                    enabled = activeConfig != null,
                    onClick = {
                        val config = activeConfig ?: return@TextButton
                        results = emptyList()
                        error = null
                        try {
                            val runner = ConnectionDiagnostics(config)
                            client = runner
                            reportHeader = buildString {
                                appendLine("DPI Control ${BuildConfig.VERSION_NAME} · ${java.util.Date()}")
                                appendLine("Сеть: ${network ?: "не определена"}; профиль: ${config.strategyName}")
                                appendLine("DNS: ${config.dns}; SNI: ${config.sni}")
                                appendLine("Meta: ${config.metaCompatibility && config.command.trim() == ByeDpiStableProfile.COMMAND}")
                                append("Аргументы: ${config.toArgs().joinToString(" ")}")
                            }
                            checking = ConnectionDiagnostics.targets.first().name
                            job = scope.launch {
                                try {
                                    for (target in ConnectionDiagnostics.targets) {
                                        checking = target.name
                                        results = results + runner.check(target)
                                    }
                                } finally {
                                    runner.close()
                                    // A previous cancelled run must not clear a newly started run.
                                    if (client === runner) {
                                        client = null
                                        checking = null
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            client?.close()
                            client = null
                            checking = null
                            error = e.message ?: "Не удалось начать проверку"
                        }
                    },
                ) { Text(if (results.isEmpty()) "Проверить" else "Повторить") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
