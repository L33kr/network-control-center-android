package io.github.l33kr.networkcontrolcenter.nativecore

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Small in-memory DNS correlation cache. It never performs network requests;
 * it only observes DNS responses already passing through the local UDP relay.
 */
object NativeDnsCache {
    private data class Entry(val host: String, val expiresAtMs: Long)

    private val byAddress = ConcurrentHashMap<String, Entry>()

    fun clear() = byAddress.clear()

    fun lookup(address: InetAddress?): String? {
        val key = address?.hostAddress ?: return null
        val entry = byAddress[key] ?: return null
        if (entry.expiresAtMs < System.currentTimeMillis()) {
            byAddress.remove(key, entry)
            return null
        }
        return entry.host
    }

    fun observeResponse(packet: ByteArray) {
        runCatching { parseResponse(packet) }
    }

    private fun parseResponse(data: ByteArray) {
        if (data.size < 12) return
        val flags = u16(data, 2)
        if ((flags and 0x8000) == 0) return // not a response

        val questionCount = u16(data, 4)
        val answerCount = u16(data, 6)
        var offset = 12
        var questionHost: String? = null

        repeat(questionCount) {
            val name = readName(data, offset)
            if (questionHost == null && name.name.isNotBlank()) questionHost = normalize(name.name)
            offset = name.nextOffset
            if (offset + 4 > data.size) return
            offset += 4 // QTYPE + QCLASS
        }

        repeat(answerCount) {
            val name = readName(data, offset)
            offset = name.nextOffset
            if (offset + 10 > data.size) return

            val type = u16(data, offset)
            val klass = u16(data, offset + 2)
            val ttlSeconds = u32(data, offset + 4).coerceIn(30L, 86_400L)
            val rdLength = u16(data, offset + 8)
            offset += 10
            if (offset + rdLength > data.size) return

            if (klass == 1 && (type == 1 || type == 28)) {
                val expected = if (type == 1) 4 else 16
                if (rdLength == expected) {
                    val address = InetAddress.getByAddress(data.copyOfRange(offset, offset + rdLength))
                    val host = questionHost ?: normalize(name.name)
                    if (!host.isNullOrBlank()) {
                        byAddress[address.hostAddress] = Entry(
                            host = host,
                            expiresAtMs = System.currentTimeMillis() + ttlSeconds * 1000L,
                        )
                    }
                }
            }
            offset += rdLength
        }

        if (byAddress.size > MAX_ENTRIES) prune()
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        byAddress.entries.removeIf { it.value.expiresAtMs < now }
        if (byAddress.size <= MAX_ENTRIES) return
        byAddress.entries
            .sortedBy { it.value.expiresAtMs }
            .take(byAddress.size - MAX_ENTRIES)
            .forEach { byAddress.remove(it.key, it.value) }
    }

    private fun readName(data: ByteArray, initialOffset: Int): NameResult {
        var offset = initialOffset
        var nextOffset = initialOffset
        var jumped = false
        var jumps = 0
        val labels = mutableListOf<String>()

        while (offset < data.size && jumps < 32) {
            val length = u8(data[offset])
            if (length == 0) {
                if (!jumped) nextOffset = offset + 1
                break
            }
            if ((length and 0xC0) == 0xC0) {
                if (offset + 1 >= data.size) break
                val pointer = ((length and 0x3F) shl 8) or u8(data[offset + 1])
                if (!jumped) nextOffset = offset + 2
                if (pointer >= data.size) break
                offset = pointer
                jumped = true
                jumps++
                continue
            }
            if (length > 63 || offset + 1 + length > data.size) break
            val label = data.copyOfRange(offset + 1, offset + 1 + length)
                .toString(Charsets.US_ASCII)
            labels += label
            offset += 1 + length
            if (!jumped) nextOffset = offset
        }

        return NameResult(labels.joinToString("."), nextOffset)
    }

    private fun normalize(host: String): String = host.trim().lowercase().trim('.')
    private fun u8(value: Byte): Int = value.toInt() and 0xff
    private fun u16(data: ByteArray, offset: Int): Int =
        (u8(data[offset]) shl 8) or u8(data[offset + 1])

    private fun u32(data: ByteArray, offset: Int): Long =
        ((u8(data[offset]).toLong() shl 24) or
            (u8(data[offset + 1]).toLong() shl 16) or
            (u8(data[offset + 2]).toLong() shl 8) or
            u8(data[offset + 3]).toLong())

    private data class NameResult(val name: String, val nextOffset: Int)
    private const val MAX_ENTRIES = 2048
}
