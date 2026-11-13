package app.rawline.mobile.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import app.rawline.mobile.R
import java.util.Locale

/**
 * The layout editor's tutorial: each gesture played by a finger on a small home screen drawn with the app's own
 * tiles, button, switches and servers, one short line under it. It lies over the editor, dimmed but see-through;
 * skipped or finished, [onClose] runs.
 */
class MobileLayoutTutorial(context: Context, private val onClose: () -> Unit) : FrameLayout(context) {
    private enum class Step(val caption: Int) {
        DRAG(R.string.qmobile_layout_step_drag), DOUBLE(R.string.qmobile_layout_step_double), PINCH(R.string.qmobile_layout_step_pinch),
        BADGES(R.string.qmobile_layout_step_badges), REMOVE(R.string.qmobile_layout_step_remove), SCROLL(R.string.qmobile_layout_step_scroll)
    }

    private fun dp(n: Float) = n * resources.displayMetrics.density
    private var step = 0
    private val demo = Demo(context)
    private val caption = TextView(context).apply {
        setTextColor(Color.WHITE); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setPadding(dp(24f).toInt(), dp(18f).toInt(), dp(24f).toInt(), dp(8f).toInt())
    }
    private val dots = LinearLayout(context).apply { gravity = Gravity.CENTER }
    private val next = TextView(context)

    init {
        // Nearly opaque: the editor behind must not mix with the small home screen played here.
        setBackgroundColor(Color.argb(238, 0, 0, 0))
        isClickable = true; isFocusable = true
        alpha = 0f; animate().alpha(1f).setDuration(220).start()
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val metrics = resources.displayMetrics
        // The whole screen at its own shape, as the editor's miniature shows it, in the room the caption and buttons leave.
        val aspect = metrics.heightPixels.toFloat() / metrics.widthPixels
        val width = minOf(metrics.widthPixels - dp(48f).toInt(), ((metrics.heightPixels - dp(240f)) / aspect).toInt())
        column.addView(demo, LinearLayout.LayoutParams(width, (width * aspect).toInt()))
        column.addView(caption, LinearLayout.LayoutParams(-1, -2))
        column.addView(dots, LinearLayout.LayoutParams(-1, dp(22f).toInt()))
        val buttons = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(32f).toInt(), dp(14f).toInt(), dp(32f).toInt(), 0) }
        buttons.addView(TextView(context).apply {
            text = context.getString(R.string.qmobile_layout_tutorial_skip); textSize = 15f
            setTextColor(ColorUtils.setAlphaComponent(Color.WHITE, 200)); setPadding(dp(8f).toInt(), dp(12f).toInt(), dp(8f).toInt(), dp(12f).toInt())
            setOnClickListener { close() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val accent = MobilePalette.accent(context)
        next.apply {
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(contrastOn(accent)); gravity = Gravity.CENTER
            setPadding(dp(26f).toInt(), dp(11f).toInt(), dp(26f).toInt(), dp(11f).toInt())
            background = android.graphics.drawable.GradientDrawable().apply { setColor(accent); cornerRadius = dp(22f) }
            setOnClickListener { if (step == Step.values().lastIndex) close() else show(step + 1) }
        }
        buttons.addView(next, LinearLayout.LayoutParams(-2, -2))
        column.addView(buttons, LinearLayout.LayoutParams(-1, -2))
        addView(column, LayoutParams(-1, -2, Gravity.CENTER))
        show(0)
    }

    private fun show(index: Int) {
        step = index
        val current = Step.values()[index]
        caption.text = context.getString(current.caption)
        caption.alpha = 0f; caption.animate().alpha(1f).setDuration(180).start()
        next.text = context.getString(if (index == Step.values().lastIndex) R.string.qmobile_layout_tutorial_done else R.string.qmobile_layout_tutorial_next)
        dots.removeAllViews()
        Step.values().indices.forEach { i ->
            dots.addView(View(context).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (i == index) Color.WHITE else ColorUtils.setAlphaComponent(Color.WHITE, 90))
                }
            }, LinearLayout.LayoutParams(dp(7f).toInt(), dp(7f).toInt()).apply { marginStart = dp(4f).toInt(); marginEnd = dp(4f).toInt() })
        }
        demo.step = current
    }

    fun close() {
        animate().alpha(0f).setDuration(180).withEndAction { (parent as? ViewGroup)?.removeView(this); onClose() }.start()
    }

    override fun onDetachedFromWindow() {
        demo.stop()
        super.onDetachedFromWindow()
    }

    /**
     * The editor's own miniature, scaled the way the editor scales it, and a finger playing [step] over and over: every
     * size, padding and outline is the editor's (MobileLayoutEditorActivity), drawn in real dp under one scale.
     */
    private inner class Demo(context: Context) : View(context) {
        var step = Step.DRAG
            set(value) { field = value; invalidate() }
        private var t = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dash = DashPathEffect(floatArrayOf(dp(7f), dp(5f)), 0f)
        private val removedDash = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
        private val box = RectF()
        private val ui = ServerUi(context)
        // The switch cells as the editor builds them, so the switch, its label and its wrapping are the real ones.
        private val switchCells = listOf(R.string.service_mode_vpn, R.string.service_mode_proxy).map { label ->
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14f).toInt(), dp(2f).toInt(), dp(8f).toInt(), dp(2f).toInt()); background = MobilePalette.rowShape(context)
                addView(ui.text(context.getString(label), 14f), LinearLayout.LayoutParams(0, -2, 1f))
                addView(androidx.appcompat.widget.SwitchCompat(context).apply { isClickable = false; isFocusable = false })
            }
        }
        private val checkLine = ui.text(context.getString(R.string.qmobile_check_connection), 12f, MobilePalette.muted).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10f).toInt(), 0, dp(6f).toInt(), 0)
        }

        /** Lays [view] out at [width] x [height] and draws it at ([x], [y]). */
        private fun place(view: View, x: Float, y: Float, width: Float, height: Float) {
            val w = width.toInt(); val h = height.toInt()
            if (view.width != w || view.height != h) {
                view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, w, h)
            }
            c.save(); c.translate(x, y); view.draw(c); c.restore()
        }
        private val accent = MobilePalette.accent(context)
        private val orb = MobileOrbArtwork(context, context.getString(R.string.qmobile_orb_idle)).apply {
            groupText = context.getString(R.string.subscription); serverText = Locale("", "de").displayCountry
        }
        private val flags = listOf("de", "fi", "nl", "se", "us", "jp", "gb", "ca", "fr", "pl")
        private val clock = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4200; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener {
                t = it.animatedValue as Float
                // The loop fades out and back in, so it never jumps back to its start: the view's alpha, no offscreen layer.
                alpha = span(0f, .04f) * (1 - span(.95f, 1f))
                invalidate()
            }
        }
        private lateinit var c: Canvas

        override fun onAttachedToWindow() { super.onAttachedToWindow(); clock.start() }
        fun stop() = clock.cancel()

        private fun span(from: Float, to: Float) = ((t - from) / (to - from)).coerceIn(0f, 1f)
        private fun ease(x: Float) = x * x * (3 - 2 * x)
        private fun lerp(a: Float, b: Float, x: Float) = a + (b - a) * x
        private fun pulse(at: Float) = span(at, at + .05f) * (1 - span(at + .05f, at + .1f))

        /** A row or tile of the app's own colour and corner; [fade] multiplies its alpha, as a view's alpha does. */
        private fun surface(left: Float, top: Float, right: Float, bottom: Float, color: Int = MobilePalette.surface, fade: Float = 1f, radius: Float = dp(MobilePalette.rowDp.toFloat())) {
            box.set(left, top, right, bottom)
            paint.style = Paint.Style.FILL; paint.pathEffect = null
            paint.color = ColorUtils.setAlphaComponent(color, (Color.alpha(color) * fade).toInt())
            c.drawRoundRect(box, radius, radius, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1f)
            paint.color = ColorUtils.setAlphaComponent(MobilePalette.outline, (Color.alpha(MobilePalette.outline) * fade).toInt())
            c.drawRoundRect(box, radius, radius, paint)
        }

        /** A part's dashed outline, breathing as the editor's do. */
        private fun outline(left: Float, top: Float, right: Float, bottom: Float, held: Boolean = false) {
            box.set(left, top, right, bottom)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2f); paint.pathEffect = dash
            val alpha = if (held) 255 else (157 + 98 * kotlin.math.sin(t * 4200f / 1300f * 6.283f)).toInt()
            paint.color = ColorUtils.setAlphaComponent(MobilePalette.text, alpha)
            val radius = dp(MobilePalette.rowDp + 4f)
            c.drawRoundRect(box, radius, radius, paint)
            paint.pathEffect = null
        }

        // Loaded once each: inflating every icon on every frame made the loop stutter.
        private val icons = HashMap<Int, android.graphics.drawable.Drawable?>()
        private fun icon(res: Int, cx: Float, cy: Float, size: Float, color: Int, fade: Float = 1f) {
            val drawable = icons.getOrPut(res) { ContextCompat.getDrawable(context, res)?.mutate() } ?: return
            drawable.setTint(color); drawable.alpha = (255 * fade).toInt()
            drawable.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
            drawable.draw(c)
        }

        /** The editor's small round button: [diameter] 26 dp on the strip, 20 dp in a tile's corner. */
        private fun badge(res: Int, cx: Float, cy: Float, fill: Int, diameter: Float, inset: Float, pressed: Float = 0f) {
            val r = diameter / 2 * (1 - .12f * pressed)
            paint.style = Paint.Style.FILL; paint.color = fill
            c.drawCircle(cx, cy, r, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1f); paint.color = ColorUtils.setAlphaComponent(MobilePalette.text, 60)
            c.drawCircle(cx, cy, r, paint)
            // A plain button keeps the text colour on its grey disc, as the editor tints it.
            icon(res, cx, cy, diameter - 2 * inset, if (fill == MobilePalette.badge) MobilePalette.text else contrastOn(fill))
        }

        private fun text(value: String, x: Float, y: Float, size: Float, color: Int = MobilePalette.text, bold: Boolean = false) {
            paint.style = Paint.Style.FILL; paint.color = color; paint.textSize = size; paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.textAlign = Paint.Align.LEFT
            c.drawText(value, x, y, paint)
        }

        private fun finger(x: Float, y: Float, press: Float, visible: Float) {
            if (visible <= 0f) return
            paint.style = Paint.Style.FILL
            paint.color = ColorUtils.setAlphaComponent(Color.WHITE, (80 * visible * press).toInt())
            c.drawCircle(x, y, dp(30f), paint)
            paint.color = ColorUtils.setAlphaComponent(Color.BLACK, (60 * visible).toInt())
            c.drawCircle(x, y + dp(2f), dp(17f), paint)
            paint.color = ColorUtils.setAlphaComponent(Color.WHITE, (235 * visible).toInt())
            c.drawCircle(x, y, dp(lerp(17f, 13f, press)), paint)
        }

        override fun onDraw(canvas: Canvas) {
            c = canvas
            // The real screen, in real dp, scaled to this view as the editor scales its miniature.
            val metrics = resources.displayMetrics
            val k = width.toFloat() / metrics.widthPixels
            val w = metrics.widthPixels.toFloat(); val h = height / k
            canvas.save(); canvas.scale(k, k)
            box.set(0f, 0f, w, h)
            val corner = dp(MobilePalette.cardDp.toFloat())
            paint.style = Paint.Style.FILL; paint.pathEffect = null; paint.color = MobilePalette.background
            canvas.drawRoundRect(box, corner, corner, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1f); paint.color = MobilePalette.outline
            canvas.drawRoundRect(box, corner, corner, paint)
            canvas.clipRect(0f, 0f, w, h)

            // MobileLayoutEditorActivity: column padding 16 (12 on top), parts 8 apart, each framed 8 dp inside its outline.
            val side = dp(16f); val frame = dp(8f); val gap = dp(8f)
            val left = side + frame; val right = w - side - frame
            val tile = dp(48f)
            val orbSize = dp(190f); val compactOrb = dp(148f)
            val tilesTop = dp(12f) + gap + frame
            val chipTop = tilesTop + tile + frame + gap + frame
            val controlTop = chipTop + tile + frame + gap + frame
            val strip = dp(32f)   // the selected outline's top grows from 8 to 40 dp
            val cx = w / 2
            fun outlineAround(top: Float, height: Float, grown: Float = 0f, held: Boolean = false) =
                outline(side, top - frame - strip * grown, w - side, top + height + frame, held)
            fun tiles(y: Float, removed: Float = 0f) {
                // Settings and the four quick tiles spanning the row; the quick ones carry a minus, the taken one a plus.
                val icons = listOf(R.drawable.ic_mobile_settings, R.drawable.ic_mobile_tun, R.drawable.ic_mobile_route, R.drawable.ic_mobile_subscriptions, R.drawable.ic_mobile_update)
                val each = (right - left - gap * 4) / 5
                icons.forEachIndexed { i, res ->
                    val x = left + i * (each + gap)
                    val fade = if (i == 3) 1 - .7f * removed else 1f
                    surface(x, y, x + each, y + tile, fade = fade)
                    icon(res, x + each / 2, y + tile / 2, dp(24f), MobilePalette.text, fade)
                    if (i == 3 && removed > 0f) {
                        box.set(x, y, x + each, y + tile)
                        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2f); paint.pathEffect = removedDash
                        paint.color = ColorUtils.setAlphaComponent(MobilePalette.muted, (255 * removed).toInt())
                        val r = dp(MobilePalette.rowDp.toFloat()); c.drawRoundRect(box, r, r, paint); paint.pathEffect = null
                    }
                    if (i > 0) {
                        val plus = i == 3 && removed > .5f
                        badge(if (plus) R.drawable.ic_mobile_add else R.drawable.ic_layout_remove, x + each - dp(13f), y + dp(13f),
                            if (plus) accent else Color.rgb(220, 66, 61), dp(20f), dp(3f))
                    }
                }
            }
            fun chip(y: Float) {
                surface(left, y, right - tile - gap, y + tile)
                text(context.getString(R.string.subscription), left + dp(16f), y + tile / 2 + dp(6f), dp(16f), bold = true)
                surface(right - tile, y, right, y + tile)
                icon(R.drawable.ic_mobile_add, right - tile / 2, y + tile / 2, dp(24f), MobilePalette.text)
            }
            fun rows(top: Float, bottom: Float = h) {
                // The list: 56 dp rows 6 dp apart, flag and country as the editor's list shows them.
                c.save(); c.clipRect(0f, 0f, w, bottom)
                var y = top
                var i = 0
                while (y < bottom) {
                    y += dp(6f)
                    surface(side, y, w - side, y + dp(56f))
                    MobileCountryFlags.bitmap(context, flags[i % flags.size])?.let { flag ->
                        box.set(side + dp(12f), y + dp(17f), side + dp(42f), y + dp(39f))
                        c.drawBitmap(flag, null, box, paint)
                    }
                    text(Locale("", flags[i % flags.size]).displayCountry, side + dp(54f), y + dp(33f), dp(15f))
                    y += dp(56f); i++
                }
                c.restore()
            }
            fun drawOrb(top: Float, size: Float) {
                // Across the part, 16 dp taller than the disc, as on the home screen.
                orb.discSize = size.toInt()
                orb.setBounds(left.toInt(), top.toInt(), right.toInt(), (top + size + dp(16f)).toInt())
                orb.draw(c)
            }
            val switchesHeight = dp(52f) + dp(40f)
            fun switches(top: Float) {
                // Two switches 52 dp tall, 8 dp apart, and the check line under them: the editor's own views, drawn here.
                val half = (right - left - gap) / 2
                switchCells.forEachIndexed { index, cell -> place(cell, left + index * (half + gap), top, half, dp(52f)) }
                place(checkLine, left, top + dp(52f), right - left, dp(40f))
            }
            fun strip(top: Float, grown: Float, scrollLit: Boolean, scrollPressed: Float = 0f, swapPressed: Float = 0f) {
                // The selected outline's buttons: 26 dp, 8 apart, 6 dp under the outline and 14 dp from its right edge:
                // the arrows join the part to the list, the other changes its look. The size is a pinch only.
                if (grown <= 0f) return
                val y = top - frame - strip * grown + dp(6f) + dp(13f)
                val last = w - side - dp(14f) - dp(13f)
                c.saveLayerAlpha(last - dp(60f), y - dp(16f), last + dp(16f), y + dp(16f), (255 * grown).toInt())
                badge(R.drawable.ic_layout_scroll, last - dp(34f), y, if (scrollLit) accent else MobilePalette.badge, dp(26f), dp(5f), scrollPressed)
                badge(R.drawable.ic_layout_swap, last, y, MobilePalette.badge, dp(26f), dp(5f), swapPressed)
                c.restore()
            }
            fun block(top: Float, height: Float, scale: Float = 1f, lift: Float = 0f, grown: Float = 0f, content: (Float) -> Unit) {
                // Picked up, the part grows 3 % and covers the list with the screen's colour, as the editor does it.
                val s = scale * (1 + .03f * lift)
                c.save(); c.scale(s, s, cx, top + height / 2)
                if (lift > 0f) surface(side, top - frame - strip * grown, w - side, top + height + frame, MobilePalette.background, radius = dp(MobilePalette.rowDp + 4f))
                content(top)
                outlineAround(top, height, grown, held = lift > .5f)
                c.restore()
            }
            /** The finger along [keys] of (time, x, y), easing from one to the next. */
            fun path(keys: List<Triple<Float, Float, Float>>): Pair<Float, Float> {
                val next = keys.indexOfFirst { it.first > t }
                if (next <= 0) return (if (next == 0) keys.first() else keys.last()).let { it.second to it.third }
                val (t0, x0, y0) = keys[next - 1]; val (t1, x1, y1) = keys[next]
                val k2 = ease(((t - t0) / (t1 - t0)).coerceIn(0f, 1f))
                return lerp(x0, x1, k2) to lerp(y0, y1, k2)
            }
            fun still() { outlineAround(tilesTop, tile); outlineAround(chipTop, tile) }
            val orbHeight = orbSize + dp(16f)
            fun listTop(controlHeight: Float, grown: Float = 0f) = controlTop + strip * grown + controlHeight + frame + gap
            when (step) {
                Step.DRAG -> {
                    // Held, the part lifts; past the list's middle the list takes its place; let go, it settles in.
                    val slot = h - side - frame - orbHeight
                    val top = lerp(controlTop, slot, ease(span(.25f, .7f)))
                    val swapped = ease(span(.47f, .513f))
                    tiles(tilesTop); chip(chipTop); still()
                    rows(lerp(listTop(orbHeight), controlTop - frame, swapped), lerp(h, slot - frame - gap, swapped))
                    val lift = span(.14f, .18f) * (1 - span(.72f, .76f))
                    block(top, orbHeight, lift = lift) { drawOrb(it, orbSize) }
                    finger(cx, top + orbSize * .43f, span(.08f, .14f) * (1 - span(.72f, .76f)), span(.02f, .08f) * (1 - span(.78f, .86f)))
                }
                Step.DOUBLE -> {
                    // Two taps swap the look at once, the outline and the list follow in 180 ms; two more swap it back.
                    val switchesOn = t >= .23f && t < .68f
                    val k2 = ease(span(.23f, .273f)) * (1 - ease(span(.68f, .723f)))
                    val height = lerp(orbHeight, switchesHeight, k2)
                    tiles(tilesTop); chip(chipTop); still(); rows(listTop(height))
                    block(controlTop, height) { top -> if (switchesOn) switches(top) else drawOrb(top, orbSize) }
                    val tap = pulse(.14f) + pulse(.2f) + pulse(.59f) + pulse(.65f)
                    finger(cx, controlTop + height / 2, tap, span(.06f, .12f) * (1 - span(.76f, .84f)))
                }
                Step.PINCH -> {
                    // The part follows the fingers within 15 %; let go, it springs back and takes the new size.
                    val squeeze = ease(span(.15f, .45f)); val spread = ease(span(.6f, .85f))
                    val compact = ease(span(.47f, .517f)) * (1 - ease(span(.87f, .917f)))
                    val size = lerp(orbSize, compactOrb, compact)
                    val scale = when {
                        t < .47f -> 1 - .15f * squeeze
                        t < .6f -> lerp(.85f, 1f, ease(span(.47f, .505f)))
                        t < .87f -> 1 + .15f * spread
                        else -> lerp(1.15f, 1f, ease(span(.87f, .905f)))
                    }
                    tiles(tilesTop); chip(chipTop); still(); rows(listTop(size + dp(16f)))
                    block(controlTop, size + dp(16f), scale) { drawOrb(it, size) }
                    val apart = if (t < .52f) lerp(dp(90f), dp(36f), squeeze) else lerp(dp(36f), dp(90f), spread)
                    val cy = controlTop + size * .43f
                    val visible = span(.05f, .12f) * (1 - span(.46f, .5f)) + span(.54f, .6f) * (1 - span(.86f, .9f))
                    finger(cx - apart, cy - apart * .5f, .6f, visible); finger(cx + apart, cy + apart * .5f, .6f, visible)
                }
                Step.BADGES -> {
                    // A tap shows the buttons, the outline growing to hold them; the look button swaps the part's look and back.
                    val grown = ease(span(.13f, .173f)) * (1 - ease(span(.9f, .943f)))
                    val switchesOn = t >= .41f && t < .69f
                    val k2 = ease(span(.41f, .453f)) * (1 - ease(span(.69f, .733f)))
                    val height = lerp(orbHeight, switchesHeight, k2)
                    val top = controlTop + strip * grown
                    tiles(tilesTop); chip(chipTop); still(); rows(listTop(height, grown))
                    block(top, height, grown = grown) { if (switchesOn) switches(it) else drawOrb(it, orbSize) }
                    strip(top, grown, false, swapPressed = pulse(.39f) + pulse(.67f))
                    val swapX = w - side - dp(27f); val stripY = controlTop - frame + dp(19f)
                    val (fx, fy) = path(listOf(Triple(.05f, cx, controlTop + orbSize * .43f), Triple(.2f, cx, controlTop + orbSize * .43f),
                        Triple(.32f, swapX, stripY), Triple(.76f, swapX, stripY), Triple(.84f, cx, h - dp(60f))))
                    finger(fx, fy, pulse(.11f) + pulse(.39f) + pulse(.67f) + pulse(.87f), span(.03f, .09f) * (1 - span(.92f, .97f)))
                }
                Step.REMOVE -> {
                    // The minus fades the tile under a dashed outline and turns into a plus; the plus brings it back.
                    val gone = ease(span(.27f, .308f)) * (1 - ease(span(.67f, .708f)))
                    tiles(tilesTop, gone); chip(chipTop); still(); rows(listTop(orbHeight))
                    block(controlTop, orbHeight) { drawOrb(it, orbSize) }
                    val each = (right - left - gap * 4) / 5
                    val bx = left + 3 * (each + gap) + each - dp(13f); val by = tilesTop + dp(13f)
                    finger(bx, by, pulse(.25f) + pulse(.65f), span(.12f, .2f) * (1 - span(.78f, .86f)))
                }
                Step.SCROLL -> {
                    // The arrows join the part to the list: its outline reaches down round the list, and a swipe scrolls the
                    // two together, up and back; the arrows again split them.
                    val grown = ease(span(.06f, .103f)) * (1 - ease(span(.9f, .943f)))
                    val lit = t >= .25f && t < .77f
                    val join = ease(span(.26f, .32f)) * (1 - ease(span(.77f, .83f)))
                    val scroll = -dp(150f) * ease(span(.38f, .5f)) * (1 - ease(span(.58f, .7f)))
                    val outlineTop = controlTop - frame - strip * grown
                    val top = controlTop + strip * grown + scroll
                    tiles(tilesTop); chip(chipTop); still()
                    // The joined content scrolls under the outline's top edge.
                    c.save(); c.clipRect(0f, outlineTop + dp(1f), w, h)
                    rows(listTop(orbHeight, grown) + scroll)
                    drawOrb(top, orbSize)
                    c.restore()
                    outline(side, outlineTop, w - side, lerp(controlTop + strip * grown + orbHeight + frame, h - dp(10f), join))
                    strip(controlTop, grown, lit, scrollPressed = pulse(.24f) + pulse(.76f))
                    val scrollX = w - side - dp(27f) - dp(34f); val stripY = controlTop - frame + dp(19f)
                    val low = h * .8f; val high = low - dp(150f)
                    val (fx, fy) = path(listOf(Triple(.02f, cx, controlTop + orbSize * .43f), Triple(.1f, cx, controlTop + orbSize * .43f),
                        Triple(.19f, scrollX, stripY), Triple(.3f, scrollX, stripY), Triple(.36f, cx, low), Triple(.38f, cx, low),
                        Triple(.5f, cx, high), Triple(.58f, cx, high), Triple(.7f, cx, low), Triple(.72f, scrollX, stripY),
                        Triple(.8f, scrollX, stripY), Triple(.88f, cx, h - dp(60f))))
                    val drag = span(.37f, .38f) * (1 - span(.5f, .51f)) + span(.57f, .58f) * (1 - span(.7f, .71f))
                    finger(fx, fy, pulse(.04f) + pulse(.24f) + drag + pulse(.76f), span(.01f, .05f) * (1 - span(.92f, .97f)))
                }
            }
            canvas.restore()
        }
    }
}
