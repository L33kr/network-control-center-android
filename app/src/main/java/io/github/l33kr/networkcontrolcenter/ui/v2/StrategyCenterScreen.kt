package io.github.l33kr.networkcontrolcenter.ui.v2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiStrategyPlan
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
    val targets = remember(activeSet, domainLists) {
        ByeDpiStrategyTester.collectTargets(context, activeSet)
    }

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
    var customCommand by remember { mutableStateOf("-o1 -a1 -r-5+se") }

    val categories = listOf(
        "Рекомендуемые",
        "Android",
        "ByeByeDPI",
        "QUIC",
        "Избранные",
        "Все",
    )

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

    fun startSearch(deep: Boolean) {
        if (isTesting || !enabled || targets.none { !it.passOnly }) return
        isTesting = true
        report = null
        singleResult = null
        scope.launch {
            report = try {
                ByeDpiStrategyTester.findBestAdaptive(
                    context = context,
                    sni = ByeDpiConfigStore.load(context).sni,
                    set = activeSet,
                    candidates = if (deep) {
                        ByeDpiStrategyCatalog.deepCandidates(context)
                    } else {
                        ByeDpiStrategyCatalog.quickCandidates(context)
                    },
                    onProgress = { progress = it },
                )
            } finally {
                isTesting = false
                progress = null
            }
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
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Стратегии ByeDPI",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Здесь стратегия — это полная цепочка ByeDPI. Внутренние -A переходы " +
                    "не упрощаются и сохраняются при назначении профилю.",
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
            SearchCard(
                enabled = enabled,
                isTesting = isTesting,
                progress = progress,
                onQuick = { startSearch(false) },
                onDeep = { startSearch(true) },
            )
        }

        if (!enabled) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        "Чтобы тестировать стратегии, сначала останови ByeDPI. " +
                            "Сам каталог и просмотр цепочек доступны всегда.",
                        modifier = Modifier.padding(14.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        report?.let { currentReport ->
            item {
                Text(
                    "Результаты поиска",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
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
                Text(
                    "Последняя проверка",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
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
            Text(
                "Ручная цепочка",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Можно вставить полную многоступенчатую команду ByeDPI. " +
                            "Переносы строк допустимы: они нужны только для удобства чтения.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                        label = { Text("Полная команда ByeDPI") },
                        minLines = 4,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                    val customStages = ByeDpiStrategyPlan.parse(customCommand)
                    Text(
                        "Распознано стадий: ${customStages.size.coerceAtLeast(1)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                description = "Пользовательская полная цепочка ByeDPI",
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
private fun SearchCard(
    enabled: Boolean,
    isTesting: Boolean,
    progress: StrategySearchProgress?,
    onQuick: () -> Unit,
    onDeep: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Поиск рабочей цепочки", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Быстрый поиск использует реальные ByeDPI seeds. Глубокий дополнительно " +
                    "перебирает исходные длинные многоступенчатые цепочки ByeByeDPI.",
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
                            "HTTPS: ${value.currentSuccess}/${value.currentTotal}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled && !isTesting,
                onClick = onQuick,
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Быстрый поиск")
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled && !isTesting,
                onClick = onDeep,
            ) {
                Text("Глубокий поиск ByeDPI")
            }
            Text(
                "Глубокий поиск заметно дольше. Финалисты всё равно перепроверяются по всем " +
                    "BYPASS-доменам, включая добавленные вручную.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
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
                "PASS-домены видны в конфигурации, но не снижают рейтинг BYPASS-стратегии.",
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
    var chainExpanded by remember(strategy.id) { mutableStateOf(false) }
    val stages = remember(strategy.command) { ByeDpiStrategyPlan.parse(strategy.command) }

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

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StageBadge("${stages.size.coerceAtLeast(1)} стадий")
                if (stages.size > 1) StageBadge("fallback chain")
            }

            if (strategy.description.isNotBlank()) {
                Text(strategy.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (stages.size <= 1) {
                CommandSurface(strategy.command, maxLines = 5)
            } else if (chainExpanded) {
                stages.forEach { stage ->
                    StageSurface(
                        index = stage.index,
                        trigger = stage.trigger,
                        arguments = stage.arguments,
                    )
                }
                TextButton(onClick = { chainExpanded = false }) {
                    Icon(Icons.Rounded.ExpandLess, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Свернуть цепочку")
                }
            } else {
                StageSurface(
                    index = stages.first().index,
                    trigger = stages.first().trigger,
                    arguments = stages.first().arguments,
                )
                TextButton(onClick = { chainExpanded = true }) {
                    Icon(Icons.Rounded.ExpandMore, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Показать все ${stages.size} стадий")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = enabled && !testing && strategy.protocol == "TCP/TLS",
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
private fun StageBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun StageSurface(index: Int, trigger: String?, arguments: List<String>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(11.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Этап $index · ${ByeDpiStrategyPlan.triggerLabel(trigger)}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                arguments.joinToString(" "),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

@Composable
private fun CommandSurface(command: String, maxLines: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            command,
            modifier = Modifier.padding(10.dp),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
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
    val stages = remember(result.strategy.command) {
        ByeDpiStrategyPlan.parse(result.strategy.command)
    }

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
                        "HTTPS ${result.successCount}/${result.totalCount} · ${result.percent}%" +
                            (result.averageLatencyMs?.let { " · ~${it} мс" } ?: "") +
                            " · ${stages.size.coerceAtLeast(1)} стадий",
                        color = if (result.percent == 100) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(onClick = onExpand) {
                    Icon(
                        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                    )
                }
            }

            if (expanded) {
                if (stages.size > 1) {
                    Text("Цепочка ByeDPI", fontWeight = FontWeight.SemiBold)
                    stages.forEach { stage ->
                        StageSurface(stage.index, stage.trigger, stage.arguments)
                    }
                } else {
                    CommandSurface(result.strategy.command, maxLines = 8)
                }

                Text("Домены", fontWeight = FontWeight.SemiBold)
                result.probes.forEach { probe ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(if (probe.success) "✓" else "✕", fontWeight = FontWeight.Bold)
                        Column(modifier = Modifier.fillMaxWidth(0.90f)) {
                            Text(probe.target.host, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                buildString {
                                    append(probe.target.listNames.joinToString(", "))
                                    if (probe.target.manual) append(" · ручной")
                                    probe.httpCode?.let { append(" · HTTP $it") }
                                    if (probe.bytesRead > 0) append(" · ${probe.bytesRead} B")
                                    probe.latencyMs?.let { append(" · ${it} мс") }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!probe.success && !probe.error.isNullOrBlank()) {
                                Text(
                                    probe.error,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
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
        if (strategy.protocol == "UDP/QUIC") {
            it.protocol == ProfileProtocol.UDP_QUIC || it.protocol == ProfileProtocol.ANY
        } else {
            true
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Куда применить") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strategy.name, fontWeight = FontWeight.Bold)
                Text(
                    "Полная цепочка: ${strategy.stageCount} стадий",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}
