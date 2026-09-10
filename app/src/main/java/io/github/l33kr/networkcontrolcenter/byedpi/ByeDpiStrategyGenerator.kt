package io.github.l33kr.networkcontrolcenter.byedpi

/**
 * Seed-based strategy evolution for Android/ByeDPI.
 *
 * Seeds below are complete commands taken from ByeByeDPI's proxy tester (plus
 * its default command). Multi-stage -A chains are kept intact. We only create
 * one-dimensional descendants; no synthetic Cartesian-product fuzzing.
 */
object ByeDpiStrategyGenerator {
    private data class Seed(
        val name: String,
        val command: String,
        val description: String,
    )

    private val seeds = listOf(
        Seed(
            "Default",
            "-o1 -a1 -r-5+se",
            "Точная команда по умолчанию ByeByeDPI.",
        ),
        Seed(
            "Complex fake/split chain",
            "-f-200 -Qr -s3:5+sm -a1 -As -d1 -s4+sm -s8+sh -f-300 -d6+sh -a1 -At,r,s -o2 -f-30 -As -r5 -Mh -r6+sh -f-250 -s2:7+s -s3:6+sm -a1 -At,r,s -s3:5+sm -s6+s -s7:9+s -q30+sm -a1",
            "Полная многоступенчатая стратегия из ByeByeDPI: fake/split/disorder/OOB с fallback-группами.",
        ),
        Seed(
            "Record/split fallback",
            "-q2 -s2 -s3+s -r3 -s4 -r4 -s5+s -r5+s -s6 -s7+s -r8 -s9+s -Qr -Mh,d,r -a1 -At,r -s2+s -r2 -d2 -s3 -r3 -r4 -s4 -d5+s -r5 -d6 -s7+s -d7 -a1",
            "Двухступенчатая цепочка TLS record/split/disorder.",
        ),
        Seed(
            "Fake SNI four-stage",
            "-n {sni} -Qr -f-204 -s1:5+sm -a1 -As -d1 -s3+s -s5+s -q7 -a1 -As -o2 -f-43 -a1 -As -r5 -Mh -s1:5+s -s3:7+sm -a1",
            "Четыре стадии: fake SNI → disorder/split → OOB/fake → TLS record.",
        ),
        Seed(
            "Fake SNI staged offsets",
            "-n {sni} -Qr -f-205 -a1 -As -s1:3+sm -a1 -As -s5:8+sm -a1 -As -d3 -q7 -o2 -f-43 -f-85 -f-165 -r5 -Mh -a1",
            "Четырёхступенчатая стратегия с разными SNI-relative позициями.",
        ),
        Seed(
            "Large split fallback",
            "-d1+s -s50+s -a1 -As -f20 -r2+s -a1 -At -d2 -s1+s -s5+s -s10+s -s15+s -s25+s -s35+s -s50+s -s60+s -a1",
            "Трёхступенчатая цепочка: disorder/split → fake/record → глубокий multisplit.",
        ),
        Seed(
            "Disoob to multisplit",
            "-d1 -s1 -q1 -a1 -Ar -s5 -o1+s -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -a1",
            "Fallback от короткой disoob-схемы к глубокой multisplit/disorder.",
        ),
        Seed(
            "Fake/SNI staged",
            "-f1+nme -t6 -a1 -As -n {sni} -Qr -s1:6+sm -a1 -As -s5:12+sm -a1 -As -d3 -q7 -r6 -Mh -a1",
            "Четыре стадии с fake, SNI, split и HTTP/TLS модификациями.",
        ),
        Seed(
            "Auto fallback chain",
            "-o1 -a1 -At,r,s -f-1 -a1 -Ar,s -o1 -a1 -At -r1+s -f-1 -t6 -a1",
            "Четыре fallback-группы: OOB → fake → OOB → record/fake TTL.",
        ),
        Seed(
            "Fake/OOB two-stage",
            "-f-1 -Qr -s1+sm -d3+s -s5+sm -o2 -a1 -As -r1+s -d8+s -a1",
            "Двухступенчатая fake/split/OOB стратегия.",
        ),
        Seed(
            "OOB then fake SNI",
            "-o1 -r-5+se -a1 -At,r,s -d1 -n {sni} -Qr -f-1 -a1",
            "OOB/TLS record с переходом на disorder + fake SNI.",
        ),
        Seed(
            "Disorder/OOB/fake fallback",
            "-d1 -o1 -a1 -Ar -o1 -a1 -At -f-1 -r1+s -a1",
            "Три последовательных подхода: disorder+OOB → OOB → fake+TLS record.",
        ),
        Seed(
            "OOB/disoob/fake fallback",
            "-o1 -a1 -Ar -q1 -a1 -At -f-1 -r1+s -a1",
            "Три стадии: OOB → disoob → fake/TLS record.",
        ),
        Seed(
            "Disoob/OOB/fake fallback",
            "-q1 -a1 -Ar -o1 -a1 -At -f-1 -r1+s -a1",
            "Три стадии с альтернативным первым методом.",
        ),
        Seed(
            "None-trigger fake fallback",
            "-o1 -a1 -An -f1+nme -t6 -a1",
            "Если первая группа не подходит по фильтрам, используется fake fallback.",
        ),
    )

    fun quick(): List<CatalogStrategy> {
        val output = mutableListOf<CatalogStrategy>()
        var index = 20_000

        seeds.forEachIndexed { seedIndex, seed ->
            output += strategy(
                index = index++,
                idPrefix = "seed",
                name = "ByeDPI · ${seedIndex + 1} · ${seed.name}",
                command = seed.command,
                description = seed.description,
                source = if (seedIndex == 0) "ByeByeDPI default" else "ByeByeDPI proxytest exact",
                recommended = true,
            )
        }

        // One nearby descendant per seed. Exact multi-stage structure is preserved;
        // only the first matching token of one family is changed.
        seeds.drop(1).forEachIndexed { seedIndex, seed ->
            mutations(seed.command).firstOrNull()?.let { mutation ->
                output += strategy(
                    index = index++,
                    idPrefix = "evo",
                    name = "ByeDPI · ${seedIndex + 2}A · ${mutation.first}",
                    command = mutation.second,
                    description = "Одна контролируемая мутация полной стратегии «${seed.name}»: ${mutation.first}.",
                    source = "ByeByeDPI exact → Android mutation",
                    recommended = true,
                )
            }
        }

        return output.distinctBy { shellSplit(it.command).joinToString(" ") }.take(30)
    }

    fun deep(): List<CatalogStrategy> {
        val output = quick().toMutableList()
        var index = 30_000

        seeds.forEachIndexed { seedIndex, seed ->
            mutations(seed.command).forEach { mutation ->
                val normalized = shellSplit(mutation.second).joinToString(" ")
                if (output.any { shellSplit(it.command).joinToString(" ") == normalized }) return@forEach
                output += strategy(
                    index = index++,
                    idPrefix = "deep",
                    name = "ByeDPI · ${seedIndex + 1} → ${mutation.first}",
                    command = mutation.second,
                    description = "Одна контролируемая мутация полной многоступенчатой стратегии.",
                    source = "ByeByeDPI exact → deep mutation",
                    recommended = false,
                )
            }
        }
        return output
    }

    private fun mutations(command: String): List<Pair<String, String>> {
        val tokens = shellSplit(command)
        if (tokens.isEmpty()) return emptyList()
        val output = mutableListOf<Pair<String, String>>()

        fun mutateFirst(label: String, predicate: (String) -> Boolean, replacements: List<String>) {
            val position = tokens.indexOfFirst(predicate)
            if (position < 0) return
            val current = tokens[position]
            replacements.filter { it != current }.forEach { replacement ->
                val next = tokens.toMutableList()
                next[position] = replacement
                output += "$label ${replacement.removePrefix("-")}" to next.joinToString(" ")
            }
        }

        if (tokens.any { it.startsWith("-f") }) {
            mutateFirst("TTL", { it.matches(Regex("-t\\d+")) }, listOf("-t4", "-t6", "-t8", "-t10", "-t12"))
        }
        mutateFirst("OOB", { it.matches(Regex("-o\\d+(\\+[a-z]+)?")) }, listOf("-o1", "-o2", "-o3", "-o1+s", "-o3+s"))
        mutateFirst("TLSrec", { it.startsWith("-r") && it.length > 2 }, listOf("-r1+s", "-r3+s", "-r-3+se", "-r-5+se", "-r-7+se"))
        mutateFirst("Split", { it.startsWith("-s") && it.length > 2 }, listOf("-s1+s", "-s2+s", "-s3+s", "-s5+s", "-s1+sm", "-s3+sm"))
        mutateFirst("Disorder", { it.startsWith("-d") && it.length > 2 }, listOf("-d1", "-d1+s", "-d3+s", "-d5+s"))
        mutateFirst("Fake", { it.startsWith("-f") && it.length > 2 }, listOf("-f-1", "-f1", "-f1+s", "-f3+sm"))

        return output.filter { it.second != command }.distinctBy { it.second }
    }

    private fun strategy(
        index: Int,
        idPrefix: String,
        name: String,
        command: String,
        description: String,
        source: String,
        recommended: Boolean,
    ): CatalogStrategy {
        val normalized = shellSplit(command).joinToString(" ")
        return CatalogStrategy(
            index = index,
            id = "$idPrefix-${stableId(normalized)}",
            name = name,
            // Newlines are only presentation whitespace; shellSplit executes the
            // exact same argv while the UI now visibly shows each -A stage.
            command = ByeDpiStrategyPlan.formatForDisplay(normalized),
            category = "Android",
            description = "$description · ${ByeDpiStrategyPlan.stageCount(normalized)} стадий",
            source = source,
            recommended = recommended,
            protocol = "TCP/TLS",
        )
    }

    private fun stableId(command: String): String = command.hashCode().toUInt().toString(16)
}
