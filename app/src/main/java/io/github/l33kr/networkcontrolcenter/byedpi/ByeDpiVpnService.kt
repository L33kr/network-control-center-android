package io.github.l33kr.networkcontrolcenter.byedpi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
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
        _status.value = EngineStatus.STARTING
        startForegroundCompat(createNotification("Запуск ByeDPI…"))

        scope.launch {
            try {
                val config = ByeDpiConfigStore.load(this@ByeDpiVpnService)
                startProxy(config)

                if (!waitForProxy(config.bindIp, config.port)) {
                    error("ByeDPI SOCKS5 did not become ready")
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

                if (config.ipv6Mode == Ipv6Mode.ON) {
                    builder.addAddress("fd00::1", 128)
                        .addRoute("::", 0)
                }

                if (config.dns.isNotBlank()) builder.addDnsServer(config.dns)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

                // Critical loop prevention: ByeDPI and TG WS outbound sockets belong
                // to this application and must use the physical network directly.
                builder.addDisallowedApplication(packageName)

                val descriptor = builder.establish() ?: error("VpnService establish() returned null")
                tun = descriptor

                if (!TProxyService.TProxyStartService(configFile.absolutePath, descriptor.fd)) {
                    error("hev-socks5-tunnel failed to start")
                }

                _status.value = EngineStatus.RUNNING
                updateNotification("ByeDPI работает · ${config.bindIp}:${config.port}")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start ByeDPI VPN", t)
                _status.value = EngineStatus.FAILED
                updateNotification("Ошибка ByeDPI: ${t.message ?: t.javaClass.simpleName}")
                cleanupAfterFailure()
            }
        }
    }

    private fun startProxy(config: ByeDpiConfig) {
        check(proxyJob == null) { "ByeDPI proxy job already exists" }
        proxyJob = scope.launch(Dispatchers.IO) {
            val code = runCatching { proxy.start(config) }
                .onFailure { Log.e(TAG, "ByeDPI native loop failed", it) }
                .getOrDefault(-1)

            withContext(Dispatchers.Main) {
                if (!stopping.get() && _status.value != EngineStatus.FAILED) {
                    Log.e(TAG, "ByeDPI exited unexpectedly with code $code")
                    _status.value = EngineStatus.FAILED
                    updateNotification("ByeDPI завершился: $code")
                    scope.launch { cleanupAfterFailure() }
                }
            }
        }
    }

    private suspend fun waitForProxy(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        repeat(30) {
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

    private suspend fun stopEngine() {
        if (_status.value == EngineStatus.STOPPED || !stopping.compareAndSet(false, true)) return
        _status.value = EngineStatus.STOPPING
        updateNotification("Остановка ByeDPI…")

        runCatching {
            if (TProxyService.TProxyIsRunning()) TProxyService.TProxyStopService()
        }.onFailure { Log.w(TAG, "Failed to stop hev", it) }

        runCatching { tun?.close() }
        tun = null

        runCatching { proxy.stop() }
        val job = proxyJob
        if (job != null) {
            val stopped = withTimeoutOrNull(3000) {
                job.join()
                true
            } ?: false
            if (!stopped) runCatching { proxy.forceClose() }
        }
        proxyJob = null

        hevConfig?.let { runCatching { it.delete() } }
        hevConfig = null

        _status.value = EngineStatus.STOPPED
        stopping.set(false)
        stopForegroundCompat()
        stopSelf()
    }

    private fun cleanupAfterFailure() {
        runCatching { if (TProxyService.TProxyIsRunning()) TProxyService.TProxyStopService() }
        runCatching { tun?.close() }
        tun = null
        runCatching { proxy.stop() }
        proxyJob = null
        hevConfig?.let { runCatching { it.delete() } }
        hevConfig = null
    }

    override fun onRevoke() {
        scope.launch { stopEngine() }
        super.onRevoke()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
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
}
