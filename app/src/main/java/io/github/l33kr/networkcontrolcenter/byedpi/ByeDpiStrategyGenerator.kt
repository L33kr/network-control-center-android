package io.github.l33kr.networkcontrolcenter.byedpi

/**
 * Deterministic strategy generator for current ByeDPI primitives.
 *
 * This is intentionally not a random argv fuzzer. Every generated command is
 * assembled from syntax already supported by the pinned ByeDPI core. The quick
 * set varies one or two dimensions at a time so the tester can discover which
 * desync family works on a specific ISP without creating thousands of cases.
 */
object ByeDpiStrategyGenerator {
    fun quick(): List<CatalogStrategy> = buildList {
        var index = 20_000

        fun add(
            family: String,
            label: String,
            command: String,
            description: String,
        ) {
            add(
                CatalogStrategy(
                    index = index++,
                    id = "gen-${stableId(command)}",
                    name = "Generator · $label",
                    command = command,
                    category = "Generator",
                    description = description,
                    source = "DPI Control Strategy Lab",
                    recommended = true,
                    protocol = "TCP/TLS",
                ),
            )
        }

        // OOB family: cheap and often enough for simple DPI implementations.
        listOf(
            "1" to "-Kt,h -o1 -a1",
            "2" to "-Kt,h -o2 -a1",
            "3" to "-Kt,h -o3 -a1",
            "5+s" to "-Kt,h -o5+s -a1",
        ).forEach { (position, command) ->
            add("OOB", "OOB $position", command, "OOB с позицией $position")
        }

        // Single split variants. Symbolic offsets (+s/+sm) are preferable to
        // absolute ClientHello sizes because they adapt to different hosts.
        listOf(
            "1+s" to "-Kt,h -s1+s -a1",
            "2+s" to "-Kt,h -s2+s -a1",
            "3+s" to "-Kt,h -s3+s -a1",
            "5+s" to "-Kt,h -s5+s -a1",
            "1:3+sm" to "-Kt,h -s1:3+sm -a1",
            "3:7+sm" to "-Kt,h -s3:7+sm -a1",
        ).forEach { (position, command) ->
            add("Split", "Split $position", command, "Разбиение TLS ClientHello: $position")
        }

        // Disorder + split explores packet ordering independently from fake data.
        listOf(
            "-Kt,h -d1 -s1+s -a1",
            "-Kt,h -d1 -s3+s -a1",
            "-Kt,h -d3+s -s1+s -a1",
            "-Kt,h -d3+s -s5+s -a1",
            "-Kt,h -d1:3+sm -s3:7+sm -a1",
        ).forEachIndexed { n, command ->
            add("Disorder", "Disorder/Split ${n + 1}", command, "Изменение порядка + split")
        }

        // OOB + split changes two independent properties but keeps the chain small.
        listOf(
            "-Kt,h -o1 -s1+s -a1",
            "-Kt,h -o1 -s3+s -a1",
            "-Kt,h -o2 -s1+s -a1",
            "-Kt,h -o2 -s5+s -a1",
        ).forEachIndexed { n, command ->
            add("Hybrid", "OOB/Split ${n + 1}", command, "OOB + TLS split")
        }

        // TLS record splitting targets DPI implementations that parse records
        // differently from the TCP stream.
        listOf(
            "-Kt,h -r1+s -a1",
            "-Kt,h -r3+s -a1",
            "-Kt,h -r-5+se -a1",
            "-Kt,h -o1 -r-5+se -a1",
        ).forEachIndexed { n, command ->
            add("TLS record", "TLS record ${n + 1}", command, "Изменение границ TLS record")
        }

        // Fake TTL explores a small safe range rather than brute-forcing TTL 1..255.
        listOf(4, 6, 8, 12).forEach { ttl ->
            add(
                "Fake",
                "Fake TTL $ttl",
                "-Kt,h -f-1 -t$ttl -s1+s -d3+s -a1",
                "Fake packet TTL=$ttl + split/disorder",
            )
        }

        // Fake SNI uses the SNI configured in the app and varies TTL. Keeping the
        // placeholder in the generated command lets the normal config layer inject it.
        listOf(4, 6, 8, 12).forEach { ttl ->
            add(
                "Fake SNI",
                "Fake SNI TTL $ttl",
                "-Kt,h -n {sni} -Qr -f-1 -t$ttl -s1+s -a1",
                "Fake ClientHello/SNI, TTL=$ttl",
            )
        }

        // A few deeper combinations are kept in the quick set because they vary
        // the shape significantly without exploding the search space.
        listOf(
            "-Kt,h -n {sni} -Qr -f-1 -t6 -d1 -s1+s -s5+s -a1",
            "-Kt,h -n {sni} -Qr -f-1 -t8 -d3+s -s1+s -s6+s -a1",
            "-Kt,h -o1 -r1+s -d1 -s3+s -a1",
            "-Kt,h -o2 -r-5+se -s1:3+sm -a1",
        ).forEachIndexed { n, command ->
            add("Deep", "Deep ${n + 1}", command, "Комбинированный fallback ${n + 1}")
        }
    }.distinctBy { it.command }

    /**
     * Larger deterministic pool for a future/explicit deep scan. It extends the
     * quick set by mutating split/disorder positions around the most useful
     * families, still avoiding a full Cartesian product.
     */
    fun deep(): List<CatalogStrategy> {
        val output = quick().toMutableList()
        var index = 30_000

        val splits = listOf("1+s", "2+s", "3+s", "5+s", "1:3+sm", "3:7+sm")
        val disorders = listOf("1", "1+s", "3+s", "5+s")
        val ttls = listOf(4, 6, 8, 10, 12)

        fun add(label: String, command: String) {
            if (output.any { it.command == command }) return
            output += CatalogStrategy(
                index = index++,
                id = "gen-${stableId(command)}",
                name = "Generator · $label",
                command = command,
                category = "Generator",
                description = "Глубокая сгенерированная комбинация ByeDPI",
                source = "DPI Control Strategy Lab",
                recommended = false,
                protocol = "TCP/TLS",
            )
        }

        // Pair nearby split/disorder values instead of every possible pair.
        splits.forEachIndexed { i, split ->
            val disorder = disorders[i % disorders.size]
            add("D$disorder + S$split", "-Kt,h -d$disorder -s$split -a1")
            add("OOB + S$split", "-Kt,h -o${if (i % 2 == 0) 1 else 2} -s$split -a1")
        }

        ttls.forEachIndexed { i, ttl ->
            val split = splits[i % splits.size]
            val disorder = disorders[(i + 1) % disorders.size]
            add("Fake $ttl / S$split", "-Kt,h -f-1 -t$ttl -s$split -d$disorder -a1")
            add("Fake SNI $ttl / S$split", "-Kt,h -n {sni} -Qr -f-1 -t$ttl -s$split -a1")
        }

        listOf("1+s", "3+s", "-5+se").forEachIndexed { i, rec ->
            val split = splits[(i + 2) % splits.size]
            add("TLSrec $rec / S$split", "-Kt,h -r$rec -s$split -a1")
        }

        return output.distinctBy { it.command }
    }

    private fun stableId(command: String): String = command.hashCode().toUInt().toString(16)
}
