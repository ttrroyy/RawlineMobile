package app.rawline.mobile

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.rawline.mobile.bg.proto.CoreConfigs
import app.rawline.mobile.bg.proto.SpeedTestSnapshot
import app.rawline.mobile.aidl.ITestSessionCallback
import app.rawline.mobile.bg.test.TestEngine
import app.rawline.mobile.bg.test.TestSpec
import app.rawline.mobile.bg.carrier.CarrierKind
import app.rawline.mobile.bg.carrier.CarrierProcess
import app.rawline.mobile.bg.carrier.CarrierBridgeCredentials
import app.rawline.mobile.database.ProxyEntity
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.database.GroupRepo
import app.rawline.mobile.database.ProxyGroup
import app.rawline.mobile.database.SagerDatabase
import app.rawline.mobile.database.backup.BackupExport
import app.rawline.mobile.database.backup.RwlBackup
import app.rawline.mobile.outbound.config.ProfileProvider
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.json.JsonInput
import app.rawline.mobile.outbound.types.DnsTunnel
import app.rawline.mobile.outbound.types.WireGuard
import app.rawline.mobile.outbound.types.Socks
import app.rawline.mobile.ui.MainActivity
import app.rawline.mobile.ui.PinnedServers
import app.rawline.mobile.utils.Theme
import app.rawline.mobile.ui.MobileFeedback
import app.rawline.mobile.ui.MobileReorder
import app.rawline.mobile.ui.MobileSubscriptionSwipe
import app.rawline.mobile.ui.MobileHomeLayout
import app.rawline.mobile.ui.MobileOrbArtwork
import app.rawline.mobile.bg.carrier.CarrierUiRequests
import android.view.MotionEvent
import android.os.SystemClock
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

@RunWith(AndroidJUnit4::class)
class NovemberRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun pinningSelectedProfilesPreservesOtherPinsAndGroupOrder() {
        val previous = DataStore.mobilePinnedProfiles
        try {
            DataStore.mobilePinnedProfiles = "10,30"
            val profiles = listOf(40L, 30L, 20L, 10L)
            PinnedServers.toggle(listOf(10L, 20L))
            assertEquals(listOf(30L, 20L, 10L, 40L), PinnedServers.first(profiles) { it })
            assertTrue(PinnedServers.sameSection(10L, 30L))
            assertFalse(PinnedServers.sameSection(10L, 40L))

            PinnedServers.toggle(listOf(10L, 20L))
            assertEquals(setOf(30L), PinnedServers.ids())
            assertEquals(listOf(30L, 40L, 20L, 10L), PinnedServers.first(profiles) { it })
        } finally {
            DataStore.mobilePinnedProfiles = previous
        }
    }

    @Test fun profilesOnlyBackupCarriesPinsWithoutSettings() {
        val group = ProxyGroup(name = "Pinned backup regression")
        group.id = SagerDatabase.groupDao.insert(group)
        val previousPins = DataStore.mobilePinnedProfiles
        val snapshot = File.createTempFile("pins", ".db", instrumentation.targetContext.cacheDir)
        try {
            val profile = ProxyEntity(groupId = group.id).putOutbound(Socks().apply {
                name = "Pinned server"
                server = "192.0.2.1"
                serverPort = 1080
            })
            profile.id = SagerDatabase.proxyDao.addProxy(profile)
            DataStore.mobilePinnedProfiles = profile.id.toString()
            val output = ByteArrayOutputStream()
            BackupExport.write(BackupExport.Selection(profiles = true, routes = false, settings = false), output)
            val backup = RwlBackup.read(output.toByteArray().inputStream(), snapshot)
            assertFalse(backup.parts.settings)
            SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                database.rawQuery("SELECT pinned_json FROM groups WHERE id = ?", arrayOf(group.id.toString())).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("[${profile.id}]", it.getString(0))
                }
            }
        } finally {
            DataStore.mobilePinnedProfiles = previousPins
            SagerDatabase.groupDao.deleteById(group.id)
            snapshot.delete()
        }
    }

    @Test fun cancellingAJustStartedProbeReleasesTheTestSession() = runBlocking {
        val callback = object : ITestSessionCallback.Stub() {
            override fun onStarted(session: Int, kind: Int, total: Int) = Unit
            override fun onUrlResult(profileId: Long, latency: Int, error: String?) = Unit
            override fun onIpResult(profileId: Long, ip: String?, country: String?, error: String?) = Unit
            override fun onSpeedProgress(snapshot: SpeedTestSnapshot?) = Unit
            override fun onSpeedResult(profileId: Long, dl: String?, ul: String?, latency: Int, country: String?, error: String?) = Unit
            override fun onDone(session: Int, cancelled: Boolean) = Unit
        }
        val spec = TestSpec(TestSpec.KIND_URL)
        repeat(32) {
            withTimeout(5_000) {
                while (TestEngine.start(spec, callback) < 0) yield()
                TestEngine.stopAndJoin()
                while (TestEngine.runningSession() != 0) yield()
            }
        }
    }

    @Test fun dockCaptionWrappingDoesNotOscillateWhenItsHeightChanges() {
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync {
            val density = context.resources.displayMetrics.density
            val width = (320 * density).toInt()
            val group = "Subscription"
            val server = "Amsterdam 01"
            val measure = Paint().apply {
                textSize = 12f * density
                typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.geologica)
            }
            val combinedWidth = measure.measureText("$group  ·  $server")
            val changes = mutableListOf<Int>()
            val artwork = MobileOrbArtwork(context, "Tap to connect").apply {
                discSize = (160 * density).toInt()
                groupText = group
                serverText = server
                textRoom = (combinedWidth - density) / .94f
                linesChanged = { changes.add(it) }
            }
            val bitmap = Bitmap.createBitmap(width, (200 * density).toInt(), Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                for (height in listOf(176, 192, 176, 192)) {
                    artwork.setBounds(0, 0, width, (height * density).toInt())
                    artwork.draw(canvas)
                }
                assertEquals(listOf(2), changes)
                artwork.textRoom = (combinedWidth + density) / .94f
                artwork.draw(canvas)
                assertEquals(listOf(2, 1), changes)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test fun autoSelectorFilterFitsTheFullRussianLabelInEveryPalette() {
        val context = instrumentation.targetContext
        val previousStyle = DataStore.uiStyle
        val previousNight = DataStore.nightTheme
        try {
            for (style in listOf("new", "pastel")) for (night in listOf(1, 2)) {
                DataStore.uiStyle = style
                DataStore.nightTheme = night
                instrumentation.runOnMainSync { Theme.applyNightTheme() }
                val activity = instrumentation.startActivitySync(Intent(context, app.rawline.mobile.ui.AutoSelectorStatusActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                try {
                    instrumentation.runOnMainSync {
                        activity.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.only_problems).apply {
                            text = "Показывать только профили с проблемами"
                            textSize = 22f
                        }
                    }
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(500)
                    instrumentation.runOnMainSync {
                        val filter = activity.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.only_problems)
                        assertTrue("Filter is hidden in $style / $night", filter.getGlobalVisibleRect(Rect()))
                        val layout = requireNotNull(filter.layout)
                        assertTrue("Large filter label did not wrap", layout.lineCount > 1)
                        for (line in 0 until layout.lineCount) assertEquals("Filter label was truncated", 0, layout.getEllipsisCount(line))
                        assertTrue("Filter text is clipped vertically", layout.height <= filter.height - filter.compoundPaddingTop - filter.compoundPaddingBottom)
                        assertFalse(filter.isChecked)
                        filter.performClick()
                        assertTrue(filter.isChecked)
                    }
                    val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try { File(context.filesDir, "autosel-filter-$style-$night.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                    finally { screenshot.recycle() }
                } finally { instrumentation.runOnMainSync { activity.finish() } }
            }
        } finally {
            DataStore.uiStyle = previousStyle
            DataStore.nightTheme = previousNight
            instrumentation.runOnMainSync { Theme.applyNightTheme() }
        }
    }

    @Test fun rejectedCoreBindingFailsAndCanRecoverWithoutStartingVpn() = runBlocking {
        var unbound = 0
        var throwOnBind = false
        val context = object : android.content.ContextWrapper(instrumentation.targetContext) {
            override fun bindService(intent: Intent, connection: android.content.ServiceConnection, flags: Int): Boolean {
                if (throwOnBind) throw SecurityException("fixture binding denied")
                return false
            }
            override fun unbindService(connection: android.content.ServiceConnection) { unbound++ }
        }
        val connection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) = Unit
            override fun onServiceDisconnected(name: android.content.ComponentName?) = Unit
        }
        assertNull(app.rawline.mobile.bg.CoreServiceClient.bind(context, connection))
        assertEquals("Rejected binding was not released", 1, unbound)
        throwOnBind = true
        var rejected = false
        try {
            app.rawline.mobile.bg.CoreServiceClient.bind(context, connection)
        } catch (e: SecurityException) {
            rejected = true
        }
        assertTrue("Binding permission failure was hidden", rejected)
        assertEquals("Throwing binding was not released", 2, unbound)
        kotlinx.coroutines.withTimeout(20_000) {
            app.rawline.mobile.bg.CoreServiceClient.call { it.runningTestSession() }
        }
        assertFalse("Restoring the import service started VPN", DataStore.serviceState.canStop)
    }

    @Test fun wdttLinksImportThroughTheBackgroundServiceWithVpnStopped() = runBlocking {
        val previousSelected = DataStore.selectedProxy
        val previousGroup = GroupRepo.currentId()
        val group = ProxyGroup(name = "WDTT import regression")
        group.id = SagerDatabase.groupDao.insert(group)
        val hash = "abcDEF123_-xyz789"
        try {
            assertFalse("Fixture requires stopped VPN", DataStore.serviceState.canStop)
            for (link in listOf(
                "wdtt://192.0.2.5:56000:56001:0:fixture:$hash#Legacy",
                "wdtt://connect?v=1&host=192.0.2.6&dtls=56000&wg=56001&local=9000&password=fixture&hashes=$hash&name=Connect",
                "qwdtt://config?peer=192.0.2.7%3A56000&pass=fixture&hashes=$hash&name=Qwdtt",
            )) {
                val imported = app.rawline.mobile.group.SubscriptionClient.importText(link, group.id)
                assertEquals("Background import did not store the WDTT profile: " +
                    if (imported.count != 1) String(moe.matsuri.nb4a.utils.CoreLog.read(8_192)) else "", 1, imported.count)
                assertFalse("Import started the VPN", DataStore.serviceState.canStop)
            }
            assertEquals(3, SagerDatabase.proxyDao.getByGroup(group.id).size)
        } finally {
            SagerDatabase.proxyDao.getByGroup(group.id).forEach { SagerDatabase.proxyDao.deleteById(it.id) }
            SagerDatabase.groupDao.deleteById(group.id)
            GroupRepo.setCurrent(previousGroup)
            DataStore.selectedProxy = previousSelected
        }
    }

    @Test fun tunProtectionUpgradeRunsOnceAndThenPreservesUserChoices() {
        val store = DataStore.configurationStore
        val mainKey = "block_unowned_tun"
        val strictKey = "block_unowned_tun_strict"
        val marker = DataStore.TUN_PROTECTION_MODES_MIGRATED
        val original = listOf(mainKey, strictKey, marker).associateWith(store::getRaw)
        try {
            store.remove(marker)
            store.putBoolean(mainKey, false)
            store.putBoolean(strictKey, true)
            DataStore.migrateTunProtectionModes()
            assertTrue(DataStore.blockUnownedTun)
            assertFalse(DataStore.blockUnownedTunStrict)
            assertTrue(store.getBoolean(marker, false))
            // A fresh store reads the persisted upgrade, then saves a choice made after it.
            val reopened = app.rawline.mobile.database.preference.SettingsStore({ SagerDatabase.instance },
                app.rawline.mobile.database.SettingsRegistry::defaultOf)
            assertTrue(reopened.getBoolean(mainKey, false))
            assertFalse(reopened.getBoolean(strictKey, true))
            reopened.putBoolean(mainKey, false)
            reopened.putBoolean(strictKey, true)
            DataStore.migrateTunProtectionModes()
            assertFalse("A later startup overwrote the chosen main mode", DataStore.blockUnownedTun)
            assertTrue("A later startup overwrote the chosen strict mode", DataStore.blockUnownedTunStrict)
        } finally {
            original.forEach { (key, value) -> if (value == null) store.remove(key) else store.putAll(mapOf(key to value)) }
        }
    }

    @Test fun strictTunSwitchTracksTheParentAcrossRawlinePalettes() {
        val context = instrumentation.targetContext
        val store = DataStore.configurationStore
        val mainKey = "block_unowned_tun"
        val strictKey = "block_unowned_tun_strict"
        val original = mapOf(mainKey to store.getRaw(mainKey), strictKey to store.getRaw(strictKey))
        val previousStyle = DataStore.uiStyle
        val previousNight = DataStore.nightTheme
        fun screens(fragments: List<androidx.fragment.app.Fragment>): List<androidx.preference.PreferenceFragmentCompat> =
            fragments.flatMap { listOfNotNull(it as? androidx.preference.PreferenceFragmentCompat) + screens(it.childFragmentManager.fragments) }
        try {
            for (style in listOf("new", "pastel")) for (night in listOf(1, 2)) {
                store.remove(mainKey); store.remove(strictKey)
                assertTrue("Fresh settings must enable base protection", DataStore.blockUnownedTun)
                assertFalse("Fresh settings must leave strict protection off", DataStore.blockUnownedTunStrict)
                DataStore.uiStyle = style; DataStore.nightTheme = night
                instrumentation.runOnMainSync { Theme.applyNightTheme() }
                val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
                try {
                    instrumentation.runOnMainSync {
                        activity.openSettingsScreen(app.rawline.mobile.ui.settings.TunSettingsFragment::class.java.name, "TUN")
                    }
                    var screen: androidx.preference.PreferenceFragmentCompat? = null
                    val deadline = System.currentTimeMillis() + 15_000
                    while (screen == null && System.currentTimeMillis() < deadline) {
                        instrumentation.runOnMainSync {
                            screen = screens(activity.supportFragmentManager.fragments).firstOrNull {
                                it.isResumed && it.findPreference<androidx.preference.SwitchPreference>(strictKey) != null
                            }
                        }
                        if (screen == null) Thread.sleep(50)
                    }
                    assertNotNull("TUN settings did not open", screen)
                    instrumentation.runOnMainSync {
                        val parent = requireNotNull(screen!!.findPreference<androidx.preference.SwitchPreference>(mainKey))
                        val strict = requireNotNull(screen!!.findPreference<androidx.preference.SwitchPreference>(strictKey))
                        assertTrue(parent.isChecked)
                        assertFalse(strict.isChecked)
                        assertTrue(strict.isEnabled)
                        strict.isChecked = true
                        parent.isChecked = false
                        assertFalse("Strict option remained enabled without its parent", strict.isEnabled)
                        assertTrue("Disabling the parent must retain the strict choice", strict.isChecked)
                        assertFalse(DataStore.blockUnownedTun)
                        parent.isChecked = true
                        assertTrue(strict.isEnabled)
                        assertTrue(strict.isChecked)
                        assertTrue(DataStore.blockUnownedTunStrict)
                    }
                } finally { instrumentation.runOnMainSync { activity.finish() } }
            }
        } finally {
            original.forEach { (key, value) -> if (value == null) store.remove(key) else store.putAll(mapOf(key to value)) }
            DataStore.uiStyle = previousStyle; DataStore.nightTheme = previousNight
            instrumentation.runOnMainSync { Theme.applyNightTheme() }
        }
    }

    @Test fun bundledQwdttClientSupports145DiagnosticsWithoutConnecting() {
        val path = File(instrumentation.targetContext.applicationInfo.nativeLibraryDir,"libqwdtt.so")
        val child = ProcessBuilder(path.absolutePath,"-h").redirectErrorStream(true).start()
        try {
            val output = child.inputStream.bufferedReader().readText()
            assertTrue(child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0,child.exitValue())
            for (flag in listOf("-diag-key","-diag-ports","-dns-fallback","-turn-transport")) assertTrue("Missing qWDTT 1.4.5 flag $flag",output.contains(flag))
        } finally { child.destroy() }
    }

    @Test fun subscriptionDragWorksSlowlyInBothDirectionsAndIgnoresCancel() {
        instrumentation.runOnMainSync {
            val view = View(instrumentation.targetContext)
            val changes = mutableListOf<Int>()
            var taps = 0
            MobileSubscriptionSwipe.bind(view, { taps++ }, {}, {}) { changes.add(it) }
            val start = SystemClock.uptimeMillis()
            val width = 200f * view.resources.displayMetrics.density
            fun send(action: Int, x: Float, elapsed: Long) {
                val event = MotionEvent.obtain(start,start+elapsed,action,x,10f,0)
                try { view.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            send(MotionEvent.ACTION_DOWN,width,0);send(MotionEvent.ACTION_MOVE,width/2,150);send(MotionEvent.ACTION_UP,0f,400)
            send(MotionEvent.ACTION_DOWN,0f,500);send(MotionEvent.ACTION_MOVE,width/2,650);send(MotionEvent.ACTION_UP,width,900)
            send(MotionEvent.ACTION_DOWN,width,1000);send(MotionEvent.ACTION_MOVE,0f,1100);send(MotionEvent.ACTION_CANCEL,0f,1200)
            assertEquals(listOf(1,-1),changes)
            assertEquals(0,taps)
        }
    }

    @Test fun freeTurnLocalhostCaptchaOpensAVisibleSheet() {
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val id = UUID.randomUUID().toString()
        try {
            instrumentation.waitForIdleSync()
            assertTrue(CarrierUiRequests.post(id,"http://localhost:9/",client="freeturn"))
            val deadline = SystemClock.uptimeMillis()+10_000
            var shown = false
            while (!shown && SystemClock.uptimeMillis()<deadline) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                shown = root?.findAccessibilityNodeInfosByText("VK · Captcha")?.isNotEmpty() == true
                if (!shown) Thread.sleep(50)
            }
            assertTrue("FreeTurn localhost captcha sheet was discarded",shown)
        } finally {
            CarrierUiRequests.clear(id)
            context.sendBroadcast(Intent(context.packageName+".CAPTCHA_CLOSE").setPackage(context.packageName).putExtra("id",id),context.packageName+".SERVICE")
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun homeControlsAndServerRemainVisibleInEveryRawlinePalette() {
        val context = instrumentation.targetContext
        val previousStyle = DataStore.uiStyle
        val previousNight = DataStore.nightTheme
        val previousHiddenChip = MobileHomeLayout.chipHidden
        val previousSelected = DataStore.selectedProxy
        val previousGroup = GroupRepo.currentId()
        val group = ProxyGroup(name = "Theme regression")
        group.id = SagerDatabase.groupDao.insert(group)
        val profile = ProxyEntity(groupId = group.id).putOutbound(Socks().apply {
            name = "Theme regression server"; server = "192.0.2.1"; serverPort = 1080
        })
        profile.id = SagerDatabase.proxyDao.addProxy(profile)
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        try {
            for (style in listOf("new", "pastel")) for (night in listOf(1, 2)) for (hidden in listOf(false,true)) {
                MobileHomeLayout.chipHidden = hidden
                DataStore.uiStyle = style
                DataStore.nightTheme = night
                GroupRepo.setCurrent(group.id)
                instrumentation.runOnMainSync { Theme.applyNightTheme() }
                val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
                try {
                    val ready = java.util.concurrent.CountDownLatch(1)
                    val deadline = System.currentTimeMillis() + 15_000
                    while (System.currentTimeMillis() < deadline && ready.count > 0) {
                        instrumentation.runOnMainSync {
                            if (descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == profile.displayName() && it.isShown }) ready.countDown()
                        }
                        if (ready.count > 0) Thread.sleep(50)
                    }
                    assertEquals("Server missing in $style / $night", 0L, ready.count)
                    instrumentation.runOnMainSync {
                        val controls = descendants(activity.window.decorView)
                        for (scroller in controls.filter { it is android.widget.ScrollView || it is android.widget.HorizontalScrollView }) {
                            assertTrue("Unexpected home scrollbar in $style / $night", !scroller.isVerticalScrollBarEnabled && !scroller.isHorizontalScrollBarEnabled)
                        }
                        val row = controls.filterIsInstance<TextView>().first { it.text.toString() == profile.displayName() }.parent.parent as View
                        assertTrue("Profile tap was not handled", row.performClick())
                        assertEquals(profile.id, DataStore.selectedProxy)
                        assertTrue("Profile selection started the VPN", !DataStore.serviceState.canStop)
                        val settings = descendants(activity.window.decorView).first { it.contentDescription == context.getString(R.string.qmobile_settings) }
                        assertTrue("Settings hidden in $style / $night", settings.getGlobalVisibleRect(Rect()))
                        if(hidden) assertTrue("Relocated add button hidden in $style / $night",
                            descendants(activity.window.decorView).any { it.contentDescription == context.getString(R.string.qmobile_add) && it.isShown && it.getGlobalVisibleRect(Rect()) })
                    }
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(500)
                    val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try { File(context.filesDir, "home-$style-$night${if(hidden) "-hidden" else ""}.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                    finally { screenshot.recycle() }
                } finally {
                    instrumentation.runOnMainSync { activity.finish() }
                }
            }
        } finally {
            DataStore.selectedProxy = previousSelected
            DataStore.uiStyle = previousStyle
            DataStore.nightTheme = previousNight
            MobileHomeLayout.chipHidden = previousHiddenChip
            GroupRepo.setCurrent(previousGroup)
            instrumentation.runOnMainSync { Theme.applyNightTheme() }
            SagerDatabase.proxyDao.deleteById(profile.id)
            SagerDatabase.groupDao.deleteById(group.id)
        }
    }

    @Test fun combinedConnectionStatusSurvivesEditorLayoutsAndReconnects() {
        val context = instrumentation.targetContext
        val previousStyle = DataStore.uiStyle
        val previousNight = DataStore.nightTheme
        val previousSwitches = MobileHomeLayout.controlSwitches
        val previousBottom = MobileHomeLayout.controlBottom
        val previousScroll = MobileHomeLayout.scrollParts
        val previousHiddenSwitch = MobileHomeLayout.hiddenSwitch
        val previousProxyFirst = MobileHomeLayout.proxySwitchFirst
        val previousGroup = GroupRepo.currentId()
        val previousSelected = DataStore.selectedProxy
        val previousCurrent = DataStore.currentProfile
        val previousConnectedAt = DataStore.mobileConnectedAt
        val previousState = DataStore.serviceState
        val group = ProxyGroup(name = "State")
        group.id = SagerDatabase.groupDao.insert(group)
        val profile = ProxyEntity(groupId = group.id).putOutbound(Socks().apply {
            name = "State server"; server = "192.0.2.1"; serverPort = 1080
        })
        profile.id = SagerDatabase.proxyDao.addProxy(profile)
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        fun homes(fragments: List<androidx.fragment.app.Fragment>): List<app.rawline.mobile.ui.MobileHomeFragment> =
            fragments.flatMap { listOfNotNull(it as? app.rawline.mobile.ui.MobileHomeFragment) + homes(it.childFragmentManager.fragments) }
        try {
            MobileHomeLayout.controlSwitches = true
            MobileHomeLayout.hiddenSwitch = ""
            DataStore.selectedProxy = profile.id
            DataStore.currentProfile = profile.id
            for (style in listOf("new", "pastel")) for (night in listOf(1, 2))
                for (bottom in listOf(false, true)) for (scroll in listOf(false, true)) {
                    DataStore.uiStyle = style; DataStore.nightTheme = night
                    MobileHomeLayout.controlBottom = bottom
                    MobileHomeLayout.proxySwitchFirst = bottom
                    MobileHomeLayout.scrollParts = if (scroll) setOf("control", "chip") else emptySet()
                    DataStore.serviceState = app.rawline.mobile.bg.BaseService.State.Stopped
                    GroupRepo.setCurrent(group.id)
                    instrumentation.runOnMainSync { Theme.applyNightTheme() }
                    val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
                    try {
                        val deadline = System.currentTimeMillis() + 15_000
                        var ready = false
                        while (!ready && System.currentTimeMillis() < deadline) {
                            instrumentation.runOnMainSync {
                                ready = descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "State server" && it.isShown }
                            }
                            if (!ready) Thread.sleep(50)
                        }
                        assertTrue("Editor layout did not render: $style/$night/$bottom/$scroll", ready)
                        instrumentation.runOnMainSync {
                            val home = homes(activity.supportFragmentManager.fragments).first()
                            val controls = descendants(activity.window.decorView)
                            val tunLabel = context.getString(R.string.service_mode_vpn)
                            val proxyLabel = context.getString(R.string.service_mode_proxy)
                            val switchLabels = controls.filterIsInstance<TextView>().filter { label ->
                                label.isShown && label.text.toString() in listOf(tunLabel, proxyLabel) &&
                                    (label.parent as? ViewGroup)?.let { parent ->
                                        (0 until parent.childCount).any { parent.getChildAt(it) is androidx.appcompat.widget.SwitchCompat }
                                    } == true
                            }
                            assertEquals(if (bottom) listOf(proxyLabel, tunLabel) else listOf(tunLabel, proxyLabel),
                                switchLabels.map { it.text.toString() })
                            assertTrue(switchLabels.none { (it.parent as View).isLongClickable })
                            val power = controls.filterIsInstance<TextView>().first {
                                it.contentDescription?.toString()?.startsWith(context.getString(R.string.qmobile_connect) + ", ") == true
                            }
                            // No VPN starts: exercise the view transition while its combined container is reused.
                            for (state in listOf(app.rawline.mobile.bg.BaseService.State.Stopped,
                                app.rawline.mobile.bg.BaseService.State.Connected, app.rawline.mobile.bg.BaseService.State.Stopped,
                                app.rawline.mobile.bg.BaseService.State.Connected)) {
                                DataStore.serviceState = state
                                DataStore.mobileConnectedAt = android.os.SystemClock.elapsedRealtime() - 65_000
                                home.updateState()
                                assertTrue("Combined state row vanished: $style/$night/$bottom/$scroll/$state", power.isShown)
                                if (state.connected) {
                                    assertTrue("Connected timer missing", Regex("\\d{2}:\\d{2}:\\d{2}").containsMatchIn(power.text))
                                    assertTrue("Active server missing", power.text.contains("State server"))
                                    val check = controls.filterIsInstance<TextView>().first { it.text.toString() == context.getString(R.string.qmobile_check_connection) }
                                    assertTrue("Connection check hidden by its parent", check.isShown)
                                }
                            }
                        }
                    } finally {
                        DataStore.serviceState = app.rawline.mobile.bg.BaseService.State.Stopped
                        instrumentation.runOnMainSync { activity.finish() }
                    }
                }
        } finally {
            DataStore.uiStyle = previousStyle; DataStore.nightTheme = previousNight
            MobileHomeLayout.controlSwitches = previousSwitches; MobileHomeLayout.controlBottom = previousBottom
            MobileHomeLayout.scrollParts = previousScroll; MobileHomeLayout.hiddenSwitch = previousHiddenSwitch
            MobileHomeLayout.proxySwitchFirst = previousProxyFirst
            DataStore.selectedProxy = previousSelected; DataStore.currentProfile = previousCurrent
            DataStore.mobileConnectedAt = previousConnectedAt; DataStore.serviceState = previousState
            GroupRepo.setCurrent(previousGroup)
            instrumentation.runOnMainSync { Theme.applyNightTheme() }
            SagerDatabase.proxyDao.deleteById(profile.id); SagerDatabase.groupDao.deleteById(group.id)
        }
    }

    @Test fun disablingSubscriptionRoutingRemovesTheAppBypassCaption() {
        val context = instrumentation.targetContext
        val group = ProxyGroup(name = "Subscription caption regression")
        val apps = app.rawline.mobile.group.ProviderApps(app.rawline.mobile.group.ProviderApps.BYPASS,
            (1..16).map { "com.example.app$it" })
        group.subOptions.routingProvider = "Happ"
        group.subOptions.providerRouting = """{"Name":"Provider regression"}"""
        group.subOptions.providerApps = apps.toJson()
        group.subOptions.providerRoutingEnabled = true
        val suffix = context.getString(R.string.qmobile_route_apps_bypass, 16)
        val active = app.rawline.mobile.ui.RouteLabel.of(context, group, null)
        assertTrue("Enabled subscription must show its app count", active.contains(suffix))
        group.subOptions.providerRoutingEnabled = false
        val disabled = app.rawline.mobile.ui.RouteLabel.of(context, group, null)
        assertFalse("Disabled subscription still advertises app bypass", disabled.contains(suffix))
        assertNull(app.rawline.mobile.route.RouteSource.of(group).apps)
        group.subOptions.providerRoutingEnabled = true
        assertEquals("Re-enabling restores the caption without losing the imported app list", active,
            app.rawline.mobile.ui.RouteLabel.of(context, group, null))
    }

    @Test fun soarStartsItsAuthenticatedBridgeWithoutContactingTheServer() = runBlocking {
        val context = instrumentation.targetContext
        val profile = DnsTunnel("soar")
        assertTrue(profile.parseFromJson(JsonInput.parseObject("""{"type":"soar","domain":"v.test.invalid","pubkey":"JFHtVsmq5DauWWsc/PbXYS3xd5jCnstAPYEIEVKUC08","resolvers":["127.0.0.1:9"],"soar":{"transport":"auto","discovery":false,"country":"","discoveryWant":2,"discoveryConcurrency":2,"discoveryPaceMs":1000,"safeQName":101,"maxQName":253,"maxAnswer":1232,"holdMs":500,"maxInflight":128,"maxPathWindow":32,"maxStreams":128,"streamWindowKiB":256,"sendBufferKiB":64,"sessionIdleSeconds":90,"dialTimeoutSeconds":90}}""")))
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val credentials = CarrierBridgeCredentials("bridge-test", "bridge-test-password")
        val carrier = CarrierProcess.start(CarrierKind.SOAR, File(context.applicationInfo.nativeLibraryDir),
            File(context.cacheDir, "soar-regression"), Build.VERSION.SDK_INT,
            profile.privateConfig(port, port, credentials).toCompact(), port, credentials, allowInteractions = false)
        try {
            assertNull(carrier.recoveryFailure())
        } finally {
            carrier.close()
        }
    }

    @Test fun uniformAndIndividualCornersAcceptFeedbackWithoutCrashing() {
        instrumentation.runOnMainSync {
            for (separate in listOf(false, true)) {
                val background = GradientDrawable().apply {
                    setColor(android.graphics.Color.GRAY)
                    if (separate) cornerRadii = floatArrayOf(4f, 4f, 8f, 8f, 12f, 12f, 16f, 16f)
                    else cornerRadius = 14f
                }
                val view = View(instrumentation.targetContext).apply { this.background = background }
                MobileFeedback.apply(view)
                assertTrue(view.background is RippleDrawable)
                val ripple = view.background as RippleDrawable
                assertSame(background, ripple.getDrawable(0))
                MobileFeedback.apply(view)
                assertSame(ripple, view.background)
            }
        }
    }

    @Test fun wireGuardAndAmneziaProfilesReadTheirGroupProxiesWithoutRecursing() {
        val group = ProxyGroup(name = "November group lookup", frontProxyId = 123456L, landingProxyId = 654321L)
        group.id = SagerDatabase.groupDao.insert(group)
        val profile = ProxyEntity(groupId = group.id).putOutbound(WireGuard())
        profile.id = SagerDatabase.proxyDao.addProxy(profile)
        try {
            val type = CoreConfigs::class.java.declaredClasses.single { it.simpleName == "DatabaseProfiles" }
            val constructor = type.getDeclaredConstructor(JsonObject::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            val profiles = constructor.newInstance(JsonObject(), false) as ProfileProvider
            assertEquals(654321L to 123456L, profiles.groupProxies(profile.id))
            assertEquals(-1L to -1L, profiles.groupProxies(-1L))
        } finally {
            SagerDatabase.proxyDao.deleteById(profile.id)
            SagerDatabase.groupDao.deleteById(group.id)
        }
    }

    @Test fun remoteReorderingMovesTheNeighbourAndKeepsTheHeldRowInItsParent() {
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val rows = LinearLayout(context)
            val items = (1..3).map { TextView(context).apply { text = "$it"; rows.addView(this) } }
            val reorder = MobileReorder(rows, rows, {}, {})
            items.forEachIndexed { index, item -> reorder.bind(item, item, index.toLong()) }
            val step = MobileReorder::class.java.getDeclaredMethod("keyStep", View::class.java, View::class.java, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
            step.invoke(reorder, items[1], items[1], 1)
            assertSame(items[0], rows.getChildAt(0))
            assertSame(items[2], rows.getChildAt(1))
            assertSame(items[1], rows.getChildAt(2))
            step.invoke(reorder, items[1], items[1], -1)
            assertSame(items[1], rows.getChildAt(1))
        }
    }
}
