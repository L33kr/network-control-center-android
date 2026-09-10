package io.github.l33kr.networkcontrolcenter.byedpi.profile

import android.content.Context
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiConfigStore

object ProfileEngineBridge {
    fun applyActiveSet(context: Context): CompiledProfileSet {
        val set = ProfileStore.activeSet(context)
        val config = ByeDpiConfigStore.load(context)
        val compiled = ProfileCompiler.compile(context, set, config.sni)
        ByeDpiConfigStore.setManualStrategy(
            context = context,
            command = compiled.command,
            name = "Набор · ${set.name}",
        )
        return compiled
    }
}
