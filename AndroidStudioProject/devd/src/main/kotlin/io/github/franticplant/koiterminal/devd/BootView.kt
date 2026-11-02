package io.github.franticplant.koiterminal.devd

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val ERROR = Color(0xFFE06C75)
private val WARN = Color(0xFFE5C07B)
private val OK = Color(0xFF98C379)
private val DIM = Color(0xFF9AA4B2)

private val ALIVE = setOf("starting", "running", "stopping", "rebooting")

private fun JSONObject.array(name: String): List<String> =
    optJSONArray(name)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()

private fun stripAnsi(text: String): String =
    text.replace(Regex("\\u001B\\[[0-9;?]*[ -/]*[@-~]"), "")
        .replace(Regex("\\u001B\\][^\\u0007]*\\u0007"), "")
        .replace("\r\n", "\n")
        .replace('\r', '\n')

/** Boot profiles, lifecycle, the last boot plan and a small serial console. */
@Composable
fun BootView(app: RemoteDevd) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var profiles by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var confirmRestart by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var removeFile by remember { mutableStateOf<String?>(null) }
    var console by remember { mutableStateOf("") }
    var cursor by remember { mutableLongStateOf(-1) }
    var inputArmed by remember { mutableStateOf(false) }
    var armPrompt by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var newline by remember { mutableStateOf(true) }
    var editParams by remember { mutableStateOf("") }
    var editMemory by remember { mutableStateOf("") }
    var editNetwork by remember { mutableStateOf(false) }

    fun fail(what: String, e: Throwable) {
        error = "$what: ${e.message ?: e.javaClass.simpleName}"
    }

    suspend fun loadDetail(name: String?) {
        if (name == null) {
            detail = null
            return
        }
        detail = app.appCall("profile.get", JSONObject().put("name", name)) as? JSONObject
        val cfg = detail?.optJSONObject("config")
        editParams = cfg?.optString("params").orEmpty()
        editMemory = cfg?.optInt("memory_mib", 0).toString()
        editNetwork = cfg?.optBoolean("network", false) ?: false
    }

    suspend fun refresh() {
        status = app.appCall("vm.status") as? JSONObject
        val list = app.appCall("profile.list") as? JSONObject
        profiles =
            list?.optJSONArray("profiles")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }
            } ?: emptyList()
    }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                refresh()
                val info =
                    app.appCall("console.info") as? JSONObject
                if (info?.optBoolean("attached") == true) {
                    val params =
                        JSONObject()
                            .put("max", 32 * 1024)
                            .put("waitMs", 0)
                    if (cursor >= 0) params.put("after", cursor)
                    val read = app.appCall("console.read", params) as? JSONObject
                    if (read != null) {
                        val bytes =
                            runCatching { Base64.getDecoder().decode(read.optString("data")) }
                                .getOrDefault(ByteArray(0))
                        val nextCursor = read.optLong("cursor", cursor)
                        if (cursor >= 0 && nextCursor < cursor) {
                            // The ring restarted (new VM start); the old text belongs to it.
                            console = ""
                        }
                        if (bytes.isNotEmpty()) {
                            console =
                                (console + String(bytes, Charsets.UTF_8)).takeLast(32 * 1024)
                        }
                        cursor = nextCursor
                        if (read.optBoolean("gap")) {
                            console += "\n[... older output dropped ...]\n"
                        }
                    }
                }
                error = null
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            delay(1_000)
        }
    }

    LaunchedEffect(selected) {
        runCatching { loadDetail(selected) }.onFailure { fail("profile", it) }
    }

    fun start(source: String?) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val params = JSONObject()
                if (source != null) params.put("profile", source)
                app.appCall("vm.start", params)
                status = app.awaitVm("running", 120_000)
                refresh()
            } catch (e: Exception) {
                fail("start", e)
            } finally {
                busy = false
            }
        }
    }

    fun restart() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val params = JSONObject()
                val running = status?.optJSONObject("boot")?.optString("source").orEmpty()
                val profile = selected ?: running.removePrefix("profile:").takeIf { it != running }
                if (profile != null) params.put("profile", profile)
                app.appCall("vm.restart", params)
                status = app.awaitVm("running", 120_000)
                refresh()
            } catch (e: Exception) {
                fail("restart", e)
            } finally {
                busy = false
            }
        }
    }

    fun lifecycle(method: String, label: String) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                app.appCall(method)
                if (method == "vm.stop") status = app.awaitVm("stopped", 60_000)
                else if (method == "vm.shutdown") status = app.awaitVm("stopped", 90_000)
                refresh()
            } catch (e: Exception) {
                fail(label, e)
            } finally {
                busy = false
            }
        }
    }

    fun saveProfile() {
        val name = selected ?: return
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                app.appCall(
                    "profile.set",
                    JSONObject().put("name", name).put("key", "params").put("value", JSONObject.quote(editParams)),
                )
                editMemory.trim().toIntOrNull()?.let {
                    app.appCall(
                        "profile.set",
                        JSONObject().put("name", name).put("key", "memory_mib").put("value", it.toString()),
                    )
                }
                app.appCall(
                    "profile.set",
                    JSONObject().put("name", name).put("key", "network").put("value", editNetwork.toString()),
                )
                loadDetail(name)
            } catch (e: Exception) {
                fail("save", e)
            } finally {
                busy = false
            }
        }
    }

    fun sendInput() {
        if (!inputArmed || input.isEmpty()) return
        val text = input + if (newline) "\n" else ""
        input = ""
        scope.launch {
            try {
                app.appCall(
                    "console.write",
                    JSONObject().put("data", text),
                )
            } catch (e: Exception) {
                fail("console", e)
            }
        }
    }

    val vmName = status?.optJSONObject("vm")?.optString("name") ?: "unknown"
    val alive = vmName in ALIVE
    val boot = status?.optJSONObject("boot")
    val lastExit = status?.optJSONObject("lastExit")
    val problems = detail?.optJSONArray("problems")?.length() ?: 0

    Column(
        Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        error?.let { Text(it, color = ERROR, fontSize = 12.sp) }

        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Machine", fontWeight = FontWeight.Bold)
                Text(
                    "state: $vmName" +
                        (boot?.optString("source")?.let { " · source: $it" } ?: ""),
                    color = if (vmName == "error") ERROR else if (alive) OK else DIM,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { start(selected) },
                        enabled = !busy && !alive && (selected == null || problems == 0),
                    ) { Text(if (selected == null) "Start installed" else "Start profile") }
                    OutlinedButton(
                        onClick = { confirmRestart = true },
                        enabled = !busy && alive,
                    ) { Text("Restart") }
                    OutlinedButton(
                        onClick = { confirmStop = true },
                        enabled = !busy && alive,
                    ) { Text("Force stop") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { lifecycle("vm.shutdown", "shutdown") },
                        enabled = !busy && alive,
                    ) { Text("Shutdown") }
                    TextButton(
                        onClick = { lifecycle("vm.reset", "reset") },
                        enabled = !busy && vmName == "error",
                    ) { Text("Clear error") }
                }
            }
        }

        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Boot source", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = selected == null,
                        onClick = { selected = null },
                        label = { Text("Installed") },
                    )
                    for (p in profiles) {
                        FilterChip(
                            selected = selected == p.optString("name"),
                            onClick = { selected = p.optString("name") },
                            label = { Text(p.optString("name")) },
                        )
                    }
                }
                TextButton(onClick = { showNew = true }, enabled = !busy) { Text("New profile") }
                if (selected != null) {
                    val d = detail
                    if (d == null) {
                        Text("loading…", color = DIM)
                    } else {
                        Text("base: ${d.optString("base")} · agent: ${d.optString("agent")}")
                        val probs = d.optJSONArray("problems")
                        if (probs != null && probs.length() > 0) {
                            for (i in 0 until probs.length()) {
                                Text(probs.optString(i), color = WARN, fontSize = 12.sp)
                            }
                        } else {
                            Text("ready", color = OK, fontSize = 12.sp)
                        }
                        val files = d.optJSONArray("files")
                        if (files != null && files.length() > 0) {
                            Text("Files", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            for (i in 0 until files.length()) {
                                val f = files.optJSONObject(i) ?: continue
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${f.optString("name")}  ${f.optLong("size") / 1024} KiB",
                                        Modifier.weight(1f),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                    )
                                    TextButton(
                                        onClick = { removeFile = f.optString("name") },
                                        enabled = !busy && !alive,
                                    ) { Text("remove") }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = { confirmDelete = true },
                                enabled = !busy && !alive,
                            ) { Text("Delete profile") }
                        }
                    }
                }
            }
        }

        if (selected != null && detail != null) {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Profile settings", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = editParams,
                        onValueChange = { editParams = it },
                        label = { Text("kernel command line (params)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = editMemory,
                        onValueChange = { editMemory = it },
                        label = { Text("memory_mib") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("network", Modifier.weight(1f))
                        Switch(checked = editNetwork, onCheckedChange = { editNetwork = it })
                    }
                    Text(
                        "Changes take effect at the next start.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DIM,
                    )
                    Button(onClick = ::saveProfile, enabled = !busy) { Text("Save profile") }
                }
            }
        }

        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Console", fontWeight = FontWeight.Bold)
                Text(
                    if (inputArmed) "input armed: keys can run as root" else "read-only",
                    color = if (inputArmed) WARN else DIM,
                    fontSize = 12.sp,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enable input", Modifier.weight(1f))
                    Switch(
                        checked = inputArmed,
                        onCheckedChange = {
                            if (it) armPrompt = true
                            else inputArmed = false
                        },
                    )
                }
                Text(
                    stripAnsi(console).takeLast(16 * 1024).ifEmpty { "no serial output yet" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    modifier =
                        Modifier.fillMaxWidth()
                            .background(Color(0xFF111318))
                            .padding(6.dp),
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("console input") },
                    enabled = inputArmed,
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("append newline", Modifier.weight(1f), fontSize = 12.sp)
                    Switch(checked = newline, onCheckedChange = { newline = it }, enabled = inputArmed)
                    Button(onClick = ::sendInput, enabled = inputArmed && input.isNotEmpty()) {
                        Text("Send")
                    }
                }
            }
        }

        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Last boot", fontWeight = FontWeight.Bold)
                if (boot == null) {
                    Text("no boot recorded", color = DIM)
                } else {
                    Text("source: ${boot.optString("source")}")
                    Text("applied: ${boot.array("applied").joinToString()}", fontSize = 12.sp)
                    Text("skipped: ${boot.array("skipped").joinToString()}", fontSize = 12.sp)
                }
                if (lastExit != null) {
                    Text(
                        "last exit: ${lastExit.optString("name")} (${lastExit.optInt("reason")}) " +
                            "after ${lastExit.optLong("uptimeMs") / 1000}s",
                        color = if (lastExit.optString("name") == "SHUTDOWN") OK else WARN,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }

    if (showNew) {
        var name by remember { mutableStateOf("") }
        var base by remember { mutableStateOf("none") }
        AlertDialog(
            onDismissRequest = { showNew = false },
            title = { Text("New profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("name, e.g. k1") },
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = base == "none",
                            onClick = { base = "none" },
                            label = { Text("standalone") },
                        )
                        FilterChip(
                            selected = base == "installed",
                            onClick = { base = "installed" },
                            label = { Text("overlay installed") },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNew = false
                        scope.launch {
                            try {
                                app.appCall(
                                    "profile.create",
                                    JSONObject().put("name", name).put("base", base),
                                )
                                selected = name
                                refresh()
                            } catch (e: Exception) {
                                fail("create", e)
                            }
                        }
                    },
                    enabled = name.isNotBlank(),
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNew = false }) { Text("Cancel") } },
        )
    }

    if (confirmRestart) {
        AlertDialog(
            onDismissRequest = { confirmRestart = false },
            title = { Text("Restart the VM?") },
            text = { Text("The guest is stopped and started again. Unsaved guest data can be lost.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRestart = false
                        restart()
                    }
                ) { Text("Restart") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestart = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Force stop?") },
            text = { Text("This is not a guest shutdown. Unsaved guest data can be lost.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmStop = false
                        lifecycle("vm.stop", "stop")
                    }
                ) { Text("Force stop") }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmDelete) {
        val name = selected
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete profile $name?") },
            text = { Text("Its files are deleted. The installed image is not touched.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        if (name != null)
                            scope.launch {
                                try {
                                    app.appCall("profile.delete", JSONObject().put("name", name))
                                    selected = null
                                    refresh()
                                } catch (e: Exception) {
                                    fail("delete", e)
                                }
                            }
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }

    removeFile?.let { file ->
        val name = selected
        AlertDialog(
            onDismissRequest = { removeFile = null },
            title = { Text("Remove $file?") },
            text = { Text("The profile may stop booting until the file is pushed again.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        removeFile = null
                        if (name != null)
                            scope.launch {
                                try {
                                    app.appCall(
                                        "staging.remove",
                                        JSONObject().put("profile", name).put("name", file),
                                    )
                                    loadDetail(name)
                                } catch (e: Exception) {
                                    fail("remove", e)
                                }
                            }
                    }
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { removeFile = null }) { Text("Cancel") }
            },
        )
    }

    if (armPrompt) {
        AlertDialog(
            onDismissRequest = { armPrompt = false },
            title = { Text("Enable serial input?") },
            text = {
                Text(
                    "Serial input usually runs as root inside the guest. Only type commands you " +
                        "understand, and disable it when you leave this screen."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        armPrompt = false
                        inputArmed = true
                    }
                ) { Text("I understand") }
            },
            dismissButton = { TextButton(onClick = { armPrompt = false }) { Text("Cancel") } },
        )
    }
}
