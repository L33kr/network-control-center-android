package io.github.l33kr.networkcontrolcenter.byedpi

/** DNS providers exposed by the current ZapretGUI family. */
data class DnsProvider(
    val name: String,
    val category: String,
    val ipv4: List<String>,
    val ipv6: List<String> = emptyList(),
    val description: String,
    val warning: String? = null,
) {
    val primary: String
        get() = ipv4.firstOrNull() ?: ipv6.first()
}

object DpiDnsPolicy {
    const val DEFAULT_PRIMARY = "9.9.9.9"

    val providers: List<DnsProvider> = listOf(
        DnsProvider(
            name = "Cloudflare",
            category = "Популярные",
            ipv4 = listOf("1.1.1.1", "1.0.0.1"),
            ipv6 = listOf("2606:4700:4700::1111", "2606:4700:4700::1001"),
            description = "Быстрый и приватный",
        ),
        DnsProvider(
            name = "Google DNS",
            category = "Популярные",
            ipv4 = listOf("8.8.8.8", "8.8.4.4"),
            ipv6 = listOf("2001:4860:4860::8888", "2001:4860:4860::8844"),
            description = "Надёжный публичный DNS",
        ),
        DnsProvider(
            name = "Dns.SB",
            category = "Популярные",
            ipv4 = listOf("185.222.222.222", "45.11.45.11"),
            ipv6 = listOf("2a09::", "2a11::"),
            description = "Публичный DNS без контентной фильтрации",
        ),
        DnsProvider(
            name = "Quad9",
            category = "Безопасные",
            ipv4 = listOf("9.9.9.9", "149.112.112.112"),
            ipv6 = listOf("2620:fe::fe", "2620:fe::9"),
            description = "Защита от вредоносных доменов",
        ),
        DnsProvider(
            name = "AdGuard",
            category = "Безопасные",
            ipv4 = listOf("94.140.14.14", "94.140.15.15"),
            ipv6 = listOf("2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff"),
            description = "DNS с блокировкой рекламы и трекеров",
        ),
        DnsProvider(
            name = "OpenDNS",
            category = "Безопасные",
            ipv4 = listOf("208.67.222.222", "208.67.220.220"),
            ipv6 = listOf("2620:119:35::35", "2620:119:53::53"),
            description = "Публичный DNS Cisco",
        ),
        DnsProvider(
            name = "dnsdoh.art",
            category = "Безопасные",
            ipv4 = listOf("194.180.189.33"),
            description = "Приватный DNS",
        ),
        DnsProvider(
            name = "Xbox DNS",
            category = "Для ИИ",
            ipv4 = listOf("176.99.11.77", "80.78.247.254"),
            description = "DNS для доступа к отдельным сервисам",
            warning = "Может блокироваться или работать нестабильно у некоторых операторов",
        ),
        DnsProvider(
            name = "Comss DNS",
            category = "Для ИИ",
            ipv4 = listOf("83.220.169.155", "212.109.195.93"),
            description = "DNS для доступа к отдельным сервисам",
            warning = "Может блокироваться или работать нестабильно у некоторых операторов",
        ),
        DnsProvider(
            name = "dns.malw.link",
            category = "Для ИИ",
            ipv4 = listOf("84.21.189.133", "64.188.98.242"),
            ipv6 = listOf("2a12:bec4:1460:d5::2", "2a01:ecc0:2c1:2::2"),
            description = "Альтернативный DNS для отдельных сервисов",
        ),
    )

    fun providerFor(address: String): DnsProvider? {
        val normalized = address.trim()
        return providers.firstOrNull { provider ->
            normalized in provider.ipv4 || normalized in provider.ipv6
        }
    }

    /**
     * A selected provider contributes its own resolver pair. IPv6 addresses are
     * only installed when the VPN actually routes IPv6. A manually entered DNS
     * is kept as-is instead of silently mixing providers.
     */
    fun servers(configured: String, includeIpv6: Boolean = false): List<String> {
        val custom = configured.trim().ifBlank { DEFAULT_PRIMARY }
        val provider = providerFor(custom)
        if (provider == null) return listOf(custom)

        return buildList {
            addAll(provider.ipv4)
            if (includeIpv6) addAll(provider.ipv6)
        }.distinct()
    }
}
