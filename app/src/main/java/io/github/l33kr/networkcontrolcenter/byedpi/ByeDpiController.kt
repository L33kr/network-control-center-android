package io.github.l33kr.networkcontrolcenter.byedpi

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object ByeDpiController {
    val status = ByeDpiVpnService.status

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
}
