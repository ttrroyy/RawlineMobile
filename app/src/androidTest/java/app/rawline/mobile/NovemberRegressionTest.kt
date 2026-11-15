package app.rawline.mobile

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.rawline.mobile.bg.proto.CoreConfigs
import app.rawline.mobile.database.ProxyEntity
import app.rawline.mobile.database.ProxyGroup
import app.rawline.mobile.database.SagerDatabase
import app.rawline.mobile.outbound.config.ProfileProvider
import app.rawline.mobile.outbound.json.JsonObject
import app.rawline.mobile.outbound.types.WireGuard
import app.rawline.mobile.ui.MobileFeedback
import app.rawline.mobile.ui.MobileReorder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NovemberRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

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
