package io.github.l33kr.networkcontrolcenter.nativecore

import android.content.Context

enum class EngineMode {
    NATIVE_ALPHA,
    LEGACY_BYEDPI,
}

object EngineModeStore {
    private const val PREFS = "native_engine_alpha"
    private const val KEY_MODE = "engine_mode"

    fun load(context: Context): EngineMode {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, EngineMode.NATIVE_ALPHA.name)
        return runCatching { EngineMode.valueOf(raw ?: EngineMode.NATIVE_ALPHA.name) }
            .getOrDefault(EngineMode.NATIVE_ALPHA)
    }

    fun save(context: Context, mode: EngineMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.name)
            .apply()
    }
}
