package io.github.l33kr.networkcontrolcenter.nativecore

/** Lightweight TLS ClientHello parser used by the native stream engine. */
data class TlsClientHelloInfo(
    val sni: String?,
    val sniStart: Int?,
    val sniEndExclusive: Int?,
    val recordStart: Int,
    val recordPayloadStart: Int,
    val recordPayloadEndExclusive: Int,
)

sealed interface TlsParseResult {
    data object NotTls : TlsParseResult
    data class NeedMore(val minimumBytes: Int) : TlsParseResult
    data class ClientHello(val info: TlsClientHelloInfo) : TlsParseResult
}

object TlsClientHelloParser {
    private const val TLS_HANDSHAKE = 22
    private const val CLIENT_HELLO = 1
    private const val EXT_SERVER_NAME = 0

    fun parse(data: ByteArray, length: Int = data.size): TlsParseResult {
        if (length < 5) return TlsParseResult.NeedMore(5)
        if (u8(data[0]) != TLS_HANDSHAKE) return TlsParseResult.NotTls

        val major = u8(data[1])
        if (major != 3) return TlsParseResult.NotTls

        val recordLength = u16(data, 3)
        val recordEnd = 5 + recordLength
        if (recordLength < 4) return TlsParseResult.NotTls
        if (length < recordEnd) return TlsParseResult.NeedMore(recordEnd)

        if (u8(data[5]) != CLIENT_HELLO) return TlsParseResult.NotTls
        val helloLength = u24(data, 6)
        val helloEnd = 9 + helloLength
        // The common case is a ClientHello contained in one TLS record. We do not
        // rewrite a fragmented ClientHello until the multi-record parser is added.
        if (helloEnd > recordEnd) {
            return TlsParseResult.ClientHello(
                TlsClientHelloInfo(
                    sni = null,
                    sniStart = null,
                    sniEndExclusive = null,
                    recordStart = 0,
                    recordPayloadStart = 5,
                    recordPayloadEndExclusive = recordEnd,
                ),
            )
        }

        var p = 9
        if (p + 34 > helloEnd) return TlsParseResult.NotTls
        p += 2 // legacy_version
        p += 32 // random

        if (p >= helloEnd) return TlsParseResult.NotTls
        val sessionIdLength = u8(data[p])
        p += 1 + sessionIdLength
        if (p + 2 > helloEnd) return TlsParseResult.NotTls

        val cipherLength = u16(data, p)
        p += 2 + cipherLength
        if (p >= helloEnd) return TlsParseResult.NotTls

        val compressionLength = u8(data[p])
        p += 1 + compressionLength
        if (p == helloEnd) {
            return TlsParseResult.ClientHello(
                TlsClientHelloInfo(null, null, null, 0, 5, recordEnd),
            )
        }
        if (p + 2 > helloEnd) return TlsParseResult.NotTls

        val extensionsLength = u16(data, p)
        p += 2
        val extensionsEnd = minOf(helloEnd, p + extensionsLength)

        while (p + 4 <= extensionsEnd) {
            val type = u16(data, p)
            val extLength = u16(data, p + 2)
            val extData = p + 4
            val extEnd = extData + extLength
            if (extEnd > extensionsEnd) break

            if (type == EXT_SERVER_NAME && extLength >= 5) {
                var q = extData
                if (q + 2 > extEnd) break
                val listLength = u16(data, q)
                q += 2
                val listEnd = minOf(extEnd, q + listLength)
                while (q + 3 <= listEnd) {
                    val nameType = u8(data[q])
                    val nameLength = u16(data, q + 1)
                    val nameStart = q + 3
                    val nameEnd = nameStart + nameLength
                    if (nameEnd > listEnd) break
                    if (nameType == 0 && nameLength > 0) {
                        val host = runCatching {
                            data.copyOfRange(nameStart, nameEnd).toString(Charsets.US_ASCII)
                                .trim().lowercase()
                        }.getOrNull()
                        return TlsParseResult.ClientHello(
                            TlsClientHelloInfo(
                                sni = host,
                                sniStart = nameStart,
                                sniEndExclusive = nameEnd,
                                recordStart = 0,
                                recordPayloadStart = 5,
                                recordPayloadEndExclusive = recordEnd,
                            ),
                        )
                    }
                    q = nameEnd
                }
            }
            p = extEnd
        }

        return TlsParseResult.ClientHello(
            TlsClientHelloInfo(null, null, null, 0, 5, recordEnd),
        )
    }

    private fun u8(value: Byte): Int = value.toInt() and 0xff
    private fun u16(data: ByteArray, offset: Int): Int =
        (u8(data[offset]) shl 8) or u8(data[offset + 1])

    private fun u24(data: ByteArray, offset: Int): Int =
        (u8(data[offset]) shl 16) or (u8(data[offset + 1]) shl 8) or u8(data[offset + 2])
}
