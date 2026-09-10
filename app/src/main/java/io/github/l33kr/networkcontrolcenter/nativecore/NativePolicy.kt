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
    val shouldBypass: Boolean = false,
)

/**
 * Resolves the existing v2 profile/list model into actions owned by our engine.
 *
 * Important matching rule:
 * - a profile with NO list references is a catch-all;
 * - a profile that references one or more lists matches only domains contained
 *   in those lists;
 * - referenced-but-empty lists match nothing.
 *
 * The last rule is essential for optional PASS/ignore lists: an empty ignore
 * list must never turn into a global PASS rule.
 */
class NativePolicyResolver(private val context: Context) {
    fun resolve(host: String?, transport: NativeTransport): NativePolicyDecision {
        val normalizedHost = normalizeHost(host)
        val set = ProfileStore.activeSet(context)

        for (profile in set.profiles) {
            if (!profile.enabled || !protocolMatches(profile.protocol, transport)) continue

            val hasDomainSelectors = profile.domainListIds.isNotEmpty()
            val domains = ProfileStore.resolveDomains(context, profile.domainListIds)

            val match = if (hasDomainSelectors) {
                // A referenced but empty list is intentionally inert. It is NOT
                // a catch-all. This prevents an empty PASS list from bypassing
                // every connection before real BYPASS profiles are evaluated.
                if (domains.isEmpty()) continue
                domains.firstOrNull { domainMatches(normalizedHost, it) } ?: continue
            } else {
                // Only a profile with no list references at all is catch-all.
                null
            }

            if (profile.action == ProfileAction.PASS) {
                return NativePolicyDecision(
                    technique = NativeTechnique.PASS,
                    profileName = profile.name,
                    matchedDomain = match,
                    strategyTitle = "PASS",
                    shouldBypass = false,
                )
            }

            val nativePreset = NativeStrategies.fromCommand(profile.strategyCommand)
            val technique = when (transport) {
                // Dedicated QUIC manipulation is separate from TCP techniques.
                NativeTransport.UDP -> NativeTechnique.PASS
                NativeTransport.TCP -> nativePreset?.technique ?: NativeTechnique.HYBRID
            }
            return NativePolicyDecision(
                technique = technique,
                profileName = profile.name,
                matchedDomain = match,
                strategyTitle = nativePreset?.title ?: "Auto / Hybrid",
                shouldBypass = true,
            )
        }

        return NativePolicyDecision(
            technique = NativeTechnique.PASS,
            profileName = "Остальной трафик",
            strategyTitle = "PASS",
            shouldBypass = false,
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
