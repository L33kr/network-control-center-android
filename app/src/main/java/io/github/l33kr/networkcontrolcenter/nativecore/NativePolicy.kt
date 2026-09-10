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
)

/**
 * Resolves the existing v2 profile/list model into native-engine actions.
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
                )
            }

            // Native plans deliberately do not interpret ByeDPI command strings.
            // They are selected from protocol-safe primitives that we own.
            val technique = when (transport) {
                NativeTransport.UDP -> NativeTechnique.PASS // UDP/QUIC relay is transparent in alpha 1.
                NativeTransport.TCP -> when {
                    profile.strategyName?.contains("record", ignoreCase = true) == true -> NativeTechnique.TLS_RECORD_SPLIT
                    profile.strategyName?.contains("split", ignoreCase = true) == true -> NativeTechnique.MULTI_SPLIT
                    else -> NativeTechnique.HYBRID
                }
            }
            return NativePolicyDecision(
                technique = technique,
                profileName = profile.name,
                matchedDomain = match,
            )
        }

        return NativePolicyDecision(NativeTechnique.PASS, "Остальной трафик")
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
