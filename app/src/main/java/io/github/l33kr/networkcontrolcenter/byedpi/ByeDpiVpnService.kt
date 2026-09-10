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
import io.github.l33kr.networkcontrolcenter.core.EngineStatus
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

class ByeDpiVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val proxy = ByeDpiProxy()
    private val stopping = AtomicBoolean(false)
    private var proxyJob: Job? = null
    private var tun: ParcelFileDescriptor? = null
    private var hevConfig: File? = null

    companion object {
        const val ACTION_START = "io.github.l33kr.networkcontrolcenter.byedpi.START"
        const val ACTION_STOP = "io.github.l33kr.networkcontrolcenter.byedpi.STOP"

        private const val TAG = "ByeDpiVpnService"
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
        startForegroundCompat(createNotification("Запуск ByeDPI…"))

        scope.launch {
            try {
                val config = ByeDpiConfigStore.load(this@ByeDpiVpnService)
                val network = detectNetwork()
                val strategy = ByeDpiStrategies.resolve(config, network.isCellular)
                val useIpv6 = shouldRouteIpv6(config.ipv6Mode, network)

                _activeProfile.value = strategy.title
                _networkLabel.value = network.label
                _ipv6Active.value = useIpv6

                Log.i(
                    TAG,
                    "Starting profile=${strategy.title}, network=${network.label}, ipv6=$useIpv6, command=${strategy.command}",
                )
                updateNotification("${strategy.title} · ${network.label} · запуск…")

                startProxy(config, strategy.command)

                if (!waitForProxy(config.bindIp, config.port)) {
                    error("ByeDPI SOCKS5 не запустился. Проверьте стратегию")
                }

                val configFile = createHevConfig(config)
                hevConfig = configFile

                val builder = Builder()
                    .setSession("Network Control Center · ByeDPI")
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

                // The native ByeDPI proxy and TG WS live in this package. Keeping the
                // package outside the TUN prevents VPN -> proxy -> VPN routing loops.
                builder.addDisallowedApplication(packageName)

                val descriptor = builder.establish()
                    ?: error("Android не создал VPN-интерфейс")
                tun = descriptor

                if (!TProxyService.TProxyStartService(configFile.absolutePath, descriptor.fd)) {
                    error("hev-socks5-tunnel не запустился")
                }

                _status.value = EngineStatus.RUNNING
                val ipv6Text = if (useIpv6) "IPv4+IPv6" else "IPv4"
                updateNotification("${strategy.title} · ${network.label} · $ipv6Text")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start ByeDPI VPN", t)
                val message = t.message ?: t.javaClass.simpleName
                _lastError.value = message
                _status.value = EngineStatus.FAILED
                updateNotification("Ошибка ByeDPI: $message")
                cleanupAfterFailure()
            }
        }
    }

    private fun startProxy(config: ByeDpiConfig, command: String) {
        check(proxyJob == null) { "ByeDPI proxy job already exists" }
        val argsConfig = config.copy(command = command)
        proxyJob = scope.launch(Dispatchers.IO) {
            val code = runCatching { proxy.start(argsConfig) }
                .onFailure { Log.e(TAG, "ByeDPI native loop failed", it) }
                .getOrDefault(-1)

            withContext(Dispatchers.Main) {
                if (!stopping.get() && _status.value != EngineStatus.FAILED) {
                    Log.e(TAG, "ByeDPI exited unexpectedly with code $code")
                    _lastError.value = "Ядро ByeDPI завершилось: $code"
                    _status.value = EngineStatus.FAILED
                    updateNotification("ByeDPI завершился: $code")
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

        return File.createTempFile("hev-ncc-", ".yml", cacheDir).apply {
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
        // Conservative default for mobile DPI: avoid an IPv6 path bypassing the
        // IPv4 desync path. Wi-Fi keeps IPv6 when the active link actually has it.
        Ipv6Mode.AUTO -> network.hasIpv6 && !network.isCellular
    }

    private suspend fun stopEngine() {
        if (_status.value == EngineStatus.STOPPED || !stopping.compareAndSet(false, true)) return
        _status.value = EngineStatus.STOPPING
        updateNotification("Остановка ByeDPI…")

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
        runCatching { proxy.stop() }
        val stopped = withTimeoutOrNull(3000) {
            job.join()
            true
        } ?: false
        if (!stopped) {
            runCatching { proxy.forceClose() }
            withTimeoutOrNull(1000) { job.join() }
        }
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
            "ByeDPI VPN",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Локальная обработка трафика через ByeDPI"
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
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Network Control Center · ByeDPI")
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
