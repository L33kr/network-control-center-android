package io.github.l33kr.networkcontrolcenter.byedpi

class ByeDpiProxy {
    companion object {
        init {
            System.loadLibrary("byedpi")
        }
    }

    fun start(config: ByeDpiConfig): Int = jniStartProxy(config.toArgs())

    fun stop(): Int = jniStopProxy()

    fun forceClose(): Int = jniForceClose()

    private external fun jniStartProxy(args: Array<String>): Int
    private external fun jniStopProxy(): Int
    private external fun jniForceClose(): Int
}
