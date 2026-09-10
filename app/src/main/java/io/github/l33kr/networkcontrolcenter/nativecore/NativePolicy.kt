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
 * For UDP/443 there is one deliberate alpha compatibility fallback: if the
 * destination is known only as an IP (for example because the app used DoH/DoT)
 * and the active set contains an enabled UDP/QUIC BYPASS profile, the flow is
 * marked for bypass. NativeDpiProxy then suppresses that UDP/443 packet so the
 * client can retry over TCP/TLS, where our stream transformations are available.
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
                if (domains.isEmpty()) continue
                domains.firstOrNull { domainMatches(normalizedHost, it) } ?: continue
            } else {
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

        // Some modern apps resolve through DoH/DoT, so hev can hand the UDP relay
        // only a numeric destination. In that case passive DNS correlation has no
        // hostname to match. If the user explicitly has an enabled UDP/QUIC BYPASS
        // profile, treat an unresolved UDP destination as a candidate for the
        // QUIC->TCP compatibility fallback. NativeDpiProxy applies this decision
        // only on destination port 443; ordinary UDP/DNS/voice traffic is untouched.
        if (
            transport == NativeTransport.UDP &&
            normalizedHost == null &&
            set.profiles.any { profile ->
                profile.enabled &&
                    profile.action == ProfileAction.BYPASS &&
                    profile.protocol == ProfileProtocol.UDP_QUIC
            }
        ) {
            return NativePolicyDecision(
                technique = NativeTechnique.PASS,
                profileName = "QUIC compatibility",
                strategyTitle = "Force TCP for unresolved UDP/443",
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
