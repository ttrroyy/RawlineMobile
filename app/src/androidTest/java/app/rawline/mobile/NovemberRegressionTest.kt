package app.rawline.mobile

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.Bitmap
import android.graphics.Rect
import android.content.Intent
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.rawline.mobile.bg.proto.CoreConfigs
import app.rawline.mobile.bg.carrier.CarrierKind
import app.rawline.mobile.bg.carrier.CarrierProcess
import app.rawline.mobile.bg.carrier.CarrierBridgeCredentials
import app.rawline.mobile.database.ProxyEntity
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.database.GroupRepo
import app.rawline.mobile.database.ProxyGroup
import app.rawline.mobile.database.SagerDatabase
import app.rawline.mobile.outbound.config.ProfileProvider
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.json.JsonInput
import app.rawline.mobile.outbound.types.DnsTunnel
import app.rawline.mobile.outbound.types.WireGuard
import app.rawline.mobile.outbound.types.Socks
import app.rawline.mobile.ui.MainActivity
import app.rawline.mobile.utils.Theme
import app.rawline.mobile.ui.MobileFeedback
import app.rawline.mobile.ui.MobileReorder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

@RunWith(AndroidJUnit4::class)
class NovemberRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun homeControlsAndServerRemainVisibleInEveryRawlinePalette() {
        val context = instrumentation.targetContext
        val previousStyle = DataStore.uiStyle
        val previousNight = DataStore.nightTheme
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
            for (style in listOf("new", "pastel")) for (night in listOf(1, 2)) {
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
                        val settings = descendants(activity.window.decorView).first { it.contentDescription == context.getString(R.string.qmobile_settings) }
                        assertTrue("Settings hidden in $style / $night", settings.getGlobalVisibleRect(Rect()))
                    }
                    val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try { File(context.filesDir, "home-$style-$night.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                    finally { screenshot.recycle() }
                } finally {
                    instrumentation.runOnMainSync { activity.finish() }
                }
            }
        } finally {
            DataStore.uiStyle = previousStyle
            DataStore.nightTheme = previousNight
            GroupRepo.setCurrent(previousGroup)
            instrumentation.runOnMainSync { Theme.applyNightTheme() }
            SagerDatabase.proxyDao.deleteById(profile.id)
            SagerDatabase.groupDao.deleteById(group.id)
        }
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
