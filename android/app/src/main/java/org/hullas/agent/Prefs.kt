package org.hullas.agent

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val NAME = "hullas_agent"

    fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun serverUrl(@Suppress("UNUSED_PARAMETER") ctx: Context): String =
        BuildConfig.SERVER_URL

    fun deviceToken(@Suppress("UNUSED_PARAMETER") ctx: Context): String =
        BuildConfig.DEVICE_TOKEN

    fun isConfigured(ctx: Context): Boolean =
        serverUrl(ctx).isNotBlank() && deviceToken(ctx).isNotBlank()

    fun isInitialized(ctx: Context): Boolean =
        sp(ctx).getBoolean("initialized", false)

    fun setInitialized(ctx: Context) {
        sp(ctx).edit().putBoolean("initialized", true).apply()
    }

    fun screenshotGranted(ctx: Context): Boolean =
        sp(ctx).getBoolean("screenshot_granted", false)

    fun setScreenshotGranted(ctx: Context, granted: Boolean) {
        sp(ctx).edit().putBoolean("screenshot_granted", granted).apply()
    }
}