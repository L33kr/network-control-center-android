package io.github.l33kr.networkcontrolcenter.tgws

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.l33kr.networkcontrolcenter.MainActivity
import io.github.l33kr.networkcontrolcenter.R
import io.github.l33kr.networkcontrolcenter.core.EngineStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class TgWsProxyService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val stopping = AtomicBoolean(false)

    companion object {
        const val ACTION_START = "io.github.l33kr.networkcontrolcenter.tgws.START"
        const val ACTION_STOP = "io.github.l33kr.networkcontrolcenter.tgws.STOP"

        private const val TAG = "TgWsProxyService"
        private const val CHANNEL_ID = "tg_ws_proxy"
        private const val NOTIFICATION_ID = 1201
        private const val STATE_PREFS = "tg_ws_service_state"
        private const val KEY_WANTED = "wanted"

        private val _status = MutableStateFlow(EngineStatus.STOPPED)
        val status: StateFlow<EngineStatus> = _status.asStateFlow()

        private val _activePort = MutableStateFlow(0)
        val activePort: StateFlow<Int> = _activePort.asStateFlow()

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError.asStateFlow()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                setWanted(true)
                startProxy()
            }
            ACTION_STOP -> {
                setWanted(false)
                stopProxy()
            }
            null -> {
                // START_STICKY recreation after the process/service was reclaimed.
                if (isWanted()) startProxy()
            }
        }
        return START_STICKY
    }

    private fun startProxy() {
        if (_status.value == EngineStatus.STARTING || _status.value == EngineStatus.RUNNING) return

        stopping.set(false)
        _lastError.value = null
        _activePort.value = 0
        _status.value = EngineStatus.STARTING
        startForegroundCompat(createNotification("Запуск Telegram WS…"))
        acquireWakeLock()

        Thread({
            try {
                val config = TgWsConfigStore.load(this)
                val selectedPort = selectPort(config.bindIp, config.port)
                    ?: throw IllegalStateException("Нет свободного локального порта рядом с ${config.port}")

                if (selectedPort != config.port) {
                    Log.w(TAG, "Port ${config.port} is busy, using $selectedPort")
                    updateNotification("Порт ${config.port} занят · пробуем $selectedPort")
                }

                TgWsNative.setPoolSize(config.poolSize)
                TgWsNative.setCloudflareCacheDir(cacheDir.absolutePath)
                TgWsNative.setCloudflare(config.cloudflareEnabled, config.cloudflareDomain)

                if (!TgWsNative.trySetWorkerDomains(config.workerDomains)) {
                    Log.w(TAG, "Bundled libtgwsproxy.so has no SetCfWorkerDomains export")
                }

                val result = TgWsNative.start(
                    host = config.bindIp,
                    port = selectedPort,
                    dcIps = config.dcIps,
                    secret = config.secret,
                    verbose = true,
                )

                if (result != 0) {
                    if (!stopping.get()) fail(nativeErrorText(result))
                    return@Thread
                }

                // StartProxy returning 0 is not enough: make sure the local listener
                // is actually reachable before telling Telegram to use it.
                if (!waitUntilListening(config.bindIp, selectedPort)) {
                    runCatching { TgWsNative.stop() }
                    if (!stopping.get()) fail("TG WS запустился, но локальный порт не отвечает")
                    return@Thread
                }

                if (!stopping.get()) {
                    _activePort.value = selectedPort
                    _lastError.value = null
                    _status.value = EngineStatus.RUNNING
                    updateNotification("127.0.0.1:$selectedPort · работает")
                    Log.i(TAG, "TG WS proxy started on ${config.bindIp}:$selectedPort")
                }
            } catch (t: Throwable) {
                if (!stopping.get()) {
                    val details = buildString {
                        append(t.javaClass.simpleName)
                        t.message?.takeIf { it.isNotBlank() }?.let { append(": $it") }
                    }
                    fail(details)
                    Log.e(TAG, "TG WS startup failed", t)
                }
            }
        }, "tg-ws-start").apply {
            isDaemon = true
            start()
        }
    }

    private fun fail(message: String) {
        _activePort.value = 0
        _lastError.value = message
        _status.value = EngineStatus.FAILED
        updateNotification("Ошибка: $message")
        // Keep the foreground service alive only when the user still wants the proxy.
        if (!isWanted()) {
            releaseWakeLock()
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun nativeErrorText(code: Int): String = when (code) {
        -1 -> "native -1: прокси уже запущен"
        -3 -> "native -3: не удалось открыть локальный порт"
        else -> "native $code"
    }

    private fun selectPort(host: String, preferredPort: Int): Int? {
        if (isPortAvailable(host, preferredPort)) return preferredPort

        for (port in (preferredPort + 1)..(preferredPort + 20).coerceAtMost(65535)) {
            if (isPortAvailable(host, port)) return port
        }

        return runCatching {
            ServerSocket().use { socket ->
                socket.reuseAddress = false
                socket.bind(InetSocketAddress(InetAddress.getByName(host), 0))
                socket.localPort
            }
        }.getOrNull()
    }

    private fun isPortAvailable(host: String, port: Int): Boolean = runCatching {
        ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(InetAddress.getByName(host), port))
        }
        true
    }.getOrDefault(false)

    private fun waitUntilListening(host: String, port: Int): Boolean {
        repeat(30) {
            val listening = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 150)
                }
                true
            }.getOrDefault(false)
            if (listening) return true
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    private fun stopProxy() {
        if (_status.value == EngineStatus.STOPPED || !stopping.compareAndSet(false, true)) return
        _status.value = EngineStatus.STOPPING
        updateNotification("Остановка Telegram WS…")

        Thread({
            runCatching { TgWsNative.stop() }
                .onFailure { Log.w(TAG, "TG WS stop failed", it) }

            _activePort.value = 0
            _lastError.value = null
            _status.value = EngineStatus.STOPPED
            releaseWakeLock()
            stopForegroundCompat()
            stopSelf()
            stopping.set(false)
        }, "tg-ws-stop").apply {
            isDaemon = true
            start()
        }
    }

    private fun setWanted(value: Boolean) {
        getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WANTED, value)
            .apply()
    }

    private fun isWanted(): Boolean =
        getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WANTED, false)

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
            "DPI Control · Telegram",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Локальный MTProto/WebSocket прокси"
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
            Intent(this, TgWsProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dpi)
            .setContentTitle("DPI Control · Telegram")
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

    private fun acquireWakeLock() {
        runCatching {
            val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = manager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "DPIControl:TgWs",
            ).apply {
                setReferenceCounted(false)
                // Foreground proxy is explicitly stopped by the user/service lifecycle;
                // a fixed 30-minute timeout caused long-lived sessions to go idle/drop.
                acquire()
            }
        }.onFailure { Log.w(TAG, "Could not acquire wake lock", it) }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        // Never leave the native singleton running after Android destroys the service.
        runCatching { TgWsNative.stop() }
        _activePort.value = 0
        if (!isWanted()) _status.value = EngineStatus.STOPPED
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
