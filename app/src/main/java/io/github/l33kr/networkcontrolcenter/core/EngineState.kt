package io.github.l33kr.networkcontrolcenter.core

enum class EngineStatus {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    FAILED,
}

data class EngineState(
    val byeDpi: EngineStatus = EngineStatus.STOPPED,
    val tgWs: EngineStatus = EngineStatus.STOPPED,
)
