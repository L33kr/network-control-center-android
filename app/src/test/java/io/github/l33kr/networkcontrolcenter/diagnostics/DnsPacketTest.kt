package io.github.l33kr.networkcontrolcenter.diagnostics

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class DnsPacketTest {
    private val query = DnsPacket.query("example.com", 1234)

    private fun response(flags: Int = 0x8180): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write(query.copyOf().apply {
                ByteBuffer.wrap(this).putShort(2, flags.toShort()).putShort(6, 1)
            })
            out.writeShort(0xc00c)
            out.writeShort(1)
            out.writeShort(1)
            out.writeInt(60)
            out.writeShort(4)
            out.write(byteArrayOf(192.toByte(), 0, 2, 10))
        }
        return bytes.toByteArray()
    }

    @Test fun parsesCompressedAnswerAndKeepsAddressNumeric() {
        assertEquals("192.0.2.10", DnsPacket.addresses(response(), "example.com", 1234).single().hostAddress)
    }

    @Test fun rejectsAnswersForAnotherQuery() {
        assertThrows(IOException::class.java) { DnsPacket.addresses(response(), "example.com", 1235) }
        assertThrows(IOException::class.java) { DnsPacket.addresses(response(), "other.com", 1234) }
    }

    @Test fun rejectsCyclicCompressionAndTruncatedRecords() {
        val cyclic = response().apply { this[12] = 0xc0.toByte(); this[13] = 12 }
        assertThrows(IOException::class.java) { DnsPacket.addresses(cyclic, "example.com", 1234) }
        assertThrows(IOException::class.java) { DnsPacket.addresses(response().dropLast(2).toByteArray(), "example.com", 1234) }
    }

    @Test fun truncationRequiresTcpAndNxdomainIsNotSuccess() {
        assertThrows(TruncatedDnsResponse::class.java) { DnsPacket.addresses(response(0x8380), "example.com", 1234) }
        assertThrows(IOException::class.java) { DnsPacket.addresses(response(0x8183), "example.com", 1234) }
    }

    @Test fun validatesDnsAndSniWithoutResolvingNames() {
        assertTrue(ConnectionDiagnostics.validDns("1.1.1.1"))
        assertTrue(ConnectionDiagnostics.validDns("2606:4700:4700::1111"))
        assertFalse(ConnectionDiagnostics.validDns("dns.google"))
        assertFalse(ConnectionDiagnostics.validDns("https://dns.google/dns-query"))
        assertFalse(ConnectionDiagnostics.validDns("0.0.0.0"))
        assertFalse(ConnectionDiagnostics.validDns("999.1.1.1"))
        assertTrue(ConnectionDiagnostics.validSni("google.com"))
        assertTrue(ConnectionDiagnostics.validSni("??##*.example.org"))
        assertFalse(ConnectionDiagnostics.validSni("google.com -U"))
    }

    @Test fun stunRequiresMatchingTransactionAndValidEnvelope() {
        val id = ByteArray(12) { it.toByte() }
        val good = ByteBuffer.allocate(20).putShort(0x0101).putShort(0).putInt(0x2112a442).put(id).array()
        assertTrue(ConnectionDiagnostics.validStunResponse(good, id))
        assertFalse(ConnectionDiagnostics.validStunResponse(good, ByteArray(12)))
        assertFalse(ConnectionDiagnostics.validStunResponse(good.dropLast(1).toByteArray(), id))
        assertFalse(ConnectionDiagnostics.validStunResponse(good.copyOf().apply { this[3] = 4 }, id))
    }

    @Test fun tlsKeepsHostnameVerificationAndSni() {
        val parameters = ConnectionDiagnostics.tlsParameters("www.instagram.com")
        assertEquals("HTTPS", parameters.endpointIdentificationAlgorithm)
        assertEquals("www.instagram.com", (parameters.serverNames.single() as javax.net.ssl.SNIHostName).asciiName)
    }
}
