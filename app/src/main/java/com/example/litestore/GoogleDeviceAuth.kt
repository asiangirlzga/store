package com.example.litestore

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val intervalSec: Int,
    val expiresInSec: Int
)

/**
 * Google OAuth 2.0 "device flow" (TVs and Limited Input devices).
 * The TV shows a QR code that opens Google's own sign-in page on the phone.
 * The TV never sees the password.
 */
object GoogleDeviceAuth {
    private const val DEVICE_URL = "https://oauth2.googleapis.com/device/code"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val USERINFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo"

    private fun post(url: String, params: Map<String, String>): Pair<Int, JSONObject> {
        val body = params.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            return code to JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    fun start(ctx: Context): DeviceCode {
        val (code, j) = post(DEVICE_URL, mapOf(
            "client_id" to ctx.getString(R.string.google_client_id),
            "scope" to "email profile"
        ))
        if (code != 200) throw IllegalStateException(j.optString("error_description", "Login unavailable"))
        return DeviceCode(
            j.getString("device_code"),
            j.getString("user_code"),
            j.optString("verification_url", "https://www.google.com/device"),
            j.optInt("interval", 5),
            j.optInt("expires_in", 1800)
        )
    }

    /** Returns access token, null while still pending; throws on denial or expiry. */
    fun poll(ctx: Context, d: DeviceCode): String? {
        val (_, j) = post(TOKEN_URL, mapOf(
            "client_id" to ctx.getString(R.string.google_client_id),
            "client_secret" to ctx.getString(R.string.google_client_secret),
            "device_code" to d.deviceCode,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"
        ))
        if (j.has("access_token")) return j.getString("access_token")
        return when (j.optString("error")) {
            "authorization_pending", "slow_down" -> null
            else -> throw IllegalStateException(j.optString("error", "Login failed"))
        }
    }

    fun email(accessToken: String): String {
        val c = URL(USERINFO_URL).openConnection() as HttpURLConnection
        try {
            c.setRequestProperty("Authorization", "Bearer $accessToken")
            c.connectTimeout = 10000
            c.readTimeout = 15000
            val text = c.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text).optString("email", "Signed in")
        } finally {
            c.disconnect()
        }
    }
}
