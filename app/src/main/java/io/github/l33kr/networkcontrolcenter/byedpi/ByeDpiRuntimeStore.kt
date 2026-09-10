package io.github.l33kr.networkcontrolcenter.byedpi

import android.content.Context

enum class ByeDpiRuntimeMode {
    PROFILE_SET,
    NATIVE_RAW,
}

object ByeDpiRuntimeStore {
    private const val PREFS = "byedpi_runtime"
    private const val KEY_MODE = "mode"
    private const val KEY_NATIVE_NAME = "native_name"
    private const val KEY_NATIVE_COMMAND = "native_command"
    private const val KEY_NATIVE_UDP = "native_udp"

    fun mode(context: Context): ByeDpiRuntimeMode {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, ByeDpiRuntimeMode.PROFILE_SET.name)
        return runCatching { ByeDpiRuntimeMode.valueOf(raw.orEmpty()) }
            .getOrDefault(ByeDpiRuntimeMode.PROFILE_SET)
    }

    fun useProfileSet(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, ByeDpiRuntimeMode.PROFILE_SET.name)
            .apply()
    }

    fun useNativeStrategy(
        context: Context,
        strategy: CatalogStrategy,
        addUdpFallback: Boolean = true,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, ByeDpiRuntimeMode.NATIVE_RAW.name)
            .putString(KEY_NATIVE_NAME, strategy.name)
            .putString(KEY_NATIVE_COMMAND, strategy.command)
            .putBoolean(KEY_NATIVE_UDP, addUdpFallback)
            .apply()
    }

    fun nativeName(context: Context): String = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_NATIVE_NAME, "Native ByeDPI")
        .orEmpty()
        .ifBlank { "Native ByeDPI" }

    fun nativeRawCommand(context: Context): String = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_NATIVE_COMMAND, null)
        .orEmpty()
        .ifBlank { ByeDpiStrategies.BALANCED.command }

    /**
     * Native mode deliberately skips ProfileCompiler. For TCP/TLS strategies we
     * can append the same separate UDP group used by ByeByeDPI UI so QUIC is not
     * silently left untreated. If the raw command already defines a UDP group,
     * it is preserved exactly.
     */
    fun nativeCommand(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = nativeRawCommand(context).trim()
        if (!prefs.getBoolean(KEY_NATIVE_UDP, true) || hasUdpGroup(raw)) return raw

        return if (raw.endsWith("-An")) {
            "$raw -Ku -a1 -An"
        } else {
            "$raw -An -Ku -a1 -An"
        }
    }

    private fun hasUdpGroup(command: String): Boolean {
        val args = shellSplit(command)
        return args.withIndex().any { (index, token) ->
            token == "-Ku" ||
                token.startsWith("-Ku") ||
                token == "--proto=u" ||
                token.startsWith("--proto=u,") ||
                (token == "--proto" && args.getOrNull(index + 1)?.split(',')?.contains("u") == true)
        }
    }
}
