package io.github.l33kr.networkcontrolcenter.byedpi

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object ByeDpiController {
    val status = ByeDpiVpnService.status
    val activeProfile = ByeDpiVpnService.activeProfile
    val networkLabel = ByeDpiVpnService.networkLabel
    val ipv6Active = ByeDpiVpnService.ipv6Active
    val lastError = ByeDpiVpnService.lastError

    fun start(context: Context) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, ByeDpiVpnService::class.java).setAction(ByeDpiVpnService.ACTION_START),
        )
    }

    fun stop(context: Context) {
        context.startService(
            Intent(context, ByeDpiVpnService::class.java).setAction(ByeDpiVpnService.ACTION_STOP),
        )
    }

    fun selectedMode(context: Context): ByeDpiMode = ByeDpiConfigStore.load(context).mode

    fun setMode(context: Context, mode: ByeDpiMode) {
        ByeDpiConfigStore.setMode(context, mode)
    }
}
