package app.rawline.mobile.deploy

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PanelCommandsTest {
    private fun server(vararg values: Pair<String, String>) = PanelCommands.newServer().apply {
        for ((key, value) in listOf("host" to "203.0.113.7", "sshPassword" to "ssh-secret") + values) this[key] = value
    }

    /** Undoes the quoting of [PanelCommands.root], so a test reads the command as the VPS runs it. */
    private fun plain(command: String) = command.replace("'\"'\"'", "'")

    @Test fun envAnswersEveryInstallerQuestion() {
        val env = PanelCommands.env(server("protocols" to "wdtt,dnstt", "access" to "nginx", "domain" to "Panel.Example.org", "dnsZone" to "t.example.org", "dnsNs" to "ns.example.org"))
        assertEquals("WDTT_PROTOCOLS='wdtt,dnstt' WDTT_FRONTEND=nginx WDTT_DOMAIN='panel.example.org' WDTT_CERT_EMAIL='' WDTT_DNS_ZONE='t.example.org' WDTT_DNS_NS='ns.example.org'", env)
        val local = PanelCommands.env(server("access" to "local"))
        for (name in listOf("WDTT_PROTOCOLS=''", "WDTT_FRONTEND=local", "WDTT_DNS_ZONE=''", "WDTT_DNS_NS=''")) assertTrue(name, local.contains(name))
        assertFalse(local.contains("WDTT_DOMAIN"))
    }

    @Test fun envRejectsWhatCouldReachTheShell() {
        val bad = listOf(
            arrayOf("protocols" to "wdtt;reboot", "access" to "local"),
            arrayOf("protocols" to "apache", "access" to "local"),
            arrayOf("access" to "apache"),
            arrayOf("access" to ""),
            arrayOf("access" to "caddy", "domain" to ""),
            arrayOf("access" to "caddy", "domain" to "https://x.org"),
            arrayOf("access" to "caddy", "domain" to "x.org/path"),
            arrayOf("access" to "caddy", "domain" to "x.org:443"),
            arrayOf("access" to "caddy", "domain" to "a'b.org"),
            arrayOf("access" to "caddy", "domain" to "-x.org"),
            arrayOf("access" to "nginx", "domain" to "x.org", "email" to "a@b.c'; id"),
            arrayOf("access" to "local", "dnsZone" to "t.example.org;id"),
            arrayOf("access" to "local", "dnsNs" to "\$(id)"),
        )
        for (values in bad) assertThrows(values.joinToString(), IllegalArgumentException::class.java) { PanelCommands.env(server(*values)) }
    }

    @Test fun commandsRunTheInstallerAsRoot() {
        val s = server("role" to "combined", "protocols" to "wdtt", "access" to "local")
        val install = plain(PanelCommands.install(s))
        assertTrue(install.startsWith("if [ \"\$(id -u)\" = 0 ]; then bash -c '"))
        assertTrue(install.contains(PanelCommands.INSTALLER))
        assertTrue(install.contains("env WDTT_PROTOCOLS='wdtt' WDTT_FRONTEND=local WDTT_DNS_ZONE='' WDTT_DNS_NS='' bash /root/wdtt-install.sh combined'"))
        assertTrue(plain(PanelCommands.check(s)).contains("bash /root/wdtt-install.sh check combined'"))
        assertTrue(plain(PanelCommands.status()).contains("bash /root/wdtt-install.sh status'"))
        for (target in listOf("all", "panel", "node")) assertTrue(plain(PanelCommands.uninstall(target)).contains("bash /root/wdtt-install.sh uninstall $target'"))
        assertThrows(IllegalArgumentException::class.java) { PanelCommands.uninstall("everything") }
        assertThrows(IllegalArgumentException::class.java) { PanelCommands.install(server("role" to "all", "access" to "local")) }
        assertTrue(plain(PanelCommands.resolve("Panel.Example.org")).contains("getent ahostsv4 'panel.example.org'"))
        assertThrows(IllegalArgumentException::class.java) { PanelCommands.resolve("x.org;reboot") }
    }

    @Test fun quotingSurvivesTheShell() {
        assumeTrue(File("/bin/bash").exists())
        for (value in listOf("plain", "it's", "\$(id)", "`id`", "a b;c|d&e", "'\"'\"'")) {
            val process = ProcessBuilder("/bin/bash", "-c", "printf %s " + PanelCommands.quote(value)).start()
            assertEquals(value, process.inputStream.readBytes().toString(Charsets.UTF_8))
        }
        val process = ProcessBuilder("/bin/bash", "-c", PanelCommands.root("printf %s 'inner'\"'\"'quote'").replace("sudo -S -p ''", "")).start()
        assertEquals("inner'quote", process.inputStream.readBytes().toString(Charsets.UTF_8))
    }

    @Test fun credentialsGoOnStdinAfterSudoPassword() {
        assertEquals("boss\nNew Pass\n", PanelCommands.credentialsInput("0\n", "sudo", "boss", "New Pass"))
        assertEquals("sudo\nboss\nNew Pass\n", PanelCommands.credentialsInput("1000", "sudo", "boss", "New Pass"))
        for ((user, password) in listOf("" to "x", "a" to "", " a" to "x", "a" to "x ", "a" to "x\ny", "a" to "x".repeat(257)))
            assertThrows("$user/$password", IllegalArgumentException::class.java) { PanelCommands.credentialsInput("0", "", user, password) }
        assertEquals("if [ \"\$(id -u)\" = 0 ]; then ${PanelCommands.CREDENTIALS}; else sudo -S -p '' ${PanelCommands.CREDENTIALS}; fi", PanelCommands.rootStdin(PanelCommands.CREDENTIALS))
    }

    @Test fun statusIsTheLastJsonLine() {
        val status = PanelCommands.parseStatus("Reading package lists...\n{\"role\": \"combined\", \"mode\": \"local\", \"protocols\": [\"wdtt\", \"dnstt\"], \"dns_zone\": \"t.example.org\", \"dns_ns\": \"ns.example.org\", \"public_ip\": \"203.0.113.7\", \"panel_url\": \"http://127.0.0.1:2860/Abc/\", \"panel_port\": 2860, \"panel_path\": \"/Abc/\", \"username\": \"admin\", \"password\": \"secret\", \"version\": \"1.4.83\", \"domain\": \"\"}\n")
        val s = server()
        PanelCommands.apply(s, status)
        assertEquals("combined", s["installedRole"]); assertEquals("wdtt,dnstt", s["installedProtocols"]); assertEquals("2860", s["panelPort"])
        assertEquals("admin", s["panelUser"]); assertEquals("secret", s["panelPassword"]); assertEquals("/Abc/", s["panelPath"])
        assertEquals("203.0.113.7", s["publicIp"]); assertEquals("t.example.org", s["installedZone"])
        assertThrows(IllegalStateException::class.java) { PanelCommands.parseStatus("curl: (6) Could not resolve host\n") }
        PanelCommands.apply(s, JSONObject("{\"role\": \"\", \"protocols\": []}"))
        assertEquals("", s["installedRole"]); assertEquals("", s["panelPassword"])
    }

    @Test fun installerOutputHelpers() {
        assertEquals("Сборка панели", PanelCommands.step("==> Сборка панели\n"))
        assertNull(PanelCommands.step("go: downloading x"))
        assertEquals("error: build failed", PanelCommands.lastLine("==> Сборка\nerror: build failed\n\n", "код 1"))
        assertEquals("код 1", PanelCommands.lastLine("\n", "код 1"))
        assertEquals("Совместимость проверена", PanelCommands.checkText("==> Проверка совместимости\nСовместимость проверена\n"))
        assertEquals(listOf("203.0.113.7"), PanelCommands.ipv4("203.0.113.7\n203.0.113.7\nnot-an-ip\n2001:db8::1\n999.1.1.1\n"))
    }

    @Test fun namesMustPointHere() {
        assertNull(PanelCommands.resolveError("Домен", "p.example.org", listOf("198.51.100.1", "203.0.113.7"), "203.0.113.7"))
        assertTrue(PanelCommands.resolveError("Домен", "p.example.org", emptyList(), "203.0.113.7")!!.contains("не находится"))
        val wrong = PanelCommands.resolveError("Домен", "p.example.org", listOf("198.51.100.1"), "203.0.113.7")!!
        assertTrue(wrong.contains("198.51.100.1") && wrong.contains("203.0.113.7"))
        assertNull(PanelCommands.zoneError("t.example.org", "ns.example.org", listOf("dnstt")))
        assertNotNull(PanelCommands.zoneError("t.example.org", "ns.t.example.org", listOf("dnstt")))
        assertNotNull(PanelCommands.zoneError("tunnel.long-example.org", "ns.example.org", listOf("slipstream")))
        assertNull(PanelCommands.zoneError("tunnel.long-example.org", "ns.example.org", listOf("soar")))
        assertNotNull(PanelCommands.zoneError("a".repeat(20) + ".example.org", "ns.example.org", listOf("soar")))
        assertNotNull(PanelCommands.zoneError("", "ns.example.org", listOf("dnstt")))
    }

    @Test fun addingAPartKeepsWhatTheVpsRuns() {
        val s = server("installedRole" to "node", "mode" to "", "installedProtocols" to "wdtt,dnstt", "installedZone" to "t.example.org", "installedNs" to "ns.example.org")
        PanelCommands.prepareAdd(s, "panel")
        assertEquals("panel", s["role"]); assertEquals("local", s["access"]); assertEquals("wdtt,dnstt", s["protocols"]); assertEquals("t.example.org", s["dnsZone"])
        val p = server("installedRole" to "panel", "mode" to "nginx", "installedDomain" to "p.example.org")
        PanelCommands.prepareAdd(p, "node")
        assertEquals("node", p["role"]); assertEquals("nginx", p["access"]); assertEquals("p.example.org", p["domain"]); assertEquals("", p["protocols"])
        // The installer then gets the existing access, so it asks nothing.
        p["protocols"] = "wdtt"
        assertTrue(PanelCommands.env(p).contains("WDTT_DOMAIN='p.example.org'"))
    }

    @Test fun newServerIsAPanelServerAndNotADraft() {
        val s = PanelCommands.newServer()
        assertEquals("panel", s.kind); assertEquals("root", s["user"]); assertEquals("22", s["sshPort"])
        assertFalse(s.isEmptyDraft())
        assertNotEquals(PanelCommands.newServer().id, s.id)
    }

    @Test fun connectionChecks() {
        PanelCommands.validateConnection(server())
        for (values in listOf(arrayOf("host" to "1.2.3.4;id"), arrayOf("user" to "root id"), arrayOf("sshPort" to "70000"), arrayOf("sshPassword" to ""),
            arrayOf("auth" to "key", "key" to ""))) assertThrows(values.joinToString(), IllegalArgumentException::class.java) { PanelCommands.validateConnection(server(*values)) }
    }

    @Test fun choicesAreComplete() {
        assertEquals(listOf("combined", "panel", "node"), PanelCommands.roles.map { it.value })
        assertEquals(listOf("nginx", "caddy", "local"), PanelCommands.access.map { it.value })
        assertEquals(14, PanelCommands.protocols.size)
        assertTrue(PanelCommands.access.first { it.value == "local" }.text.contains("Подписки по ссылке в этом режиме не работают"))
        // Production texts: no long dashes.
        for (text in (PanelCommands.roles + PanelCommands.access).flatMap { listOf(it.title, it.text) }) assertFalse(text, text.contains('—'))
        assertEquals(PanelCommands.protocols.size, PanelCommands.protocols.map { it.id }.distinct().size)
        assertEquals(20, PanelCommands.randomSecret().length)
    }
}
