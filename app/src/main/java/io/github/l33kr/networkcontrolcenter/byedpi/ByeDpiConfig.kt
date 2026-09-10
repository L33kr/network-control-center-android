package io.github.l33kr.networkcontrolcenter.byedpi

import android.content.Context

enum class Ipv6Mode {
    OFF,
    ON,
}

data class ByeDpiConfig(
    val bindIp: String = "127.0.0.1",
    val port: Int = 1080,
    val command: String = "-o1 -a1 -r-5+se",
    val dns: String = "1.1.1.1",
    val ipv6Mode: Ipv6Mode = Ipv6Mode.OFF,
) {
    fun toArgs(): Array<String> = buildList {
        add("ciadpi")
        add("--ip")
        add(bindIp)
        add("--port")
        add(port.toString())
        addAll(shellSplit(command))
    }.toTypedArray()
}

object ByeDpiConfigStore {
    private const val PREFS = "byedpi"

    fun load(context: Context): ByeDpiConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ByeDpiConfig(
            bindIp = prefs.getString("bind_ip", "127.0.0.1") ?: "127.0.0.1",
            port = prefs.getInt("port", 1080).coerceIn(1, 65535),
            command = prefs.getString("command", "-o1 -a1 -r-5+se") ?: "-o1 -a1 -r-5+se",
            dns = prefs.getString("dns", "1.1.1.1") ?: "1.1.1.1",
            ipv6Mode = runCatching {
                Ipv6Mode.valueOf(prefs.getString("ipv6_mode", Ipv6Mode.OFF.name) ?: Ipv6Mode.OFF.name)
            }.getOrDefault(Ipv6Mode.OFF),
        )
    }

    fun save(context: Context, config: ByeDpiConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("bind_ip", config.bindIp)
            .putInt("port", config.port)
            .putString("command", config.command)
            .putString("dns", config.dns)
            .putString("ipv6_mode", config.ipv6Mode.name)
            .apply()
    }
}

private fun shellSplit(input: String): List<String> {
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
