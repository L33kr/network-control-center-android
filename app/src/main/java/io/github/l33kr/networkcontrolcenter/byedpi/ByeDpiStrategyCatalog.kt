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
)

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
                    .mapIndexed { index, command ->
                        CatalogStrategy(
                            index = 40_000 + index,
                            id = "legacy-${index + 1}",
                            name = "ByeByeDPI test · ${index + 1}",
                            command = command,
                            category = "ByeByeDPI",
                            description = "Исходная команда из proxytest_strategies.list без адаптации.",
                            source = "ByeByeDPI proxy tester",
                            recommended = false,
                        )
                    }
                    .toList()
            }
        return (androidNative + quic + legacy).distinctBy { it.command }
    }

    /**
     * Default automatic search intentionally uses only the Android/Linux-native
     * family. The large ByeByeDPI proxy-test list stays available for manual/deep
     * checks but no longer drowns the first pass in unrelated combinations.
     */
    fun quickCandidates(context: Context): List<CatalogStrategy> = ByeDpiStrategyGenerator.quick()
        .filter { it.protocol == "TCP/TLS" }
        .distinctBy { it.command }

    fun deepCandidates(context: Context): List<CatalogStrategy> = (
        ByeDpiStrategyGenerator.deep() +
            load(context).filter { it.category == "ByeByeDPI" && it.protocol == "TCP/TLS" }
        ).distinctBy { it.command }

    fun find(context: Context, id: String): CatalogStrategy? = load(context).firstOrNull { it.id == id }
}
