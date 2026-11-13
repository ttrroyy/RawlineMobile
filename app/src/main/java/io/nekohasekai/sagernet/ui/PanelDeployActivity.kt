package app.rawline.mobile.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.rawline.mobile.R
import app.rawline.mobile.deploy.DeployServer
import app.rawline.mobile.deploy.DeployStore
import app.rawline.mobile.deploy.PanelCommands
import app.rawline.mobile.deploy.PanelOperation
import app.rawline.mobile.deploy.PanelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Deploy of WDTT Panel, screen by screen: the connection, what to install, protocols, access, domain, DNS zone, the
 * compatibility check and the install; then the server's page with the panel's address, login and removal.
 */
class PanelDeployActivity : ServerScreenActivity() {
    enum class Screen { CONNECT, ROLE, PROTOCOLS, ACCESS, DOMAIN, DNS, CHECK, PROGRESS, SERVER }

    class Model : ViewModel() {
        var server: DeployServer? = null
        val history = ArrayList<Screen>()
        var screen = Screen.CONNECT
        // What the wizard is doing: a full install, or adding the part a VPS lacks.
        var goal = "install"
        // The action whose end the screen waits for, and the end it has already handled.
        var pending = ""
        var handled = 0L
        // The name a domain or DNS screen is checking, and how its error names it.
        var resolving = "" to ""
    }

    private val model by lazy { ViewModelProvider(this)[Model::class.java] }
    private val server get() = model.server!!
    private lateinit var toolbar: Toolbar
    private lateinit var content: FrameLayout
    private val ui by lazy { ServerUi(this) }
    private var errorText: TextView? = null
    private var primary: TextView? = null
    private var shownTrust = ""
    // Live parts of the check and progress screens.
    private var stepText: TextView? = null
    private var bar: ProgressBar? = null
    private var logText: TextView? = null
    private var logScroll: ScrollView? = null
    private var checkBody: TextView? = null
    private var stopButton: TextView? = null
    private val busy get() = PanelOperation.state.value.running

    private val keyPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { runCatching {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytes(); require(bytes.size <= 65536); String(bytes, Charsets.UTF_8)
                } ?: error("Нет доступа к файлу")
            } }
            text.onSuccess { key -> server["key"] = key; server["keyName"] = uri.lastPathSegment.orEmpty(); show(Screen.CONNECT, remember = false) }
                .onFailure { Toast.makeText(this@PanelDeployActivity, "Не удалось прочитать SSH-ключ", Toast.LENGTH_LONG).show() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(MobilePalette.background))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(MobilePalette.screen) }
        toolbar = Toolbar(this).apply { setTitleTextColor(MobilePalette.text); setNavigationIcon(R.drawable.baseline_arrow_back_24); setNavigationOnClickListener { back() } }
        content = FrameLayout(this)
        root.addView(toolbar); root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets -> val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()); view.setPadding(b.left, b.top, b.right, b.bottom); insets }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) { override fun handleOnBackPressed() { back() } })
        lifecycleScope.launch {
            if (model.server == null) {
                val id = intent.getStringExtra("server")
                model.server = if (id == null) PanelCommands.newServer() else withContext(Dispatchers.IO) { runCatching { DeployStore.all().firstOrNull { it.id == id } }.getOrNull() }
                if (model.server == null) { Toast.makeText(this@PanelDeployActivity, "Не удалось открыть VPS", Toast.LENGTH_LONG).show(); finish(); return@launch }
                model.screen = if (server["connectedHost"].isEmpty()) Screen.CONNECT else Screen.SERVER
            }
            show(model.screen, remember = false, animate = false)
            repeatOnLifecycle(Lifecycle.State.STARTED) { PanelOperation.state.collect { render(it) } }
        }
    }

    // Navigation
    private fun show(screen: Screen, remember: Boolean = true, forward: Boolean = true, animate: Boolean = true) {
        if (remember && model.screen != screen && model.screen != Screen.PROGRESS) model.history += model.screen
        if (screen == Screen.SERVER) model.history.clear()
        model.screen = screen
        stepText = null; bar = null; logText = null; logScroll = null; checkBody = null; stopButton = null
        val body = ui.column().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(24)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(MobilePalette.screen); addView(body) }
        toolbar.title = when (screen) {
            Screen.CONNECT -> "Подключение к VPS"; Screen.ROLE -> "Что установить"; Screen.PROTOCOLS -> "Протоколы"
            Screen.ACCESS -> "Доступ к панели"; Screen.DOMAIN -> "Домен панели"; Screen.DNS -> "DNS-туннели"
            Screen.CHECK -> "Проверка совместимости"; Screen.PROGRESS -> if (PanelOperation.state.value.action.startsWith("uninstall")) "Удаление" else "Установка"
            Screen.SERVER -> server["name"].ifEmpty { server["host"] }
        }
        errorText = null; primary = null
        when (screen) {
            Screen.CONNECT -> connect(body)
            Screen.ROLE -> role(body)
            Screen.PROTOCOLS -> protocols(body)
            Screen.ACCESS -> access(body)
            Screen.DOMAIN -> domain(body)
            Screen.DNS -> dns(body)
            Screen.CHECK -> check(body)
            Screen.PROGRESS -> progress(body)
            Screen.SERVER -> serverPage(body)
        }
        val old = content.getChildAt(0)
        content.addView(scroll)
        if (old != null) {
            if (animate && !reducedMotion()) {
                val shift = ui.dp(24).toFloat() * (if (forward) 1 else -1)
                scroll.alpha = 0f; scroll.translationX = shift
                scroll.animate().alpha(1f).translationX(0f).setDuration(200).start()
                old.animate().alpha(0f).translationX(-shift).setDuration(160).withEndAction { content.removeView(old) }.start()
            } else content.removeView(old)
        }
        render(PanelOperation.state.value)
        if (screen == Screen.CHECK && !busy) runCheck()
    }

    private fun reducedMotion() = android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    private fun back() {
        if (busy && model.screen in listOf(Screen.PROGRESS, Screen.CHECK)) { confirmStop(); return }
        if (busy) return
        val previous = model.history.removeLastOrNull() ?: return finish()
        show(previous, remember = false, forward = false)
    }

    private fun confirmStop() {
        MobileAlertDialogBuilder(this).setTitle("Остановить операцию?")
            .setMessage("Уже выполненные изменения на VPS сохранятся, установку можно запустить снова.")
            .setPositiveButton("Остановить") { _, _ -> PanelOperation.cancel() }.setNegativeButton(android.R.string.cancel, null).show()
    }

    // Shared pieces
    private fun intro(body: LinearLayout, text: String) = ui.hint(body, text)
    private fun footer(body: LinearLayout, label: String, click: () -> Unit) {
        errorText = ui.text("", 14f, android.graphics.Color.rgb(232, 120, 112)).apply { visibility = View.GONE; setPadding(0, ui.dp(12), 0, 0) }
        body.addView(errorText)
        primary = ui.button(label, primary = true) { if (!busy) { fail(""); click() } }
        body.addView(primary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(16) })
    }
    private fun fail(text: String) { errorText?.apply { this.text = text; visibility = if (text.isEmpty()) View.GONE else View.VISIBLE } }
    private fun card(body: LinearLayout) = ui.card().also { body.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) }) }
    private fun choices(body: LinearLayout, list: List<PanelCommands.Choice>, selected: String, picked: (String) -> Unit) {
        var current = selected
        val rows = ArrayList<Pair<String, View>>()
        fun mark() = rows.forEach { (value, row) -> row.background = if (value == current) MobilePalette.selectedShape(this) else MobilePalette.rowShape(this) }
        for (choice in list) {
            val row = ui.item(choice.title, choice.text) { current = choice.value; mark(); picked(choice.value) }
            (row.getChildAt(1) as? TextView)?.maxLines = Int.MAX_VALUE
            rows += choice.value to row
            body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        }
        mark()
    }

    // Screens
    private fun connect(body: LinearLayout) {
        intro(body, "Rawline подключится к VPS по SSH и установит WDTT Panel официальным скриптом. Лучше взять новый VPS без других VPN и панелей: так не будет конфликтов портов и сетевых правил.")
        val box = card(body)
        val name = ui.field(box, "Название", server["name"])
        val host = ui.field(box, "IP или домен", server["host"])
        val port = ui.field(box, "Порт SSH", server["sshPort"].ifEmpty { "22" }, number = true)
        val user = ui.field(box, "Логин SSH", server["user"].ifEmpty { "root" })
        ui.heading(box, "Вход на сервер")
        var password: EditText? = null
        var keyPassword: EditText? = null
        var sudo: EditText? = null
        ui.segmented(box, listOf("password" to "Пароль", "key" to "SSH-ключ"), server["auth"].ifEmpty { "password" }) {
            server["auth"] = it; server["name"] = name.text.toString(); server["host"] = host.text.toString().trim()
            server["sshPort"] = port.text.toString().trim(); server["user"] = user.text.toString().trim()
            show(Screen.CONNECT, remember = false, animate = false)
        }
        if (server["auth"] == "key") {
            box.addView(ui.textButton(if (server["key"].isEmpty()) "Выбрать файл ключа" else "Ключ: " + server["keyName"].ifEmpty { "загружен" }) { keyPicker.launch("*/*") })
            keyPassword = ui.field(box, "Пароль ключа", server["keyPassword"], secret = true)
            sudo = ui.field(box, "Пароль sudo, если логин не root", server["sudoPassword"], secret = true)
        } else password = ui.field(box, "Пароль SSH", server["sshPassword"], secret = true)
        footer(body, "Подключиться") {
            server["name"] = name.text.toString().trim(); server["host"] = host.text.toString().trim()
            server["sshPort"] = port.text.toString().trim(); server["user"] = user.text.toString().trim()
            password?.let { server["sshPassword"] = it.text.toString(); server["sudoPassword"] = "" }
            keyPassword?.let { server["keyPassword"] = it.text.toString(); server["sshPassword"] = "" }
            sudo?.let { server["sudoPassword"] = it.text.toString() }
            val valid = runCatching { PanelCommands.validateConnection(server) }
            if (valid.isFailure) return@footer fail(valid.exceptionOrNull()?.message.orEmpty())
            // Another address is another server: its old key and state no longer apply.
            if (server["host"] != server["connectedHost"] || server["sshPort"] != server["connectedPort"]) { server["hostKey"] = ""; server["installedRole"] = "" }
            begin("connect", "status")
        }
    }

    private fun role(body: LinearLayout) {
        intro(body, "Панель управляет пользователями, подписками и нодами. Нода запускает VPN-протоколы. Недостающий компонент можно добавить позже.")
        if (server["role"].isEmpty()) server["role"] = "combined"
        choices(body, PanelCommands.roles, server["role"]) { server["role"] = it }
        footer(body, "Далее") {
            if (server["role"] == "panel") { server["protocols"] = ""; show(Screen.ACCESS) } else show(Screen.PROTOCOLS)
        }
    }

    private fun protocols(body: LinearLayout) {
        intro(body, "Каждый протокол получает свой инбаунд. Остальные можно добавить позже в самой панели: ядра всех протоколов на ноде собираются сразу.")
        val selected = (if (server["protocols"].isEmpty() && model.goal == "install") PanelCommands.defaultProtocols else PanelCommands.split(server["protocols"])).toMutableSet()
        PanelCommands.groups.forEachIndexed { index, title ->
            body.addView(ui.section(title))
            val box = ui.card(); body.addView(box)
            for (p in PanelCommands.protocols.filter { it.group == index })
                ui.switchRow(box, p.title, p.id in selected) { on -> if (on) selected += p.id else selected -= p.id }
        }
        footer(body, "Далее") {
            val names = PanelCommands.protocols.map { it.id }.filter { it in selected }
            if (names.isEmpty()) return@footer fail("Выберите хотя бы один протокол")
            server["protocols"] = names.joinToString(",")
            when {
                // A node added to a panel keeps the panel's access and domain.
                model.goal == "add-node" -> show(if (PanelCommands.hasDns(names)) Screen.DNS else Screen.CHECK)
                // A node alone is reached by the panel over SSH: it needs no public address of its own.
                server["role"] == "node" -> { server["access"] = "local"; server["domain"] = ""; afterDomain() }
                else -> show(Screen.ACCESS)
            }
        }
    }

    private fun access(body: LinearLayout) {
        intro(body, "Как открывать панель и откуда клиенты получают подписки.")
        if (server["access"].isEmpty()) server["access"] = "nginx"
        choices(body, PanelCommands.access, server["access"]) { server["access"] = it }
        footer(body, "Далее") {
            if (server["access"] != "local") show(Screen.DOMAIN) else { server["domain"] = ""; afterDomain() }
        }
    }

    private fun afterDomain() = show(if (server["role"] != "panel" && PanelCommands.hasDns(PanelCommands.split(server["protocols"]))) Screen.DNS else Screen.CHECK)

    private fun domain(body: LinearLayout) {
        intro(body, "Сертификат Let's Encrypt выпускается на этот домен, поэтому он должен уже указывать на VPS.")
        val box = card(body)
        val domain = ui.field(box, "Домен, например panel.example.org", server["domain"])
        val email = ui.field(box, "Email для писем о сертификате (необязательно)", server["email"])
        val ip = server["publicIp"].ifEmpty { "IP VPS" }
        val help = ui.text("", 14f, MobilePalette.muted).apply { setPadding(0, ui.dp(12), 0, 0) }
        fun refresh() {
            val name = domain.text.toString().trim().lowercase().ifEmpty { "panel.example.org" }
            help.text = "Создайте у регистратора домена A-запись:\n$name  A  $ip\n\nЕсли домен в Cloudflare, выключите проксирование (серое облако): сертификат выпускается напрямую на VPS. Новая запись обычно начинает работать за несколько минут."
        }
        refresh(); body.addView(help)
        domain.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { refresh() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        footer(body, "Проверить и продолжить") {
            val name = domain.text.toString().trim().lowercase()
            if (!PanelCommands.isHostname(name)) return@footer fail("Укажите домен без схемы, порта и пути")
            server["domain"] = name; server["email"] = email.text.toString().trim()
            runCatching { PanelCommands.env(server) }.onFailure { return@footer fail(it.message.orEmpty()) }
            model.resolving = "Домен" to name
            begin("domain", "resolve", name)
        }
    }

    private fun dns(body: LinearLayout) {
        intro(body, "DNS-туннели работают через делегированную зону, которую обслуживает этот VPS.")
        val box = card(body)
        val zone = ui.field(box, "Зона туннелей, например t.example.org", server["dnsZone"])
        val ns = ui.field(box, "NS-сервер, например ns.example.org", server["dnsNs"])
        val ip = server["publicIp"].ifEmpty { "IP VPS" }
        val help = ui.text("", 14f, MobilePalette.muted).apply { setPadding(0, ui.dp(12), 0, 0) }
        fun refresh() {
            val z = zone.text.toString().trim().lowercase().ifEmpty { "t.example.org" }
            val n = ns.text.toString().trim().lowercase().ifEmpty { "ns.example.org" }
            help.text = "Создайте у регистратора две записи:\n$n  A  $ip\n$z  NS  $n\n\nNS-сервер должен быть вне зоны, CDN и проксирование для обеих записей выключите. На VPS должны быть свободны порты 53 TCP и UDP. Для Slipstream зона не длиннее 18 символов, для Soar не длиннее 26."
        }
        refresh(); body.addView(help)
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { refresh() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }
        zone.addTextChangedListener(watcher); ns.addTextChangedListener(watcher)
        footer(body, "Проверить и продолжить") {
            val z = zone.text.toString().trim().lowercase(); val n = ns.text.toString().trim().lowercase()
            PanelCommands.zoneError(z, n, PanelCommands.split(server["protocols"]))?.let { return@footer fail(it) }
            server["dnsZone"] = z; server["dnsNs"] = n
            model.resolving = "NS-сервер" to n
            begin("dns", "resolve", n)
        }
    }

    private fun check(body: LinearLayout) {
        if (model.goal == "reinstall") intro(body, "Обновление с теми же параметрами: " + PanelCommands.roleTitle(server["role"]).lowercase() + ", " +
            PanelCommands.accessTitle(server["access"]) + server["domain"].let { if (it.isEmpty()) "" else ", $it" } +
            ". Пользователи, подписки и настройки панели сохранятся.")
        intro(body, "Скрипт панели проверяет VPS, ничего не меняя: порты, сетевые права, подсети, прокси, DNS-записи и место на диске.")
        val box = card(body)
        checkBody = ui.text("", 13f, mono = true).apply { setTextIsSelectable(true) }
        box.addView(checkBody)
        body.addView(ui.textButton("Проверить снова") { if (!busy) runCheck() })
        footer(body, if (model.goal == "reinstall") "Установить заново" else "Установить") { install() }
    }

    private fun runCheck() { begin("check", "check") }

    private fun install() {
        if (!PanelOperation.state.value.checkOk) return fail("Сначала проверка должна пройти")
        if (begin("install", "install")) show(Screen.PROGRESS)
    }

    private fun progress(body: LinearLayout) {
        intro(body, "Сборка ядер на VPS занимает от 5 до 40 минут в зависимости от процессора. Можно свернуть приложение: установка продолжится, пока Rawline работает.")
        val box = card(body)
        stepText = ui.text("", 16f, bold = true); box.addView(stepText)
        bar = MobileProgress.bar(this); box.addView(bar, LinearLayout.LayoutParams(-1, ui.dp(8)).apply { topMargin = ui.dp(10); bottomMargin = ui.dp(4) })
        val log = ui.text("", 11f, mono = true).apply { setTextIsSelectable(true); setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)) }
        logText = log
        logScroll = ScrollView(this).apply { background = ui.well(); addView(log) }
        body.addView(logScroll, LinearLayout.LayoutParams(-1, ui.dp(320)).apply { topMargin = ui.dp(8) })
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        stopButton = ui.textButton("Остановить", danger = true) { confirmStop() }
        actions.addView(stopButton)
        actions.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        actions.addView(ui.textButton("К серверу") { if (!busy) show(Screen.SERVER) })
        body.addView(actions)
    }

    private fun serverPage(body: LinearLayout) {
        val role = server["installedRole"]
        val summary = card(body)
        summary.addView(ui.stat("Установлено", PanelCommands.roleTitle(role)))
        if (role == "combined" || role == "node") summary.addView(ui.stat("Протоколы", PanelCommands.protocolTitles(PanelCommands.split(server["installedProtocols"]))).apply { setPadding(0, ui.dp(10), 0, 0) })
        if (role.isNotEmpty()) summary.addView(ui.stat("Доступ", PanelCommands.accessTitle(server["mode"]) + server["installedDomain"].let { if (it.isEmpty()) "" else ", $it" }).apply { setPadding(0, ui.dp(10), 0, 0) })
        if (server["version"].isNotEmpty()) summary.addView(ui.stat("Версия WDTT Panel", server["version"]).apply { setPadding(0, ui.dp(10), 0, 0) })
        // Updating is installing again with what is installed: nothing to choose, users and settings stay.
        if (role.isNotEmpty()) body.addView(ui.button("Установить заново (обновить)", primary = true) { reinstall() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        if (role == "combined" || role == "panel") {
            body.addView(ui.section("Панель"))
            val panel = ui.card(); body.addView(panel)
            if (server["mode"] == "local") ui.hint(panel, "Панель доступна только с VPS. Rawline откроет её через SSH внутри приложения, не меняя маршруты и VPN.")
            else panel.addView(ui.linkRow("Адрес", server["panelUrl"]))
            panel.addView(ui.linkRow("Логин", server["panelUser"], lines = 1))
            panel.addView(secretRow("Пароль", server["panelPassword"]))
            body.addView(ui.button("Открыть панель") { openPanel() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
            body.addView(ui.button("Сменить логин и пароль") { changeLogin() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        }
        if (role.isEmpty()) {
            ui.hint(body, "На этом VPS панель и нода не установлены.")
            body.addView(ui.button("Установить", primary = true) { model.goal = "install"; show(Screen.ROLE) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        }
        if (role == "panel" || role == "node") body.addView(ui.button(if (role == "panel") "Добавить ноду" else "Добавить панель") { startAdd(if (role == "panel") "node" else "panel") },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        if (role.isNotEmpty()) body.addView(removeRow(role), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        errorText = ui.text("", 14f, android.graphics.Color.rgb(232, 120, 112)).apply { visibility = View.GONE; setPadding(0, ui.dp(12), 0, 0) }
        body.addView(errorText)
        val links = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, ui.dp(8), 0, 0) }
        links.addView(ui.textButton("Обновить состояние") { if (!busy) begin("refresh", "status") })
        links.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        links.addView(ui.textButton("Подключение SSH") { if (!busy) show(Screen.CONNECT) })
        body.addView(links)
    }

    /** «Удалить» removes everything; the arrow beside it picks one part of a combined VPS. */
    private fun removeRow(role: String) = LinearLayout(this).apply {
        val remove = ui.button("Удалить", danger = true) { uninstall("all") }
        addView(remove, LinearLayout.LayoutParams(0, -2, 1f))
        val arrow = FrameLayout(context).apply {
            background = MobilePalette.rowShape(context); minimumHeight = ui.dp(48); isFocusable = true; MobileFeedback.apply(this)
            addView(android.widget.ImageView(context).apply {
                setImageResource(R.drawable.ic_baseline_expand_more_24)
                imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(232, 120, 112))
            }, FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER))
            contentDescription = "Выбрать, что удалить"
            setOnClickListener { anchor ->
                if (busy) return@setOnClickListener
                val popup = androidx.appcompat.widget.PopupMenu(context, anchor, Gravity.END)
                popup.menu.add("Всё").setOnMenuItemClickListener { uninstall("all"); true }
                if (role == "combined") {
                    popup.menu.add("Только панель").setOnMenuItemClickListener { uninstall("panel"); true }
                    popup.menu.add("Только ноду").setOnMenuItemClickListener { uninstall("node"); true }
                }
                popup.show()
            }
        }
        addView(arrow, LinearLayout.LayoutParams(ui.dp(56), -1).apply { marginStart = ui.dp(8) })
    }

    private fun secretRow(label: String, value: String) = ui.column().apply {
        setPadding(0, ui.dp(10), 0, 0)
        addView(ui.text(label, 12f, MobilePalette.muted).apply { setPadding(0, 0, 0, ui.dp(6)) })
        var shown = false
        val text = ui.text("••••••••••••", 12f, mono = true).apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = ui.well(); maxLines = 1 }
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            val eye = ui.iconButton(R.drawable.ic_baseline_visibility_24, "Показать пароль") {}.apply { imageTintList = android.content.res.ColorStateList.valueOf(ui.accent) }
            eye.setOnClickListener {
                shown = !shown; text.text = if (shown) value else "••••••••••••"; text.setTextIsSelectable(shown)
                eye.contentDescription = if (shown) "Скрыть пароль" else "Показать пароль"
                eye.setImageResource(if (shown) R.drawable.ic_baseline_visibility_off_24 else R.drawable.ic_baseline_visibility_24)
            }
            addView(eye, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)).apply { marginStart = ui.dp(4) })
            addView(ui.copyButton("Скопировать пароль") { app.rawline.mobile.SagerNet.trySetPrimaryClip(value) }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        })
    }

    // Actions on the server page
    private fun reinstall() {
        if (busy) return
        model.goal = "reinstall"
        PanelCommands.prepareReinstall(server)
        show(Screen.CHECK)
    }

    private fun startAdd(part: String) {
        model.goal = if (part == "node") "add-node" else "add-panel"
        PanelCommands.prepareAdd(server, part)
        show(if (part == "node") Screen.PROTOCOLS else Screen.ACCESS)
    }

    private fun uninstall(target: String) {
        if (busy) return
        val what = when (target) {
            "all" -> "всю установку: панель, ноду, пользователей и сертификаты панели"
            "panel" -> "панель. Нода, пользователи и настройки останутся"
            else -> "ноду и её протоколы. Панель, пользователи и настройки останутся"
        }
        MobileAlertDialogBuilder(this).setTitle("Удалить с VPS?").setMessage("Будет удалена $what.")
            .setPositiveButton("Удалить") { _, _ -> if (begin("uninstall", "uninstall-$target")) show(Screen.PROGRESS) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun changeLogin() {
        if (busy) return
        var user: EditText? = null
        var password: EditText? = null
        ui.sheet("Логин и пароль панели", "Сохранить", apply = {
            val u = user!!.text.toString(); val p = password!!.text.toString()
            if (!PanelCommands.validCredential(u) || !PanelCommands.validCredential(p)) {
                Toast.makeText(this, "Логин и пароль: без пробелов по краям, до 256 символов", Toast.LENGTH_LONG).show(); false
            } else begin("credentials", "credentials", user = u, password = p)
        }) { body, _ ->
            user = ui.field(body, "Логин", server["panelUser"])
            password = ui.field(body, "Пароль", PanelCommands.randomSecret(), secret = true)
            body.addView(ui.textButton("Создать пароль") { password?.setText(PanelCommands.randomSecret()) })
        }
    }

    private fun openPanel() {
        if (server["mode"] != "local") {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(server["panelUrl"]))) }
                .onFailure { Toast.makeText(this, "Нет браузера для ссылки", Toast.LENGTH_LONG).show() }
            return
        }
        startActivity(Intent(this, PanelWebActivity::class.java).putExtra("server", server.id))
    }

    // Operations
    private fun begin(pending: String, action: String, argument: String = "", user: String = "", password: String = ""): Boolean {
        if (busy) { Toast.makeText(this, "Дождитесь завершения текущей операции", Toast.LENGTH_SHORT).show(); return false }
        model.pending = pending
        PanelOperation.start(server, action, argument, user, password)
        render(PanelOperation.state.value)
        return true
    }

    private fun render(state: PanelState) {
        val mine = state.serverId == model.server?.id
        primary?.isEnabled = !state.running
        primary?.alpha = if (state.running) .5f else 1f
        if (state.trust.isBlank()) shownTrust = ""
        if (state.trust.isNotBlank() && state.trust != shownTrust) {
            shownTrust = state.trust
            MobileAlertDialogBuilder(this).setTitle("Ключ SSH сервера")
                .setMessage("Первое подключение к ${server["host"]}.\n\nОтпечаток ключа:\n${state.trust}\n\nСверьте его с ключом VPS у провайдера.")
                .setPositiveButton("Доверять") { _, _ -> PanelOperation.approve(true) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> PanelOperation.approve(false) }
                .setOnCancelListener { PanelOperation.approve(false) }.show()
        }
        if (!mine) return
        if (state.running && model.screen in listOf(Screen.CONNECT, Screen.DOMAIN, Screen.DNS)) primary?.text = "Проверка…"
        stepText?.text = if (state.error.isNotEmpty() && !state.running) state.error else state.step
        bar?.apply { isIndeterminate = state.running && state.percent in 1..99; progress = state.percent }
        logText?.let { log ->
            val text = state.log.takeLast(20_000)
            if (log.text.length != text.length) { log.text = text; logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) } }
        }
        stopButton?.visibility = if (state.running) View.VISIBLE else View.INVISIBLE
        checkBody?.text = when {
            model.pending == "check" && state.running -> "Проверка…"
            state.error.isNotEmpty() && model.pending == "check" -> state.error
            else -> state.checkText
        }
        if (!state.running && state.finished != model.handled && model.pending.isNotEmpty()) {
            model.handled = state.finished
            val pending = model.pending
            model.pending = ""
            lifecycleScope.launch { finished(pending, state) }
        }
    }

    private suspend fun finished(pending: String, state: PanelState) {
        // The operation saved its copy of the server: its key and what is installed now.
        withContext(Dispatchers.IO) { runCatching { DeployStore.all().firstOrNull { it.id == server.id } } }.getOrNull()?.let { stored ->
            for (key in listOf("hostKey", "connectedHost", "connectedPort", "installedRole", "mode", "installedDomain", "installedProtocols", "installedZone",
                "installedNs", "publicIp", "panelUrl", "panelPort", "panelPath", "panelUser", "panelPassword", "version")) server[key] = stored[key]
        }
        if (state.error.isNotEmpty()) {
            when (pending) {
                "install", "uninstall" -> Unit
                "check" -> { checkBody?.text = state.error; primary?.isEnabled = false }
                else -> { fail(state.error); primary?.text = when (model.screen) { Screen.CONNECT -> "Подключиться"; Screen.SERVER -> primary?.text; else -> "Проверить и продолжить" } }
            }
            return
        }
        when (pending) {
            "connect" -> {
                server["connectedHost"] = server["host"]; server["connectedPort"] = server["sshPort"]
                withContext(Dispatchers.IO) { runCatching { DeployStore.save(server) } }
                if (server["installedRole"].isNotEmpty()) show(Screen.SERVER) else { model.goal = "install"; show(Screen.ROLE) }
            }
            "domain", "dns" -> {
                val (label, name) = model.resolving
                val problem = PanelCommands.resolveError(label, name, state.resolved.orEmpty(), server["publicIp"])
                if (problem != null) { fail(problem); primary?.text = "Проверить и продолжить"; return }
                if (pending == "domain") afterDomain() else show(Screen.CHECK)
            }
            "check" -> { primary?.isEnabled = state.checkOk; primary?.alpha = if (state.checkOk) 1f else .5f }
            "install", "uninstall" -> if (model.screen == Screen.PROGRESS) show(Screen.SERVER)
            "credentials" -> { Toast.makeText(this, "Логин и пароль панели изменены", Toast.LENGTH_SHORT).show(); if (model.screen == Screen.SERVER) show(Screen.SERVER, remember = false, animate = false) }
            "refresh" -> show(Screen.SERVER, remember = false, animate = false)
        }
    }
}
