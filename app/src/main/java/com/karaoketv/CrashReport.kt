package com.karaoketv

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * If the app ever crashes, the error is saved, and the next launch shows it on screen (instead of
 * crashing again) so it can be photographed and fixed without connecting a computer.
 */
class KaraokeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { CrashReport.save(this, error) }
            previous?.uncaughtException(thread, error)
        }
    }
}

object CrashReport {
    private fun file(c: Context) = File(c.filesDir, "last_crash.txt")

    fun save(c: Context, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val info = runCatching {
            c.packageManager.getPackageInfo(c.packageName, 0).versionName
        }.getOrNull() ?: "?"
        file(c).writeText(
            "Karaoke Night $info · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MODEL}\n\n" +
                trace.take(8_000)
        )
    }

    fun pending(c: Context): String? = runCatching { file(c).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(c: Context) { runCatching { file(c).delete() } }
}

/** The launcher entry: shows the last crash if there was one, otherwise opens the party. */
class StartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = if (CrashReport.pending(this) != null) CrashActivity::class.java else MainActivity::class.java
        startActivity(Intent(this, target))
        finish()
    }
}

/** Plain screen with no scaling or extras, so it can always open. */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = CrashReport.pending(this) ?: "No error saved."
        val pad = (24 * resources.displayMetrics.density).toInt()

        fun text(s: String, size: Float, color: Int) = TextView(this).apply {
            text = s; textSize = size; setTextColor(color)
        }
        fun button(label: String, action: () -> Unit) = TextView(this).apply {
            text = label; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.chip_bg)
            setPadding(pad, pad / 2, pad, pad / 2)
            isFocusable = true; isClickable = true
            setOnClickListener { action() }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(11, 6, 20))
            setPadding(pad * 2, pad, pad * 2, pad)
        }
        root.addView(text("😕 Karaoke Night stopped last time", 26f, Color.WHITE))
        root.addView(text("Take a photo of this screen and send it to whoever set up the app. Then press Try again.", 16f, Color.rgb(185, 174, 219)))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, pad / 2, 0, pad / 2) }
        val retry = button("▶ Try again") {
            CrashReport.clear(this)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        buttons.addView(retry)
        buttons.addView(View(this), LinearLayout.LayoutParams(pad / 2, 1))
        buttons.addView(button("Close") { CrashReport.clear(this); finish() })
        root.addView(buttons)
        val scroll = ScrollView(this).apply { isFocusable = true }
        scroll.addView(text(report, 13f, Color.rgb(255, 210, 63)).apply { setTextIsSelectable(false); typeface = android.graphics.Typeface.MONOSPACE })
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        retry.requestFocus()
    }
}
