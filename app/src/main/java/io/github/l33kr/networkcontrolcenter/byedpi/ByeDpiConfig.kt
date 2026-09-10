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

data class ByeDpiConfig(
    val bindIp: String = "127.0.0.1",
    val port: Int = 1080,
    val mode: ByeDpiMode = ByeDpiMode.AUTO,
    /** Used for manual and imported catalog strategies. */
    val command: String = ByeDpiStrategies.BALANCED.command,
    val strategyName: String? = null,
    val sni: String = "google.com",
    val dns: String = "1.1.1.1",
    val ipv6Mode: Ipv6Mode = Ipv6Mode.AUTO,
) {
    fun toArgs(commandOverride: String? = null): Array<String> = buildList {
        add("ciadpi")
        add("--ip")
        add(bindIp)
        add("--port")
        add(port.toString())
        addAll(shellSplit((commandOverride ?: command).replace("{sni}", sni)))
    }.toTypedArray()
}

object ByeDpiConfigStore {
    private const val PREFS = "byedpi"

    fun load(context: Context): ByeDpiConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ByeDpiConfig(
            bindIp = prefs.getString("bind_ip", "127.0.0.1") ?: "127.0.0.1",
            port = prefs.getInt("port", 1080).coerceIn(1, 65535),
            mode = runCatching {
                ByeDpiMode.valueOf(
                    prefs.getString("mode", ByeDpiMode.AUTO.name) ?: ByeDpiMode.AUTO.name,
                )
            }.getOrDefault(ByeDpiMode.AUTO),
            command = prefs.getString("command", ByeDpiStrategies.BALANCED.command)
                ?: ByeDpiStrategies.BALANCED.command,
            strategyName = prefs.getString("strategy_name", null),
            sni = prefs.getString("sni", "google.com")?.trim().orEmpty().ifBlank { "google.com" },
            dns = prefs.getString("dns", "1.1.1.1") ?: "1.1.1.1",
            ipv6Mode = runCatching {
                Ipv6Mode.valueOf(
                    prefs.getString("ipv6_mode", Ipv6Mode.AUTO.name) ?: Ipv6Mode.AUTO.name,
                )
            }.getOrDefault(Ipv6Mode.AUTO),
        )
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

    fun setSni(context: Context, sni: String) {
        val current = load(context)
        save(context, current.copy(sni = sni.trim().ifBlank { "google.com" }))
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
