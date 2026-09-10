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
 * Resolves the profile/list model into actions owned by Native Engine.
 *
 * Only native:// commands are understood. Old ByeDPI argument strings are never
 * interpreted by this resolver; they simply fall back to our Native default.
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
                    strategyTitle = NativeStrategies.PASS.title,
                )
            }

            // Alpha 1 keeps UDP transparent until the dedicated QUIC engine is
            // ready. Breaking UDP globally would be worse than not modifying it.
            if (transport == NativeTransport.UDP) {
                return NativePolicyDecision(
                    technique = NativeTechnique.PASS,
                    profileName = profile.name,
                    matchedDomain = match,
                    strategyTitle = "UDP passthrough",
                )
            }

            val strategy = NativeStrategies.fromCommand(profile.strategyCommand)
                ?: NativePolicyStore.strategyForProfile(context, profile.id)
                ?: NativePolicyStore.defaultStrategy(context)

            return NativePolicyDecision(
                technique = strategy.technique,
                profileName = profile.name,
                matchedDomain = match,
                strategyTitle = strategy.title,
            )
        }

        return NativePolicyDecision(
            technique = NativeTechnique.PASS,
            profileName = "Остальной трафик",
            strategyTitle = NativeStrategies.PASS.title,
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
