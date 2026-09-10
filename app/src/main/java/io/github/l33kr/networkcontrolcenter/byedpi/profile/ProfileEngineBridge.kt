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

        // Android · strategies are a compatibility/diagnostic family. When one is
        // assigned to an active TCP/TLS BYPASS profile, execute it directly rather
        // than wrapping it in ProfileCompiler groups. This gives us a one-to-one
        // comparison with ByeByeDPI and tells us whether profile compilation itself
        // is breaking otherwise valid ByeDPI commands.
        val directAndroidProfile = set.profiles.firstOrNull { profile ->
            profile.enabled &&
                profile.action == ProfileAction.BYPASS &&
                profile.protocol != ProfileProtocol.UDP_QUIC &&
                profile.strategyName?.startsWith("Android ·") == true &&
                !profile.strategyCommand.isNullOrBlank()
        }

        if (directAndroidProfile != null) {
            ByeDpiConfigStore.setManualStrategy(
                context = context,
                command = ByeDpiRuntimeStore.prepareNativeCommand(
                    command = directAndroidProfile.strategyCommand.orEmpty(),
                    addUdpFallback = true,
                ),
                name = "Native · ${directAndroidProfile.strategyName}",
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
