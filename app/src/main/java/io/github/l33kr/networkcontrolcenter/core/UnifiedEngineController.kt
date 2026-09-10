package io.github.l33kr.networkcontrolcenter.core

import kotlinx.coroutines.flow.StateFlow

interface UnifiedEngineController {
    val state: StateFlow<EngineState>

    suspend fun startByeDpi()
    suspend fun stopByeDpi()
    suspend fun startTgWs()
    suspend fun stopTgWs()

    suspend fun startAll() {
        startByeDpi()
        startTgWs()
    }

    suspend fun stopAll() {
        stopTgWs()
        stopByeDpi()
    }
}
