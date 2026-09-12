package io.github.l33kr.networkcontrolcenter.byedpi

/**
 * Targeted compatibility groups placed in front of the proven strategy #16.
 *
 * The key rule here is that #16 itself remains an unchanged catch-all fallback.
 * Only traffic that can be identified as Meta by destination network and needs
 * treatment outside ordinary HTTP/TLS is intercepted by the preceding groups.
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
            // Meta UDP: QUIC plus messenger call/media traffic. Multiple short-
            // TTL fakes mirror the IPSet/UDP approach commonly used with zapret,
            // without affecting UDP traffic to unrelated networks.
            append("-Ku -j \"")
            append(":")
            append(inlineIpset)
            append("\" -a11 -t8")

            // WhatsApp also maintains non-HTTP/TLS TCP connections around 5222.
            // Limit this fallback to Meta destinations and the messenger port
            // range so normal HTTPS is still handled by the known-good #16.
            append(" -An -j \"")
            append(":")
            append(inlineIpset)
            append("\" -V 5222-5242 -d1 -o1")

            // Exact working 0.4 strategy, unchanged, for everything else.
            append(" -An ")
            append(ByeDpiStableProfile.COMMAND)
        }
    }
}
