package io.github.franticplant.koiterminal.devd

import org.json.JSONObject

/** A short, truthful line for a control result. Kept pure so host tests can cover it. */
internal fun summarizeControl(method: String, result: Any?): String {
    val o = result as? JSONObject
    return when (method) {
        "app.info" ->
            "pid ${o?.optInt("pid")} · ${o?.optString("versionName")} " +
                "(${o?.optLong("versionCode")})"
        "vm.status",
        "vm.start",
        "vm.stop",
        "vm.shutdown",
        "vm.reset" -> {
            val vm = o?.optJSONObject("vm")?.optString("name") ?: o?.optString("name")
            val install = o?.optJSONObject("install")?.optString("name")
            "vm $vm · install $install"
        }
        "settings.get" ->
            "${o?.optInt("memoryMiB")} MiB · ${o?.optString("network")} · " +
                "keep-awake ${o?.optBoolean("keepAwake")}"
        "app.restart" -> "pid ${o?.optInt("oldPid")} → ${o?.optInt("newPid")}"
        else -> "ok"
    }
}
