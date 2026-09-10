package io.github.l33kr.networkcontrolcenter.nativecore

import android.content.Context

/**
 * Stores Native Engine choices independently from ByeDPI command strings.
 * This keeps the new engine truly self-contained while preserving the old
 * profile data for Legacy mode and rollback/testing.
 */
object NativePolicyStore {
    private const val PREFS = "native_engine_policy_v1"
    private const val KEY_DEFAULT = "default_strategy"

    fun defaultStrategy(context: Context): NativeStrategyPreset {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DEFAULT, NativeStrategies.AUTO.id)
        return NativeStrategies.byId(raw) ?: NativeStrategies.AUTO
    }

    fun setDefaultStrategy(context: Context, strategy: NativeStrategyPreset) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEFAULT, strategy.id)
            .apply()
    }

    private fun profileKey(profileId: String) = "profile:$profileId"

    fun strategyForProfile(context: Context, profileId: String): NativeStrategyPreset? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(profileKey(profileId), null)
        return NativeStrategies.byId(raw)
    }

    fun setStrategyForProfile(context: Context, profileId: String, strategy: NativeStrategyPreset?) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (strategy == null) editor.remove(profileKey(profileId))
        else editor.putString(profileKey(profileId), strategy.id)
        editor.apply()
    }
}
