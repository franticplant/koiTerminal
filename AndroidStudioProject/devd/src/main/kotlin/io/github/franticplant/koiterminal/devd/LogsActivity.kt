package io.github.franticplant.koiterminal.devd

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/** devd's journal: the log and the access log, with a kind and level filter. */
class LogsActivity : Activity() {
    private val app
        get() = application as DevdApp
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var view: TextView
    private var kind = "log"
    private var level = "debug"

    private val refresh =
        object : Runnable {
            override fun run() {
                render()
                handler.postDelayed(this, 1_000)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val kinds = Spinner(this).apply {
            adapter =
                ArrayAdapter(
                    this@LogsActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    listOf("log", "access", "all"),
                )
            onItemSelectedListener =
                object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?,
                        view: android.view.View?,
                        position: Int,
                        id: Long,
                    ) {
                        kind = listOf("log", "access", "all")[position]
                    }

                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }
        }
        val levels = Spinner(this).apply {
            adapter =
                ArrayAdapter(
                    this@LogsActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    listOf("debug", "info", "warn", "error"),
                )
            setSelection(0)
            onItemSelectedListener =
                object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?,
                        view: android.view.View?,
                        position: Int,
                        id: Long,
                    ) {
                        level = listOf("debug", "info", "warn", "error")[position]
                    }

                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }
        }
        controls.addView(kinds)
        controls.addView(levels)
        controls.addView(
            Button(this).apply {
                text = "Back"
                setOnClickListener { finish() }
            }
        )
        root.addView(controls)
        view =
            TextView(this).apply {
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
                gravity = Gravity.START
            }
        root.addView(ScrollView(this).apply { addView(view) })
        setContentView(root)
        handler.post(refresh)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        super.onDestroy()
    }

    private fun render() {
        val entries = app.journal.tail(500, JournalFilter.of(kind, level, null, null))
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        view.text =
            buildString {
                for (i in 0 until entries.length()) {
                    val e = entries.getJSONObject(i)
                    val time = fmt.format(Date(e.optLong("unixMs")))
                    if (e.optString("kind") == "access") {
                        append(
                            "$time ${e.optString("outcome")} ${e.optString("method")} " +
                                "(${e.optJSONArray("params")?.toString() ?: "[]"}) " +
                                "${e.optLong("ms")}ms ${e.optString("peer")}\n"
                        )
                    } else {
                        append(
                            "$time ${e.optString("level")} ${e.optString("component")} " +
                                "${e.optString("message")}\n"
                        )
                    }
                }
            }
    }
}
