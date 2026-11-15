package app.rawline.mobile.outbound.config

import app.rawline.mobile.outbound.json.JsonArray
import app.rawline.mobile.outbound.json.JsonInput
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.json.jsonObjectOf
import app.rawline.mobile.R
import app.rawline.mobile.validation.vtr

/**
 * A DNS rule of the settings: the sites it lists resolve through its own server, through the proxy or directly.
 * The sites take the NekoBox / v2ray forms, one per line: `domain:` (with subdomains, also a bare name), `full:`,
 * `keyword:`, `regexp:` and `geosite:<name>` for a list of the rule-set catalog.
 */
data class DnsRule(
    val enabled: Boolean = true,
    val sites: List<String> = emptyList(),
    val server: String = "",
    val direct: Boolean = false,
) {
    fun toJson(): JsonObject = jsonObjectOf(
        "enabled" to enabled,
        "sites" to JsonArray().apply { sites.forEach { add(it) } },
        "server" to server,
        "direct" to direct,
    )

    /** Why the core could not use the rule, "" when it can. A disabled rule is never checked. */
    fun problem(): String {
        if (!enabled) return ""
        if (DnsRules.parse(sites).isEmpty()) return vtr(R.string.dns_rules_no_sites)
        DnsRules.siteProblem(sites)?.let { return it }
        val address = DnsServers.parseDnsAddress(server)
        if (address.address == null) return vtr(R.string.dns_rules_bad_server, server, address.why)
        return ""
    }

    companion object {
        fun fromJson(obj: JsonObject) = DnsRule(
            enabled = obj["enabled"] as? Boolean ?: true,
            sites = (obj["sites"] as? JsonArray)?.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }.orEmpty(),
            server = (obj["server"] as? String).orEmpty().trim(),
            direct = obj["direct"] as? Boolean ?: false,
        )
    }
}

object DnsRules {
    /** The stored list; anything unreadable reads as no rules, so a damaged setting never stops a connection. */
    fun decode(stored: String): List<DnsRule> {
        if (stored.isBlank()) return emptyList()
        val array = JsonInput.parseValue(stored) as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonObject)?.let(DnsRule::fromJson) }
    }

    fun encode(rules: List<DnsRule>): String = JsonArray().apply { rules.forEach { add(it.toJson()) } }.toCompact()

    /** The sites of a text, one per line or separated by commas; blank lines and `#` notes are left out. */
    fun lines(text: String): List<String> =
        text.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    fun withRuleSets(sites: List<String>, selected: List<String>): List<String> =
        sites.filterNot { it.substringBefore(':').lowercase() in setOf("geosite", "rule-set") } +
            selected.distinct().map { "rule-set:$it" }

    class Match {
        val domains = ArrayList<String>()
        val suffixes = ArrayList<String>()
        val keywords = ArrayList<String>()
        val regexes = ArrayList<String>()
        /** Catalog names: `geosite:x` is the rule-set `geosite-x`. */
        val ruleSets = ArrayList<String>()
        fun isEmpty() = domains.isEmpty() && suffixes.isEmpty() && keywords.isEmpty() && regexes.isEmpty() && ruleSets.isEmpty()
    }

    fun parse(sites: List<String>): Match {
        val match = Match()
        for (raw in sites) {
            val site = raw.trim()
            if (site.isEmpty()) continue
            val colon = site.indexOf(':')
            val kind = if (colon > 0) site.substring(0, colon).lowercase() else ""
            val value = if (colon > 0) site.substring(colon + 1).trim() else site
            if (value.isEmpty()) continue
            when (kind) {
                "full" -> match.domains += value.lowercase()
                "keyword" -> match.keywords += value
                "regexp", "regex" -> match.regexes += value
                "geosite" -> match.ruleSets += "geosite-" + value.lowercase()
                "rule-set" -> match.ruleSets += value
                // `domain:` and a bare name both take the subdomains, as v2ray reads them.
                else -> match.suffixes += value.removePrefix(".").lowercase()
            }
        }
        return match
    }

    /** A regex the core would refuse, or an unknown kind, named for the user; null when all are fine. */
    fun siteProblem(sites: List<String>): String? {
        for (raw in sites) {
            val site = raw.trim()
            val colon = site.indexOf(':')
            val kind = if (colon > 0) site.substring(0, colon).lowercase() else ""
            if (kind !in KINDS) return vtr(R.string.dns_rules_unknown_kind, kind, site)
            if (kind == "regexp" || kind == "regex") {
                val value = site.substring(colon + 1).trim()
                if (runCatching { Regex(value) }.isFailure) return vtr(R.string.dns_rules_bad_regex, value)
            }
        }
        return null
    }

    private val KINDS = setOf("", "domain", "full", "keyword", "regexp", "regex", "geosite", "rule-set")
}
