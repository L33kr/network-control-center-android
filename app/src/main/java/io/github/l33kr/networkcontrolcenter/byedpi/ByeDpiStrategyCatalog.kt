package io.github.l33kr.networkcontrolcenter.byedpi

import android.content.Context

data class CatalogStrategy(
    val index: Int,
    val name: String,
    val command: String,
    val id: String = "strategy-$index",
    val category: String = "Legacy",
    val description: String = "",
    val source: String = "ByeByeDPI",
    val recommended: Boolean = false,
    val protocol: String = "TCP/TLS",
) {
    val stageCount: Int get() = ByeDpiStrategyPlan.stageCount(command)
    val isMultiStage: Boolean get() = stageCount > 1
    val displayCommand: String get() = ByeDpiStrategyPlan.formatForDisplay(command)
}

object ByeDpiStrategyCatalog {
    private val quic = listOf(
        CatalogStrategy(
            index = 10_000,
            id = "quic-fake-1",
            name = "QUIC · UDP fake 1",
            command = "-Ku -a1",
            category = "QUIC",
            description = "Один fake UDP-пакет — значение по умолчанию в ByeByeDPI UI.",
            source = "ByeDPI / ByeByeDPI",
            recommended = true,
            protocol = "UDP/QUIC",
        ),
        CatalogStrategy(
            index = 10_001,
            id = "quic-fake-2",
            name = "QUIC · UDP fake 2",
            command = "-Ku -a2",
            category = "QUIC",
            description = "Два fake UDP-пакета для более жёсткой обработки QUIC.",
            source = "ByeDPI",
            protocol = "UDP/QUIC",
        ),
        CatalogStrategy(
            index = 10_002,
            id = "quic-fake-4",
            name = "QUIC · UDP fake 4",
            command = "-Ku -a4",
            category = "QUIC",
            description = "Четыре fake UDP-пакета; использовать только если лёгкие варианты не помогают.",
            source = "ByeDPI",
            protocol = "UDP/QUIC",
        ),
    )

    fun load(context: Context): List<CatalogStrategy> {
        val androidNative = ByeDpiStrategyGenerator.quick()
        val legacy = context.assets.open("byedpi_strategies.list")
            .bufferedReader()
            .useLines { lines ->
                lines
                    .map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .mapIndexed { index, rawCommand ->
                        val normalized = shellSplit(rawCommand).joinToString(" ")
                        val stages = ByeDpiStrategyPlan.stageCount(normalized)
                        CatalogStrategy(
                            index = 40_000 + index,
                            id = "legacy-${index + 1}",
                            name = if (stages > 1) {
                                "ByeByeDPI · ${index + 1} · $stages стадии"
                            } else {
                                "ByeByeDPI · ${index + 1}"
                            },
                            // Only whitespace changes: each -A fallback starts on a new
                            // line so the UI reflects the real strategy structure.
                            command = ByeDpiStrategyPlan.formatForDisplay(normalized),
                            category = "ByeByeDPI",
                            description = if (stages > 1) {
                                "Оригинальная многоступенчатая стратегия из proxytest_strategies.list. " +
                                    "Внутренние -A переходы сохранены без упрощения."
                            } else {
                                "Оригинальная одноступенчатая команда из proxytest_strategies.list."
                            },
                            source = "ByeByeDPI proxy tester",
                            recommended = false,
                        )
                    }
                    .toList()
            }
        return (androidNative + quic + legacy)
            .distinctBy { shellSplit(it.command).joinToString(" ") }
    }

    fun quickCandidates(context: Context): List<CatalogStrategy> = ByeDpiStrategyGenerator.quick()
        .filter { it.protocol == "TCP/TLS" }
        .distinctBy { shellSplit(it.command).joinToString(" ") }

    fun deepCandidates(context: Context): List<CatalogStrategy> = (
        ByeDpiStrategyGenerator.deep() +
            load(context).filter { it.category == "ByeByeDPI" && it.protocol == "TCP/TLS" }
        ).distinctBy { shellSplit(it.command).joinToString(" ") }

    fun find(context: Context, id: String): CatalogStrategy? = load(context).firstOrNull { it.id == id }
}
