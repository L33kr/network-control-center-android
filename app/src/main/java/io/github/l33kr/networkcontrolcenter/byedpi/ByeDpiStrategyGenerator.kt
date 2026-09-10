package io.github.l33kr.networkcontrolcenter.byedpi

/**
 * Seed-based strategy evolution for Android/ByeDPI.
 *
 * The previous generator invented short combinations from individual primitives.
 * This version starts from commands that are actually present in ByeByeDPI's
 * proxytest_strategies.list (plus its exact default command) and changes only one
 * dimension at a time. This keeps the search explainable and much closer to the
 * configurations that ByeDPI users really test in practice.
 *
 * Android note: the quick seed set deliberately avoids -S/--md5sig and -Y because
 * availability/behaviour of those Linux socket features varies across Android
 * kernels. They can still be entered manually or tested from the raw legacy list.
 */
object ByeDpiStrategyGenerator {
    private data class Seed(
        val name: String,
        val command: String,
        val description: String,
    )

    private val seeds = listOf(
        Seed(
            "ByeBye default",
            "-o1 -a1 -r-5+se",
            "Точная команда по умолчанию ByeByeDPI. Контрольная точка для сравнения приложений.",
        ),
        Seed(
            "OOB + TLS record",
            "-o1 -r-5+se -a1",
            "Реальный seed из proxytest_strategies.list: OOB + TLS record split.",
        ),
        Seed(
            "Linux disorder + split",
            "-d1 -s3+s -a1",
            "Реальный seed: disorder=1 с разбиением внутри SNI.",
        ),
        Seed(
            "OOB/SNI disorder",
            "-o1+s -d3+s -a1",
            "Реальный seed: OOB и disorder относительно SNI.",
        ),
        Seed(
            "Fake SNI + TLS record",
            "-n {sni} -Qr -f-1 -r1+s -a1",
            "Реальный seed: fake ClientHello/SNI и отдельная TLS-record граница.",
        ),
        Seed(
            "Fake SNI + disorder range",
            "-n {sni} -Qr -d1:3 -f-1 -a1",
            "Реальный seed: fake SNI и диапазон disorder.",
        ),
        Seed(
            "Fake TTL + SNI split",
            "-f-1 -t8 -n {sni} -s1+s -a1",
            "Реальный seed: fake TTL=8 и split в SNI.",
        ),
        Seed(
            "Fake SNI + Linux disorder",
            "-n {sni} -Qr -d1 -f-1 -a1",
            "Реальный seed, особенно интересный для Linux/Android: disorder=1 + fake.",
        ),
        Seed(
            "OOB + fake + record",
            "-o1 -f-1 -r-5+se -a1",
            "Реальный seed: OOB, fake и TLS-record split.",
        ),
        Seed(
            "Disorder + split + fake TTL",
            "-d1 -s1+s -r1+s -f-1 -t8 -a1",
            "Реальный многокомпонентный seed без MD5SIG.",
        ),
        Seed(
            "Fake SNI/OOB chain",
            "-f-1 -n {sni} -Qr -s2+s -r3 -o20 -t4 -a1",
            "Реальный seed: fake SNI + split + TLS record + OOB + TTL.",
        ),
        Seed(
            "Fake SNI/disorder/OOB",
            "-n {sni} -Qr -d5+sm -f3+sm -o2 -t4 -a1",
            "Реальный seed с SNI-relative позициями и TTL=4.",
        ),
        Seed(
            "Multi split/disorder/OOB",
            "-f-1 -Qr -s1+sm -d3+s -s5+sm -o2 -a1 -As -r1+s -d8+s -a1",
            "Реальный двухгрупповой seed из тестового набора ByeByeDPI.",
        ),
        Seed(
            "Auto fallback chain",
            "-o1 -a1 -At,r,s -f-1 -a1 -Ar,s -o1 -a1 -At -r1+s -f-1 -t6 -a1",
            "Реальный seed с несколькими auto-trigger fallback группами.",
        ),
    )

    /**
     * Fast pool: exact seeds first, then at most a small number of one-parameter
     * descendants. The exact seeds are never rewritten or wrapped here.
     */
    fun quick(): List<CatalogStrategy> {
        val output = mutableListOf<CatalogStrategy>()
        var index = 20_000

        seeds.forEachIndexed { seedIndex, seed ->
            output += strategy(
                index = index++,
                idPrefix = "seed",
                name = "ByeDPI seed ${seedIndex + 1} · ${seed.name}",
                command = seed.command,
                description = seed.description,
                source = if (seedIndex == 0) "ByeByeDPI default" else "ByeByeDPI proxytest seed",
                recommended = true,
            )
        }

        // Mutate only one token family at a time. Two descendants per seed keeps
        // the phone-side quick scan bounded while still exploring nearby behaviour.
        seeds.forEachIndexed { seedIndex, seed ->
            mutations(seed.command)
                .take(2)
                .forEach { mutation ->
                    output += strategy(
                        index = index++,
                        idPrefix = "evo",
                        name = "Seed ${seedIndex + 1} → ${mutation.first}",
                        command = mutation.second,
                        description = "Одна мутация реальной стратегии «${seed.name}»: ${mutation.first}.",
                        source = "ByeByeDPI seed → ByeDPI Android",
                        recommended = true,
                    )
                }
        }

        return output.distinctBy { it.command }.take(36)
    }

    /**
     * Deep pool explores every one-dimensional mutation for every seed. It still
     * avoids a Cartesian product: no mutation is built on top of another mutation.
     */
    fun deep(): List<CatalogStrategy> {
        val output = quick().toMutableList()
        var index = 30_000

        seeds.forEachIndexed { seedIndex, seed ->
            mutations(seed.command).forEach { mutation ->
                val command = mutation.second
                if (output.any { it.command == command }) return@forEach
                output += strategy(
                    index = index++,
                    idPrefix = "deep",
                    name = "Seed ${seedIndex + 1} → ${mutation.first}",
                    command = command,
                    description = "Глубокий поиск: одна контролируемая мутация seed «${seed.name}».",
                    source = "ByeByeDPI seed evolution",
                    recommended = false,
                )
            }
        }

        return output.distinctBy { it.command }
    }

    private fun mutations(command: String): List<Pair<String, String>> {
        val tokens = shellSplit(command)
        if (tokens.isEmpty()) return emptyList()
        val output = mutableListOf<Pair<String, String>>()

        fun mutateFirst(
            labelPrefix: String,
            predicate: (String) -> Boolean,
            replacements: List<String>,
        ) {
            val position = tokens.indexOfFirst(predicate)
            if (position < 0) return
            val current = tokens[position]
            replacements
                .filter { it != current }
                .forEach { replacement ->
                    val next = tokens.toMutableList()
                    next[position] = replacement
                    output += "$labelPrefix ${replacement.removePrefix("-")}" to next.joinToString(" ")
                }
        }

        // TTL only matters when a fake packet exists. Keep a narrow Android range.
        if (tokens.any { it == "-f-1" || it.startsWith("-f") }) {
            mutateFirst(
                labelPrefix = "TTL",
                predicate = { it.matches(Regex("-t\\d+")) },
                replacements = listOf("-t4", "-t6", "-t8", "-t10", "-t12"),
            )
        }

        // Move a simple OOB point but preserve the rest of the seed verbatim.
        mutateFirst(
            labelPrefix = "OOB",
            predicate = { it.matches(Regex("-o\\d+(\\+s)?")) },
            replacements = listOf("-o1", "-o2", "-o3", "-o1+s", "-o3+s"),
        )

        // Explore TLS-record boundaries around SNI/end-SNI.
        mutateFirst(
            labelPrefix = "TLSrec",
            predicate = { it.startsWith("-r") && it.length > 2 },
            replacements = listOf("-r1+s", "-r3+s", "-r-3+se", "-r-5+se", "-r-7+se"),
        )

        // Change one split point, favouring SNI-relative offsets.
        mutateFirst(
            labelPrefix = "Split",
            predicate = { it.startsWith("-s") && it.length > 2 },
            replacements = listOf("-s1+s", "-s2+s", "-s3+s", "-s5+s", "-s1+sm", "-s3+sm"),
        )

        // Linux/Android-specific disorder neighbourhood. Avoid Windows-only assumptions.
        mutateFirst(
            labelPrefix = "Disorder",
            predicate = { it.startsWith("-d") && it.length > 2 },
            replacements = listOf("-d1", "-d1+s", "-d3+s", "-d5+s"),
        )

        // Fake position itself is another meaningful single dimension.
        mutateFirst(
            labelPrefix = "Fake",
            predicate = { it.startsWith("-f") && it.length > 2 },
            replacements = listOf("-f-1", "-f1", "-f1+s", "-f3+sm"),
        )

        return output
            .filter { it.second != command }
            .distinctBy { it.second }
    }

    private fun strategy(
        index: Int,
        idPrefix: String,
        name: String,
        command: String,
        description: String,
        source: String,
        recommended: Boolean,
    ) = CatalogStrategy(
        index = index,
        id = "$idPrefix-${stableId(command)}",
        name = name,
        command = command,
        category = "Android",
        description = description,
        source = source,
        recommended = recommended,
        protocol = "TCP/TLS",
    )

    private fun stableId(command: String): String = command.hashCode().toUInt().toString(16)
}
