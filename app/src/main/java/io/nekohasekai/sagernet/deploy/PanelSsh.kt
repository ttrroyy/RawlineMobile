package app.rawline.mobile.deploy

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** One SSH connection to the VPS; the host key remembered first is the only one accepted after. */
class PanelSsh(private val server: DeployServer, private val trust: (String) -> Boolean) : AutoCloseable {
    private var session: Session? = null

    fun connect(): PanelSsh {
        val jsch = JSch()
        if (server["auth"] == "key") jsch.addIdentity("Rawline", server["key"].toByteArray(), null, server["keyPassword"].takeIf { it.isNotEmpty() }?.toByteArray())
        // The host is verified before the password or the key is sent.
        jsch.hostKeyRepository = object : HostKeyRepository {
            override fun check(host: String, bytes: ByteArray): Int {
                val fingerprint = "SHA256:" + android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(bytes),
                    android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                val known = server["hostKey"]
                if (known.isNotBlank()) return if (known == fingerprint) HostKeyRepository.OK else HostKeyRepository.CHANGED
                if (!trust(fingerprint)) return HostKeyRepository.CHANGED
                server["hostKey"] = fingerprint
                return HostKeyRepository.OK
            }
            override fun add(hostkey: HostKey?, userinfo: UserInfo?) {}
            override fun remove(host: String?, type: String?) {}
            override fun remove(host: String?, type: String?, key: ByteArray?) {}
            override fun getKnownHostsRepositoryID() = "Rawline encrypted servers"
            override fun getHostKey(): Array<HostKey> = emptyArray()
            override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
        }
        session = jsch.getSession(server["user"], server["host"], server["sshPort"].toInt()).apply {
            setConfig("StrictHostKeyChecking", "yes")
            setConfig("PreferredAuthentications", if (server["auth"] == "key") "publickey" else "password,keyboard-interactive")
            if (server["auth"] != "key") setPassword(server["sshPassword"])
            setServerAliveInterval(15_000); setServerAliveCountMax(4)
            connect(30_000)
        }
        return this
    }

    private val sudoPassword get() = server["sudoPassword"].ifEmpty { server["sshPassword"] }

    /** Runs a command; each output line goes to [line] as it comes. Returns the exit code and the whole output. */
    fun exec(command: String, input: String = sudoPassword + "\n", timeoutMs: Long = 5 * 60_000L, line: (String) -> Unit = {}): Pair<Int, String> {
        val channel = session!!.openChannel("exec") as ChannelExec
        channel.setCommand("$command 2>&1")
        channel.setInputStream(input.byteInputStream())
        val stream = channel.inputStream
        channel.connect(30_000)
        val output = StringBuilder()
        val pending = StringBuilder()
        val buffer = ByteArray(8192)
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (true) {
                while (stream.available() > 0) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    val text = String(buffer, 0, count, Charsets.UTF_8)
                    if (output.length < 4_000_000) output.append(text)
                    pending.append(text)
                    while (true) {
                        val end = pending.indexOf("\n")
                        if (end < 0) break
                        line(pending.substring(0, end)); pending.delete(0, end + 1)
                    }
                }
                if (channel.isClosed && stream.available() == 0) break
                check(session?.isConnected == true) { "Соединение с VPS прервано" }
                check(System.currentTimeMillis() < deadline) { "Превышено время выполнения команды на VPS" }
                Thread.sleep(80)
            }
            if (pending.isNotEmpty()) line(pending.toString())
            return channel.exitStatus to output.toString()
        } finally {
            channel.disconnect()
        }
    }

    fun status(): JSONObject {
        val (code, output) = exec(PanelCommands.status())
        check(code == 0) { "Не удалось получить состояние VPS: " + PanelCommands.lastLine(output, "код $code") }
        return PanelCommands.parseStatus(output)
    }

    fun resolve(name: String): List<String> = PanelCommands.ipv4(exec(PanelCommands.resolve(name)).second)

    fun credentials(user: String, password: String) {
        val uid = exec("id -u", "").second
        val (code, output) = exec(PanelCommands.rootStdin(PanelCommands.CREDENTIALS), PanelCommands.credentialsInput(uid, sudoPassword, user, password), 60_000)
        check(code == 0) { "Не удалось сменить вход: " + PanelCommands.lastLine(output, "код $code") }
    }

    /** Forwards a free port of the phone to the panel's 127.0.0.1 port on the VPS; the panel's own port first. */
    fun forward(port: Int): Int = runCatching { session!!.setPortForwardingL("127.0.0.1", port, "127.0.0.1", port) }
        .getOrElse { session!!.setPortForwardingL("127.0.0.1", 0, "127.0.0.1", port) }

    val connected get() = session?.isConnected == true

    override fun close() { runCatching { session?.disconnect() } }
}

data class PanelState(
    val serverId: String = "",
    val running: Boolean = false,
    val action: String = "",
    val step: String = "",
    val percent: Int = 0,
    val log: String = "",
    val error: String = "",
    val trust: String = "",
    // The result of the last action: a check's text and verdict, or the addresses of a name.
    val checkOk: Boolean = false,
    val checkText: String = "",
    val resolved: List<String>? = null,
    val finished: Long = 0,
)

/** The panel action under way. It outlives its screen, as an install does; the screen follows [state]. */
object PanelOperation {
    val state = MutableStateFlow(PanelState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var ssh: PanelSsh? = null
    @Volatile private var trustReply: CompletableDeferred<Boolean>? = null
    // Ready cores are downloaded; built on the VPS only without them, at the same point of the install.
    private val steps = listOf("Проверка совместимости", "Установка пакетов", "Загрузка ядер протоколов", "Сборка панели", "Настройка служб", "Публикация панели")

    fun approve(accepted: Boolean) { trustReply?.complete(accepted) }

    fun cancel() {
        job?.cancel(); trustReply?.complete(false)
        scope.launch { ssh?.close() }
    }

    /** [action]: status, resolve, check, install, uninstall-all|panel|node, credentials. [saved] gets the server after a success. */
    fun start(server: DeployServer, action: String, argument: String = "", user: String = "", password: String = "", saved: (DeployServer) -> Unit = {}) {
        if (state.value.running) return
        val copy = DeployServer(JSONObject(server.json.toString()))
        state.value = PanelState(copy.id, true, action, if (action.startsWith("uninstall")) "Удаление" else "Подключение к VPS", 2)
        job = scope.launch {
            try {
                PanelCommands.validateConnection(copy)
                PanelSsh(copy) { fingerprint -> ask(fingerprint) }.also { ssh = it }.use { connection ->
                    connection.connect()
                    run(connection, copy, action, argument, user, password)
                }
                DeployStore.save(copy)
                saved(copy)
                state.update { it.copy(running = false, percent = if (it.percent > 0) 100 else 0, finished = System.currentTimeMillis(),
                    step = if (action == "install" || action.startsWith("uninstall")) "Готово" else it.step) }
            } catch (e: Throwable) {
                // Stop closes the connection under a running command: that is the stop, not a failure.
                val message = if (e is kotlinx.coroutines.CancellationException || !isActive) "Операция остановлена" else friendly(e)
                // A trusted key is kept even when the action after it failed.
                if (copy["hostKey"].isNotBlank() && copy["hostKey"] != server["hostKey"]) runCatching { DeployStore.save(copy); saved(copy) }
                state.update { it.copy(running = false, error = message, log = it.log + "\n" + message, finished = System.currentTimeMillis()) }
            } finally {
                ssh = null
            }
        }
    }

    private fun ask(fingerprint: String): Boolean {
        val reply = CompletableDeferred<Boolean>(); trustReply = reply
        state.update { it.copy(trust = fingerprint) }
        val accepted = runBlocking { withTimeoutOrNull(120_000) { reply.await() } } == true
        state.update { it.copy(trust = "") }; trustReply = null
        return accepted
    }

    private fun run(ssh: PanelSsh, server: DeployServer, action: String, argument: String, user: String, password: String) {
        fun log(line: String) {
            val step = PanelCommands.step(line)
            state.update { s ->
                val index = steps.indexOf(if (step == "Сборка ядер протоколов") "Загрузка ядер протоколов" else step)
                s.copy(log = (s.log + line + "\n").takeLast(200_000), step = step ?: s.step,
                    percent = if (index >= 0) maxOf(s.percent, 10 + index * 15) else s.percent)
            }
        }
        when (action) {
            "status" -> Unit
            "resolve" -> { state.update { it.copy(resolved = ssh.resolve(argument)) }; return }
            "check" -> {
                state.update { it.copy(step = "Проверка совместимости") }
                val (code, output) = ssh.exec(PanelCommands.check(server))
                state.update { it.copy(checkOk = code == 0, checkText = PanelCommands.checkText(output).ifEmpty { "Проверка не вернула ответ" }) }
                return
            }
            "install" -> {
                state.update { it.copy(step = "Загрузка установщика", percent = 5) }
                val (code, output) = ssh.exec(PanelCommands.install(server), timeoutMs = 3 * 3600_000L) { log(it) }
                check(code == 0) { "Установка остановлена: " + PanelCommands.lastLine(output, "код $code") }
            }
            "uninstall-all", "uninstall-panel", "uninstall-node" -> {
                val (code, output) = ssh.exec(PanelCommands.uninstall(action.removePrefix("uninstall-")), timeoutMs = 30 * 60_000L) { log(it) }
                check(code == 0) { "Удаление остановлено: " + PanelCommands.lastLine(output, "код $code") }
            }
            "credentials" -> ssh.credentials(user, password)
            else -> throw IllegalArgumentException("Неизвестное действие")
        }
        state.update { it.copy(step = if (action == "status") it.step else "Готово") }
        PanelCommands.apply(server, ssh.status())
    }

    private fun friendly(e: Throwable): String {
        val text = e.message.orEmpty()
        return when {
            e is com.jcraft.jsch.JSchException && text.contains("Auth fail", true) -> "SSH не принял логин, пароль или ключ"
            e is com.jcraft.jsch.JSchException && text.contains("HostKey", true) -> if (text.contains("reject", true)) "Ключ SSH сервера не подтверждён" else "Ключ SSH сервера изменился или не подтверждён"
            e is com.jcraft.jsch.JSchException && (text.contains("timeout", true) || text.contains("connect", true)) -> "VPS не отвечает по SSH: проверьте адрес и порт"
            text.isNotBlank() -> text
            else -> e.javaClass.simpleName
        }
    }
}

/** SSH forwards to panels on the VPS's 127.0.0.1; one per server, closed with its screen. */
object PanelTunnels {
    private val open = HashMap<String, Pair<PanelSsh, Int>>()

    @Synchronized fun open(server: DeployServer): Int {
        open[server.id]?.let { (ssh, port) -> if (ssh.connected) return port else ssh.close() }
        val port = server["panelPort"].toIntOrNull()?.takeIf { it in 1..65535 } ?: error("Неизвестный порт панели")
        // Only a key already confirmed is accepted here.
        val ssh = PanelSsh(server) { false }.connect()
        val local = runCatching { ssh.forward(port) }.getOrElse { ssh.close(); throw it }
        open[server.id] = ssh to local
        return local
    }

    @Synchronized fun close(id: String) { open.remove(id)?.first?.close() }
}
