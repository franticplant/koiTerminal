package io.github.franticplant.koiterminal.devd

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
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
    private lateinit var scroll: ScrollView
    private lateinit var component: EditText
    private lateinit var grep: EditText
    private var kind = "log"
    private var level = "debug"
    private var paused = false
    @Volatile private var appLogsText = ""
    @Volatile private var appLogsBusy = false

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
                    listOf("log", "access", "all", "app"),
                )
            onItemSelectedListener =
                object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?,
                        view: android.view.View?,
                        position: Int,
                        id: Long,
                    ) {
                        kind = listOf("log", "access", "all", "app")[position]
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
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        component =
            EditText(this).apply {
                hint = "component"
                setSingleLine()
            }
        grep =
            EditText(this).apply {
                hint = "grep"
                setSingleLine()
            }
        filters.addView(component)
        filters.addView(grep)
        filters.addView(
            Button(this).apply {
                text = "Apply"
                setOnClickListener { render() }
            }
        )
        root.addView(filters)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(
            CheckBox(this).apply {
                text = "Pause"
                setOnCheckedChangeListener { _, checked -> paused = checked }
            }
        )
        actions.addView(
            Button(this).apply {
                text = "Copy"
                setOnClickListener {
                    getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("koi devd logs", view.text))
                }
            }
        )
        root.addView(actions)
        view =
            TextView(this).apply {
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
                gravity = Gravity.START
            }
        scroll = ScrollView(this).apply { addView(view) }
        root.addView(scroll)
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

    private fun refreshAppLogs() {
        if (appLogsBusy) return
        appLogsBusy = true
        Thread {
                val text =
                    try {
                        app.hooks.get(5_000)
                        val reply = app.hooks.call("app.logs", JSONObject().put("max", 500))
                        val err = reply.optJSONObject("error")
                        if (err != null) {
                            "error: ${err.optString("code")} ${err.optString("message")}"
                        } else {
                            val entries = reply.optJSONObject("result")?.optJSONArray("entries")
                            val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                            buildString {
                                for (i in 0 until (entries?.length() ?: 0)) {
                                    val e = entries!!.getJSONObject(i)
                                    append(
                                        "${fmt.format(Date(e.optLong("unixMs")))} " +
                                            "${e.optString("level")} ${e.optString("component")} " +
                                            "${e.optString("message")}\n"
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        "error: ${e.message ?: e.javaClass.simpleName}"
                    }
                appLogsText = text
                appLogsBusy = false
                runOnUiThread { if (kind == "app" && !isFinishing && !isDestroyed) view.text = appLogsText }
            }
            .start()
    }

    private fun render() {
        if (paused) return
        if (kind == "app") {
            view.text = appLogsText
            refreshAppLogs()
            return
        }
        val entries =
            app.journal.tail(
                500,
                JournalFilter.of(
                    kind,
                    level,
                    component.text.toString().takeIf { it.isNotEmpty() },
                    grep.text.toString().takeIf { it.isNotEmpty() },
                ),
            )
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
        scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }
}
