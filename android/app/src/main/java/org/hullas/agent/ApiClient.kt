package org.hullas.agent

import android.content.Context
import android.os.Build
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class ApiClient(private val ctx: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val baseUrl: String get() = Prefs.serverUrl(ctx)
    private val token: String get() = Prefs.deviceToken(ctx)

    private fun authBuilder(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("X-Device-Token", token)

    fun poll(): Command? {
        val req = authBuilder("$baseUrl/api/device/poll").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val json = JSONObject(body)
            if (json.isNull("command")) return null
            val cmd = json.getJSONObject("command")
            return Command(
                id = cmd.getString("id"),
                cmd = cmd.getString("cmd"),
                args = cmd.optJSONObject("args") ?: JSONObject(),
            )
        }
    }

    fun heartbeat() {
        val body = JSONObject()
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .toString()
            .toRequestBody("application/json".toMediaType())
        val req = authBuilder("$baseUrl/api/device/heartbeat").post(body).build()
        try {
            client.newCall(req).execute().close()
        } catch (_: Exception) {
        }
    }

    fun sendResult(
        commandId: String,
        status: String,
        file: File? = null,
        lat: Double? = null,
        lon: Double? = null,
        error: String? = null,
    ): Boolean {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("command_id", commandId)
            .addFormDataPart("status", status)
        lat?.let { builder.addFormDataPart("lat", it.toString()) }
        lon?.let { builder.addFormDataPart("lon", it.toString()) }
        error?.let { builder.addFormDataPart("error", it) }
        file?.let {
            val mime = when (it.extension.lowercase()) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "m4a", "mp4" -> "audio/mp4"
                else -> "application/octet-stream"
            }
            builder.addFormDataPart(
                "file", it.name,
                it.asRequestBody(mime.toMediaType()),
            )
        }
        val req = authBuilder("$baseUrl/api/device/result")
            .post(builder.build())
            .build()
        return try {
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    fun sendLiveFrame(commandId: String, file: File): Boolean {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("command_id", commandId)
            .addFormDataPart(
                "file", file.name,
                file.asRequestBody("image/png".toMediaType()),
            )
        val req = authBuilder("$baseUrl/api/device/live_frame")
            .post(builder.build())
            .build()
        return try {
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }
}

data class Command(
    val id: String,
    val cmd: String,
    val args: JSONObject,
)