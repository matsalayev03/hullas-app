package org.hullas.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object ProjectionHelper {
    private const val TAG = "ProjectionHelper"
    private var activeProjection: MediaProjection? = null

    fun hasSavedPermission(ctx: Context): Boolean {
        val sp = Prefs.sp(ctx)
        return sp.contains("projection_data") && sp.getInt("projection_result_code", 0) == Activity.RESULT_OK
    }

    fun savePermission(ctx: Context, resultCode: Int, data: Intent) {
        if (resultCode != Activity.RESULT_OK) return
        Prefs.sp(ctx).edit()
            .putInt("projection_result_code", resultCode)
            .putString("projection_data", data.toUri(0))
            .putBoolean("screenshot_granted", true)
            .apply()
        activeProjection?.stop()
        activeProjection = null
        Log.i(TAG, "Ekran ruxsati saqlandi")
    }

    fun clearPermission(ctx: Context) {
        Prefs.sp(ctx).edit()
            .remove("projection_result_code")
            .remove("projection_data")
            .putBoolean("screenshot_granted", false)
            .apply()
        activeProjection?.stop()
        activeProjection = null
    }

    private fun obtainProjection(ctx: Context): MediaProjection? {
        if (!hasSavedPermission(ctx)) return null
        val sp = Prefs.sp(ctx)
        val resultCode = sp.getInt("projection_result_code", 0)
        val uri = sp.getString("projection_data", null) ?: return null
        return try {
            val data = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
            val mgr = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            activeProjection?.stop()
            val projection = mgr.getMediaProjection(resultCode, data)
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection to'xtadi")
                    clearPermission(ctx)
                }
            }, Handler(Looper.getMainLooper()))
            activeProjection = projection
            projection
        } catch (e: Exception) {
            Log.e(TAG, "Projection olish xato", e)
            clearPermission(ctx)
            null
        }
    }

    fun captureScreenshot(ctx: Context): File {
        if (!hasSavedPermission(ctx)) {
            requestPermission(ctx)
            throw ScreenshotException("Ekran ruxsati yo'q. Telefonda ruxsat oynasini tasdiqlang.")
        }
        val projection = obtainProjection(ctx)
            ?: throw ScreenshotException("Ekran ruxsati muddati tugagan. Ilovani ochib qayta bering.")

        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val density = metrics.densityDpi

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val latch = CountDownLatch(1)
        val bitmapRef = AtomicReference<Bitmap?>(null)
        val handler = Handler(Looper.getMainLooper())

        reader.setOnImageAvailableListener({ r ->
            try {
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
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
                bitmapRef.set(Bitmap.createBitmap(bmp, 0, 0, w, h))
                bmp.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Image o'qish xato", e)
            } finally {
                latch.countDown()
            }
        }, handler)

        var display: VirtualDisplay? = null
        try {
            display = projection.createVirtualDisplay(
                "hullas_cap", w, h, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler,
            )
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw ScreenshotException("Ekran tasviri vaqti tugadi. Qayta urinib ko'ring.")
            }
            val bitmap = bitmapRef.get()
                ?: throw ScreenshotException("Ekran tasviri olinmadi (Xiaomi: ilovani ochiq qoldiring).")
            val file = File(ctx.cacheDir, "screenshot_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
            bitmap.recycle()
            if (!file.exists() || file.length() == 0L) {
                throw ScreenshotException("Screenshot fayli yaratilmadi")
            }
            return file
        } finally {
            display?.release()
            reader.close()
        }
    }

    fun requestPermission(ctx: Context) {
        try {
            val intent = ScreenshotPermissionActivity.createIntent(ctx)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Ruxsat activity ochilmadi", e)
        }
    }
}

class ScreenshotException(message: String) : Exception(message)