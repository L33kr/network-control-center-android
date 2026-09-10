package io.github.l33kr.networkcontrolcenter.nativecore

import android.content.Context

data class NativeSettings(
    val forceTcpForBypassDomains: Boolean = true,
)

object NativeSettingsStore {
    private const val PREFS = "native_engine_alpha"
    private const val KEY_FORCE_TCP = "force_tcp_for_bypass_domains"

    fun load(context: Context): NativeSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return NativeSettings(
            forceTcpForBypassDomains = prefs.getBoolean(KEY_FORCE_TCP, true),
        )
    }

    fun setForceTcpForBypassDomains(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FORCE_TCP, enabled)
            .apply()
    }
}
