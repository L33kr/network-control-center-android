package io.github.l33kr.networkcontrolcenter.byedpi

import android.content.Context

enum class ByeDpiMode {
    AUTO,
    MOBILE_RU,
    BALANCED,
    STRONG_FAKE,
    MANUAL,
}

enum class Ipv6Mode {
    AUTO,
    OFF,
    ON,
}

enum class DomainFilterMode {
    ALL,
    ONLY_LISTED,
    EXCLUDE_LISTED,
}

/**
 * Proven baseline from the 0.4 catalog. Keep the stored command byte-for-byte:
 * it relies on native ByeDPI Linux socket behaviour (fake + DISOOB/OOB + TLS record split).
 */
object ByeDpiStableProfile {
    const val NAME = "Стратегия 16"
    const val COMMAND = "-f1 -t5 -n {sni} -q3+h -Qr -f2 -q1 -r1+s -t15 -q1 -o2 -a1"
}

data class ByeDpiConfig(
    val bindIp: String = "127.0.0.1",
    val port: Int = 1080,
    val mode: ByeDpiMode = ByeDpiMode.MANUAL,
    /** Used for manual and imported catalog strategies. */
    val command: String = ByeDpiStableProfile.COMMAND,
    val strategyName: String? = ByeDpiStableProfile.NAME,
    val sni: String = "google.com",
    val dns: String = "1.1.1.1",
    val ipv6Mode: Ipv6Mode = Ipv6Mode.AUTO,
    val domainFilterMode: DomainFilterMode = DomainFilterMode.ALL,
    val domains: String = "",
) {
    fun normalizedDomains(): List<String> = domains
        .lineSequence()
        .flatMap { line -> line.split(' ', ',', ';').asSequence() }
        .map { it.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/') }
        .map { it.removePrefix("*.").trim('.') }
        .filter { it.isNotBlank() && it.length <= 253 && !it.contains('"') && !it.contains('\'') }
        .distinct()
        .toList()

    fun toArgs(commandOverride: String? = null): Array<String> = buildList {
        add("ciadpi")
        add("--ip")
        add(bindIp)
        add("--port")
        add(port.toString())

        val originalCommand = commandOverride ?: command
        val effectiveCommand = MetaCompatibility.enhance(originalCommand)
        val strategyArgs = shellSplit(effectiveCommand.replace("{sni}", sni))

        // The rebuilt UI no longer exposes the old domain-filter screen. More
        // importantly, hidden settings left by an older install must not inject
        // -H into the Meta/IPSet fallback groups around the proven strategy #16.
        if (originalCommand.trim() == ByeDpiStableProfile.COMMAND) {
            addAll(strategyArgs)
        } else {
            addAll(applyDomainFilter(strategyArgs))
        }
    }.toTypedArray()

    private fun applyDomainFilter(strategyArgs: List<String>): List<String> {
        val list = normalizedDomains()
        if (domainFilterMode == DomainFilterMode.ALL || list.isEmpty()) return strategyArgs

        val hostsArg = ":${list.joinToString(" ")}"

        return when (domainFilterMode) {
            DomainFilterMode.ALL -> strategyArgs
            DomainFilterMode.ONLY_LISTED -> injectHostsIntoEveryGroup(strategyArgs, hostsArg)
            DomainFilterMode.EXCLUDE_LISTED -> buildList {
                add("-H")
                add(hostsArg)
                add("-An")
                addAll(strategyArgs)
            }
        }
    }

    private fun injectHostsIntoEveryGroup(args: List<String>, hostsArg: String): List<String> = buildList {
        add("-H")
        add(hostsArg)

        var index = 0
        while (index < args.size) {
            val token = args[index]
            add(token)

            when {
                token == "-A" || token == "--auto" -> {
                    if (index + 1 < args.size) {
                        index++
                        add(args[index])
                    }
                    add("-H")
                    add(hostsArg)
                }

                token.startsWith("-A") && token.length > 2 -> add("-H").also { add(hostsArg) }
                token.startsWith("--auto=") -> add("-H").also { add(hostsArg) }
            }
            index++
        }
    }
}

object ByeDpiConfigStore {
    private const val PREFS = "byedpi"
    private const val KEY_REBUILD_MIGRATED = "stable_rebuild_0_5_migrated"

    fun load(context: Context): ByeDpiConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ByeDpiConfig(
            bindIp = prefs.getString("bind_ip", "127.0.0.1") ?: "127.0.0.1",
            port = prefs.getInt("port", 1080).coerceIn(1, 65535),
            mode = runCatching {
                ByeDpiMode.valueOf(
                    prefs.getString("mode", ByeDpiMode.MANUAL.name) ?: ByeDpiMode.MANUAL.name,
                )
            }.getOrDefault(ByeDpiMode.MANUAL),
            command = prefs.getString("command", ByeDpiStableProfile.COMMAND)
                ?: ByeDpiStableProfile.COMMAND,
            strategyName = prefs.getString("strategy_name", ByeDpiStableProfile.NAME),
            sni = prefs.getString("sni", "google.com")?.trim().orEmpty().ifBlank { "google.com" },
            dns = prefs.getString("dns", "1.1.1.1") ?: "1.1.1.1",
            ipv6Mode = runCatching {
                Ipv6Mode.valueOf(
                    prefs.getString("ipv6_mode", Ipv6Mode.AUTO.name) ?: Ipv6Mode.AUTO.name,
                )
            }.getOrDefault(Ipv6Mode.AUTO),
            domainFilterMode = runCatching {
                DomainFilterMode.valueOf(
                    prefs.getString("domain_filter_mode", DomainFilterMode.ALL.name)
                        ?: DomainFilterMode.ALL.name,
                )
            }.getOrDefault(DomainFilterMode.ALL),
            domains = prefs.getString("domains", "") ?: "",
        )
    }

    /** One-time migration for the rebuild branch: preserve DNS/domain settings but start from proven #16. */
    fun ensureStableRebuildBaseline(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_REBUILD_MIGRATED, false)) return
        val current = load(context)
        save(
            context,
            current.copy(
                mode = ByeDpiMode.MANUAL,
                command = ByeDpiStableProfile.COMMAND,
                strategyName = ByeDpiStableProfile.NAME,
            ),
        )
        prefs.edit().putBoolean(KEY_REBUILD_MIGRATED, true).apply()
    }

    fun save(context: Context, config: ByeDpiConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("bind_ip", config.bindIp)
            .putInt("port", config.port)
            .putString("mode", config.mode.name)
            .putString("command", config.command)
            .putString("strategy_name", config.strategyName)
            .putString("sni", config.sni)
            .putString("dns", config.dns)
            .putString("ipv6_mode", config.ipv6Mode.name)
            .putString("domain_filter_mode", config.domainFilterMode.name)
            .putString("domains", config.domains)
            .apply()
    }

    fun setMode(context: Context, mode: ByeDpiMode) {
        val current = load(context)
        save(context, current.copy(mode = mode, strategyName = null))
    }

    fun setCatalogStrategy(context: Context, name: String, command: String) {
        val current = load(context)
        save(
            context,
            current.copy(
                mode = ByeDpiMode.MANUAL,
                command = command,
                strategyName = name,
            ),
        )
    }

    fun setManualStrategy(context: Context, command: String, name: String = "Ручная стратегия") {
        val current = load(context)
        save(
            context,
            current.copy(
                mode = ByeDpiMode.MANUAL,
                command = command.trim().ifBlank { ByeDpiStableProfile.COMMAND },
                strategyName = name,
            ),
        )
    }

    fun setSni(context: Context, sni: String) {
        val current = load(context)
        save(context, current.copy(sni = sni.trim().ifBlank { "google.com" }))
    }

    fun setDomainFilter(context: Context, mode: DomainFilterMode, domains: String) {
        val current = load(context)
        save(context, current.copy(domainFilterMode = mode, domains = domains))
    }
}

internal fun shellSplit(input: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaped = false

    fun flush() {
        if (current.isNotEmpty()) {
            result += current.toString()
            current.setLength(0)
        }
    }

    for (ch in input) {
        if (escaped) {
            current.append(ch)
            escaped = false
            continue
        }
        when {
            ch == '\\' -> escaped = true
            quote != null && ch == quote -> quote = null
            quote != null -> current.append(ch)
            ch == '\'' || ch == '"' -> quote = ch
            ch.isWhitespace() -> flush()
            else -> current.append(ch)
        }
    }
    if (escaped) current.append('\\')
    flush()
    return result
}
