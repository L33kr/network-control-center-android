package io.github.l33kr.networkcontrolcenter.byedpi

/**
 * Extra compatibility groups for Meta traffic while preserving the proven #16
 * TLS/HTTP behaviour. This is intentionally applied only to the known working
 * strategy, so selecting another catalog entry still runs it unchanged.
 *
 * The IP ranges cover the commonly used Meta / WhatsApp / Instagram networks.
 * UDP traffic to these networks gets stronger fake-packet desync, while raw
 * non-HTTP/TLS TCP traffic gets a generic disorder + OOB fallback. A regular
 * UDP fallback is kept for non-Meta traffic so the original #16 behaviour is
 * not weakened for QUIC-heavy services.
 */
object MetaCompatibility {
    private val metaNetworks = listOf(
        "31.13.24.0/17",
        "45.64.40.0/22",
        "57.141.0.0/20",
        "57.144.0.0/14",
        "66.220.144.0/20",
        "69.63.176.0/20",
        "69.171.224.0/19",
        "74.119.76.0/22",
        "102.132.96.0/20",
        "103.4.96.0/22",
        "129.134.0.0/17",
        "157.240.0.0/17",
        "157.240.192.0/18",
        "163.70.128.0/17",
        "173.252.64.0/19",
        "173.252.96.0/19",
        "179.60.192.0/22",
        "185.60.216.0/22",
        "204.15.20.0/22",
        "2a03:2880::/32",
        "2620:0:1c00::/40",
    )

    private val inlineIpset: String = metaNetworks.joinToString(" ")

    fun enhance(command: String): String {
        if (command.trim() != ByeDpiStableProfile.COMMAND) return command

        return buildString {
            // Group 1: keep strategy #16 byte-for-byte for TLS/HTTP, only adding
            // an L7 selector so UDP/raw TCP can fall through to dedicated groups.
            append("-Kt,h ")
            append(ByeDpiStableProfile.COMMAND)

            // Group 2: stronger UDP fake for Meta ranges. This also covers QUIC
            // and the UDP flows used by calls/media without touching unrelated UDP.
            append(" -An -Ku -j \"")
            append(":")
            append(inlineIpset)
            append("\" -a11 -t8")

            // Group 3: retain the original lightweight UDP fake for everything else.
            append(" -An -Ku -a1")

            // Group 4: raw Meta TCP that is neither HTTP nor TLS (messenger traffic).
            append(" -An -j \"")
            append(":")
            append(inlineIpset)
            append("\" -d1 -o1")

            // Final no-op group for traffic that matched none of the selectors.
            append(" -An")
        }
    }
}
