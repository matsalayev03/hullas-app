package org.hullas.agent

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val NAME = "hullas_agent"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun serverUrl(ctx: Context): String =
        sp(ctx).getString("server_url", "https://hullas.azro.uz") ?: ""

    fun deviceToken(ctx: Context): String =
        sp(ctx).getString("device_token", "") ?: ""

    fun save(ctx: Context, serverUrl: String, token: String) {
        sp(ctx).edit()
            .putString("server_url", serverUrl.trimEnd('/'))
            .putString("device_token", token.trim())
            .apply()
    }

    fun isConfigured(ctx: Context): Boolean =
        serverUrl(ctx).isNotBlank() && deviceToken(ctx).isNotBlank()

    fun screenshotGranted(ctx: Context): Boolean =
        sp(ctx).getBoolean("screenshot_granted", false)

    fun setScreenshotGranted(ctx: Context, granted: Boolean) {
        sp(ctx).edit().putBoolean("screenshot_granted", granted).apply()
    }
}