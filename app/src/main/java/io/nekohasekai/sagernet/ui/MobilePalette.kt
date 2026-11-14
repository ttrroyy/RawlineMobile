package app.rawline.mobile.ui

import android.content.Context
import android.graphics.Color
import app.rawline.mobile.database.DataStore
import app.rawline.mobile.utils.Theme
import com.google.android.material.color.MaterialColors

/** One interface, Rawline's; Pastel is a palette of it, chosen with the theme. */
object MobileStyle {
    val pastel get() = DataStore.uiStyle == "pastel"
    /** Black or white (OLED / White); the stored "classic" of older versions reads as this too. */
    val new get() = !pastel
    const val modern = true
}

object MobilePalette {
    /** Every night is OLED black, except Pastel's, which stays plum. */
    val oled get() = Theme.usingNightMode() && !MobileStyle.pastel
    val light get() = !oled && !Theme.usingNightMode()
    private val lilac = Color.rgb(0x7C, 0x6B, 0xF2)
    val background get() = when {
        MobileStyle.pastel && light -> Color.rgb(255, 228, 214)
        MobileStyle.pastel -> Color.rgb(22, 20, 31)
        light -> Color.WHITE
        else -> Color.BLACK
    }
    /** Translucent in the new style: anything that has to hide what is under it uses [solid] or [frosted]. */
    val surface get() = when {
        MobileStyle.pastel && light -> Color.WHITE
        MobileStyle.pastel -> Color.rgb(42, 36, 62)
        light -> Color.argb(190, 255, 255, 255)
        else -> Color.argb(18, 255, 255, 255)
    }
    val solid get() = when {
        MobileStyle.pastel && light -> Color.rgb(255, 247, 252)
        MobileStyle.pastel -> Color.rgb(36, 31, 51)
        light -> Color.rgb(243, 243, 246)
        else -> Color.rgb(21, 21, 24)
    }
    val toolbar get() = Color.TRANSPARENT
    val text get() = when {
        MobileStyle.pastel && light -> Color.rgb(0x1B, 0x1C, 0x2A)
        MobileStyle.pastel -> Color.rgb(0xF4, 0xF2, 0xFA)
        light -> Color.rgb(10, 10, 11)
        else -> Color.rgb(245, 245, 247)
    }
    val muted get() = when {
        MobileStyle.pastel && light -> Color.rgb(110, 108, 136)
        MobileStyle.pastel -> Color.rgb(176, 170, 198)
        light -> Color.rgb(105, 105, 112)
        else -> Color.rgb(142, 142, 150)
    }
    /** The dark field inside a card; on true black a lighter one, or it would vanish. */
    val well get() = when {
        MobileStyle.pastel && light -> Color.argb(18, 124, 107, 242)
        MobileStyle.pastel -> Color.argb(28, 255, 255, 255)
        light -> Color.argb(16, 0, 0, 0)
        else -> Color.argb(20, 255, 255, 255)
    }
    /** The editor's small round buttons: solid, a step lighter (or darker) than the screen, in either theme. */
    val badge get() = androidx.core.graphics.ColorUtils.blendARGB(background, text, if (light) .09f else .2f)
    /** Corner radii in dp. */
    const val rowDp = 20
    const val cardDp = 28
    const val wellDp = 18
    /** A screen's own colour: the screens let the backdrop through. */
    val screen = Color.TRANSPARENT
    /** Over content that must stay readable: notices, dialogs, sheets. */
    /** The blur behind sheets and dialogs: enough to lift them off the screen, while what is behind stays recognisable. */
    const val BLUR_DP = 20
    val windowDim get() = if (light) .35f else .6f
    val frosted get() = when {
        MobileStyle.pastel && light -> Color.argb(248, 255, 250, 252)
        MobileStyle.pastel -> Color.argb(248, 24, 20, 36)
        light -> Color.argb(248, 250, 250, 252)
        else -> Color.argb(248, 0, 0, 0)
    }
    val outline get() = androidx.core.graphics.ColorUtils.setAlphaComponent(muted,55)
    @Suppress("UNUSED_PARAMETER")
    fun accent(context: Context) = if (MobileStyle.pastel) lilac else text

    /** A row or button: every list row and action in the app. */
    fun rowShape(context: Context): android.graphics.drawable.Drawable = shape(context, rowDp)
    /** A card that holds rows, fields or text: also the dialogs, notices and sheets. */
    fun cardShape(context: Context): android.graphics.drawable.Drawable = shape(context, cardDp)
    /** The chosen row or the main action: a tint and an outline of [color], never a solid fill, the same on every theme. */
    fun selectedShape(context: Context, color: Int = accent(context)): android.graphics.drawable.Drawable {
        val density = context.resources.displayMetrics.density
        // The row's own plate a step towards lilac, with a lilac edge: set apart from the rows around it, not a blue wash.
        if (MobileStyle.pastel) return android.graphics.drawable.GradientDrawable().apply {
            setColor(androidx.core.graphics.ColorUtils.blendARGB(if (light) Color.WHITE else Color.rgb(42, 36, 62), lilac, if (light) .12f else .22f))
            setStroke((1.5f * density).toInt().coerceAtLeast(1), androidx.core.graphics.ColorUtils.setAlphaComponent(lilac, if (light) 200 else 230))
            cornerRadius = rowDp * density
        }
        return MobileGlass(rowDp * density, color)
    }
    /** A button revealed behind a swiped row: a drop of glass in [color], clear of its neighbours. */
    fun actionShape(context: Context, color: Int): android.graphics.drawable.Drawable {
        val density = context.resources.displayMetrics.density
        return android.graphics.drawable.InsetDrawable(MobileGlass(rowDp * density, color), (4 * density).toInt())
    }
    /** A notice over the content behind it. */
    fun frostedShape(context: Context): android.graphics.drawable.Drawable = android.graphics.drawable.GradientDrawable().apply {
        setColor(frosted); cornerRadius = cardDp * context.resources.displayMetrics.density; setStroke(1, outline)
    }
    private fun shape(context: Context, radius: Int): android.graphics.drawable.Drawable {
        val density = context.resources.displayMetrics.density
        if (MobileStyle.pastel) return android.graphics.drawable.GradientDrawable().apply {
            setColor(if (light) Color.WHITE else Color.rgb(42, 36, 62))
            cornerRadius = radius * density
        }
        return MobileGlass(radius * density)
    }

    /** The caret and selection handles in the accent: the theme draws them black, which vanishes on the dark themes. */
    fun tintCursor(edit: android.widget.EditText) {
        val accent = accent(edit.context)
        edit.highlightColor = androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 80)
        if (android.os.Build.VERSION.SDK_INT < 29) return
        // The theme has no caret drawable to recolour, so the caret is drawn here: 2dp wide, the line's height.
        edit.textCursorDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(accent); setSize((2 * edit.resources.displayMetrics.density).toInt(), 0)
        }
        // A handle the view has not loaded yet comes from the platform theme.
        fun handle(current: android.graphics.drawable.Drawable?, attr: Int) = (current ?: run {
            val styled = edit.context.obtainStyledAttributes(intArrayOf(attr))
            val res = styled.getResourceId(0, 0)
            styled.recycle()
            if (res == 0) null else androidx.core.content.ContextCompat.getDrawable(edit.context, res)
        })?.mutate()?.apply { setTint(accent) }
        handle(edit.textSelectHandle, android.R.attr.textSelectHandle)?.let { edit.setTextSelectHandle(it) }
        handle(edit.textSelectHandleLeft, android.R.attr.textSelectHandleLeft)?.let { edit.setTextSelectHandleLeft(it) }
        handle(edit.textSelectHandleRight, android.R.attr.textSelectHandleRight)?.let { edit.setTextSelectHandleRight(it) }
    }
}
