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
        val width = minOf(dp(320f).toInt(), metrics.widthPixels - dp(48f).toInt(), ((metrics.heightPixels - dp(260f)) / 1.2f).toInt())
        column.addView(demo, LinearLayout.LayoutParams(width, (width * 1.2f).toInt()))
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

    /** A small home screen and a finger playing [step] over and over. */
    private inner class Demo(context: Context) : View(context) {
        var step = Step.DRAG
            set(value) { field = value; invalidate() }
        private var t = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dash = DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
        private val box = RectF()
        private val accent = MobilePalette.accent(context)
        private val orb = MobileOrbArtwork(context, context.getString(R.string.qmobile_orb_idle))
        private val flags = listOf("de", "fi", "nl", "se", "us", "jp", "gb")
        private val clock = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4200; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
        }
        private lateinit var c: Canvas

        override fun onAttachedToWindow() { super.onAttachedToWindow(); clock.start() }
        fun stop() = clock.cancel()

        private fun span(from: Float, to: Float) = ((t - from) / (to - from)).coerceIn(0f, 1f)
        private fun ease(x: Float) = x * x * (3 - 2 * x)
        private fun lerp(a: Float, b: Float, x: Float) = a + (b - a) * x
        private fun pulse(at: Float) = span(at, at + .05f) * (1 - span(at + .05f, at + .1f))

        /** A surface of the app's own colour; [fade] multiplies its own alpha, so see-through colours stay see-through. */
        private fun surface(left: Float, top: Float, right: Float, bottom: Float, color: Int = MobilePalette.surface, fade: Float = 1f, radius: Float = dp(10f)) {
            box.set(left, top, right, bottom)
            paint.style = Paint.Style.FILL; paint.pathEffect = null
            paint.color = ColorUtils.setAlphaComponent(color, (Color.alpha(color) * fade).toInt())
            c.drawRoundRect(box, radius, radius, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1f)
            paint.color = ColorUtils.setAlphaComponent(MobilePalette.outline, (Color.alpha(MobilePalette.outline) * fade).toInt())
            c.drawRoundRect(box, radius, radius, paint)
        }

        private fun outline(left: Float, top: Float, right: Float, bottom: Float) {
            box.set(left, top, right, bottom)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1.6f); paint.pathEffect = dash
            paint.color = ColorUtils.setAlphaComponent(MobilePalette.text, (120 + 135 * kotlin.math.abs(kotlin.math.sin(t * 6.283f * 2))).toInt())
            c.drawRoundRect(box, dp(12f), dp(12f), paint)
            paint.pathEffect = null
        }

        private fun icon(res: Int, cx: Float, cy: Float, size: Float, color: Int, fade: Float = 1f) {
            val drawable = ContextCompat.getDrawable(context, res)?.mutate() ?: return
            drawable.setTint(color); drawable.alpha = (255 * fade).toInt()
            drawable.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
            drawable.draw(c)
        }

        private fun badge(res: Int, cx: Float, cy: Float, fill: Int, pressed: Float = 0f) {
            paint.style = Paint.Style.FILL; paint.color = fill
            c.drawCircle(cx, cy, dp(10f) * (1 - .12f * pressed), paint)
            icon(res, cx, cy, dp(13f), contrastOn(fill))
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
            c.drawCircle(x, y, dp(24f), paint)
            paint.color = ColorUtils.setAlphaComponent(Color.BLACK, (60 * visible).toInt())
            c.drawCircle(x, y + dp(2f), dp(13f), paint)
            paint.color = ColorUtils.setAlphaComponent(Color.WHITE, (235 * visible).toInt())
            c.drawCircle(x, y, dp(lerp(13f, 10f, press)), paint)
        }

        override fun onDraw(canvas: Canvas) {
            c = canvas
            val w = width.toFloat(); val h = height.toFloat()
            val pad = dp(12f)
            box.set(0f, 0f, w, h)
            paint.style = Paint.Style.FILL; paint.pathEffect = null; paint.color = MobilePalette.background
            canvas.drawRoundRect(box, dp(22f), dp(22f), paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1f); paint.color = MobilePalette.outline
            canvas.drawRoundRect(box, dp(22f), dp(22f), paint)
            canvas.save(); canvas.clipRect(0f, 0f, w, h)
            val tile = (w - 2 * pad - dp(6f) * 4) / 5
            val tilesTop = pad
            fun tiles(y: Float, removed: Float = 0f, minus: Boolean = false) {
                val icons = listOf(R.drawable.ic_mobile_settings, R.drawable.ic_mobile_tun, R.drawable.ic_mobile_route, R.drawable.ic_mobile_subscriptions, R.drawable.ic_mobile_update)
                icons.forEachIndexed { i, res ->
                    val left = pad + i * (tile + dp(6f))
                    val fade = if (i == 3) 1 - .7f * removed else 1f
                    surface(left, y, left + tile, y + tile, fade = fade)
                    icon(res, left + tile / 2, y + tile / 2, tile * .45f, MobilePalette.text, fade)
                    // Taken away, as the editor shows it: faded under a dashed outline.
                    if (i == 3 && removed > 0f) {
                        box.set(left, y, left + tile, y + tile)
                        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1.5f); paint.pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f)
                        paint.color = ColorUtils.setAlphaComponent(MobilePalette.muted, (255 * removed).toInt())
                        c.drawRoundRect(box, dp(10f), dp(10f), paint); paint.pathEffect = null
                    }
                    if (minus && i == 3) badge(if (removed > .5f) R.drawable.ic_mobile_add else R.drawable.ic_layout_remove, left + tile - dp(8f), y + dp(8f),
                        if (removed > .5f) accent else Color.rgb(220, 66, 61))
                }
            }
            val chipTop = tilesTop + tile + dp(8f)
            fun chip(y: Float) {
                surface(pad, y, w - pad - dp(40f), y + dp(34f))
                text(context.getString(R.string.subscription), pad + dp(12f), y + dp(22f), dp(13f), bold = true)
                surface(w - pad - dp(34f), y, w - pad, y + dp(34f))
                icon(R.drawable.ic_mobile_add, w - pad - dp(17f), y + dp(17f), dp(16f), MobilePalette.text)
            }
            fun rows(top: Float) {
                var y = top
                var i = 0
                while (y < h) {
                    surface(pad, y, w - pad, y + dp(34f))
                    MobileCountryFlags.bitmap(context, flags[i % flags.size])?.let { flag ->
                        box.set(pad + dp(10f), y + dp(10f), pad + dp(32f), y + dp(24f))
                        c.drawBitmap(flag, null, box, paint)
                    }
                    text(Locale("", flags[i % flags.size]).displayCountry, pad + dp(42f), y + dp(22f), dp(12f))
                    y += dp(40f); i++
                }
            }
            fun drawOrb(cx: Float, top: Float, size: Float) {
                orb.setBounds((cx - size / 2).toInt(), top.toInt(), (cx + size / 2).toInt(), (top + size).toInt())
                orb.draw(c)
            }
            fun switches(top: Float, fade: Float) {
                val mid = w / 2
                listOf(R.string.service_mode_vpn to pad, R.string.service_mode_proxy to mid + dp(4f)).forEach { (label, left) ->
                    val right = if (left == pad) mid - dp(4f) else w - pad
                    surface(left, top, right, top + dp(36f), fade = fade)
                    text(context.getString(label), left + dp(10f), top + dp(23f), dp(11f), ColorUtils.setAlphaComponent(MobilePalette.text, (255 * fade).toInt()))
                    paint.color = ColorUtils.setAlphaComponent(MobilePalette.muted, (255 * fade).toInt()); paint.style = Paint.Style.FILL
                    box.set(right - dp(34f), top + dp(11f), right - dp(10f), top + dp(25f)); c.drawRoundRect(box, dp(7f), dp(7f), paint)
                }
            }
            // Each step is the editor's own action with its own timing: a 180 ms transition is .043 of the 4.2 s loop.
            val gap = dp(4f)
            val orbSize = dp(110f)
            val blockTop = chipTop + dp(34f) + dp(12f)
            val cx = w / 2
            fun block(top: Float, height: Float, scale: Float = 1f, lift: Float = 0f, grown: Float = 0f, content: (Float) -> Unit) {
                // grown: a selected part's outline holds its buttons above it; lift: picked up and carried over the list.
                val outlineTop = top - gap - dp(22f) * grown
                val s = scale * (1 + .03f * lift)
                c.save(); c.scale(s, s, cx, top + height / 2)
                if (lift > 0f) surface(pad - gap, outlineTop, w - pad + gap, top + height + gap, MobilePalette.background, radius = dp(12f))
                content(top)
                outline(pad - gap, outlineTop, w - pad + gap, top + height + gap)
                c.restore()
            }
            fun strip(top: Float, grown: Float, scrollLit: Boolean, sizePressed: Float = 0f, scrollPressed: Float = 0f, small: Boolean = false) {
                if (grown <= 0f) return
                val by = top - gap - dp(22f) * grown + dp(12f)
                c.saveLayerAlpha(0f, 0f, w, h, (255 * grown).toInt())
                badge(R.drawable.ic_layout_scroll, w - pad - dp(64f), by, if (scrollLit) accent else MobilePalette.well, scrollPressed)
                badge(R.drawable.ic_layout_swap, w - pad - dp(40f), by, accent)
                badge(if (small) R.drawable.ic_layout_grow else R.drawable.ic_layout_shrink, w - pad - dp(16f), by, accent, sizePressed)
                c.restore()
            }
            /** The finger along [keys] of (time, x, y), easing from one to the next. */
            fun path(keys: List<Triple<Float, Float, Float>>): Pair<Float, Float> {
                val next = keys.indexOfFirst { it.first > t }
                if (next <= 0) return (if (next == 0) keys.first() else keys.last()).let { it.second to it.third }
                val (t0, x0, y0) = keys[next - 1]; val (t1, x1, y1) = keys[next]
                val k = ease(((t - t0) / (t1 - t0)).coerceIn(0f, 1f))
                return lerp(x0, x1, k) to lerp(y0, y1, k)
            }
            // The loop fades out and back in, so it never jumps back to its start.
            c.saveLayerAlpha(0f, 0f, w, h, (255 * span(0f, .04f) * (1 - span(.95f, 1f))).toInt())
            when (step) {
                Step.DRAG -> {
                    // Held, the part lifts; past the list's middle the list takes its place; let go, it settles in.
                    val slot = h - pad - gap - orbSize
                    val top = lerp(blockTop, slot, ease(span(.25f, .7f)))
                    val swapped = ease(span(.47f, .513f))
                    tiles(tilesTop); chip(chipTop)
                    c.save(); c.clipRect(0f, 0f, w, lerp(h, slot - gap - dp(6f), swapped))
                    rows(lerp(blockTop + orbSize + gap + dp(10f), blockTop, swapped))
                    c.restore()
                    val lift = span(.14f, .18f) * (1 - span(.72f, .76f))
                    block(top, orbSize, lift = lift) { drawOrb(cx, it, orbSize) }
                    finger(cx, top + orbSize * .45f, span(.08f, .14f) * (1 - span(.72f, .76f)), span(.02f, .08f) * (1 - span(.78f, .86f)))
                }
                Step.DOUBLE -> {
                    // Two taps swap the look at once, the outline and the list follow in 180 ms; two more swap it back.
                    val switchesOn = t >= .23f && t < .68f
                    val k = ease(span(.23f, .273f)) * (1 - ease(span(.68f, .723f)))
                    val height = lerp(orbSize, dp(36f), k)
                    tiles(tilesTop); chip(chipTop); rows(blockTop + height + gap + dp(10f))
                    block(blockTop, height) { top -> if (switchesOn) switches(top, 1f) else drawOrb(cx, top, orbSize) }
                    val tap = pulse(.14f) + pulse(.2f) + pulse(.59f) + pulse(.65f)
                    finger(cx, blockTop + height / 2, tap, span(.06f, .12f) * (1 - span(.76f, .84f)))
                }
                Step.PINCH -> {
                    // The part follows the fingers within 15 %; let go, it springs back and takes the new size.
                    val squeeze = ease(span(.15f, .45f)); val spread = ease(span(.6f, .85f))
                    val compact = ease(span(.47f, .517f)) * (1 - ease(span(.87f, .917f)))
                    val size = lerp(orbSize, orbSize * .78f, compact)
                    val scale = when {
                        t < .47f -> 1 - .15f * squeeze
                        t < .6f -> lerp(.85f, 1f, ease(span(.47f, .505f)))
                        t < .87f -> 1 + .15f * spread
                        else -> lerp(1.15f, 1f, ease(span(.87f, .905f)))
                    }
                    tiles(tilesTop); chip(chipTop); rows(blockTop + size + gap + dp(10f))
                    block(blockTop, size, scale) { drawOrb(cx, it, size) }
                    val apart = if (t < .52f) lerp(dp(64f), dp(28f), squeeze) else lerp(dp(28f), dp(64f), spread)
                    val cy = blockTop + size * .43f
                    val visible = span(.05f, .12f) * (1 - span(.46f, .5f)) + span(.54f, .6f) * (1 - span(.86f, .9f))
                    finger(cx - apart, cy - apart * .5f, .6f, visible); finger(cx + apart, cy + apart * .5f, .6f, visible)
                }
                Step.BADGES -> {
                    // A tap shows the buttons, the outline growing to hold them; the size button shrinks and grows the part.
                    val grown = ease(span(.13f, .173f)) * (1 - ease(span(.9f, .943f)))
                    val compact = ease(span(.41f, .457f)) * (1 - ease(span(.69f, .737f)))
                    val size = lerp(orbSize, orbSize * .78f, compact)
                    val top = blockTop + dp(22f) * grown
                    tiles(tilesTop); chip(chipTop); rows(top + size + gap + dp(10f))
                    block(top, size, grown = grown) { drawOrb(cx, it, size) }
                    strip(top, grown, false, sizePressed = pulse(.39f) + pulse(.67f), small = compact > .5f)
                    val sizeX = w - pad - dp(16f); val stripY = blockTop - gap + dp(12f)
                    val (fx, fy) = path(listOf(Triple(.05f, cx, blockTop + orbSize / 2), Triple(.2f, cx, blockTop + orbSize / 2),
                        Triple(.32f, sizeX, stripY), Triple(.76f, sizeX, stripY), Triple(.84f, cx, h - dp(30f))))
                    finger(fx, fy, pulse(.11f) + pulse(.39f) + pulse(.67f) + pulse(.87f), span(.03f, .09f) * (1 - span(.92f, .97f)))
                }
                Step.REMOVE -> {
                    // The minus fades the tile under a dashed outline and turns into a plus; the plus brings it back.
                    val gone = ease(span(.27f, .308f)) * (1 - ease(span(.67f, .708f)))
                    tiles(tilesTop, gone, minus = true); chip(chipTop); rows(blockTop + orbSize + gap + dp(10f))
                    outline(pad - gap, tilesTop - gap, w - pad + gap, tilesTop + tile + gap)
                    block(blockTop, orbSize) { drawOrb(cx, it, orbSize) }
                    val bx = pad + 3 * (tile + dp(6f)) + tile - dp(8f); val by = tilesTop + dp(8f)
                    finger(bx, by, pulse(.25f) + pulse(.65f), span(.12f, .2f) * (1 - span(.78f, .86f)))
                }
                Step.SCROLL -> {
                    // Selected, the arrows button lights and the part rises a little and back, as the editor does it.
                    val grown = ease(span(.11f, .153f)) * (1 - ease(span(.9f, .943f)))
                    val lit = t >= .36f && t < .71f
                    val bounce = -dp(12f) * ease(span(.37f, .418f)) * (1 - ease(span(.418f, .466f)))
                    val top = blockTop + dp(22f) * grown + bounce
                    tiles(tilesTop); chip(chipTop); rows(blockTop + dp(22f) * grown + orbSize + gap + dp(10f))
                    block(top, orbSize, grown = grown) { drawOrb(cx, it, orbSize) }
                    strip(top, grown, lit, scrollPressed = pulse(.34f) + pulse(.69f))
                    val scrollX = w - pad - dp(64f); val stripY = blockTop - gap + dp(12f)
                    val (fx, fy) = path(listOf(Triple(.03f, cx, blockTop + orbSize / 2), Triple(.18f, cx, blockTop + orbSize / 2),
                        Triple(.28f, scrollX, stripY), Triple(.76f, scrollX, stripY), Triple(.84f, cx, h - dp(30f))))
                    finger(fx, fy, pulse(.09f) + pulse(.34f) + pulse(.69f) + pulse(.87f), span(.02f, .07f) * (1 - span(.92f, .97f)))
                }
            }
            c.restore()
            canvas.restore()
        }
    }
}
