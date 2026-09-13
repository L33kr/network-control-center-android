package io.github.l33kr.networkcontrolcenter.byedpi

/**
 * Resolver policy for the Android VPN.
 *
 * VpnService.addDnsServer() configures ordinary DNS resolvers for the TUN; this
 * is intentionally not presented as DoH. The order follows the resilient
 * resolver choices used by zapretGUI: Quad9 first, DNS.SB second, with
 * Cloudflare retained as a final compatibility fallback.
 */
object DpiDnsPolicy {
    const val DEFAULT_PRIMARY = "9.9.9.9"

    private val resilientDefaults = listOf(
        DEFAULT_PRIMARY,
        "185.222.222.222",
        "1.1.1.1",
    )

    /**
     * Preserve an explicitly configured resolver first. The legacy 1.1.1.1
     * default is treated as a default rather than as a user override so older
     * installs automatically gain the more resilient resolver order.
     */
    fun servers(configured: String): List<String> = buildList {
        val custom = configured.trim()
        if (custom.isNotBlank() && custom != "1.1.1.1") {
            add(custom)
        }
        addAll(resilientDefaults)
    }.distinct().take(3)
}
