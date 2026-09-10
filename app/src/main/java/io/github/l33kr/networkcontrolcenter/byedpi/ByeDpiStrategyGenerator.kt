package io.github.l33kr.networkcontrolcenter.byedpi

/**
 * Android/Linux-oriented ByeDPI strategy families.
 *
 * These variants intentionally stay close to ByeDPI's own primitives and Linux
 * recommendations instead of synthesizing Windows/zapret-style chains. In
 * particular, disorder is centered around -d1 and OOB positions are explored
 * relative to the SNI marker (+s), which is where ByeDPI's documentation suggests
 * placing the out-of-band byte.
 */
object ByeDpiStrategyGenerator {
    fun quick(): List<CatalogStrategy> = buildList {
        var index = 20_000

        fun add(label: String, command: String, description: String) {
            add(
                CatalogStrategy(
                    index = index++,
                    id = "android-${stableId(command)}",
                    name = "Android · $label",
                    command = command,
                    category = "Android",
                    description = description,
                    source = "ByeDPI Linux/Android",
                    recommended = true,
                    protocol = "TCP/TLS",
                ),
            )
        }

        // Exact raw default used by ByeByeDPI command mode. Keep it as a control
        // candidate: if this works in ByeByeDPI but fails here, the problem is not
        // strategy generation.
        add(
            "ByeBye raw default",
            "-o1 -a1 -r-5+se",
            "Контрольная команда ByeByeDPI: OOB + UDP fake + TLS record split.",
        )

        // OOB family. +s addresses positions relative to the SNI extension.
        add("OOB 1", "-Kt,h -o1", "Минимальный OOB, близкий к UI-дефолту ByeByeDPI.")
        add("OOB SNI +1", "-Kt,h -o1+s", "OOB внутри области SNI, смещение +1.")
        add("OOB SNI +3", "-Kt,h -o3+s", "OOB внутри SNI; основной Android-кандидат.")
        add("OOB SNI +5", "-Kt,h -o5+s", "Более глубокое OOB-смещение внутри SNI.")
        add("DISOOB SNI +3", "-Kt,h -q3+s", "Disorder + OOB на позиции, привязанной к SNI.")

        // Linux-native disorder. ByeDPI documentation specifically recommends
        // disorder=1 on Linux, so do not start with the Windows 3+s pattern.
        add("Linux disorder", "-Kt,h -d1", "Базовый disorder для Linux/Android.")
        add("Linux disorder + split", "-Kt,h -s3+s -d1", "Split внутри SNI + Linux disorder=1.")
        add("Linux disorder + TLS record", "-Kt,h -d1 -r1+s", "Linux disorder и отдельная граница TLS record.")

        // TLS record splitting works at the TLS framing layer instead of relying
        // only on TCP segmentation.
        add("TLS record SNI +1", "-Kt,h -r1+s", "TLS record split относительно начала SNI.")
        add("TLS record SNI end-5", "-Kt,h -r-5+se", "TLS record split относительно конца SNI.")
        add("OOB SNI + TLS record", "-Kt,h -o3+s -r1+s", "OOB внутри SNI + TLS record split.")
        add("OOB SNI + record end", "-Kt,h -o3+s -r-5+se", "OOB внутри SNI + split у конца SNI.")

        // Fake packets on Android cannot assume TCP_MD5SIG is available, so use a
        // small TTL family and let the real HTTP tester decide whether the fake is
        // useful on this carrier.
        listOf(4, 6, 8).forEach { ttl ->
            add(
                "Fake SNI TTL $ttl",
                "-Kt,h -n {sni} -Qr -f-1 -t$ttl",
                "Fake ClientHello/SNI с TTL=$ttl без зависимости от MD5SIG.",
            )
        }
        add(
            "Fake SNI + Linux disorder",
            "-Kt,h -n {sni} -Qr -f-1 -t6 -d1",
            "Fake SNI TTL=6 + Linux disorder=1.",
        )
        add(
            "Fake + OOB SNI",
            "-Kt,h -n {sni} -Qr -f-1 -t6 -o3+s",
            "Fake SNI TTL=6 + OOB внутри настоящего SNI.",
        )

        // Keep a plain SNI-relative split as a low-complexity control.
        add("Split SNI +1", "-Kt,h -s1+s", "Один TCP split относительно начала SNI.")
        add("Split SNI +3", "-Kt,h -s3+s", "Один TCP split глубже внутри SNI.")
    }.distinctBy { it.command }

    /**
     * Optional larger pool. It varies only parameters that are meaningful for the
     * same Android/Linux families, avoiding an uncontrolled Cartesian product.
     */
    fun deep(): List<CatalogStrategy> {
        val output = quick().toMutableList()
        var index = 30_000

        fun add(label: String, command: String, description: String) {
            if (output.any { it.command == command }) return
            output += CatalogStrategy(
                index = index++,
                id = "android-${stableId(command)}",
                name = "Android · $label",
                command = command,
                category = "Android",
                description = description,
                source = "ByeDPI Linux/Android deep scan",
                recommended = false,
                protocol = "TCP/TLS",
            )
        }

        listOf(1, 2, 3, 4, 5, 7).forEach { offset ->
            add("OOB SNI +$offset", "-Kt,h -o$offset+s", "OOB SNI offset +$offset")
            add("DISOOB SNI +$offset", "-Kt,h -q$offset+s", "DISOOB SNI offset +$offset")
            add("Split SNI +$offset", "-Kt,h -s$offset+s", "Split SNI offset +$offset")
        }

        listOf(1, 2, 3, 5).forEach { offset ->
            add("TLS record +$offset", "-Kt,h -r$offset+s", "TLS record split at SNI +$offset")
            add("OOB + record +$offset", "-Kt,h -o3+s -r$offset+s", "OOB SNI + TLS record +$offset")
        }

        listOf(3, 4, 5, 6, 7, 8, 10, 12).forEach { ttl ->
            add(
                "Fake SNI TTL $ttl",
                "-Kt,h -n {sni} -Qr -f-1 -t$ttl",
                "Fake SNI with TTL=$ttl",
            )
            add(
                "Fake SNI TTL $ttl + d1",
                "-Kt,h -n {sni} -Qr -f-1 -t$ttl -d1",
                "Fake SNI TTL=$ttl + Linux disorder=1",
            )
        }

        listOf("1+s", "3+s", "-3+se", "-5+se", "-7+se").forEach { record ->
            add("TLS record $record", "-Kt,h -r$record", "TLS record boundary $record")
        }

        return output.distinctBy { it.command }
    }

    private fun stableId(command: String): String = command.hashCode().toUInt().toString(16)
}
