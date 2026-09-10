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

        // Strategy Lab candidates are compatibility/diagnostic strategies derived
        // from actual ByeByeDPI seeds. Run them raw instead of injecting profile
        // -H/-K/-A groups, otherwise a valid seed can be changed before it reaches
        // the native ByeDPI parser and we lose the one-to-one comparison.
        val directSeedProfile = set.profiles.firstOrNull { profile ->
            profile.enabled &&
                profile.action == ProfileAction.BYPASS &&
                profile.protocol != ProfileProtocol.UDP_QUIC &&
                isNativeSeedName(profile.strategyName) &&
                !profile.strategyCommand.isNullOrBlank()
        }

        if (directSeedProfile != null) {
            ByeDpiConfigStore.setManualStrategy(
                context = context,
                // The seed already contains the UDP behaviour chosen by the real
                // ByeByeDPI template. Keep it byte-for-byte; do not append another
                // -Ku group while we are validating strategy compatibility.
                command = ByeDpiRuntimeStore.prepareNativeCommand(
                    command = directSeedProfile.strategyCommand.orEmpty(),
                    addUdpFallback = false,
                ),
                name = "Native · ${directSeedProfile.strategyName}",
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

    private fun isNativeSeedName(name: String?): Boolean {
        val value = name.orEmpty()
        return value.startsWith("Android ·") ||
            value.startsWith("ByeDPI seed ") ||
            value.startsWith("Seed ")
    }
}
