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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiConfigStore
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiStrategyCatalog
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiStrategyTester
import io.github.l33kr.networkcontrolcenter.byedpi.CatalogStrategy
import io.github.l33kr.networkcontrolcenter.byedpi.StrategyPreferences
import io.github.l33kr.networkcontrolcenter.byedpi.StrategySearchProgress
import io.github.l33kr.networkcontrolcenter.byedpi.StrategySearchReport
import io.github.l33kr.networkcontrolcenter.byedpi.StrategyTestResult
import io.github.l33kr.networkcontrolcenter.byedpi.profile.DomainListModel
import io.github.l33kr.networkcontrolcenter.byedpi.profile.ProfileAction
import io.github.l33kr.networkcontrolcenter.byedpi.profile.ProfileProtocol
import io.github.l33kr.networkcontrolcenter.byedpi.profile.ProfileSetModel
import kotlinx.coroutines.launch

@Composable
fun StrategyCenterScreen(
    modifier: Modifier = Modifier,
    activeSet: ProfileSetModel,
    domainLists: List<DomainListModel>,
    enabled: Boolean,
    onApplyStrategy: (profileId: String, strategy: CatalogStrategy) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val catalog = remember { ByeDpiStrategyCatalog.load(context) }
    val targets = remember(activeSet, domainLists) { ByeDpiStrategyTester.collectTargets(context, activeSet) }

    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("Рекомендуемые") }
    var favorites by remember { mutableStateOf(StrategyPreferences.favorites(context)) }
    var isTesting by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<StrategySearchProgress?>(null) }
    var report by remember { mutableStateOf<StrategySearchReport?>(null) }
    var singleResult by remember { mutableStateOf<StrategyTestResult?>(null) }
    var pendingApply by remember { mutableStateOf<CatalogStrategy?>(null) }
    var expandedResultId by remember { mutableStateOf<String?>(null) }
    var customName by remember { mutableStateOf("Моя стратегия") }
    var customCommand by remember { mutableStateOf("-Kt,h -o1 -a1") }

    val categories = remember(catalog) {
        listOf("Рекомендуемые", "ZapretGUI", "Mobile", "QUIC", "Избранные", "Все")
    }

    val filtered = remember(catalog, query, selectedCategory, favorites) {
        catalog.filter { strategy ->
            val categoryOk = when (selectedCategory) {
                "Рекомендуемые" -> strategy.recommended
                "Избранные" -> strategy.id in favorites
                "Все" -> true
                else -> strategy.category == selectedCategory
            }
            val q = query.trim().lowercase()
            val queryOk = q.isBlank() || listOf(
                strategy.name,
                strategy.description,
                strategy.source,
                strategy.command,
            ).any { it.lowercase().contains(q) }
            categoryOk && queryOk
        }
    }

    pendingApply?.let { strategy ->
        ApplyStrategyDialog(
            strategy = strategy,
            activeSet = activeSet,
            onDismiss = { pendingApply = null },
            onApply = { profileId ->
                onApplyStrategy(profileId, strategy)
                pendingApply = null
            },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Стратегии",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Адаптированные идеи zapretgui + совместимые стратегии ByeDPI. " +
                    "Стратегия назначается конкретному профилю, а не всему VPN.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            CoverageSummaryCard(
                total = targets.size,
                manual = targets.count { it.manual },
                passOnly = targets.count { it.passOnly },
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Автопоиск", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Сначала короткий отбор, затем 3 лучших варианта проверяются по всем вашим доменам. " +
                            "Ручные домены тоже входят в итог X/Y.",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )

                    if (isTesting) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        progress?.let { value ->
                            Text(
                                "${value.phase}: ${value.strategyIndex}/${value.strategyTotal} · ${value.strategyName}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (value.currentTotal > 0) {
                                Text(
                                    "Проверено: ${value.currentSuccess}/${value.currentTotal}",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isTesting && targets.any { !it.passOnly },
                        onClick = {
                            isTesting = true
                            report = null
                            singleResult = null
                            scope.launch {
                                report = try {
                                    ByeDpiStrategyTester.findBestAdaptive(
                                        context = context,
                                        sni = ByeDpiConfigStore.load(context).sni,
                                        set = activeSet,
                                        onProgress = { progress = it },
                                    )
                                } finally {
                                    isTesting = false
                                    progress = null
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Найти лучшую")
                    }
                }
            }
        }

        report?.let { currentReport ->
            item {
                Text("Результаты автопоиска", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            items(currentReport.verified, key = { "verified-${it.strategy.id}" }) { result ->
                StrategyResultCard(
                    result = result,
                    expanded = expandedResultId == result.strategy.id,
                    onExpand = {
                        expandedResultId = if (expandedResultId == result.strategy.id) null else result.strategy.id
                    },
                    onApply = { pendingApply = result.strategy },
                    enabled = enabled,
                )
            }
        }

        singleResult?.let { result ->
            item {
                Text("Последняя проверка", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            item {
                StrategyResultCard(
                    result = result,
                    expanded = expandedResultId == result.strategy.id,
                    onExpand = {
                        expandedResultId = if (expandedResultId == result.strategy.id) null else result.strategy.id
                    },
                    onApply = { pendingApply = result.strategy },
                    enabled = enabled,
                )
            }
        }

        item {
            Text("Ручная стратегия", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("Название") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = customCommand,
                        onValueChange = { customCommand = it },
                        label = { Text("Аргументы ByeDPI") },
                        minLines = 2,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled && customCommand.isNotBlank(),
                        onClick = {
                            pendingApply = CatalogStrategy(
                                index = Int.MAX_VALUE,
                                id = "custom-${customCommand.hashCode()}",
                                name = customName.trim().ifBlank { "Моя стратегия" },
                                command = customCommand.trim(),
                                category = "Custom",
                                description = "Пользовательская стратегия",
                                source = "Пользователь",
                            )
                        },
                    ) {
                        Text("Назначить профилю")
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = query,
                onValueChange = { query = it },
                label = { Text("Поиск по стратегиям") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                categories.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { category ->
                            FilterChip(
                                selected = selectedCategory == category,
                                onClick = { selectedCategory = category },
                                label = { Text(category) },
                            )
                        }
                    }
                }
            }
        }

        items(filtered, key = { it.id }) { strategy ->
            StrategyCard(
                strategy = strategy,
                favorite = strategy.id in favorites,
                testing = isTesting,
                enabled = enabled,
                onFavorite = {
                    favorites = StrategyPreferences.toggleFavorite(context, strategy.id)
                },
                onTest = {
                    isTesting = true
                    report = null
                    singleResult = null
                    scope.launch {
                        singleResult = try {
                            ByeDpiStrategyTester.testStrategyAgainstAllDomains(
                                context = context,
                                strategy = strategy,
                                set = activeSet,
                            )
                        } finally {
                            isTesting = false
                        }
                    }
                },
                onApply = { pendingApply = strategy },
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun CoverageSummaryCard(total: Int, manual: Int, passOnly: Int) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Домены для проверки", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Всего: $total · ручных: $manual · PASS: $passOnly")
            Text(
                "PASS-домены отображаются, но не снижают оценку стратегии.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StrategyCard(
    strategy: CatalogStrategy,
    favorite: Boolean,
    testing: Boolean,
    enabled: Boolean,
    onFavorite: () -> Unit,
    onTest: () -> Unit,
    onApply: () -> Unit,
) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.fillMaxWidth(0.82f)) {
                    Text(strategy.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${strategy.category} · ${strategy.protocol} · ${strategy.source}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onFavorite) {
                    Icon(
                        if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = "Избранное",
                    )
                }
            }

            if (strategy.description.isNotBlank()) {
                Text(strategy.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    strategy.command,
                    modifier = Modifier.padding(10.dp),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !testing && strategy.protocol == "TCP/TLS",
                    onClick = onTest,
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Проверить")
                }
                Button(enabled = enabled, onClick = onApply) {
                    Text("Применить")
                }
            }
        }
    }
}

@Composable
private fun StrategyResultCard(
    result: StrategyTestResult,
    expanded: Boolean,
    onExpand: () -> Unit,
    onApply: () -> Unit,
    enabled: Boolean,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (result.percent == 100) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.fillMaxWidth(0.75f)) {
                    Text(result.strategy.name, fontWeight = FontWeight.Bold)
                    Text(
                        "Работает ${result.successCount}/${result.totalCount} · ${result.percent}%" +
                            (result.averageLatencyMs?.let { " · ~${it} мс" } ?: ""),
                        color = if (result.percent == 100) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onExpand) {
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
                }
            }

            if (expanded) {
                result.probes.forEach { probe ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (probe.success) "✓" else "✕", fontWeight = FontWeight.Bold)
                        Column(modifier = Modifier.fillMaxWidth(0.90f)) {
                            Text(probe.target.host, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                buildString {
                                    append(probe.target.listNames.joinToString(", "))
                                    if (probe.target.manual) append(" · ручной")
                                    probe.latencyMs?.let { append(" · ${it} мс") }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Button(modifier = Modifier.fillMaxWidth(), enabled = enabled, onClick = onApply) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Назначить профилю")
            }
        }
    }
}

@Composable
private fun ApplyStrategyDialog(
    strategy: CatalogStrategy,
    activeSet: ProfileSetModel,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    val profiles = activeSet.profiles.filter {
        it.enabled && it.action == ProfileAction.BYPASS &&
            (strategy.protocol == "UDP/QUIC" || it.protocol != ProfileProtocol.UDP_QUIC)
    }.filter {
        if (strategy.protocol == "UDP/QUIC") it.protocol == ProfileProtocol.UDP_QUIC || it.protocol == ProfileProtocol.ANY else true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Куда применить") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strategy.name, fontWeight = FontWeight.Bold)
                if (profiles.isEmpty()) {
                    Text("В активном наборе нет совместимого BYPASS-профиля.")
                } else {
                    profiles.forEach { profile ->
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onApply(profile.id) },
                        ) {
                            Text("${profile.name} · ${profile.protocol.name}")
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
