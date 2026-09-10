package io.github.l33kr.networkcontrolcenter.nativecore

import android.content.Context
import io.github.l33kr.networkcontrolcenter.byedpi.profile.ProfileAction
import io.github.l33kr.networkcontrolcenter.byedpi.profile.ProfileProtocol
import io.github.l33kr.networkcontrolcenter.byedpi.profile.ProfileStore

enum class NativeTransport {
    TCP,
    UDP,
}

enum class NativeTechnique {
    PASS,
    TLS_RECORD_SPLIT,
    MULTI_SPLIT,
    HYBRID,
}

data class NativePolicyDecision(
    val technique: NativeTechnique,
    val profileName: String,
    val matchedDomain: String? = null,
    val strategyTitle: String? = null,
)

/**
 * Resolves the existing v2 profile/list model into actions owned by our engine.
 * The first enabled matching profile wins; an empty-domain profile is a catch-all.
 */
class NativePolicyResolver(private val context: Context) {
    fun resolve(host: String?, transport: NativeTransport): NativePolicyDecision {
        val normalizedHost = normalizeHost(host)
        val set = ProfileStore.activeSet(context)

        for (profile in set.profiles) {
            if (!profile.enabled || !protocolMatches(profile.protocol, transport)) continue
            val domains = ProfileStore.resolveDomains(context, profile.domainListIds)
            val match = if (domains.isEmpty()) null else domains.firstOrNull { domainMatches(normalizedHost, it) }
            if (domains.isNotEmpty() && match == null) continue

            if (profile.action == ProfileAction.PASS) {
                return NativePolicyDecision(
                    technique = NativeTechnique.PASS,
                    profileName = profile.name,
                    matchedDomain = match,
                    strategyTitle = "PASS",
                )
            }

            val nativePreset = NativeStrategies.fromCommand(profile.strategyCommand)
            val technique = when (transport) {
                // UDP/QUIC is intentionally transparent in alpha 1. It remains
                // functional while the dedicated QUIC manipulator is developed.
                NativeTransport.UDP -> NativeTechnique.PASS
                NativeTransport.TCP -> nativePreset?.technique ?: NativeTechnique.HYBRID
            }
            return NativePolicyDecision(
                technique = technique,
                profileName = profile.name,
                matchedDomain = match,
                strategyTitle = nativePreset?.title ?: "Auto / Hybrid",
            )
        }

        return NativePolicyDecision(
            technique = NativeTechnique.PASS,
            profileName = "Остальной трафик",
            strategyTitle = "PASS",
        )
    }

    private fun protocolMatches(protocol: ProfileProtocol, transport: NativeTransport): Boolean = when (protocol) {
        ProfileProtocol.ANY -> true
        ProfileProtocol.TCP_TLS -> transport == NativeTransport.TCP
        ProfileProtocol.UDP_QUIC -> transport == NativeTransport.UDP
    }

    private fun normalizeHost(host: String?): String? = host
        ?.trim()
        ?.lowercase()
        ?.removePrefix("*.")
        ?.trim('.')
        ?.takeIf { it.isNotBlank() }

    private fun domainMatches(host: String?, domain: String): Boolean {
        if (host == null) return false
        val normalizedDomain = normalizeHost(domain) ?: return false
        return host == normalizedDomain || host.endsWith(".$normalizedDomain")
    }
}
