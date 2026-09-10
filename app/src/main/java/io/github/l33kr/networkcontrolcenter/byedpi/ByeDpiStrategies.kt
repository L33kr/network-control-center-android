package io.github.l33kr.networkcontrolcenter.byedpi

data class ByeDpiStrategy(
    val mode: ByeDpiMode,
    val title: String,
    val description: String,
    val command: String,
)

object ByeDpiStrategies {
    val AUTO_WIFI = ByeDpiStrategy(
        mode = ByeDpiMode.AUTO,
        title = "Auto",
        description = "Автопереключение групп ByeDPI с кешем успешного варианта",
        command = "--auto-mode s,o -u86400 -T3 -o1 -r-5+se -a1 -At,r,s -d1 -n www.google.com -Qr -f-1 -t8 -a1 -At,r,s -s1+s -d3+s -s6+s -s12+s -s20+s -s30+s -a1",
    )

    val AUTO_MOBILE = ByeDpiStrategy(
        mode = ByeDpiMode.AUTO,
        title = "Auto · Mobile",
        description = "Авто-профиль для сотовой сети: split/disorder + fallback fake",
        command = "--auto-mode s,o -u86400 -T2.5 -o1 -d1 -a1 -At,r,s -s1 -d1 -s5+s -s10+s -s15+s -s20+s -r1+s -a1 -At,r,s -f-1 -t8 -s1+s -d3+s -s6+s -s12+s -s20+s -s30+s -a1",
    )

    val MOBILE_RU = ByeDpiStrategy(
        mode = ByeDpiMode.MOBILE_RU,
        title = "Mobile RU",
        description = "Агрессивное дробление TLS ClientHello для мобильных операторов",
        command = "-d1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -s30+s -a1",
    )

    val BALANCED = ByeDpiStrategy(
        mode = ByeDpiMode.BALANCED,
        title = "Balanced",
        description = "Лёгкий OOB + TLS record split; меньше вмешательства в трафик",
        command = "-o1 -r-5+se -a1",
    )

    val STRONG_FAKE = ByeDpiStrategy(
        mode = ByeDpiMode.STRONG_FAKE,
        title = "Strong / Fake",
        description = "Fake packet + split/disorder; использовать если обычные режимы не проходят",
        command = "--fake -1 --ttl 8 --split 1+s --disorder 3+s -a1",
    )

    val selectable: List<ByeDpiStrategy> = listOf(
        ByeDpiStrategy(
            mode = ByeDpiMode.AUTO,
            title = "Auto",
            description = "Сам выбирает лёгкую или мобильную авто-цепочку",
            command = "",
        ),
        MOBILE_RU,
        BALANCED,
        STRONG_FAKE,
        ByeDpiStrategy(
            mode = ByeDpiMode.MANUAL,
            title = "Manual / готовая",
            description = "Пользовательская или импортированная стратегия",
            command = "",
        ),
    )

    fun resolve(config: ByeDpiConfig, isCellular: Boolean): ByeDpiStrategy = when (config.mode) {
        ByeDpiMode.AUTO -> if (isCellular) AUTO_MOBILE else AUTO_WIFI
        ByeDpiMode.MOBILE_RU -> MOBILE_RU
        ByeDpiMode.BALANCED -> BALANCED
        ByeDpiMode.STRONG_FAKE -> STRONG_FAKE
        ByeDpiMode.MANUAL -> ByeDpiStrategy(
            mode = ByeDpiMode.MANUAL,
            title = config.strategyName ?: "Manual",
            description = "Пользовательская или импортированная стратегия",
            command = config.command.trim().ifBlank { BALANCED.command },
        )
    }
}
