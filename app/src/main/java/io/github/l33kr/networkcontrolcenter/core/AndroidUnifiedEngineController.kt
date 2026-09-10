package io.github.l33kr.networkcontrolcenter.core

import android.content.Context
import io.github.l33kr.networkcontrolcenter.tgws.TgWsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AndroidUnifiedEngineController(context: Context) : UnifiedEngineController {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(EngineState())

    override val state: StateFlow<EngineState> = mutableState.asStateFlow()

    init {
        scope.launch {
            TgWsController.status.collectLatest { status ->
                mutableState.value = mutableState.value.copy(tgWs = status)
            }
        }
    }

    override suspend fun startByeDpi() {
        // The VPN engine is connected in the next integration step.
        // Keeping the state explicit prevents the UI from claiming it is running.
        mutableState.value = mutableState.value.copy(byeDpi = EngineStatus.FAILED)
    }

    override suspend fun stopByeDpi() {
        mutableState.value = mutableState.value.copy(byeDpi = EngineStatus.STOPPED)
    }

    override suspend fun startTgWs() {
        mutableState.value = mutableState.value.copy(tgWs = EngineStatus.STARTING)
        TgWsController.start(appContext)
    }

    override suspend fun stopTgWs() {
        mutableState.value = mutableState.value.copy(tgWs = EngineStatus.STOPPING)
        TgWsController.stop(appContext)
    }
}
