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
import io.github.franticplant.koiterminal.dev.api.DevIds
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
    private lateinit var controlsStatus: TextView
    private lateinit var controlsBox: LinearLayout
    private lateinit var pairingDetail: TextView
    private lateinit var pairingBanner: TextView
    @Volatile private var busy = false
    @Volatile private var lastControlResult: String? = null
    @Volatile private var recentInstalls: String = ""
    @Volatile private var installsBusy = false

    private val refresh =
        object : Runnable {
            override fun run() {
                render()
                handler.postDelayed(this, 2_000)
            }
        }

    private val installsRefresh =
        object : Runnable {
            override fun run() {
                if (installsBusy) {
                    handler.postDelayed(this, 5_000)
                    return
                }
                installsBusy = true
                Thread {
                        val text =
                            try {
                                val installs = app.updater.status()
                                (0 until minOf(3, installs.length())).joinToString("\n") { i ->
                                    val r = installs.getJSONObject(i)
                                    "  ${r.optString("package")} ${r.optString("versionName")} " +
                                        "${r.optString("state")}" +
                                        (r.optString("message").takeIf { it.isNotEmpty() }?.let {
                                            " ($it)"
                                        } ?: "")
                                }
                            } catch (e: Exception) {
                                "  status error: ${e.message ?: e.javaClass.simpleName}"
                            }
                        recentInstalls = text
                        installsBusy = false
                        runOnUiThread { if (!isFinishing && !isDestroyed) render() }
                    }
                    .start()
                handler.postDelayed(this, 5_000)
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
        handler.post(installsRefresh)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(installsRefresh)
        handler.post(refresh)
        handler.post(installsRefresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(installsRefresh)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(installsRefresh)
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

        pairingBanner = TextView(this).apply {
            textSize = 15f
            setPadding(16, 16, 16, 16)
            visibility = View.GONE
            setBackgroundColor(0x33ffcc00)
        }
        root.addView(pairingBanner)

        root.addView(section("koiTerminal"))
        controlsStatus = TextView(this).apply {
            textSize = 13f
            setPadding(0, 0, 0, 8)
        }
        root.addView(controlsStatus)
        controlsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(controlsBox)
        controlsBox.addView(
            buttonRow(
                "Start app" to { callApp("app.info") },
                "VM status" to { callApp("vm.status") },
                "Settings" to { callApp("settings.get") },
                "Refresh" to { callApp("app.info") },
            )
        )
        controlsBox.addView(
            buttonRow(
                "VM start" to { callApp("vm.start") },
                "VM stop" to { callApp("vm.stop") },
            )
        )
        controlsBox.addView(
            buttonRow(
                "Graceful shutdown" to { callApp("vm.shutdown") },
                "Restart app" to { callDevd("app.restart") },
                "Interfaces" to { recreate() },
            )
        )
        controlsBox.addView(
            Button(this).apply {
                text = "Open koiTerminal"
                setOnClickListener {
                    val intent = packageManager.getLaunchIntentForPackage(DevIds.KOI_PACKAGE)
                    if (intent == null) {
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "koiTerminal is not installed",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        startActivity(intent)
                    }
                }
            }
        )
        root.addView(
            Button(this).apply {
                text = "Show logs"
                setOnClickListener {
                    startActivity(android.content.Intent(this@MainActivity, LogsActivity::class.java))
                }
            }
        )

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
        root.addView(
            TextView(this).apply {
                text = "Applies to the next start; use +1 hour in the notification to extend."
                textSize = 11f
            }
        )

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
        pairingDetail = TextView(this).apply { textSize = 13f }
        root.addView(pairingDetail)
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

    private fun buttonRow(vararg buttons: Pair<String, () -> Unit>): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, action) in buttons) {
            row.addView(
                Button(this).apply {
                    text = label
                    setOnClickListener { if (!busy) action() }
                }
            )
        }
        return row
    }

    /** Runs a devd-owned method off the UI thread. */
    private fun callDevd(method: String) {
        if (busy) return
        busy = true
        controlsStatus.text = "$method…"
        Thread {
            val outcome =
                try {
                    val reply = app.methods.table.call(method, JSONObject())
                    val err = reply.optJSONObject("error")
                    if (err != null) {
                        "error ${err.optString("code")}: ${err.optString("message")}"
                    } else {
                        val result = reply.optJSONObject("result")
                        if (method == "app.restart")
                            "pid ${result?.optInt("oldPid")} -> ${result?.optInt("newPid")}"
                        else "ok"
                    }
                } catch (e: Exception) {
                    "error: ${e.message ?: e.javaClass.simpleName}"
                }
            runOnUiThread {
                busy = false
                lastControlResult = "$method: $outcome"
                if (!isFinishing && !isDestroyed) render()
            }
        }.start()
    }

    /** Runs a DevHooks call off the UI thread; Binder and packaging can block. */
    private fun callApp(method: String) {
        if (busy) return
        busy = true
        controlsStatus.text = "$method…"
        Thread {
            val outcome =
                try {
                    app.hooks.get(10_000)
                    val reply = app.hooks.call(method, JSONObject())
                    val err = reply.optJSONObject("error")
                    if (err != null) {
                        "error ${err.optString("code")}: ${err.optString("message")}"
                    } else {
                        val result = reply.optJSONObject("result")
                        when (method) {
                            "app.info" ->
                                "pid ${result?.optInt("pid")} ${result?.optString("versionName")}"
                            "vm.status" -> {
                                val vm = result?.optJSONObject("vm")?.optString("name")
                                val install = result?.optJSONObject("install")?.optString("name")
                                "vm $vm, install $install"
                            }
                            "vm.start",
                            "vm.stop",
                            "vm.shutdown",
                            "vm.reset" -> result?.optString("name") ?: "ok"
                            "app.restart" ->
                                "pid ${result?.optInt("oldPid")} -> ${result?.optInt("newPid")}"
                            "settings.get" ->
                                "${result?.optInt("memoryMiB")} MiB, " +
                                    "${result?.optString("network")}, keep-awake " +
                                    "${result?.optBoolean("keepAwake")}"
                            else -> "ok"
                        }
                    }
                } catch (e: Exception) {
                    "error: ${e.message ?: e.javaClass.simpleName}"
                }
            runOnUiThread {
                busy = false
                lastControlResult = "$method: $outcome"
                if (!isFinishing && !isDestroyed) render()
            }
        }.start()
    }

    private fun packageLine(pkg: String): String {
        val info =
            try {
                packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            } catch (e: PackageManager.NameNotFoundException) {
                return "$pkg: not installed"
            } catch (e: Exception) {
                return "$pkg: unavailable (${e.message ?: e.javaClass.simpleName})"
            }
        val signer =
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.let { Tls.sha256Hex(it.toByteArray()) }
        val pinned = signer != null && signer == BuildConfig.PINNED_CERT_SHA256
        val source =
            try {
                packageManager.getInstallSourceInfo(pkg)
            } catch (e: Exception) {
                null
            }
        return "$pkg ${info.versionName} (${info.longVersionCode})" +
            " signer ${if (pinned) "pinned" else "NOT pinned"}" +
            (source?.installingPackageName?.let { " from $it" } ?: "") +
            (source?.updateOwnerPackageName?.let { " owner $it" } ?: "")
    }

    private fun render() {
        val req = app.pairings.pending.value
        pairingBanner.visibility = if (req == null) View.GONE else View.VISIBLE
        if (req != null) {
            pairingBanner.text =
                "Pairing request from ${req.name}: code ${req.code}. Open the Pairing section below."
        }
        val offAt = app.offAtMs.value
        val listening = app.server.addresses.value.isNotEmpty()
        val starting = offAt != null && !listening
        status.text =
            when {
                listening ->
                    "Listener: on" +
                        (offAt?.let {
                            " until " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))
                        } ?: "")
                starting -> "Listener: starting"
                else -> "Listener: off"
            }
        toggle.text = if (offAt != null || listening) "Stop listener" else "Start listener"
        val binds = app.server.binds.value
        addresses.text =
            buildString {
                if (listening) append(app.server.addresses.value.joinToString("\n"))
                else if (starting) append("starting…")
                else append("not listening")
                append("\nport ${app.settings.values.value.port}")
                append(" · connections ${app.server.activeConnections}")
                val fingerprint =
                    try {
                        app.server.fingerprint.take(16)
                    } catch (e: Exception) {
                        "unavailable"
                    }
                append("\nserver key $fingerprint")
                for (b in binds) {
                    append("\n${b.iface}: ${b.state}")
                    b.address?.let { append(" $it") }
                    if (b.detail.isNotEmpty()) append(" — ${b.detail}")
                }
            }

        val recent = recentInstalls
        installStatus.text =
            (if (app.packageManager.canRequestPackageInstalls())
                "Allowed: devd can install koiTerminal packages."
            else "Not allowed: Android will ask for a tap, or enable it here.") +
                (if (recent.isNotEmpty()) "\nrecent installs:\n$recent" else "")
        installBox.removeAllViews()
        app.pendingInstall?.let { (installId, confirm) ->
            installStatus.text = "Android needs a tap to finish the install."
            installBox.addView(
                Button(this).apply {
                    text = "Confirm pending install"
                    setOnClickListener {
                        try {
                            startActivity(confirm)
                            if (app.pendingInstall?.first == installId) app.pendingInstall = null
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

        controlsStatus.text =
            packageLine(DevIds.KOI_PACKAGE) + "\n" +
                packageLine(DevIds.DEVD_PACKAGE) + "\n" +
                "hooks: " + (if (app.hooks.connected) "connected" else "not connected") +
                (lastControlResult?.let { "\nlast command: $it" } ?: "")

        pairingDetail.text =
            if (req == null) ""
            else {
                val left = ((req.createdMs + Pairings.REQUEST_TIMEOUT_MS - System.currentTimeMillis()) / 1000)
                    .coerceAtLeast(0)
                "code ${req.code} · from ${req.peer} on ${req.local} · " +
                    "client ${req.clientId.take(16)} · expires in ${left}s"
            }
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
                val help =
                    mapOf(
                        "read" to "state, logs, metrics",
                        "read.content" to "terminal/screen content",
                        "operate" to "VM start/stop, console size, ports",
                        "secrets" to "credentials (none used yet)",
                        "lifecycle" to "restart the app",
                        "scenario" to "test scenarios",
                        "update" to "install app and devd APKs",
                    )
                val chosen = LinkedHashMap<String, CheckBox>()
                for (scope in req.requestedScopes.sorted()) {
                    val cb =
                        CheckBox(this@MainActivity).apply {
                            text = "$scope — ${help[scope] ?: "additional access"}"
                            isChecked = true
                        }
                    chosen[scope] = cb
                    pairingBox.addView(cb)
                }
                fun choose(scopes: Set<String>) {
                    chosen.forEach { (scope, cb) -> cb.isChecked = scope in scopes }
                }
                val presets = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                presets.addView(
                    Button(this@MainActivity).apply {
                        text = "Read-only"
                        setOnClickListener { choose(setOf("read")) }
                    }
                )
                presets.addView(
                    Button(this@MainActivity).apply {
                        text = "Update only"
                        setOnClickListener { choose(setOf("update")) }
                    }
                )
                presets.addView(
                    Button(this@MainActivity).apply {
                        text = "All requested"
                        setOnClickListener { choose(req.requestedScopes) }
                    }
                )
                pairingBox.addView(presets)
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
