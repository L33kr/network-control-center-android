package io.github.l33kr.networkcontrolcenter

import android.os.Bundle
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                NetworkControlCenterScreen()
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun NetworkControlCenterScreen() {
    var byeDpiEnabled by remember { mutableStateOf(false) }
    var tgWsEnabled by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Network Control Center", style = MaterialTheme.typography.headlineMedium)
            Text(
                "ByeDPI and Telegram WS are kept as independent engines.",
                style = MaterialTheme.typography.bodyMedium
            )

            EngineCard(
                title = "Internet / ByeDPI",
                subtitle = "System traffic through Android VPN and ByeDPI",
                enabled = byeDpiEnabled,
                onEnabledChange = { byeDpiEnabled = it }
            )

            EngineCard(
                title = "Telegram WS Proxy",
                subtitle = "Local MTProto proxy with WSS / Cloudflare transport",
                enabled = tgWsEnabled,
                onEnabledChange = { tgWsEnabled = it }
            )

            Spacer(Modifier.height(4.dp))

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    byeDpiEnabled = true
                    tgWsEnabled = true
                }
            ) {
                Text("Enable all")
            }

            Text(
                "Current build is the project shell. Engine services will be connected next.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun EngineCard(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
    }
}
