package io.github.franticplant.koiterminal.devd

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The latest metrics sample: devd and the koiTerminal app, sampled every 2 s. */
class MetricsActivity : Activity() {
    private val app
        get() = application as DevdApp
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var view: TextView

    private val refresh =
        object : Runnable {
            override fun run() {
                render()
                handler.postDelayed(this, 2_000)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        root.addView(
            Button(this).apply {
                text = "Back"
                setOnClickListener { finish() }
            }
        )
        view =
            TextView(this).apply {
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
            }
        root.addView(ScrollView(this).apply { addView(view) })
        setContentView(root)
        handler.post(refresh)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        super.onDestroy()
    }

    private fun render() {
        val m = app.metrics.latest.value
        if (m == null) {
            view.text = "no sample yet (the listener must be on)"
            return
        }
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        val age = System.currentTimeMillis() - m.optLong("unixMs")
        view.text =
            buildString {
                append("${fmt.format(Date(m.optLong("unixMs")))}  ")
                if (age > 10_000) append("STALE (${age / 1000}s)  ")
                append("koi app: ")
                append(if (m.optBoolean("koiConnected")) "connected" else "not bound")
                append("\n\n")
                append(
                    String.format(
                        Locale.US,
                        "%-12s %7s %10s %10s %7s %8s %5s\n",
                        "PROCESS",
                        "PID",
                        "RSS",
                        "PSS",
                        "CPU%",
                        "THREADS",
                        "FDS",
                    )
                )
                val ps = m.optJSONArray("processes")
                for (i in 0 until (ps?.length() ?: 0)) {
                    val p = ps!!.getJSONObject(i)
                    append(
                        String.format(
                            Locale.US,
                            "%-12s %7d %10s %10s %7s %8d %5d\n",
                            p.optString("name"),
                            p.optInt("pid"),
                            "${p.optInt("rssKb")}K",
                            "${p.optInt("pssKb")}K",
                            if (p.has("cpuPct")) String.format(Locale.US, "%.1f", p.optDouble("cpuPct")) else "-",
                            p.optInt("threads"),
                            p.optInt("fds"),
                        )
                    )
                }
                append("\nseries: ")
                append(app.metrics.keys().joinToString(", "))
            }
    }
}
