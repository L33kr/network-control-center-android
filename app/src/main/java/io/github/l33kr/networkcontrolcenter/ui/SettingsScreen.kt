package io.github.l33kr.networkcontrolcenter.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiConfigStore
import io.github.l33kr.networkcontrolcenter.byedpi.Ipv6Mode
import io.github.l33kr.networkcontrolcenter.tgws.TgWsConfigStore

@Composable
fun SettingsScreen(
    context: Context,
    onBack: () -> Unit,
) {
    val initialByeDpi = remember { ByeDpiConfigStore.load(context) }
    val initialTgWs = remember { TgWsConfigStore.load(context) }

    var byeCommand by rememberSaveable { mutableStateOf(initialByeDpi.command) }
    var byeDns by rememberSaveable { mutableStateOf(initialByeDpi.dns) }
    var byeIpv6 by rememberSaveable { mutableStateOf(initialByeDpi.ipv6Mode == Ipv6Mode.ON) }

    var tgPort by rememberSaveable { mutableStateOf(initialTgWs.port.toString()) }
    var tgPoolSize by rememberSaveable { mutableStateOf(initialTgWs.poolSize.toString()) }
    var tgCloudflare by rememberSaveable { mutableStateOf(initialTgWs.cloudflareEnabled) }
    var tgCloudflareDomain by rememberSaveable { mutableStateOf(initialTgWs.cloudflareDomain) }
    var tgWorkerDomains by rememberSaveable { mutableStateOf(initialTgWs.workerDomains) }
    var tgDcIps by rememberSaveable { mutableStateOf(initialTgWs.dcIps) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Параметры сохраняются локально. Если движок уже работает, выключите и включите его после изменения настроек.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("ByeDPI", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Аргументы передаются напрямую актуальному ядру ByeDPI. Адрес SOCKS5: 127.0.0.1:1080.",
                    style = MaterialTheme.typography.bodySmall,
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = byeCommand,
                    onValueChange = { byeCommand = it },
                    label = { Text("Стратегия / аргументы") },
                    minLines = 2,
                    maxLines = 6,
                    supportingText = { Text("По умолчанию: -o1 -a1 -r-5+se") },
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = byeDns,
                    onValueChange = { byeDns = it.trim() },
                    label = { Text("DNS") },
                    singleLine = true,
                    supportingText = { Text("Например 1.1.1.1") },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("IPv6 через ByeDPI")
                        Text(
                            "В выключенном состоянии VPN работает только с IPv4.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = byeIpv6, onCheckedChange = { byeIpv6 = it })
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Telegram WS Proxy", style = MaterialTheme.typography.titleLarge)

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = tgPort,
                    onValueChange = { tgPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("Локальный порт") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = tgPoolSize,
                    onValueChange = { tgPoolSize = it.filter(Char::isDigit).take(2) },
                    label = { Text("Размер пула соединений") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text("Допустимо: 2–16") },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Cloudflare / WSS")
                        Text(
                            "Использовать Cloudflare как основной транспорт.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = tgCloudflare, onCheckedChange = { tgCloudflare = it })
                }

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = tgCloudflareDomain,
                    onValueChange = { tgCloudflareDomain = it.trim() },
                    label = { Text("Свой Cloudflare-домен") },
                    singleLine = true,
                    supportingText = { Text("Можно оставить пустым для автоматического режима") },
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = tgWorkerDomains,
                    onValueChange = { tgWorkerDomains = it },
                    label = { Text("Worker-домены") },
                    minLines = 2,
                    maxLines = 4,
                    supportingText = { Text("Пусто = использовать встроенную логику движка") },
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = tgDcIps,
                    onValueChange = { tgDcIps = it },
                    label = { Text("Telegram DC IP") },
                    minLines = 2,
                    maxLines = 5,
                    supportingText = { Text("Необязательно. Пусто = автоматические адреса из TG WS") },
                )
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val port = tgPort.toIntOrNull()
                val pool = tgPoolSize.toIntOrNull()
                if (port == null || port !in 1..65535 || pool == null || pool !in 2..16) {
                    Toast.makeText(context, "Проверьте порт и размер пула", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                ByeDpiConfigStore.save(
                    context,
                    initialByeDpi.copy(
                        command = byeCommand.trim(),
                        dns = byeDns.trim(),
                        ipv6Mode = if (byeIpv6) Ipv6Mode.ON else Ipv6Mode.OFF,
                    ),
                )
                TgWsConfigStore.save(
                    context,
                    initialTgWs.copy(
                        port = port,
                        poolSize = pool,
                        cloudflareEnabled = tgCloudflare,
                        cloudflareDomain = tgCloudflareDomain.trim(),
                        workerDomains = tgWorkerDomains.trim(),
                        dcIps = tgDcIps.trim(),
                    ),
                )
                Toast.makeText(context, "Настройки сохранены", Toast.LENGTH_SHORT).show()
                onBack()
            },
        ) {
            Text("Сохранить")
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onBack,
        ) {
            Text("Назад")
        }

        Spacer(Modifier.height(12.dp))
    }
}
