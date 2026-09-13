package io.github.l33kr.networkcontrolcenter.diagnostics

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.Locale

internal class TruncatedDnsResponse : IOException("DNS: ответ требует TCP")

/** Minimal bounded DNS A codec. Never invokes the system name resolver. */
internal object DnsPacket {
    fun query(host: String, id: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeShort(id)
            out.writeShort(0x0100)
            out.writeShort(1)
            repeat(3) { out.writeShort(0) }
            host.split('.').forEach { label ->
                require(label.length in 1..63 && label.all { it.code in 33..126 })
                out.writeByte(label.length)
                out.writeBytes(label)
            }
            out.writeByte(0)
            out.writeShort(1)
            out.writeShort(1)
        }
        return bytes.toByteArray()
    }

    fun addresses(packet: ByteArray, host: String, id: Int): List<InetAddress> {
        val reader = Reader(packet)
        if (reader.u16() != id) throw IOException("DNS: чужой идентификатор ответа")
        val flags = reader.u16()
        if (flags and 0xf800 != 0x8000) throw IOException("DNS: неверный тип ответа")
        val questions = reader.u16()
        val answers = reader.u16()
        reader.u16()
        reader.u16()
        if (questions != 1 || answers > 128) throw IOException("DNS: неверное число записей")
        val expected = host.lowercase(Locale.ROOT)
        if (reader.name() != expected || reader.u16() != 1 || reader.u16() != 1) {
            throw IOException("DNS: ответ на другой запрос")
        }
        if (flags and 0x0200 != 0) throw TruncatedDnsResponse()
        if (flags and 0x000f != 0) throw IOException("DNS: код ошибки ${flags and 0x000f}")
        val aliases = mutableMapOf<String, String>()
        val records = mutableListOf<Pair<String, ByteArray>>()
        repeat(answers) {
            val name = reader.name()
            val type = reader.u16()
            val clazz = reader.u16()
            reader.bytes(4) // TTL
            val length = reader.u16()
            val end = reader.position + length
            if (end > packet.size) throw IOException("DNS: обрезанная запись")
            when {
                clazz == 1 && type == 1 && length == 4 -> records += name to reader.bytes(4)
                clazz == 1 && type == 5 -> {
                    aliases[name] = reader.name()
                    if (reader.position != end) throw IOException("DNS: неверная CNAME-запись")
                }
            }
            reader.position = end
        }
        val allowed = mutableSetOf(expected)
        var current = expected
        repeat(16) {
            val next = aliases[current] ?: return@repeat
            if (allowed.add(next)) current = next
        }
        return records.filter { it.first in allowed }
            .map { InetAddress.getByAddress(it.second) }.distinct()
            .ifEmpty { throw IOException("DNS: нет IPv4-адреса для $host") }
    }

    private class Reader(private val data: ByteArray) {
        var position = 0
        fun u16(): Int {
            val b = bytes(2)
            return ((b[0].toInt() and 255) shl 8) or (b[1].toInt() and 255)
        }
        fun bytes(count: Int): ByteArray {
            if (count < 0 || position + count > data.size) throw IOException("DNS: обрезанный пакет")
            return data.copyOfRange(position, position + count).also { position += count }
        }
        fun name(): String {
            var cursor = position
            var resume = -1
            var jumps = 0
            val labels = mutableListOf<String>()
            while (true) {
                if (cursor >= data.size || ++jumps > 128) throw IOException("DNS: повреждённое имя")
                val length = data[cursor++].toInt() and 255
                if (length == 0) break
                if (length and 0xc0 == 0xc0) {
                    if (cursor >= data.size) throw IOException("DNS: повреждённый указатель")
                    val offset = ((length and 0x3f) shl 8) or (data[cursor++].toInt() and 255)
                    if (resume < 0) resume = cursor
                    cursor = offset
                } else {
                    if (length > 63 || cursor + length > data.size) throw IOException("DNS: повреждённая метка")
                    labels += String(data, cursor, length, Charsets.US_ASCII)
                    cursor += length
                    if (labels.sumOf { it.length + 1 } > 254) throw IOException("DNS: слишком длинное имя")
                }
            }
            position = if (resume < 0) cursor else resume
            return labels.joinToString(".").lowercase(Locale.ROOT)
        }
    }
}
