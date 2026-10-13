package com.example.litestore

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private val ACTION_INSTALL = "com.example.litestore.INSTALL_RESULT"

    private lateinit var listView: ListView
    private lateinit var header: TextView
    private lateinit var account: TextView
    private lateinit var qr: ImageView
    private lateinit var code: TextView
    private lateinit var loginBtn: Button
    private lateinit var filterBtn: Button

    private var all: List<AppItem> = emptyList()
    private var shown: List<AppItem> = emptyList()
    private var smallOnly = true
    @Volatile private var loginRunId = 0

    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirm != null) startActivity(confirm)
                }
                PackageInstaller.STATUS_SUCCESS -> toast("Installed")
                else -> toast("Install failed: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        buildUi()
        val f = IntentFilter(ACTION_INSTALL)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(installReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(installReceiver, f)
        loadCatalog()
    }

    override fun onDestroy() {
        loginRunId++
        unregisterReceiver(installReceiver)
        super.onDestroy()
    }

    // ---------- UI (plain framework views: low RAM) ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#101418"))
            setPadding(dp(32), dp(24), dp(32), dp(24))
        }

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        left.addView(ImageView(this).apply { setImageResource(R.drawable.ic_launcher) },
            LinearLayout.LayoutParams(dp(64), dp(64)))
        left.addView(text("LiteStore", 26, true))
        account = text("Not signed in", 16, false).also { left.addView(it) }
        qr = ImageView(this).apply { visibility = View.GONE }
        left.addView(qr, LinearLayout.LayoutParams(dp(220), dp(220)).apply { topMargin = dp(16) })
        code = text("", 22, true).also { left.addView(it) }
        loginBtn = Button(this).apply {
            text = "Sign in with Google (QR)"
            setOnClickListener { startLogin() }
        }
        left.addView(loginBtn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        filterBtn = Button(this).apply {
            setOnClickListener { smallOnly = !smallOnly; refresh() }
        }
        left.addView(filterBtn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        root.addView(left, LinearLayout.LayoutParams(dp(320), -1))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        header = text("", 18, false).also { right.addView(it) }
        listView = ListView(this).apply {
            divider = null
            isFocusable = true
            setOnItemClickListener { _, _, pos, _ -> install(shown[pos]) }
        }
        right.addView(listView, LinearLayout.LayoutParams(-1, -1))
        root.addView(right, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(32) })

        setContentView(root)
        loginBtn.requestFocus()
    }

    private fun text(s: String, sp: Int, bold: Boolean) = TextView(this).apply {
        text = s
        textSize = sp.toFloat()
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    // ---------- Catalog ----------
    private fun loadCatalog() {
        header.text = "Loading apps…"
        Thread {
            try {
                val items = Catalog.load(this)
                runOnUiThread { all = items; refresh() }
            } catch (e: Exception) {
                runOnUiThread { header.text = "Could not load catalog" }
            }
        }.start()
    }

    private fun refresh() {
        shown = Catalog.filter(this, all, smallOnly)
        filterBtn.text = if (smallOnly) "Showing: small apps (≤${Catalog.SMALL_LIMIT_MB} MB)" else "Showing: all sizes"
        header.text = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) • ${shown.size} compatible apps"
        listView.adapter = object : ArrayAdapter<AppItem>(this, 0, shown) {
            override fun getView(pos: Int, v: View?, parent: ViewGroup): View {
                val a = shown[pos]
                val tv = (v as? TextView) ?: TextView(this@MainActivity).apply {
                    setTextColor(Color.WHITE)
                    textSize = 18f
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    isFocusable = false
                }
                tv.text = "${a.name}   •   ${a.sizeMb} MB   •   Android ${a.minSdk}+ (API)\n${a.desc}"
                return tv
            }
        }
    }

    // ---------- Google QR login ----------
    private fun startLogin() {
        val run = ++loginRunId
        loginBtn.isEnabled = false
        Thread {
            try {
                val d = GoogleDeviceAuth.start(this)
                runOnUiThread {
                    qr.setImageBitmap(qrBitmap(d.verificationUrl))
                    qr.visibility = View.VISIBLE
                    code.text = d.userCode
                    account.text = "Scan, then enter this code"
                }
                val deadline = System.currentTimeMillis() + d.expiresInSec * 1000L
                var wait = d.intervalSec
                while (run == loginRunId && System.currentTimeMillis() < deadline) {
                    Thread.sleep(wait * 1000L)
                    val token = GoogleDeviceAuth.poll(this, d)
                    if (token != null) {
                        val mail = GoogleDeviceAuth.email(token)
                        runOnUiThread {
                            qr.visibility = View.GONE
                            qr.setImageDrawable(null)
                            code.text = ""
                            account.text = mail
                            loginBtn.text = "Signed in"
                            loginBtn.isEnabled = false
                        }
                        return@Thread
                    }
                }
                runOnUiThread { resetLogin("Login expired") }
            } catch (e: Exception) {
                runOnUiThread { resetLogin(e.message ?: "Login failed") }
            }
        }.start()
    }

    private fun resetLogin(msg: String) {
        qr.visibility = View.GONE
        code.text = ""
        account.text = msg
        loginBtn.isEnabled = true
    }

    private fun qrBitmap(data: String): Bitmap {
        val m = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, 0, 0)
        val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.RGB_565)
        for (x in 0 until m.width) for (y in 0 until m.height)
            bmp.setPixel(x, y, if (m.get(x, y)) Color.BLACK else Color.WHITE)
        return Bitmap.createScaledBitmap(bmp, 440, 440, false)
    }

    // ---------- Install: streams straight into the system installer, no temp file ----------
    private fun install(app: AppItem) {
        toast("Downloading ${app.name}…")
        Thread {
            try {
                val pi = packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(app.pkg)
                val id = pi.createSession(params)
                val session = pi.openSession(id)
                val c = URL(app.url).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 30000
                c.inputStream.use { input ->
                    session.openWrite("app", 0, -1).use { out ->
                        input.copyTo(out, 16 * 1024)
                        session.fsync(out)
                    }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(
                    this, id, Intent(ACTION_INSTALL).setPackage(packageName), flags)
                session.commit(pending.intentSender)
                session.close()
            } catch (e: Exception) {
                toast("Download failed")
            }
        }.start()
    }

    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }
}
