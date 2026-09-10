package io.github.l33kr.networkcontrolcenter.byedpi.profile

import android.content.Context
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiConfigStore
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiRuntimeMode
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiRuntimeStore

object ProfileEngineBridge {
    fun applyActiveSet(context: Context): CompiledProfileSet {
        val set = ProfileStore.activeSet(context)
        val config = ByeDpiConfigStore.load(context)
        val compiled = ProfileCompiler.compile(context, set, config.sni)

        if (ByeDpiRuntimeStore.mode(context) == ByeDpiRuntimeMode.NATIVE_RAW) {
            ByeDpiConfigStore.setManualStrategy(
                context = context,
                command = ByeDpiRuntimeStore.nativeCommand(context),
                name = "Native · ${ByeDpiRuntimeStore.nativeName(context)}",
            )
            return compiled
        }

        ByeDpiConfigStore.setManualStrategy(
            context = context,
            command = compiled.command,
            name = "Набор · ${set.name}",
        )
        return compiled
    }
}
