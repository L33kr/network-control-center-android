package io.github.l33kr.networkcontrolcenter.nativecore

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

object NativeRuntime {
    private val active = AtomicInteger(0)
    private val total = AtomicInteger(0)
    private val modified = AtomicInteger(0)
    private val tls = AtomicInteger(0)
    private val udp = AtomicInteger(0)
    private val quicFallback = AtomicInteger(0)

    private val _snapshot = MutableStateFlow(NativeRuntimeSnapshot())
    val snapshot: StateFlow<NativeRuntimeSnapshot> = _snapshot.asStateFlow()

    fun reset() {
        active.set(0)
        total.set(0)
        modified.set(0)
        tls.set(0)
        udp.set(0)
        quicFallback.set(0)
        publish()
    }

    fun flowOpened(host: String?) {
        active.incrementAndGet()
        total.incrementAndGet()
        publish(lastHost = host)
    }

    fun flowClosed() {
        active.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
        publish()
    }

    fun tlsSeen(host: String?) {
        tls.incrementAndGet()
        publish(lastHost = host)
    }

    fun transformed(host: String?, technique: NativeTechnique) {
        modified.incrementAndGet()
        publish(lastHost = host, lastTechnique = technique.name)
    }

    fun udpPacket(host: String?) {
        udp.incrementAndGet()
        publish(lastHost = host)
    }

    fun quicFallback(host: String?) {
        quicFallback.incrementAndGet()
        publish(lastHost = host, lastTechnique = "QUIC_TO_TCP")
    }

    fun error(message: String) {
        publish(lastError = message)
    }

    private fun publish(
        lastHost: String? = _snapshot.value.lastHost,
        lastTechnique: String? = _snapshot.value.lastTechnique,
        lastError: String? = _snapshot.value.lastError,
    ) {
        _snapshot.value = NativeRuntimeSnapshot(
            activeFlows = active.get(),
            totalFlows = total.get(),
            transformedFlows = modified.get(),
            tlsFlows = tls.get(),
            udpPackets = udp.get(),
            quicFallbacks = quicFallback.get(),
            lastHost = lastHost,
            lastTechnique = lastTechnique,
            lastError = lastError,
        )
    }
}

data class NativeRuntimeSnapshot(
    val activeFlows: Int = 0,
    val totalFlows: Int = 0,
    val transformedFlows: Int = 0,
    val tlsFlows: Int = 0,
    val udpPackets: Int = 0,
    val quicFallbacks: Int = 0,
    val lastHost: String? = null,
    val lastTechnique: String? = null,
    val lastError: String? = null,
)
