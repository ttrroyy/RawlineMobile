package app.rawline.mobile.group

import app.rawline.mobile.ktx.HttpGetOptions
import app.rawline.mobile.ktx.Logs
import app.rawline.mobile.ktx.httpGet
import app.rawline.mobile.outbound.json.JsonArray
import app.rawline.mobile.outbound.json.JsonInput
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.json.jsonObjectOf
import app.rawline.mobile.outbound.link.Base64Strict

/**
 * The apps a Happ or Incy subscription puts through the VPN or past it (`per-app-proxy-*` headers, or `#name: value`
 * lines in the body). They replace the user's own app mode while a server of that subscription runs; a subscription
 * that sends none of the fields leaves the user's settings alone.
 *
 * Happ: per-app-proxy-mode off|on|bypass, per-app-proxy-list (comma separated), per-app-proxy-list-invert (all apps
 * except the list). Incy: per-app-proxy-enable 1|0, per-app-proxy-mode proxy|bypass, per-app-proxy-list (CSV,
 * newlines, base64 or a URL of a text file).
 */
data class ProviderApps(val mode: String, val packages: List<String>) {
    val off: Boolean get() = mode == OFF
    val bypass: Boolean get() = mode == BYPASS

    fun toJson(): String = jsonObjectOf("mode" to mode, "list" to JsonArray().apply { packages.forEach { add(it) } }).toCompact()

    companion object {
        const val OFF = "off"
        const val PROXY = "proxy"
        const val BYPASS = "bypass"
        private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

        fun fromJson(text: String): ProviderApps? {
            val json = JsonInput.parseObjectOrNull(text) ?: return null
            val mode = json.string("mode")
            if (mode !in listOf(OFF, PROXY, BYPASS)) return null
            return ProviderApps(mode, json.array("list").strings())
        }

        /** The stored form, "" when the subscription says nothing about apps. */
        fun read(header: (String) -> String, body: String): String {
            val fields = HashMap<String, String>()
            for (key in KEYS) header(key).trim().takeIf { it.isNotEmpty() }?.let { fields[key] = it }
            // Body lines only fill what the headers left out: headers win, as in Incy.
            for (raw in body.lineSequence().take(200)) {
                val line = raw.trim()
                if (!line.startsWith('#')) continue
                val colon = line.indexOf(':')
                if (colon < 0) continue
                val key = line.substring(1, colon).trim().lowercase()
                if (key in KEYS && key !in fields) fields[key] = line.substring(colon + 1).trim()
            }
            return parse(fields)?.toJson().orEmpty()
        }

        private val KEYS = listOf("per-app-proxy-enable", "per-app-proxy-mode", "per-app-proxy-list", "per-app-proxy-list-invert", "per-app-proxy-list-set")

        internal fun parse(fields: Map<String, String>, fetch: (String) -> String = ::download): ProviderApps? {
            if (fields.isEmpty()) return null
            val enable = fields["per-app-proxy-enable"]?.trim()
            if (enable == "0" || enable.equals("false", true)) return ProviderApps(OFF, emptyList())
            var mode = when (fields["per-app-proxy-mode"]?.trim()?.lowercase()) {
                "off" -> return ProviderApps(OFF, emptyList())
                "bypass" -> BYPASS
                "on", "proxy" -> PROXY
                null, "" -> if (enable == "1" || enable.equals("true", true)) PROXY else null
                else -> null
            }
            val listText = fields["per-app-proxy-list"] ?: fields["per-app-proxy-list-invert"] ?: fields["per-app-proxy-list-set"]
            // "All apps except the list": the listed apps bypass the VPN.
            if (fields["per-app-proxy-list"] == null && fields["per-app-proxy-list-invert"] != null) mode = if (mode == BYPASS) PROXY else BYPASS
            mode ?: return null
            val packages = packages(listText.orEmpty(), fetch)
            // A mode without apps would put nothing (or everything) in the VPN: the user's settings stay instead.
            if (packages.isEmpty()) return null
            return ProviderApps(mode, packages)
        }

        internal fun packages(value: String, fetch: (String) -> String): List<String> {
            var text = value.trim()
            if (text.startsWith("http://", true) || text.startsWith("https://", true)) text = fetch(text)
            else if (!text.contains('.') || (!text.contains(',') && !text.contains('\n') && !PACKAGE.matches(text))) {
                Base64Strict.decodeToString(text.filterNot { it.isWhitespace() }).takeIf { it.contains('.') }?.let { text = it }
            }
            return text.split(',', '\n', ';', ' ').map { it.trim() }.filter { PACKAGE.matches(it) }.distinct()
        }

        private fun download(url: String): String {
            val response = httpGet(url, HttpGetOptions(maxBytes = 256L * 1024, timeoutSeconds = 15))
            if (!response.ok) {
                Logs.w("per-app-proxy-list: ${response.error}")
                return ""
            }
            return String(response.data, Charsets.UTF_8)
        }
    }
}
