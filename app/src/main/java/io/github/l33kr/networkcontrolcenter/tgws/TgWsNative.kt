package io.github.l33kr.networkcontrolcenter.tgws

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

internal interface TgWsLibrary : Library {
    fun StartProxy(host: String, port: Int, dcIps: String, secret: String, verbose: Int): Int
    fun StopProxy(): Int
    fun SetPoolSize(size: Int)
    fun SetCfProxyCacheDir(cacheDir: String)
    fun SetCfProxyConfig(enabled: Int, priority: Int, userDomain: String)
    fun SetCfWorkerDomains(domains: String)
    fun GetSecretWithPrefix(): Pointer?
    fun GetStats(): Pointer?
    fun FreeString(pointer: Pointer)
}

object TgWsNative {
    private val library: TgWsLibrary by lazy {
        Native.load("tgwsproxy", TgWsLibrary::class.java) as TgWsLibrary
    }

    fun start(
        host: String,
        port: Int,
        dcIps: String,
        secret: String,
        verbose: Boolean,
    ): Int = library.StartProxy(host, port, dcIps, secret, if (verbose) 1 else 0)

    fun stop(): Int = library.StopProxy()

    fun setPoolSize(size: Int) = library.SetPoolSize(size.coerceIn(2, 16))

    fun setCloudflareCacheDir(path: String) = library.SetCfProxyCacheDir(path)

    fun setCloudflare(enabled: Boolean, userDomain: String = "") {
        library.SetCfProxyConfig(if (enabled) 1 else 0, 1, userDomain)
    }

    fun setWorkerDomains(domains: String) = library.SetCfWorkerDomains(domains)

    fun secretWithPrefix(): String? = readNativeString { library.GetSecretWithPrefix() }

    fun stats(): String? = readNativeString { library.GetStats() }

    private inline fun readNativeString(block: () -> Pointer?): String? {
        val pointer = block() ?: return null
        return try {
            pointer.getString(0)
        } finally {
            library.FreeString(pointer)
        }
    }
}
