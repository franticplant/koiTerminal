package io.github.franticplant.koiterminal.devd

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import org.json.JSONObject

/**
 * The phone-side control panel: switch the listener on, approve pairings, revoke them, and watch
 * the journal. There is deliberately no API to change any of this; it is only done here.
 */
class MainActivity : Activity() {
    private val app
        get() = application as DevdApp
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var addresses: TextView
    private lateinit var pairingBox: LinearLayout
    private lateinit var pairingsBox: LinearLayout
    private lateinit var logView: TextView
    private lateinit var autoOff: Spinner
    private lateinit var loopback: CheckBox
    private var renderedPairing: PairingRequest? = null
    private lateinit var installStatus: TextView
    private lateinit var installBox: LinearLayout

    private val refresh =
        object : Runnable {
            override fun run() {
                render()
                handler.postDelayed(this, 2_000)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissions = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
        if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), 1)
        setContentView(build())
        handler.post(refresh)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        super.onDestroy()
    }

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        root.addView(
            TextView(this).apply {
                text = "koiTerminal devd"
                textSize = 22f
                gravity = Gravity.START
            }
        )
        root.addView(
            TextView(this).apply {
                text =
                    "Development control plane. Keep the listener on only while you are working; " +
                        "it switches itself off after the chosen time."
                textSize = 13f
                setPadding(0, 12, 0, 24)
            }
        )

        status = TextView(this).apply { textSize = 15f }
        root.addView(status)

        toggle = Button(this).apply {
            text = "Start listener"
            setOnClickListener {
                if (app.offAtMs.value == null) DevdService.start(this@MainActivity)
                else DevdService.stop(this@MainActivity)
                handler.postDelayed({ render() }, 300)
            }
        }
        root.addView(toggle)

        addresses = TextView(this).apply {
            textSize = 12f
            setPadding(0, 8, 0, 16)
        }
        root.addView(addresses)

        root.addView(section("Auto-off"))
        autoOff =
            Spinner(this).apply {
                adapter =
                    ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        Settings.AUTO_OFF_CHOICES.map { "$it min" },
                    )
                setSelection(
                    Settings.AUTO_OFF_CHOICES.indexOf(app.settings.values.value.autoOffMinutes)
                        .coerceAtLeast(0)
                )
                onItemSelectedListener =
                    object : android.widget.AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(
                            parent: android.widget.AdapterView<*>?,
                            view: View?,
                            position: Int,
                            id: Long,
                        ) {
                            val v = app.settings.values.value
                            app.settings.update(v.copy(autoOffMinutes = Settings.AUTO_OFF_CHOICES[position]))
                        }

                        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                    }
            }
        root.addView(autoOff)

        loopback =
            CheckBox(this).apply {
                text = "Listen on loopback (127.0.0.1)"
                isChecked = app.settings.values.value.loopback
                setOnCheckedChangeListener { _, checked ->
                    val v = app.settings.values.value
                    app.settings.update(v.copy(loopback = checked))
                }
            }
        root.addView(loopback)

        for ((iface, addrs) in ipv4Interfaces()) {
            root.addView(
                CheckBox(this).apply {
                    text = "$iface (${addrs.joinToString { it.hostAddress ?: "?" }})"
                    isChecked = iface in app.settings.values.value.ifaces
                    setOnCheckedChangeListener { _, checked ->
                        val v = app.settings.values.value
                        app.settings.update(
                            v.copy(
                                ifaces =
                                    if (checked) v.ifaces + iface else v.ifaces - iface
                            )
                        )
                    }
                }
            )
        }

        root.addView(section("Install permission"))
        installStatus = TextView(this).apply { textSize = 13f }
        root.addView(installStatus)
        installBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(installBox)
        root.addView(
            Button(this).apply {
                text = "Allow installs from devd"
                setOnClickListener {
                    startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            android.net.Uri.parse("package:$packageName"),
                        )
                    )
                }
            }
        )

        root.addView(section("Pairing request"))
        pairingBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(pairingBox)

        root.addView(section("Paired clients"))
        pairingsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(pairingsBox)

        val screens = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        screens.addView(
            Button(this).apply {
                text = "Logs"
                setOnClickListener {
                    startActivity(android.content.Intent(this@MainActivity, LogsActivity::class.java))
                }
            }
        )
        screens.addView(
            Button(this).apply {
                text = "Metrics"
                setOnClickListener {
                    startActivity(
                        android.content.Intent(this@MainActivity, MetricsActivity::class.java)
                    )
                }
            }
        )
        root.addView(screens)

        root.addView(section("Journal"))
        logView =
            TextView(this).apply {
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
            }
        root.addView(logView)

        return ScrollView(this).apply { addView(root) }
    }

    private fun section(title: String): TextView =
        TextView(this).apply {
            text = title
            textSize = 17f
            setPadding(0, 28, 0, 8)
        }

    private fun render() {
        val offAt = app.offAtMs.value
        val running = offAt != null || app.server.addresses.value.isNotEmpty()
        status.text =
            if (running) "Listener: on" +
                (offAt?.let {
                    " until " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))
                } ?: "")
            else "Listener: off"
        toggle.text = if (running) "Stop listener" else "Start listener"
        addresses.text =
            app.server.addresses.value.joinToString("\n").ifEmpty {
                if (running) "starting…" else "not listening"
            }

        installStatus.text =
            if (app.packageManager.canRequestPackageInstalls())
                "Allowed: devd can install koiTerminal packages."
            else "Not allowed: Android will ask for a tap, or enable it here."
        installBox.removeAllViews()
        app.pendingInstall?.let { confirm ->
            installStatus.text = "Android needs a tap to finish the install."
            installBox.addView(
                Button(this).apply {
                    text = "Confirm pending install"
                    setOnClickListener {
                        try {
                            startActivity(confirm)
                            app.pendingInstall = null
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(
                                this@MainActivity,
                                e.message ?: "could not open the installer",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            )
        }

        val req = app.pairings.pending.value
        if (req !== renderedPairing) {
            renderedPairing = req
            pairingBox.removeAllViews()
            if (req == null) {
                pairingBox.addView(
                    TextView(this).apply {
                        text = "none"
                        textSize = 13f
                    }
                )
            } else {
                pairingBox.addView(
                    TextView(this).apply {
                        text =
                            "${req.name}\nCode: ${req.code}\nFrom: ${req.peer}\n" +
                                "Requested: ${req.requestedScopes.sorted().joinToString()}"
                        textSize = 14f
                    }
                )
                val chosen = LinkedHashMap<String, CheckBox>()
                for (scope in req.requestedScopes.sorted()) {
                    val cb =
                        CheckBox(this@MainActivity).apply {
                            text = scope
                            isChecked = true
                        }
                    chosen[scope] = cb
                    pairingBox.addView(cb)
                }
                val days =
                    Spinner(this@MainActivity).apply {
                        adapter =
                            ArrayAdapter(
                                this@MainActivity,
                                android.R.layout.simple_spinner_dropdown_item,
                                listOf("1 day", "7 days", "30 days", "90 days"),
                            )
                        setSelection(1)
                    }
                pairingBox.addView(days)
                pairingBox.addView(
                    Button(this@MainActivity).apply {
                        text = "Approve selected scopes"
                        setOnClickListener {
                            val scopes = chosen.filterValues { it.isChecked }.keys
                            val dayValues = listOf(1, 7, 30, 90)
                            try {
                                app.pairings.approve(req, scopes, dayValues[days.selectedItemPosition])
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(
                                    this@MainActivity,
                                    e.message ?: "approval failed",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                            renderedPairing = null
                            render()
                        }
                    }
                )
                pairingBox.addView(
                    Button(this@MainActivity).apply {
                        text = "Deny"
                        setOnClickListener {
                            app.pairings.deny(req, "denied on the phone")
                            renderedPairing = null
                            render()
                        }
                    }
                )
            }
        }

        pairingsBox.removeAllViews()
        val pairings = app.pairings.all.value
        if (pairings.isEmpty()) {
            pairingsBox.addView(TextView(this).apply { text = "none"; textSize = 13f })
        }
        for (p in pairings) {
            pairingsBox.addView(
                TextView(this).apply {
                    text =
                        "${p.name}\n${p.scopes.sorted().joinToString()} · expires " +
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(Date(p.expiresMs))
                    textSize = 13f
                }
            )
            pairingsBox.addView(
                Button(this).apply {
                    text = "Revoke ${p.name}"
                    setOnClickListener {
                        app.pairings.revoke(p)
                        render()
                    }
                }
            )
        }

        val entries = app.journal.tail(30, JournalFilter.of("log"))
        logView.text =
            (0 until entries.length()).joinToString("\n") { i ->
                val e = entries.getJSONObject(i)
                JSONObject(e.toString()).let {
                    "${it.optString("level")}/${it.optString("component")}: ${it.optString("message")}"
                }
            }
    }
}
