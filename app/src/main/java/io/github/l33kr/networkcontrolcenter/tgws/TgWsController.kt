package io.github.l33kr.networkcontrolcenter.tgws

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat

object TgWsController {
    val status = TgWsProxyService.status

    fun start(context: Context) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, TgWsProxyService::class.java).setAction(TgWsProxyService.ACTION_START),
        )
    }

    fun stop(context: Context) {
        context.startService(
            Intent(context, TgWsProxyService::class.java).setAction(TgWsProxyService.ACTION_STOP),
        )
    }

    fun openTelegramProxy(context: Context): Boolean {
        val config = TgWsConfigStore.load(context)
        val secret = runCatching { TgWsNative.secretWithPrefix() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: TgWsConfigStore.telegramSecret(context)

        val uri = Uri.Builder()
            .scheme("tg")
            .authority("proxy")
            .appendQueryParameter("server", "127.0.0.1")
            .appendQueryParameter("port", config.port.toString())
            .appendQueryParameter("secret", secret)
            .build()

        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
    }
}
