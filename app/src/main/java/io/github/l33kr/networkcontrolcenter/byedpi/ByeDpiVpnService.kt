package io.github.l33kr.networkcontrolcenter.byedpi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import hev.htproxy.TProxyService
import io.github.l33kr.networkcontrolcenter.MainActivity
import io.github.l33kr.networkcontrolcenter.R
import io.github.l33kr.networkcontrolcenter.core.EngineStatus
import io.github.l33kr.networkcontrolcenter.nativecore.EngineMode
import io.github.l33kr.networkcontrolcenter.nativecore.EngineModeStore
import io.github.l33kr.networkcontrolcenter.nativecore.NativeDpiProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Historical class name is retained so existing manifests/controllers keep
 * working. On the experimental branch it defaults to DPI Control's own native
 * stream engine; ByeDPI remains available only as an internal legacy fallback
 * mode for development comparisons.
 */
class ByeDpiVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val legacyProxy = ByeDpiProxy()
    private val stopping = AtomicBoolean(false)
    private var nativeProxy: NativeDpiProxy? = null
    private var activeEngineMode: EngineMode = EngineMode.NATIVE_ALPHA
    private var proxyJob: Job? = null
    private var tun: ParcelFileDescriptor? = null
    private var hevConfig: File? = null

    companion object {
        const val ACTION_START = "io.github.l33kr.networkcontrolcenter.byedpi.START"
        const val ACTION_STOP = "io.github.l33kr.networkcontrolcenter.byedpi.STOP"

        private const val TAG = "DpiVpnService"
        private const val CHANNEL_ID = "byedpi_vpn"
        private const val NOTIFICATION_ID = 1101

        private val _status = MutableStateFlow(EngineStatus.STOPPED)
        val status: StateFlow<EngineStatus> = _status.asStateFlow()

        private val _activeProfile = MutableStateFlow<String?>(null)
        val activeProfile: StateFlow<String?> = _activeProfile.asStateFlow()

        private val _networkLabel = MutableStateFlow<String?>(null)
        val networkLabel: StateFlow<String?> = _networkLabel.asStateFlow()

        private val _ipv6Active = MutableStateFlow(false)
        val ipv6Active: StateFlow<Boolean> = _ipv6Active.asStateFlow()

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError.asStateFlow()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startEngine()
            ACTION_STOP -> scope.launch { stopEngine() }
        }
        return START_NOT_STICKY
    }

    private fun startEngine() {
        if (_status.value == EngineStatus.STARTING || _status.value == EngineStatus.RUNNING) return

        stopping.set(false)
        _lastError.value = null
        _status.value = EngineStatus.STARTING
        activeEngineMode = EngineModeStore.load(this)
        startForegroundCompat(createNotification("Запуск сетевого движка…"))

        scope.launch {
            try {
                val config = ByeDpiConfigStore.load(this@ByeDpiVpnService)
                val network = detectNetwork()
                val useIpv6 = shouldRouteIpv6(config.ipv6Mode, network)
                val legacyStrategy = if (activeEngineMode == EngineMode.LEGACY_BYEDPI) {
                    ByeDpiStrategies.resolve(config, network.isCellular)
                } else null

                val profileTitle = when (activeEngineMode) {
                    EngineMode.NATIVE_ALPHA -> "Native Engine α"
                    EngineMode.LEGACY_BYEDPI -> legacyStrategy?.title ?: "Legacy"
                }

                _activeProfile.value = profileTitle
                _networkLabel.value = network.label
                _ipv6Active.value = useIpv6

                Log.i(TAG, "Starting engine=$activeEngineMode, network=${network.label}, ipv6=$useIpv6")
                updateNotification("$profileTitle · ${network.label} · запуск…")

                startProxy(config, legacyStrategy?.command)

                if (!waitForProxy(config.bindIp, config.port)) {
                    error("Локальный SOCKS5 движок не запустился")
                }

                val configFile = createHevConfig(config)
                hevConfig = configFile

                val builder = Builder()
                    .setSession("DPI Control")
                    .setConfigureIntent(
                        PendingIntent.getActivity(
                            this@ByeDpiVpnService,
                            0,
                            Intent(this@ByeDpiVpnService, MainActivity::class.java),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                    .addAddress("10.10.10.10", 32)
                    .addRoute("0.0.0.0", 0)

                if (useIpv6) {
                    builder.addAddress("fd00::1", 128)
                        .addRoute("::", 0)
                }

                if (config.dns.isNotBlank()) builder.addDnsServer(config.dns)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

                // Our local SOCKS engine and its upstream sockets must remain
                // outside the TUN or they would recursively enter the VPN.
                builder.addDisallowedApplication(packageName)

                val descriptor = builder.establish()
                    ?: error("Android не создал VPN-интерфейс")
                tun = descriptor

                if (!TProxyService.TProxyStartService(configFile.absolutePath, descriptor.fd)) {
                    error("TUN → SOCKS мост не запустился")
                }

                _status.value = EngineStatus.RUNNING
                val ipv6Text = if (useIpv6) "IPv4+IPv6" else "IPv4"
                updateNotification("$profileTitle · ${network.label} · $ipv6Text")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start DPI VPN", t)
                val message = t.message ?: t.javaClass.simpleName
                _lastError.value = message
                _status.value = EngineStatus.FAILED
                updateNotification("Ошибка: $message")
                cleanupAfterFailure()
            }
        }
    }

    private fun startProxy(config: ByeDpiConfig, legacyCommand: String?) {
        check(proxyJob == null) { "Proxy job already exists" }

        proxyJob = scope.launch(Dispatchers.IO) {
            val code = when (activeEngineMode) {
                EngineMode.NATIVE_ALPHA -> {
                    val engine = NativeDpiProxy(applicationContext)
                    nativeProxy = engine
                    runCatching { engine.start(config.bindIp, config.port) }
                        .onFailure { Log.e(TAG, "Native engine loop failed", it) }
                        .getOrDefault(-1)
                }

                EngineMode.LEGACY_BYEDPI -> {
                    val argsConfig = config.copy(command = legacyCommand ?: config.command)
                    runCatching { legacyProxy.start(argsConfig) }
                        .onFailure { Log.e(TAG, "Legacy ByeDPI loop failed", it) }
                        .getOrDefault(-1)
                }
            }

            withContext(Dispatchers.Main) {
                if (!stopping.get() && _status.value != EngineStatus.FAILED) {
                    Log.e(TAG, "Proxy engine exited unexpectedly with code $code")
                    _lastError.value = "Сетевой движок завершился: $code"
                    _status.value = EngineStatus.FAILED
                    updateNotification("Движок завершился: $code")
                    scope.launch { cleanupAfterFailure() }
                }
            }
        }
    }

    private suspend fun waitForProxy(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        repeat(40) {
            val ready = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 150)
                }
                true
            }.getOrDefault(false)
            if (ready) return@withContext true
            delay(100)
        }
        false
    }

    private fun createHevConfig(config: ByeDpiConfig): File {
        val text = """
            tunnel:
              mtu: 8500
            socks5:
              address: ${config.bindIp}
              port: ${config.port}
              udp: 'udp'
            misc:
              task-stack-size: 86016
              log-level: warn
        """.trimIndent()

        return File.createTempFile("hev-dpi-control-", ".yml", cacheDir).apply {
            writeText(text)
        }
    }

    private fun detectNetwork(): NetworkSnapshot {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)
        val linkProperties = network?.let(manager::getLinkProperties)

        val cellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val wifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val ethernet = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val hasIpv6 = linkProperties?.linkAddresses?.any { link ->
            val address = link.address
            address is Inet6Address && !address.isLinkLocalAddress && !address.isLoopbackAddress
        } == true

        val label = when {
            cellular -> "Мобильная сеть"
            wifi -> "Wi‑Fi"
            ethernet -> "Ethernet"
            else -> "Сеть"
        }

        return NetworkSnapshot(
            isCellular = cellular,
            hasIpv6 = hasIpv6,
            label = label,
        )
    }

    private fun shouldRouteIpv6(mode: Ipv6Mode, network: NetworkSnapshot): Boolean = when (mode) {
        Ipv6Mode.ON -> true
        Ipv6Mode.OFF -> false
        Ipv6Mode.AUTO -> network.hasIpv6 && !network.isCellular
    }

    private suspend fun stopEngine() {
        if (_status.value == EngineStatus.STOPPED || !stopping.compareAndSet(false, true)) return
        _status.value = EngineStatus.STOPPING
        updateNotification("Остановка…")

        stopHevAndTun()
        stopProxyOnly()
        clearRuntimeFiles()

        _status.value = EngineStatus.STOPPED
        _activeProfile.value = null
        _networkLabel.value = null
        _ipv6Active.value = false
        _lastError.value = null
        stopping.set(false)
        stopForegroundCompat()
        stopSelf()
    }

    private suspend fun cleanupAfterFailure() {
        stopHevAndTun()
        stopProxyOnly()
        clearRuntimeFiles()
    }

    private fun stopHevAndTun() {
        runCatching {
            if (TProxyService.TProxyIsRunning()) TProxyService.TProxyStopService()
        }.onFailure { Log.w(TAG, "Failed to stop hev", it) }

        runCatching { tun?.close() }
        tun = null
    }

    private suspend fun stopProxyOnly() {
        val job = proxyJob ?: return
        when (activeEngineMode) {
            EngineMode.NATIVE_ALPHA -> runCatching { nativeProxy?.stop() }
            EngineMode.LEGACY_BYEDPI -> runCatching { legacyProxy.stop() }
        }

        val stopped = withTimeoutOrNull(3000) {
            job.join()
            true
        } ?: false
        if (!stopped && activeEngineMode == EngineMode.LEGACY_BYEDPI) {
            runCatching { legacyProxy.forceClose() }
            withTimeoutOrNull(1000) { job.join() }
        }
        nativeProxy = null
        proxyJob = null
    }

    private fun clearRuntimeFiles() {
        hevConfig?.let { runCatching { it.delete() } }
        hevConfig = null
    }

    override fun onRevoke() {
        scope.launch { stopEngine() }
        super.onRevoke()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "DPI Control",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Локальная обработка сетевого трафика"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ByeDpiVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dpi)
            .setContentTitle("DPI Control")
            .setContentText(text)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отключить", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, createNotification(text))
    }

    override fun onDestroy() {
        runCatching { nativeProxy?.stop() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    private data class NetworkSnapshot(
        val isCellular: Boolean,
        val hasIpv6: Boolean,
        val label: String,
    )
}
