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
        command = "--auto-mode s,o -u86400 -T3 -Kt,h -o1 -An -Ku -a1 -An -At,r,s -Kt,h -d1 -n www.google.com -Qr -f-1 -t8 -An -Ku -a1 -An",
    )

    val AUTO_MOBILE = ByeDpiStrategy(
        mode = ByeDpiMode.AUTO,
        title = "Auto · Mobile",
        description = "Сотовый авто-профиль: отдельные TCP/TLS и UDP/QUIC группы",
        command = "--auto-mode s,o -u86400 -T2.5 -Kt,h -o1 -d1 -An -Ku -a1 -An -At,r,s -Kt,h -s1 -d1 -s5+s -s10+s -s15+s -s20+s -r1+s -An -Ku -a1 -An -At,r,s -Kt,h -f-1 -t8 -s1+s -d3+s -s6+s -s12+s -s20+s -s30+s -An -Ku -a1 -An",
    )

    val MOBILE_RU = ByeDpiStrategy(
        mode = ByeDpiMode.MOBILE_RU,
        title = "Mobile RU",
        description = "Агрессивное дробление TLS + отдельный UDP/QUIC fake",
        command = "-Kt,h -d1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -s30+s -An -Ku -a1 -An",
    )

    /** Mirrors the current ByeByeDPI UI defaults: TLS/HTTP OOB + UDP fake. */
    val BALANCED = ByeDpiStrategy(
        mode = ByeDpiMode.BALANCED,
        title = "ByeByeDPI Default",
        description = "Повторяет UI-дефолт ByeByeDPI: OOB для TLS/HTTP + отдельный UDP/QUIC fake",
        command = "-Kt,h -o1 -An -Ku -a1 -An",
    )

    val STRONG_FAKE = ByeDpiStrategy(
        mode = ByeDpiMode.STRONG_FAKE,
        title = "Strong / Fake",
        description = "Fake packet + split/disorder для TLS, UDP/QUIC отдельно",
        command = "-Kt,h --fake -1 --ttl 8 --split 1+s --disorder 3+s -An -Ku -a1 -An",
    )

    val selectable: List<ByeDpiStrategy> = listOf(
        BALANCED,
        ByeDpiStrategy(
            mode = ByeDpiMode.AUTO,
            title = "Auto",
            description = "Сам выбирает лёгкую или мобильную авто-цепочку",
            command = "",
        ),
        MOBILE_RU,
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
