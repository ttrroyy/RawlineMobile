package app.rawline.mobile.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.rawline.mobile.Key
import app.rawline.mobile.R
import app.rawline.mobile.database.DataStore
import java.util.Locale

private typealias LayoutAction = Pair<View, () -> Unit>

/** Black or white, whichever reads on [background]. */
internal fun contrastOn(background: Int) = if (ColorUtils.calculateLuminance(background) > .5) Color.BLACK else Color.WHITE

/**
 * A miniature of the home screen at the device's own shape and orientation, its parts trembling with breathing dashed
 * outlines as on a phone's home screen in edit mode: hold a part to move it, tap it twice to change its look, pinch it
 * to make it smaller or larger, and the small buttons inside its outline change its type, size, merging, whether it
 * scrolls with the server list, or take a tile or switch away (minus) and bring it back (plus).
 */
class MobileLayoutEditorActivity : ServerScreenActivity() {
    private enum class Part(val title: Int, val id: String) {
        TILES(R.string.qmobile_layout_tiles, "tiles"), CHIP(R.string.qmobile_layout_chip, "chip"), CONTROL(R.string.qmobile_layout_control, "control")
    }

    private val ui by lazy { ServerUi(this) }
    private fun dp(n: Int) = ui.dp(n)
    private val accent get() = MobilePalette.accent(this)
    private val parts = mutableMapOf<Part, FrameLayout>()
    private val dashes = mutableMapOf<Part, GradientDrawable>()
    /** The part whose small buttons show: a tap on a part shows them and its outline grows to hold them. */
    private var selected: Part? = null
    private val strips = mutableMapOf<Part, View>()
    /** Whether each part's buttons were drawn joined, to redraw them when a move joins or frees it. */
    private val shownJoined = mutableMapOf<Part, Boolean>()
    /** Every small button in the miniature with what it does, by the part it sits on. */
    private val actions = mutableMapOf<Part, List<LayoutAction>>()
    private lateinit var column: LinearLayout
    /** Where the parts stand: the column itself upright, a side column beside the list when the screen lies. */
    private lateinit var host: LinearLayout
    private lateinit var list: FrameLayout
    private lateinit var root: FrameLayout
    private var landscape = false
    private var dragged: Part? = null
    private var grabY = 0f
    private var fingerY = 0f
    private var blink: ValueAnimator? = null
    private var breath = 255
    private var tutorial: MobileLayoutTutorial? = null
    private var backToEditor: androidx.activity.OnBackPressedCallback? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(MobilePalette.background))
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        root = FrameLayout(this)
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(Toolbar(this).apply {
            title = getString(R.string.qmobile_edit_layout)
            setTitleTextColor(MobilePalette.text)
            setNavigationIcon(R.drawable.baseline_arrow_back_24); setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        })
        column = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16)); clipChildren = false; clipToPadding = false
            background = MobileHomeBackdrop()
        }
        host = if (landscape) LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false } else column
        list = serverList()
        val available = Part.values().toList()
        available.forEach { parts[it] = frameFor(it); fill(it) }
        arrange()

        page.addView(FrameLayout(this).apply {
            setPadding(dp(16), 0, dp(16), dp(4))
            addView(Miniature(this@MobileLayoutEditorActivity), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        // No instructions on the screen: one link opens the animated tutorial again.
        page.addView(ui.text(getString(R.string.qmobile_layout_tutorial), 15f, accent, bold = true).apply {
            gravity = Gravity.CENTER; setPadding(dp(16), dp(12), dp(16), dp(14)); isFocusable = true
            setOnClickListener { showTutorial() }; MobileFeedback.apply(this)
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        setContentView(root)
        // Back closes the tutorial first, then the editor.
        backToEditor = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { tutorial?.close() }
        }.also { onBackPressedDispatcher.addCallback(this, it) }
        if (!DataStore.configurationStore.getBoolean(TUTORIAL_SEEN, false)) root.post { showTutorial() }
    }

    private fun showTutorial() {
        if (tutorial != null) return
        backToEditor?.isEnabled = true
        tutorial = MobileLayoutTutorial(this) {
            tutorial = null; backToEditor?.isEnabled = false
            DataStore.configurationStore.putBoolean(TUTORIAL_SEEN, true)
        }.also { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
    }

    override fun onStart() {
        super.onStart()
        blink = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 1300; repeatCount = ValueAnimator.INFINITE; interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { animator ->
                val phase = animator.animatedValue as Float
                // The outlines breathe; the parts tremble slightly, each a little out of step.
                val alpha = (157 + 98 * kotlin.math.sin(phase)).toInt()
                dashes.forEach { (part, dash) -> dash.alpha = if (part == dragged) 255 else alpha }
                breath = alpha
                parts.entries.forEachIndexed { index, (part, frame) ->
                    frame.rotation = if (part == dragged) 0f else .3f * kotlin.math.sin(phase * 4 + index * 1.7f)
                }
            }
            start()
        }
    }

    override fun onStop() {
        blink?.cancel(); blink = null
        parts.values.forEach { it.rotation = 0f }
        super.onStop()
    }

    private fun isBottom(part: Part) = when (part) {
        Part.TILES -> MobileHomeLayout.tilesBottom
        Part.CHIP -> MobileHomeLayout.chipBottom
        Part.CONTROL -> MobileHomeLayout.controlBottom
    }

    private fun place(part: Part, bottom: Boolean) {
        when (part) {
            Part.TILES -> MobileHomeLayout.tilesBottom = bottom
            Part.CHIP -> MobileHomeLayout.chipBottom = bottom
            Part.CONTROL -> MobileHomeLayout.controlBottom = bottom
        }
        // The control looks different at the bottom, in a panel of its own; a part below the list never scrolls with it.
        fill(part)
        rearrange()
    }

    private fun rearrange() {
        TransitionManager.beginDelayedTransition(column, ChangeBounds().setDuration(180).apply { dragged?.let { excludeTarget(parts.getValue(it), true) } })
        arrange()
    }

    /** The home screen's order: from the top edge tiles, then subscription and control in either order; the same ones mirrored under the list. */
    private fun arrange() {
        column.removeAllViews()
        if (host !== column) host.removeAllViews()
        val order = (if (MobileHomeLayout.chipNearList) listOf(Part.TILES, Part.CONTROL, Part.CHIP) else listOf(Part.TILES, Part.CHIP, Part.CONTROL)).filter { it in parts }
        fun add(part: Part) = host.addView(parts.getValue(part), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        // As the home screen stacks them: the parts that stay on the screen first, then those that scroll with the list.
        val joined = joinedParts()
        order.filterNot(::isBottom).sortedBy { it in joined }.forEach(::add)
        if (landscape) {
            // As the home screen lies: the parts in a side column, the list beside it.
            host.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
            order.reversed().filter(::isBottom).forEach(::add)
            column.addView(host, LinearLayout.LayoutParams(dp(280), -1))
            column.addView(list, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(12) })
        } else {
            column.addView(list, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
            order.reversed().filter(::isBottom).forEach(::add)
        }
        updateJoins()
    }

    private fun frameFor(part: Part) = FrameLayout(this).apply {
        // Snug round the part; selected, the top grows to hold its buttons inside the outline (select).
        padFrame(this, false)
        // The buttons sit in the grown top padding: clipped to the padding, they were laid out but never drawn.
        clipToPadding = false
        val dash = GradientDrawable().apply {
            cornerRadius = dp(MobilePalette.rowDp + 4).toFloat()
            setStroke(dp(2), MobilePalette.text, dp(7).toFloat(), dp(5).toFloat())
        }
        dashes[part] = dash
        foreground = dash
        contentDescription = getString(part.title)
        // Touches go through the miniature; a remote, keyboard or screen reader gets the same choices as a menu.
        tag = MobileFeedback.NONE
        isFocusable = true
        setOnClickListener { menu(part, it) }
        setOnFocusChangeListener { view, focused -> view.animate().scaleX(if (focused) 1.02f else 1f).scaleY(if (focused) 1.02f else 1f).setDuration(120).start() }
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (dragged == part) follow() }
    }

    private fun padFrame(frame: View, open: Boolean) = frame.setPadding(dp(8), dp(if (open) 40 else 8), dp(8), dp(8))

    /** Shows [part]'s buttons, or hides them all for null, the outline growing or shrinking as on a real change. */
    private fun select(part: Part?) {
        if (selected == part) return
        // Only the bounds animate: a fade left the buttons see-through or not drawn at all; they appear on their own.
        TransitionManager.beginDelayedTransition(column, ChangeBounds().setDuration(180))
        selected = part
        // A part without buttons (the subscription row at the bottom) keeps its outline as it is.
        parts.forEach { (each, frame) -> padFrame(frame, each == part && ((strips[each] as? ViewGroup)?.childCount ?: 0) > 0) }
        // The buttons are always laid out and only fade: one gone until now was never laid out while the outline's
        // transition held the layout, and stayed a strip of no size, there but never seen.
        // No fade: the delayed fade never ran to its end on phones and left them unseen.
        strips.forEach { (each, strip) -> strip.alpha = if (each == part) 1f else 0f; strip.invalidate() }
    }

    /** A change of a part's look, animated as the tutorial plays it. */
    private fun changing() = TransitionManager.beginDelayedTransition(column, ChangeBounds().setDuration(180))

    private fun menu(part: Part, anchor: View) {
        val popup = PopupMenu(this, anchor)
        val positions = resources.getStringArray(R.array.qmobile_tiles_positions)
        positions.forEachIndexed { index, label ->
            popup.menu.add(label).setOnMenuItemClickListener {
                if (part == Part.CHIP) MobileHomeLayout.chipNearList = false
                place(part, index == 1); anchor.requestFocus(); true
            }
        }
        if (part == Part.CHIP) {
            popup.menu.add(R.string.qmobile_layout_chip_near).setOnMenuItemClickListener {
                MobileHomeLayout.chipNearList = true
                place(part, MobileHomeLayout.controlBottom); anchor.requestFocus(); true
            }
        }
        if (part == Part.CONTROL) popup.menu.add(R.string.qmobile_layout_swap).setOnMenuItemClickListener { setSwitches(!MobileHomeLayout.controlSwitches); true }
        if (part == Part.TILES) popup.menu.add(R.string.qmobile_layout_swap).setOnMenuItemClickListener { setExtended(!MobileHomeLayout.extendedTiles); true }
        if (part != Part.CHIP) popup.menu.add(R.string.qmobile_layout_size).setOnMenuItemClickListener { setCompact(part, !compact(part)); true }
        if (!isBottom(part) && !landscape) popup.menu.add(R.string.qmobile_layout_scroll).setOnMenuItemClickListener { toggleScroll(part); true }
        popup.show()
    }

    private fun setSwitches(switches: Boolean) {
        changing()
        MobileHomeLayout.controlSwitches = switches
        fill(Part.CONTROL)
    }

    private fun setExtended(extended: Boolean) {
        changing()
        MobileHomeLayout.extendedTiles = extended
        fill(Part.TILES)
    }

    private fun compact(part: Part) = if (part == Part.TILES) MobileHomeLayout.compactTiles else MobileHomeLayout.compactControl

    private fun setCompact(part: Part, compact: Boolean) {
        if (part == Part.CHIP || compact == compact(part)) return
        if (part == Part.TILES) MobileHomeLayout.compactTiles = compact else MobileHomeLayout.compactControl = compact
        TransitionManager.beginDelayedTransition(column, ChangeBounds().setDuration(200))
        fill(part)
    }

    /** The parts that scroll with the list: they share one dashed outline with it (Miniature.dispatchDraw). */
    private fun joinedParts() = if (landscape) emptyList() else parts.keys.filter { it.id in MobileHomeLayout.scrollParts && !isBottom(it) }

    /** A joined part gives up its own outline for the one round it and the list. */
    private fun updateJoins() {
        parts.keys.filter { shownJoined[it] != (it.id in MobileHomeLayout.scrollParts) }.forEach(::fill)
        val joined = joinedParts()
        parts.forEach { (part, frame) -> frame.foreground = if (part in joined) null else dashes[part] }
        column.parent?.let { (it as View).invalidate() }
    }

    private fun toggleScroll(part: Part) {
        val scrolls = MobileHomeLayout.scrollParts
        MobileHomeLayout.scrollParts = if (part.id in scrolls) scrolls - part.id else scrolls + part.id
        // The subscription moves to where its choice holds (MobileHomeLayout.scrollParts): unjoined from under a joined
        // control it goes up, joined above a control that stays it goes down to the list.
        val chipMoves = part == Part.CHIP && !MobileHomeLayout.controlBottom && ("control" in scrolls) == (part.id in scrolls) &&
            MobileHomeLayout.chipNearList == (part.id in scrolls)
        if (chipMoves) MobileHomeLayout.chipNearList = !MobileHomeLayout.chipNearList
        fill(part)
        // Joining or freeing a part can change where it stands (arrange): the order and the outlines both follow.
        rearrange()
        // The part rises a little with the list for a moment, to show that it scrolls now.
        if (part.id in MobileHomeLayout.scrollParts) parts.getValue(part).let { frame ->
            frame.animate().translationY(-dp(12).toFloat()).setDuration(200).withEndAction { frame.animate().translationY(0f).setDuration(200).start() }.start()
        }
    }

    private fun fill(part: Part) {
        val box = parts.getValue(part)
        box.removeAllViews()
        val items = mutableListOf<LayoutAction>()
        box.addView(when (part) {
            Part.TILES -> tiles(items)
            Part.CHIP -> chip()
            Part.CONTROL -> control(items)
        }, FrameLayout.LayoutParams(-1, -2))
        val strip = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        // Plain buttons are one quiet grey; only a setting that is on (the scrolling part) lights up.
        fun button(icon: Int, description: Int, lit: Boolean = false, action: () -> Unit) {
            val view = badge(icon, if (lit) accent else MobilePalette.badge, if (lit) contrastOn(accent) else MobilePalette.text, description)
            strip.addView(view, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginStart = dp(8) })
            items.add(view to action)
        }
        shownJoined[part] = part.id in MobileHomeLayout.scrollParts
        if (!isBottom(part) && !landscape) button(R.drawable.ic_layout_scroll, R.string.qmobile_layout_scroll, part.id in MobileHomeLayout.scrollParts) { toggleScroll(part) }
        when (part) {
            // The size is a pinch only.
            Part.TILES -> button(R.drawable.ic_layout_swap, R.string.qmobile_layout_swap) { setExtended(!MobileHomeLayout.extendedTiles) }
            Part.CHIP -> {}
            Part.CONTROL -> button(R.drawable.ic_layout_swap, R.string.qmobile_layout_swap) { setSwitches(!MobileHomeLayout.controlSwitches) }
        }
        // In the grown top of a selected outline: 6 dp under the outline and clear of its corner, 8 dp above the part.
        strip.alpha = if (selected == part) 1f else 0f
        box.addView(strip, FrameLayout.LayoutParams(-2, dp(26), Gravity.TOP or Gravity.END).apply { topMargin = -dp(34); marginEnd = dp(6) })
        strips[part] = strip
        actions[part] = items
    }

    /** A small round button of the miniature: an icon on a disc; its action runs from [Miniature]'s tap. */
    private fun badge(icon: Int, fill: Int, tint: Int, description: Int) = ImageView(this).apply {
        setImageResource(icon); imageTintList = ColorStateList.valueOf(tint); setPadding(dp(5), dp(5), dp(5), dp(5))
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill); setStroke(dp(1), ColorUtils.setAlphaComponent(MobilePalette.text, 60)) }
        contentDescription = getString(description)
    }

    /** [view] with a minus (or, once taken away, a plus) inside its top corner. */
    private fun removable(view: View, removed: Boolean, items: MutableList<LayoutAction>, action: () -> Unit) = FrameLayout(this).apply {
        if (removed) {
            view.alpha = .3f
            foreground = GradientDrawable().apply { cornerRadius = dp(MobilePalette.rowDp).toFloat(); setStroke(dp(2), MobilePalette.muted, dp(5).toFloat(), dp(4).toFloat()) }
        }
        addView(view, FrameLayout.LayoutParams(-1, -1))
        val fill = if (removed) accent else Color.rgb(220, 66, 61)
        val corner = badge(if (removed) R.drawable.ic_mobile_add else R.drawable.ic_layout_remove, fill, contrastOn(fill),
            if (removed) R.string.qmobile_layout_restore else R.string.qmobile_layout_remove).apply { setPadding(dp(3), dp(3), dp(3), dp(3)) }
        addView(corner, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.END).apply { topMargin = dp(3); marginEnd = dp(3) })
        items.add(corner to action)
    }

    private fun icon(res: Int, tint: Int = MobilePalette.text) = ImageView(this).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(tint); background = MobilePalette.rowShape(context)
        val pad = dp(if (MobileHomeLayout.compactTiles) 10 else 12); setPadding(pad, pad, pad, pad)
    }

    private fun tiles(items: MutableList<LayoutAction>) = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        val size = dp(MobileHomeLayout.tileSize())
        if (MobileHomeLayout.extendedTiles) {
            val hidden = MobileHomeLayout.hiddenTiles
            val quick = listOf("mode" to R.drawable.ic_mobile_tun, "route" to R.drawable.ic_mobile_route, "groups" to R.drawable.ic_mobile_subscriptions, "updates" to R.drawable.ic_mobile_update)
            val views = listOf<View>(icon(R.drawable.ic_mobile_settings)) + quick.map { (id, res) ->
                val removed = id in hidden
                removable(icon(res), removed, items) {
                    MobileHomeLayout.hiddenTiles = if (removed) hidden - id else hidden + id
                    TransitionManager.beginDelayedTransition(column, ChangeBounds().setDuration(160)); fill(Part.TILES)
                }
            }
            // Spanning the row as on the home screen; a narrow frame (a tablet's side column) shrinks them instead.
            views.forEachIndexed { index, view -> addView(view, LinearLayout.LayoutParams(0, size, 1f).apply { if (index < views.lastIndex) marginEnd = dp(8) }) }
            addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left == oldRight - oldLeft) return@addOnLayoutChangeListener
                val fit = minOf(size, ((right - left - dp(8) * views.lastIndex) / views.size).coerceAtLeast(1))
                if (fit < size) post { views.forEach { view -> view.layoutParams = (view.layoutParams as LinearLayout.LayoutParams).apply { width = fit; height = fit; weight = 0f } } }
            }
        } else {
            addView(icon(R.drawable.ic_mobile_more), LinearLayout.LayoutParams(size, size))
            addView(ui.text(getString(R.string.app_name), 18f, bold = true).apply { gravity = Gravity.CENTER; maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(icon(R.drawable.ic_mobile_settings), LinearLayout.LayoutParams(size, size))
        }
    }

    private fun chip() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(ui.text(getString(R.string.subscription), 16f, bold = true).apply {
            gravity = Gravity.CENTER_VERTICAL; maxLines = 1; setPadding(dp(16), 0, dp(12), 0); background = MobilePalette.rowShape(context)
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        addView(icon(R.drawable.ic_mobile_add), LinearLayout.LayoutParams(dp(48), dp(48)))
    }

    private fun check() = ui.text(getString(R.string.qmobile_check_connection), 12f, MobilePalette.muted).apply { gravity = Gravity.CENTER_VERTICAL }

    private fun control(items: MutableList<LayoutAction>): View {
        val bottom = MobileHomeLayout.controlBottom
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun status() = box.addView(check().apply { setPadding(dp(10), 0, dp(6), 0) }, LinearLayout.LayoutParams(-1, dp(34)))
        if (MobileHomeLayout.controlSwitches) {
            if (bottom) status()
            val hidden = MobileHomeLayout.hiddenSwitch
            val height = dp(if (MobileHomeLayout.compactControl) 44 else 52)
            box.addView(LinearLayout(this).apply {
                isBaselineAligned = false
                listOf(Key.MODE_VPN to R.string.service_mode_vpn, Key.MODE_PROXY to R.string.service_mode_proxy).forEachIndexed { index, (mode, label) ->
                    // A removed switch's place is only its dashed outline and the plus.
                    val cell: View = if (mode == hidden) View(context) else LinearLayout(context).apply {
                        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(2), dp(8), dp(2)); background = MobilePalette.rowShape(context)
                        addView(ui.text(getString(label), 14f), LinearLayout.LayoutParams(0, -2, 1f))
                        addView(SwitchCompat(context).apply { isClickable = false; isFocusable = false })
                    }
                    // One switch always stays: only the other can be taken away.
                    val wrapped = if (hidden.isEmpty() || mode == hidden) removable(cell, mode == hidden, items) {
                        MobileHomeLayout.hiddenSwitch = if (mode == hidden) "" else mode
                        TransitionManager.beginDelayedTransition(column, ChangeBounds().setDuration(160)); fill(Part.CONTROL)
                    } else cell
                    addView(wrapped, LinearLayout.LayoutParams(0, height, 1f).apply { if (index > 0) marginStart = dp(8) })
                }
            }, LinearLayout.LayoutParams(-1, -2))
            if (!bottom) status()
            return box
        }
        val size = dp(MobileHomeLayout.orbSize(true))
        val orb = View(this).apply {
            // As the home screen draws it: the disc, the caption and the subscription and server line under them.
            background = MobileOrbArtwork(context, getString(R.string.qmobile_orb_idle)).apply {
                discSize = size; groupText = getString(R.string.subscription); serverText = Locale("", "de").displayCountry
            }
        }
        val centre = FrameLayout(this)
        centre.addView(orb, FrameLayout.LayoutParams(-1, size + dp(16), Gravity.TOP))
        if (bottom) {
            centre.background = MobileDockGlass(MobilePalette.cardDp * resources.displayMetrics.density, MobileOrbArtwork.centerY(size), MobileOrbArtwork.radius(size) * 1.26f, dp(104).toFloat() * size / dp(190))
            centre.addView(check().apply { maxLines = 3 }, FrameLayout.LayoutParams(dp(92), -2, Gravity.START or Gravity.BOTTOM).apply { marginStart = dp(14); bottomMargin = dp(14) })
        }
        box.addView(centre, LinearLayout.LayoutParams(-1, size + dp(if (bottom) 20 else 16)))
        if (!bottom) status()
        return box
    }

    /** Stays put and only frames the rest; it takes whatever room the movable parts leave. */
    private fun serverList() = FrameLayout(this).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            for (code in listOf("de", "fi", "nl", "se", "us", "jp", "gb", "ca", "fr", "pl")) addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0); background = MobilePalette.rowShape(context)
                addView(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_XY; clipToOutline = true
                    background = GradientDrawable().apply { cornerRadius = dp(7).toFloat() }
                    MobileCountryFlags.bitmap(context, code)?.let(::setImageBitmap)
                }, LinearLayout.LayoutParams(dp(30), dp(22)).apply { marginEnd = dp(12) })
                addView(ui.text(Locale("", code).displayCountry, 15f).apply { maxLines = 1 })
            }, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(6) })
        }, FrameLayout.LayoutParams(-1, -2))
    }

    /** [view]'s bounds in [column]'s coordinates. */
    private fun boundsOf(view: View) = Rect().also { view.getDrawingRect(it); column.offsetDescendantRectToMyCoords(view, it) }

    private fun partAt(x: Float, y: Float) = parts.entries.firstOrNull { boundsOf(it.value).contains(x.toInt(), y.toInt()) }?.key

    /** The small button under a tap, with a margin around it for a finger. */
    private fun actionAt(x: Float, y: Float): (() -> Unit)? {
        val margin = dp(8)
        return actions.values.flatten().firstOrNull { (view, _) ->
            // The buttons of a part not selected are there but unseen: not touchable.
            val strip = view.parent as? View
            view.isShown && (strip !in strips.values || strip?.alpha == 1f || strip === strips[selected]) &&
                boundsOf(view).apply { inset(-margin, -margin) }.contains(x.toInt(), y.toInt())
        }?.second
    }

    private fun startDrag(part: Part, y: Float) {
        val frame = parts.getValue(part)
        dragged = part
        fingerY = y
        grabY = y - boundsOf(frame).top
        frame.rotation = 0f
        // Carried over the list, the part covers it instead of mixing with it.
        frame.background = GradientDrawable().apply { setColor(MobilePalette.background); cornerRadius = dp(MobilePalette.rowDp + 4).toFloat() }
        frame.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        frame.animate().scaleX(1.03f).scaleY(1.03f).translationZ(dp(8).toFloat()).setDuration(120).start()
    }

    private fun drag(y: Float) {
        val part = dragged ?: return
        fingerY = y
        // Upright the list is the divide; lying, the middle of the side column.
        val divide = if (landscape) host.top + host.height / 2f else list.top + list.height / 2f
        val bottom = y > divide
        if (bottom != isBottom(part)) place(part, bottom)
        val hostY = y - host.top.takeIf { landscape }.let { it ?: 0 }
        // On the control's side the finger also chooses which side of the control the subscription takes.
        if (part == Part.CHIP && bottom == MobileHomeLayout.controlBottom) {
            val control = parts.getValue(Part.CONTROL)
            val middle = control.top + control.height / 2f
            val near = if (bottom) hostY < middle else hostY > middle
            if (near != MobileHomeLayout.chipNearList) { MobileHomeLayout.chipNearList = near; rearrange() }
        }
        // And the control, dragged past the subscription on its side, goes between it and the edge or the list.
        if (part == Part.CONTROL && Part.CHIP in parts && bottom == MobileHomeLayout.chipBottom) {
            val chip = parts.getValue(Part.CHIP)
            val middle = chip.top + chip.height / 2f
            val near = if (bottom) hostY > middle else hostY < middle
            if (near != MobileHomeLayout.chipNearList) { MobileHomeLayout.chipNearList = near; rearrange() }
        }
        follow()
    }

    private fun follow() {
        val frame = dragged?.let(parts::getValue) ?: return
        val offset = if (landscape) host.top else 0
        frame.translationY = (fingerY - grabY - offset).coerceIn(0f, (host.height - frame.height).coerceAtLeast(0).toFloat()) - frame.top
    }

    private fun drop() {
        val frame = dragged?.let(parts::getValue) ?: return
        dragged = null
        frame.animate().translationY(0f).scaleX(1f).scaleY(1f).translationZ(0f).setDuration(160).withEndAction { frame.background = null }.start()
    }

    /** The whole screen at its own shape and orientation, scaled to the room there is; every touch lands here. */
    private inner class Miniature(context: Context) : ViewGroup(context) {
        private var scale = 1f
        private val virtualWidth = resources.displayMetrics.widthPixels
        private val virtualHeight = resources.displayMetrics.heightPixels
        private var pinched: Part? = null
        private var pinch = 1f
        private val pinches = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinched = partAt(detector.focusX / scale, detector.focusY / scale)?.takeIf { it != Part.CHIP }
                pinch = 1f
                return pinched != null
            }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val part = pinched ?: return false
                pinch *= detector.scaleFactor
                // The part follows the fingers a little, so the pinch is felt before it is decided.
                parts.getValue(part).let { val s = pinch.coerceIn(.85f, 1.15f); it.scaleX = s; it.scaleY = s }
                return true
            }
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                val part = pinched ?: return
                parts.getValue(part).animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                if (pinch < .85f) setCompact(part, true) else if (pinch > 1.15f) setCompact(part, false)
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                pinched = null
            }
        })
        private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                val action = actionAt(event.x / scale, event.y / scale)
                if (action == null) {
                    // A part shows its buttons; a tap on the list or on it again hides them.
                    val part = partAt(event.x / scale, event.y / scale)
                    select(if (part == selected) null else part)
                    return true
                }
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                action()
                return true
            }
            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (actionAt(event.x / scale, event.y / scale) != null) return true
                when (partAt(event.x / scale, event.y / scale)) {
                    Part.TILES -> setExtended(!MobileHomeLayout.extendedTiles)
                    Part.CONTROL -> setSwitches(!MobileHomeLayout.controlSwitches)
                    else -> {}
                }
                return true
            }
            override fun onLongPress(event: MotionEvent) {
                if (pinches.isInProgress) return
                partAt(event.x / scale, event.y / scale)?.let { startDrag(it, event.y / scale) }
            }
        })

        init {
            addView(column)
            background = GradientDrawable().apply { setColor(MobilePalette.background); cornerRadius = dp(MobilePalette.cardDp).toFloat() }
            foreground = GradientDrawable().apply { cornerRadius = dp(MobilePalette.cardDp).toFloat(); setStroke(dp(1).coerceAtLeast(1), MobilePalette.outline) }
            clipToOutline = true
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            scale = minOf(1f, MeasureSpec.getSize(widthSpec).toFloat() / virtualWidth, MeasureSpec.getSize(heightSpec).toFloat() / virtualHeight)
            column.measure(MeasureSpec.makeMeasureSpec(virtualWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(virtualHeight, MeasureSpec.EXACTLY))
            setMeasuredDimension((virtualWidth * scale).toInt(), (virtualHeight * scale).toInt())
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            column.layout(0, 0, virtualWidth, virtualHeight)
            column.pivotX = 0f; column.pivotY = 0f
            column.scaleX = scale; column.scaleY = scale
        }

        override fun onInterceptTouchEvent(event: MotionEvent) = true

        private val joinPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE }
        private val joinRect = android.graphics.RectF()

        override fun dispatchDraw(canvas: android.graphics.Canvas) {
            super.dispatchDraw(canvas)
            // A part that scrolls with the list shares one dashed outline with it: one block that scrolls together.
            val joined = joinedParts()
            if (joined.isEmpty() || dragged != null) return
            val bounds = boundsOf(list)
            joined.forEach { bounds.union(boundsOf(parts.getValue(it))) }
            joinRect.set(bounds.left * scale, bounds.top * scale, bounds.right * scale, bounds.bottom * scale)
            joinPaint.strokeWidth = dp(2) * scale
            joinPaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(7) * scale, dp(5) * scale), 0f)
            joinPaint.color = ColorUtils.setAlphaComponent(MobilePalette.text, breath)
            val radius = dp(MobilePalette.rowDp + 4) * scale
            canvas.drawRoundRect(joinRect, radius, radius, joinPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            pinches.onTouchEvent(event)
            if (!pinches.isInProgress) gestures.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1) drag(event.y / scale)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drop()
            }
            return true
        }
    }

    private companion object {
        const val TUTORIAL_SEEN = "qmobileLayoutTutorialSeen"
    }
}
