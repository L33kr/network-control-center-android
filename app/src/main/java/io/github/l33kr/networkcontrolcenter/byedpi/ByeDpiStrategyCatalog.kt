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
    /**
     * Curated strategies are Android/ByeDPI equivalents of the main desync ideas
     * used by zapretgui/zapret2 presets. They are intentionally not copied argv:
     * nfqws2 Lua syntax cannot be executed by ByeDPI, so the same ideas are mapped
     * to current ByeDPI primitives (split, disorder, fake, OOB, TLS record, fake SNI).
     */
    private val curated = listOf(
        CatalogStrategy(
            index = 0,
            id = "zg-oob-light",
            name = "ZapretGUI · OOB Light",
            command = "-Kt,h -o1 -a1",
            category = "ZapretGUI",
            description = "Лёгкий OOB-профиль. Хороший первый вариант для TLS без агрессивного дробления.",
            source = "zapretgui idea → ByeDPI",
            recommended = true,
        ),
        CatalogStrategy(
            index = 1,
            id = "zg-tls-record",
            name = "ZapretGUI · TLS Record",
            command = "-Kt,h -o1 -r-5+se -a1",
            category = "ZapretGUI",
            description = "OOB + разбиение TLS record. Аналог класса стратегий с отдельной обработкой ClientHello.",
            source = "zapretgui idea → ByeDPI",
            recommended = true,
        ),
        CatalogStrategy(
            index = 2,
            id = "zg-multisplit",
            name = "ZapretGUI · MultiSplit",
            command = "-Kt,h -d1 -s1+s -s3+s -s6+s -s12+s -a1",
            category = "ZapretGUI",
            description = "Несколько точек split/disorder для TLS. Умеренная нагрузка и хорошая совместимость.",
            source = "zapretgui multisplit → ByeDPI",
            recommended = true,
        ),
        CatalogStrategy(
            index = 3,
            id = "zg-multisplit-deep",
            name = "ZapretGUI · MultiSplit Deep",
            command = "-Kt,h -d1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -s30+s -a1",
            category = "ZapretGUI",
            description = "Более глубокое дробление ClientHello для сложных мобильных DPI.",
            source = "zapretgui multisplit → ByeDPI",
            recommended = true,
        ),
        CatalogStrategy(
            index = 4,
            id = "zg-disorder-split",
            name = "ZapretGUI · Disorder + Split",
            command = "-Kt,h -d1 -s1+s -r1+s -f-1 -t8 -a1",
            category = "ZapretGUI",
            description = "Комбинация disorder, split и fake с небольшим TTL. Более агрессивный fallback.",
            source = "zapretgui desync chain → ByeDPI",
            recommended = true,
        ),
        CatalogStrategy(
            index = 5,
            id = "zg-fake-ttl",
            name = "ZapretGUI · Fake TTL",
            command = "-Kt,h --fake -1 --ttl 8 --split 1+s --disorder 3+s -a1",
            category = "ZapretGUI",
            description = "Fake-пакет + TTL + split/disorder. Для сетей, где простые варианты не проходят.",
            source = "zapretgui fake/desync → ByeDPI",
            recommended = true,
        ),
        CatalogStrategy(
            index = 6,
            id = "zg-fake-sni",
            name = "ZapretGUI · Fake SNI",
            command = "-Kt,h -n {sni} -Qr -f-1 -r1+s -a1",
            category = "ZapretGUI",
            description = "Fake SNI с перестановкой/разбиением. Использует SNI из настроек приложения.",
            source = "zapretgui fake ClientHello → ByeDPI",
            recommended = true,
        ),
        CatalogStrategy(
            index = 7,
            id = "zg-fake-sni-deep",
            name = "ZapretGUI · Fake SNI Deep",
            command = "-Kt,h -n {sni} -Qr -d1:3 -f-1 -a1",
            category = "ZapretGUI",
            description = "Более агрессивная Fake-SNI схема с disorder диапазоном.",
            source = "zapretgui fake ClientHello → ByeDPI",
        ),
        CatalogStrategy(
            index = 8,
            id = "zg-oob-split",
            name = "ZapretGUI · OOB + Split",
            command = "-Kt,h -o1 -s4 -s6 -a1",
            category = "ZapretGUI",
            description = "Короткая OOB/split стратегия для провайдеров, где глубокий multisplit избыточен.",
            source = "zapretgui split family → ByeDPI",
        ),
        CatalogStrategy(
            index = 9,
            id = "mobile-multisplit",
            name = "Mobile · MultiSplit",
            command = "-Kt,h -d1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -s30+s -a1",
            category = "Mobile",
            description = "Агрессивное TLS-дробление без QUIC-части — удобно назначать только TCP/TLS профилю.",
            source = "DPI Control / ByeDPI",
            recommended = true,
        ),
        CatalogStrategy(
            index = 10,
            id = "byebye-default-tls",
            name = "ByeByeDPI · Default TLS",
            command = "-Kt,h -o1 -a1",
            category = "ByeByeDPI",
            description = "Лёгкая TLS-часть текущего подхода ByeByeDPI.",
            source = "ByeByeDPI",
            recommended = true,
        ),
        CatalogStrategy(
            index = 11,
            id = "quic-fake-light",
            name = "QUIC · Fake Light",
            command = "-Ku -a1",
            category = "QUIC",
            description = "Отдельная UDP/QUIC стратегия. Не смешивается с TLS-профилем.",
            source = "ByeDPI",
            recommended = true,
            protocol = "UDP/QUIC",
        ),
    )

    fun load(context: Context): List<CatalogStrategy> {
        val generated = ByeDpiStrategyGenerator.quick()
        val legacy = context.assets.open("byedpi_strategies.list")
            .bufferedReader()
            .useLines { lines ->
                lines
                    .map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .mapIndexed { index, command ->
                        CatalogStrategy(
                            index = curated.size + index,
                            id = "legacy-${index + 1}",
                            name = "ByeByeDPI · ${index + 1}",
                            command = command,
                            category = "Legacy",
                            description = "Готовая стратегия из каталога ByeByeDPI.",
                            source = "ByeByeDPI",
                            recommended = false,
                        )
                    }
                    .toList()
            }
        return (curated + generated + legacy).distinctBy { it.command }
    }

    /**
     * Automatic search now includes generated variants. The tester notices the
     * Generator category and switches its preliminary stage to a much smaller
     * representative sample/timeout, so generation does not multiply battery and
     * traffic usage by every configured domain.
     */
    fun quickCandidates(context: Context): List<CatalogStrategy> = load(context)
        .filter { it.recommended && it.protocol == "TCP/TLS" }
        .distinctBy { it.command }

    fun deepCandidates(context: Context): List<CatalogStrategy> = (
        load(context).filter { it.protocol == "TCP/TLS" && it.category != "Legacy" } +
            ByeDpiStrategyGenerator.deep()
        ).distinctBy { it.command }

    fun find(context: Context, id: String): CatalogStrategy? = load(context).firstOrNull { it.id == id }
}
