package io.github.l33kr.networkcontrolcenter.tgws

import android.content.Context
import java.security.SecureRandom

data class TgWsConfig(
    val bindIp: String = "127.0.0.1",
    val port: Int = 1443,
    val poolSize: Int = 4,
    val cloudflareEnabled: Boolean = true,
    val cloudflareDomain: String = "",
    val workerDomains: String = "",
    val dcIps: String = "",
    val secret: String,
)

object TgWsConfigStore {
    private const val PREFS = "tg_ws_proxy"
    private const val KEY_BIND_IP = "bind_ip"
    private const val KEY_PORT = "port"
    private const val KEY_POOL_SIZE = "pool_size"
    private const val KEY_CF_ENABLED = "cf_enabled"
    private const val KEY_CF_DOMAIN = "cf_domain"
    private const val KEY_WORKER_DOMAINS = "worker_domains"
    private const val KEY_DC_IPS = "dc_ips"
    private const val KEY_SECRET = "secret"

    fun load(context: Context): TgWsConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val secret = prefs.getString(KEY_SECRET, null)
            ?.takeIf(::isValidSecret)
            ?: generateSecret().also { prefs.edit().putString(KEY_SECRET, it).apply() }

        return TgWsConfig(
            bindIp = prefs.getString(KEY_BIND_IP, "127.0.0.1") ?: "127.0.0.1",
            port = prefs.getInt(KEY_PORT, 1443).coerceIn(1, 65535),
            poolSize = prefs.getInt(KEY_POOL_SIZE, 4).coerceIn(2, 16),
            cloudflareEnabled = prefs.getBoolean(KEY_CF_ENABLED, true),
            cloudflareDomain = prefs.getString(KEY_CF_DOMAIN, "").orEmpty().trim(),
            workerDomains = prefs.getString(KEY_WORKER_DOMAINS, "").orEmpty().trim(),
            dcIps = prefs.getString(KEY_DC_IPS, "").orEmpty().trim(),
            secret = secret,
        )
    }

    fun save(context: Context, config: TgWsConfig) {
        require(config.port in 1..65535) { "Invalid proxy port" }
        require(config.poolSize in 2..16) { "Invalid pool size" }
        require(isValidSecret(config.secret)) { "Secret must contain 32 hexadecimal characters" }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BIND_IP, config.bindIp)
            .putInt(KEY_PORT, config.port)
            .putInt(KEY_POOL_SIZE, config.poolSize)
            .putBoolean(KEY_CF_ENABLED, config.cloudflareEnabled)
            .putString(KEY_CF_DOMAIN, config.cloudflareDomain)
            .putString(KEY_WORKER_DOMAINS, config.workerDomains)
            .putString(KEY_DC_IPS, config.dcIps)
            .putString(KEY_SECRET, config.secret.lowercase())
            .apply()
    }

    fun telegramSecret(context: Context): String = "dd${load(context).secret}"

    private fun generateSecret(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }

    private fun isValidSecret(value: String): Boolean =
        value.length == 32 && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
}
