package io.github.l33kr.networkcontrolcenter.byedpi

import org.junit.Assert.*
import org.junit.Test

class StableProfileTest {
    private val exact16 = "-f1 -t5 -n {sni} -q3+h -Qr -f2 -q1 -r1+s -t15 -q1 -o2 -a1"

    @Test fun pure16IsTheExactWorkingCommandWithoutHiddenGroups() {
        assertEquals(exact16, ByeDpiStableProfile.COMMAND)
        val config = ByeDpiConfig(domainFilterMode = DomainFilterMode.ONLY_LISTED, domains = "example.org")
        assertFalse(config.metaCompatibility)
        assertArrayEquals(
            (listOf("ciadpi", "--ip", "127.0.0.1", "--port", "1080") +
                exact16.replace("{sni}", "google.com").split(' ')).toTypedArray(),
            config.toArgs(),
        )
    }

    @Test fun metaIsOptionalAndDoesNotRewrite16OrOtherStrategies() {
        val wrapped = ByeDpiConfig(metaCompatibility = true).toArgs().drop(5)
        assertEquals("-Ku", wrapped.first())
        assertEquals(2, wrapped.count { it == "-An" })
        val expected = exact16.replace("{sni}", "google.com").split(' ')
        assertEquals(expected, wrapped.takeLast(expected.size))
        val alternative = ByeDpiConfig(command = "-d1 -An", metaCompatibility = true)
        assertEquals(listOf("-d1", "-An"), alternative.toArgs().drop(5))
    }

    @Test fun sniCannotInjectExtraOptions() {
        val args = ByeDpiConfig(sni = "google.com -U --port 9999").toArgs().toList()
        assertEquals("google.com -U --port 9999", args[args.indexOf("-n") + 1])
        assertFalse(args.contains("-U"))
        assertEquals(1, args.count { it == "--port" })
    }

    @Test fun dnsCatalogKeepsOldSelectionAndProvidersSeparate() {
        assertEquals(12, DpiDnsPolicy.providers.size)
        assertEquals("Xbox DNS (old)", DpiDnsPolicy.providerFor("176.99.11.77")?.name)
        assertEquals(listOf("111.88.96.50", "111.88.96.51"), DpiDnsPolicy.servers("111.88.96.50"))
        assertEquals(listOf("87.228.47.200", "87.228.47.201"), DpiDnsPolicy.servers("87.228.47.201"))
        assertEquals(listOf("176.99.11.77", "80.78.247.254"), DpiDnsPolicy.servers("176.99.11.77"))
        assertEquals(listOf("192.0.2.53"), DpiDnsPolicy.servers("192.0.2.53"))
        assertEquals(listOf("194.180.189.33"), DpiDnsPolicy.servers("194.180.189.33"))
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), DpiDnsPolicy.servers("1.1.1.1"))
    }
}
