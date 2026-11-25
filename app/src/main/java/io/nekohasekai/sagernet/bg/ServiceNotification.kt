package app.rawline.mobile.bg

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
import android.os.Build
import android.text.format.Formatter
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.rawline.mobile.Action
import app.rawline.mobile.Key
import app.rawline.mobile.R
import app.rawline.mobile.SagerNet
import app.rawline.mobile.aidl.SpeedDisplayData
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.database.ProxyEntity
import app.rawline.mobile.database.SagerDatabase
import app.rawline.core.mobile.Mobile
import app.rawline.mobile.ktx.app
import app.rawline.mobile.ktx.appUserAgent
import app.rawline.mobile.ktx.getColorAttr
import app.rawline.mobile.ktx.runOnMainDispatcher
import app.rawline.mobile.ui.SwitchActivity
import app.rawline.mobile.ui.test.TestFormat
import app.rawline.mobile.widget.ExitIp
import app.rawline.mobile.utils.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * User can customize visibility of notification since Android 8.
 * The default visibility:
 *
 * Android 8.x: always visible due to system limitations
 * VPN:         always invisible because of VPN notification/icon
 * Other:       always visible
 *
 * See also: https://github.com/aosp-mirror/platform_frameworks_base/commit/070d142993403cc2c42eca808ff3fafcee220ac4
 */
class ServiceNotification(
    private val service: BaseService.Interface, title: String,
    channel: String, visible: Boolean = false,
) : BroadcastReceiver() {
    companion object {
        const val notificationId = 1
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        // notificationActions entries; the template shows at most three buttons, in this order.
        const val MAX_ACTIONS = 3
        const val ACTION_STOP = "stop"
        const val ACTION_PING = "ping"
        const val ACTION_SWITCH = "switch"
        const val ACTION_RESET = "reset"
        val DEFAULT_ACTIONS = listOf(ACTION_STOP)
        private val ACTION_ORDER = listOf(ACTION_STOP, ACTION_SWITCH, ACTION_PING, ACTION_RESET)

        fun notificationActions(): List<String> {
            val chosen = DataStore.configurationStore.getStringList(Key.NOTIFICATION_ACTIONS, DEFAULT_ACTIONS)
            return ACTION_ORDER.filter { it in chosen }.take(MAX_ACTIONS)
        }

        fun genTitle(ent: ProxyEntity): String {
            val group = if (DataStore.showGroupInNotification) SagerDatabase.groupDao.getById(ent.groupId)?.displayName() else null
            return if (group.isNullOrBlank()) ent.displayName() else "[$group] ${ent.displayName()}"
        }
    }

    var listenPostSpeed = SagerNet.power.isInteractive

    /** Whether the screen is on; the traffic looper stops polling the core while it is off. */
    val screenOn = MutableStateFlow(SagerNet.power.isInteractive)

    /** "flag Country, City" of the connection's exit, shown between the server name and the speeds. */
    private var location: String? = null
    private var speedText: String? = null

    private var latency: String? = null
    private var pingRunning = false

    private fun bodyText(): String? {
        val place = listOfNotNull(location, latency).joinToString(", ").ifEmpty { null }
        return listOfNotNull(place, speedText).joinToString("\n").ifEmpty { null }
    }

    suspend fun ping() {
        val running = service.data.proxy ?: return
        if (pingRunning || !service.data.state.connected) return
        pingRunning = true
        try {
            val elapsed = withContext(Dispatchers.IO) {
                runCatching { service.data.binder.urlTest() }.getOrNull()
            }
            if (service.data.proxy !== running || !service.data.state.connected) return
            val context = service as Context
            latency = if (elapsed != null && elapsed >= 0) context.getString(R.string.notification_latency, elapsed)
                else context.getString(R.string.notification_ping_unavailable)
            useBuilder { applyBody(it) }
            update()
        } finally {
            pingRunning = false
        }
    }

    private fun applyBody(builder: NotificationCompat.Builder) {
        val body = bodyText()
        builder.setContentText(body)
        builder.setStyle(if (body == null) null else NotificationCompat.BigTextStyle().bigText(body))
    }

    /** The exit lookup of this process (exit_country_lookup decides whether it runs) feeds the location line. */
    private val exitListener: () -> Unit = {
        val info = ExitIp.info
        val text = info?.let { exit ->
            val place = listOf(exit.country, exit.city).filter { it.isNotBlank() }.joinToString(", ")
            listOf(TestFormat.flag(exit.countryCode), place).filter { it.isNotBlank() }.joinToString(" ")
        }?.ifEmpty { null }
        if (text != location) runOnMainDispatcher {
            location = text
            useBuilder { applyBody(it) }
            update()
        }
    }

    suspend fun postNotificationSpeedUpdate(stats: SpeedDisplayData) {
        if (!DataStore.configurationStore.getBoolean("qmobileNotificationSpeed", false)) return
        useBuilder {
            if (!DataStore.configurationStore.getBoolean("qmobileNotificationSpeed", false)) return@useBuilder
            if (DataStore.showDirectSpeed) {
                val speedDetail = (service as Context).getString(
                    R.string.speed_detail, service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.txRateProxy)
                    ), service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.rxRateProxy)
                    ), service.getString(
                        R.string.speed,
                        Formatter.formatFileSize(service, stats.txRateDirect)
                    ), service.getString(
                        R.string.speed,
                        Formatter.formatFileSize(service, stats.rxRateDirect)
                    )
                )
                speedText = speedDetail
            } else {
                val speedSimple = (service as Context).getString(
                    R.string.traffic, service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.txRateProxy)
                    ), service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.rxRateProxy)
                    )
                )
                speedText = speedSimple
            }
            applyBody(it)
            it.setSubText(
                service.getString(
                    R.string.traffic,
                    Formatter.formatFileSize(service, stats.txTotal),
                    Formatter.formatFileSize(service, stats.rxTotal)
                )
            )
        }
        update()
    }

    suspend fun postNotificationTitle(newTitle: String) {
        useBuilder {
            shownTitle = newTitle
            it.setContentTitle(newTitle)
        }
        update()
    }

    /** A restart in place: the new profile's title and buttons, and no speed or exit of the old one. */
    suspend fun refresh(newTitle: String) {
        updateActions()
        ExitIp.clear()
        location = null
        latency = null
        speedText = null
        useBuilder {
            shownTitle = newTitle
            it.setContentTitle(newTitle)
            it.setContentText(null)
            it.setSubText(null)
            it.setStyle(null)
        }
        update()
    }

    /** What a start waits for (an Xray geo asset download) under the title, [percent] -1 while unknown; null clears it. */
    suspend fun postStartProgress(text: String?, percent: Int) {
        useBuilder {
            it.setContentText(text)
            if (text == null) it.setProgress(0, 0, false) else it.setProgress(100, percent.coerceAtLeast(0), percent < 0)
        }
        update()
    }

    suspend fun postConnected() {
        updateActions()
        useBuilder { it.priority = NotificationCompat.PRIORITY_LOW }
        update()
        // a fresh lookup for every connection, so a reconnect never shows the previous exit
        runOnMainDispatcher {
            ExitIp.coreFetch = { url, timeoutMs ->
                Mobile.fetchThroughProxy(
                    CoreRuntime.running?.instance, url, appUserAgent(), timeoutMs.toInt(), 64 * 1024,
                )
            }
            ExitIp.listener = exitListener
            ExitIp.clear()
            ExitIp.ensure()
        }
    }

    suspend fun reloadSettings(oldTitle: String, newTitle: String) {
        updateActions()
        useBuilder {
            if (shownTitle == oldTitle) {
                shownTitle = newTitle
                it.setContentTitle(newTitle)
            }
            if (speedText != null && !DataStore.configurationStore.getBoolean("qmobileNotificationSpeed", false)) {
                speedText = null
                applyBody(it)
                it.setSubText(null)
            }
        }
        update()
    }

    private var shownTitle = title

    private val builder = NotificationCompat.Builder(service as Context, channel)
        .setWhen(0)
        .setTicker(service.getString(R.string.forward_success))
        .setContentTitle(title)
        .setOnlyAlertOnce(true)
        .setContentIntent(SagerNet.configureIntent(service))
        .setSmallIcon(R.drawable.ic_rawline_tile)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(if (visible) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MIN)

    private val buildLock = Mutex()
    private val postingLock = Any()
    private var closed = false

    private suspend fun useBuilder(f: (NotificationCompat.Builder) -> Unit) {
        buildLock.withLock {
            synchronized(postingLock) {
                if (!closed) f(builder)
            }
        }
    }

    init {
        service as Context

        Theme.apply(app)
        Theme.apply(service)
        builder.color = service.getColorAttr(R.attr.colorPrimary)

        service.registerReceiver(this, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })

        runOnMainDispatcher {
            updateActions()
            show()
        }
    }

    private suspend fun updateActions() {
        service as Context
        val actions = notificationActions()
        useBuilder {
            it.clearActions()
            for (action in actions) when (action) {
                ACTION_STOP -> it.addAction(
                    broadcastAction(R.drawable.ic_notification_stop, service.getText(R.string.stop), Action.CLOSE, 1)
                )

                ACTION_SWITCH -> it.addAction(
                    NotificationCompat.Action.Builder(
                        R.drawable.ic_notification_switch, service.getText(R.string.notification_switch),
                        PendingIntent.getActivity(
                            service, 4,
                            Intent(service, SwitchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            flags,
                        )
                    ).setShowsUserInterface(true).build()
                )

                ACTION_PING -> it.addAction(
                    broadcastAction(R.drawable.ic_mobile_test, service.getText(R.string.notification_ping), Action.PING_CURRENT, 6)
                )

                ACTION_RESET -> it.addAction(
                    broadcastAction(
                        R.drawable.ic_notification_reset, service.getText(R.string.reset_connections),
                        Action.RESET_UPSTREAM_CONNECTIONS, 5,
                    )
                )
            }
        }
    }

    private fun broadcastAction(icon: Int, title: CharSequence, action: String, requestCode: Int) =
        NotificationCompat.Action.Builder(
            icon, title, PendingIntent.getBroadcast(
                service as Context, requestCode, Intent(action).setPackage(service.packageName), flags
            )
        ).setShowsUserInterface(false).build()

    override fun onReceive(context: Context, intent: Intent) {
        val on = intent.action == Intent.ACTION_SCREEN_ON
        screenOn.value = on
        listenPostSpeed = on
    }


    /** Every startForegroundService needs its startForeground, a repeated start of a running service included. */
    fun showAgain() = runOnMainDispatcher { show() }

    private suspend fun show() =
        useBuilder {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    (service as Service).startForeground(
                        notificationId,
                        it.build(),
                        FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
                    )
                } else {
                    (service as Service).startForeground(notificationId, it.build())
                }
            } catch (e: Exception) {
                Toast.makeText(
                    SagerNet.application,
                    "startForeground: $e",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private suspend fun update() = useBuilder {
        NotificationManagerCompat.from(service as Service).notify(notificationId, it.build())
    }

    fun destroy() {
        // A queued title, speed or foreground update must not bring back a stopped notification.
        synchronized(postingLock) {
            if (closed) return
            closed = true
        }
        listenPostSpeed = false
        runOnMainDispatcher {
            if (ExitIp.listener === exitListener) ExitIp.listener = null
            ExitIp.clear()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            (service as Service).stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            (service as Service).stopForeground(true)
        }
        service.unregisterReceiver(this)
    }
}
