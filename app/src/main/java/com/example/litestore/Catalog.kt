package com.example.litestore

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppItem(
    val name: String,
    val pkg: String,
    val url: String,
    val sizeMb: Int,
    val minSdk: Int,
    val tv: Boolean,
    val desc: String
)

object Catalog {
    const val SMALL_LIMIT_MB = 25

    fun load(ctx: Context): List<AppItem> {
        val remote = ctx.getString(R.string.catalog_url)
        val text = try {
            if (remote.isNotBlank()) {
                val c = URL(remote).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 15000
                try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
            } else bundled(ctx)
        } catch (e: Exception) {
            bundled(ctx)
        }
        val arr = JSONObject(text).getJSONArray("apps")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            AppItem(
                o.getString("name"), o.getString("pkg"), o.getString("url"),
                o.optInt("sizeMb", 0), o.optInt("minSdk", 21),
                o.optBoolean("tv", true), o.optString("desc", "")
            )
        }
    }

    private fun bundled(ctx: Context) =
        ctx.assets.open("catalog.json").bufferedReader().use { it.readText() }

    /** Only apps that support this TV's Android version, are TV-ready, and are small. */
    fun filter(ctx: Context, all: List<AppItem>, smallOnly: Boolean): List<AppItem> {
        val isTv = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            ctx.packageManager.hasSystemFeature("android.hardware.type.television")
        return all.filter {
            it.minSdk <= Build.VERSION.SDK_INT &&
                (it.tv || !isTv) &&
                (!smallOnly || it.sizeMb <= SMALL_LIMIT_MB)
        }.sortedBy { it.sizeMb }
    }
}
