package app.rawline.mobile.deploy

import org.json.JSONObject

/**
 * The deploy of WDTT Panel: every step is a command of the panel's own installer, the same ones Rawline on the
 * computer runs and a user types in the shell. Nothing here touches Android, so it is checked by JVM tests.
 */
object PanelCommands {
    const val INSTALLER = "https://raw.githubusercontent.com/ttrroyy/wdtt-panel/main/install.sh"

    class Choice(val value: String, val title: String, val text: String)
    class Protocol(val id: String, val title: String, val group: Int)

    val roles = listOf(
        Choice("combined", "Панель и нода", "Веб-панель и VPN-протоколы на одном VPS. Подходит, если сервер один."),
        Choice("panel", "Только панель", "Пользователи, подписки и управление нодами. VPN-протоколы на этом VPS не ставятся, ноды подключаются из панели."),
        Choice("node", "Только нода", "VPN-протоколы без веб-панели. Нода подключается к панели на другом VPS по SSH."),
    )
    val access = listOf(
        Choice("nginx", "Nginx", "Панель и подписки по HTTPS на порту 443 с сертификатом Let's Encrypt. Если Nginx на VPS уже работает, панель добавится отдельным сайтом, остальные сайты не меняются."),
        Choice("caddy", "Caddy", "Панель и подписки по HTTPS на порту 443 с сертификатом Let's Encrypt. Если Caddy на VPS уже работает, панель добавится отдельным сайтом."),
        Choice("local", "Локальный порт", "Панель слушает только 127.0.0.1 на VPS и открывается из Rawline через SSH. Домен не нужен, порты 80 и 443 остаются свободными. Подписки по ссылке в этом режиме не работают, конфиги копируются из панели вручную. Самый совместимый режим."),
    )
    val protocols = listOf(
        Protocol("wdtt", "WDTT", 0), Protocol("qwdtt", "qWDTT", 0), Protocol("wdtt-plus", "WDTT Plus", 0),
        Protocol("csqtt-2.1.9", "CSQTT 2.1.9", 0), Protocol("csqtt-2.5.0", "CSQTT 2.5.0", 0),
        Protocol("csqtt-2.5.3-community", "CSQTT 2.5.3 (community)", 0), Protocol("freeturn", "FreeTurn", 0),
        Protocol("dnstt", "DNSTT", 1), Protocol("vaydns", "VayDNS", 1), Protocol("slipstream", "Slipstream", 1),
        Protocol("masterdns", "MasterDNS / StormDNS", 1), Protocol("cottendns", "CottenDNS", 1), Protocol("soar", "Soar", 1),
        Protocol("openflux", "OpenFlux Direct", 2),
    )
    val groups = listOf("VPN через звонки", "DNS-туннели (нужна делегированная зона)", "Прямое подключение")
    val defaultProtocols = protocols.filter { it.group == 0 }.map { it.id }

    private val hostname = Regex("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{0,61}[a-z0-9]$")
    private val email = Regex("^[^\\s@'\"\\\\]+@[^\\s@'\"\\\\]+\\.[^\\s@'\"\\\\]+$")
    private val sshHost = Regex("^[A-Za-z0-9.:_-]+$")
    private val sshUser = Regex("^[A-Za-z0-9_.-]+$")

    fun roleTitle(role: String) = roles.firstOrNull { it.value == role }?.title ?: "Ничего не установлено"
    fun accessTitle(mode: String) = when (mode) { "nginx" -> "HTTPS через Nginx"; "caddy" -> "HTTPS через Caddy"; else -> "Локальный порт через SSH" }
    fun split(value: String) = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    fun hasDns(names: List<String>) = protocols.any { it.group == 1 && it.id in names }
    fun protocolTitles(names: List<String>) = protocols.filter { it.id in names }.joinToString(", ") { it.title }.ifEmpty { "нет" }
    fun isHostname(value: String) = hostname.matches(value)

    fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
    /** Root runs it directly; another login goes through sudo, whose password is the first stdin line. */
    fun root(command: String) = "if [ \"\$(id -u)\" = 0 ]; then bash -c ${quote(command)}; else sudo -S -p '' bash -c ${quote(command)}; fi"
    fun rootStdin(command: String) = "if [ \"\$(id -u)\" = 0 ]; then $command; else sudo -S -p '' $command; fi"

    /** The current installer at /root/wdtt-install.sh; curl is added on images that lack it. */
    const val FETCH = "if ! command -v curl >/dev/null 2>&1; then apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq curl ca-certificates; fi; " +
        "curl -fsSL --retry 3 --max-time 120 $INSTALLER -o /root/wdtt-install.sh"

    fun validateConnection(server: DeployServer) {
        require(sshHost.matches(server["host"])) { "Укажите IP или домен VPS без схемы и порта" }
        require(sshUser.matches(server["user"])) { "Укажите логин SSH" }
        require(server["sshPort"].toIntOrNull() in 1..65535) { "Порт SSH: от 1 до 65535" }
        if (server["auth"] == "key") require(server["key"].isNotBlank()) { "Выберите SSH-ключ" }
        else require(server["sshPassword"].isNotEmpty()) { "Введите пароль SSH" }
    }

    fun role(value: String): String {
        require(value in listOf("panel", "node", "combined")) { "Выберите, что установить" }
        return value
    }

    /** The installer's answers, so it never waits for a terminal. */
    fun env(server: DeployServer): String {
        val names = split(server["protocols"])
        names.forEach { name -> require(protocols.any { it.id == name }) { "Неизвестный протокол: $name" } }
        val parts = mutableListOf("WDTT_PROTOCOLS=" + quote(names.joinToString(",")))
        when (server["access"]) {
            "local" -> parts += "WDTT_FRONTEND=local"
            "nginx", "caddy" -> {
                val domain = server["domain"].lowercase()
                require(isHostname(domain)) { "Укажите домен без схемы, порта и пути" }
                require(server["email"].isEmpty() || email.matches(server["email"])) { "Некорректный email" }
                parts += listOf("WDTT_FRONTEND=" + server["access"], "WDTT_DOMAIN=" + quote(domain), "WDTT_CERT_EMAIL=" + quote(server["email"]))
            }
            else -> throw IllegalArgumentException("Выберите способ доступа к панели")
        }
        for ((key, name) in listOf("dnsZone" to "WDTT_DNS_ZONE", "dnsNs" to "WDTT_DNS_NS")) {
            val value = server[key].lowercase()
            require(value.isEmpty() || isHostname(value)) { "Некорректное DNS-имя: $value" }
            parts += name + "=" + quote(value)
        }
        return parts.joinToString(" ")
    }

    fun status() = root("$FETCH && bash /root/wdtt-install.sh status")
    fun check(server: DeployServer) = root("$FETCH && env ${env(server)} bash /root/wdtt-install.sh check ${role(server["role"])}")
    fun install(server: DeployServer) = root("$FETCH && env ${env(server)} bash /root/wdtt-install.sh ${role(server["role"])}")
    fun uninstall(target: String): String {
        require(target in listOf("all", "panel", "node")) { "Неизвестное удаление" }
        return root("$FETCH && bash /root/wdtt-install.sh uninstall $target")
    }
    fun resolve(name: String): String {
        val lower = name.lowercase()
        require(isHostname(lower)) { "Укажите домен без схемы, порта и пути" }
        return root("getent ahostsv4 ${quote(lower)} | awk '{print \$1}' | sort -u")
    }
    const val CREDENTIALS = "/usr/local/bin/wdtt-panel-credentials"

    fun validCredential(value: String) = value.isNotEmpty() && value.length <= 256 && value.trim() == value && value.none { it == '\n' || it == '\r' || it == '\u0000' }

    /** Root reads the login straight away; sudo first takes its own password from stdin. */
    fun credentialsInput(uid: String, sudoPassword: String, user: String, password: String): String {
        require(validCredential(user) && validCredential(password)) { "Логин и пароль: без пробелов по краям и переводов строк, до 256 символов" }
        val login = "$user\n$password\n"
        return if (uid.trim() == "0") login else "$sudoPassword\n$login"
    }

    /** The installer's last JSON line; apt and curl may print before it. */
    fun parseStatus(output: String): JSONObject {
        val line = output.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("{") && it.endsWith("}") }
            ?: throw IllegalStateException("Установщик вернул неизвестный ответ")
        return JSONObject(line)
    }

    /** Puts the installer's status into the stored server. */
    fun apply(server: DeployServer, status: JSONObject) {
        val names = status.optJSONArray("protocols")?.let { array -> (0 until array.length()).map { array.optString(it) } }.orEmpty()
        server["installedRole"] = status.optString("role")
        server["mode"] = status.optString("mode")
        server["installedDomain"] = status.optString("domain")
        server["installedProtocols"] = names.joinToString(",")
        server["installedZone"] = status.optString("dns_zone")
        server["installedNs"] = status.optString("dns_ns")
        server["publicIp"] = status.optString("public_ip")
        server["panelUrl"] = status.optString("panel_url")
        server["panelPort"] = status.optInt("panel_port").toString()
        server["panelPath"] = status.optString("panel_path")
        server["panelUser"] = status.optString("username")
        server["panelPassword"] = status.optString("password")
        server["version"] = status.optString("version")
    }

    /** The installer's own message: its last non-empty line. */
    fun lastLine(output: String, fallback: String) = output.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: fallback

    /** A phase header of the installer ("==> Сборка панели"), shown as the install step. */
    fun step(line: String): String? = line.trim().takeIf { it.startsWith("==> ") }?.removePrefix("==> ")

    /** The check's text without phase headers. */
    fun checkText(output: String) = output.lineSequence().filterNot { it.startsWith("==> ") }.joinToString("\n").trim()

    /** A name must resolve, from the VPS, to the VPS: the installer's check sees it the same way. */
    fun resolveError(label: String, name: String, found: List<String>, publicIp: String): String? = when {
        found.isEmpty() -> "$label $name пока не находится в DNS. Проверьте запись и повторите через несколько минут."
        publicIp.isNotEmpty() && publicIp !in found -> "$label $name указывает на ${found.joinToString(", ")}, а IP этого VPS $publicIp."
        else -> null
    }

    fun ipv4(output: String) = output.split(Regex("\\s+")).filter { part ->
        val numbers = part.split('.')
        numbers.size == 4 && numbers.all { it.toIntOrNull() in 0..255 && it.isNotEmpty() && it.length <= 3 }
    }.distinct()

    /** The DNS zone's own rules, before its records are looked up. */
    fun zoneError(zone: String, ns: String, names: List<String>): String? = when {
        !isHostname(zone) || !isHostname(ns) -> "Укажите зону и NS-сервер, например t.example.org и ns.example.org"
        ns.endsWith(".$zone") -> "NS-сервер должен быть вне зоны $zone"
        "slipstream" in names && zone.length > 18 -> "Для Slipstream зона не длиннее 18 символов"
        "soar" in names && zone.length > 26 -> "Для Soar зона не длиннее 26 символов"
        else -> null
    }

    fun newServer() = DeployServer(JSONObject().put("id", java.util.UUID.randomUUID().toString()).put("kind", "panel")
        .put("name", "").put("user", "root").put("auth", "password").put("sshPort", "22"))

    /** Which parts the next install adds, keeping what the VPS already runs. */
    fun prepareAdd(server: DeployServer, part: String) {
        server["role"] = part
        server["access"] = server["mode"].ifEmpty { "local" }
        server["domain"] = server["installedDomain"]
        server["protocols"] = if (part == "node") "" else server["installedProtocols"]
        server["dnsZone"] = server["installedZone"]
        server["dnsNs"] = server["installedNs"]
    }

    /** Installing again over what the VPS runs: the same role, access, domain, protocols and DNS zone. */
    fun prepareReinstall(server: DeployServer) {
        prepareAdd(server, server["installedRole"])
        server["protocols"] = server["installedProtocols"]
    }

    fun randomSecret(length: Int = 20): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val random = java.security.SecureRandom()
        return (1..length).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }
}
