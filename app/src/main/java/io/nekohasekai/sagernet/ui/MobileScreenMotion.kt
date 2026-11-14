package app.rawline.mobile.ui

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.os.Build
import android.view.View
import android.view.animation.AnimationUtils
import androidx.fragment.app.Fragment
import app.rawline.mobile.R

/** Screen changes: the classic style fades quickly everywhere; the new style rises softly, screens and windows alike. */
object MobileScreenMotion {
    /** Some phones hold an app at 60 Hz unless its window asks for the fastest mode of the screen. */
    fun fullFrameRate(window: android.view.Window) {
        @Suppress("DEPRECATION") val display = window.windowManager.defaultDisplay ?: return
        val mode = display.mode
        val fastest = display.supportedModes.filter { it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight }.maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = fastest.modeId }
    }

    private var themeShot: android.graphics.Bitmap? = null
    /** A theme change recreates the whole screen: the old picture stays on top until the new one is ready, then fades. */
    fun holdForRecreate(activity: Activity, recreate: () -> Unit) {
        val window = activity.window
        val decor = window.decorView
        if (Build.VERSION.SDK_INT < 26 || decor.width == 0 || decor.height == 0) return recreate()
        val shot = android.graphics.Bitmap.createBitmap(decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888)
        // The screen's real pixels: a software redraw fails on hardware bitmaps and misses what is mid-animation.
        android.view.PixelCopy.request(window, shot, { result ->
            if (result == android.view.PixelCopy.SUCCESS) themeShot = shot
            recreate()
        }, android.os.Handler(android.os.Looper.getMainLooper()))
    }
    fun fadeAfterRecreate(activity: Activity) {
        val shot = themeShot ?: return
        themeShot = null
        // The same screen comes back: no rise or scale over it.
        if (Build.VERSION.SDK_INT >= 34) activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        else @Suppress("DEPRECATION") activity.overridePendingTransition(0, 0)
        val decor = activity.window.decorView as android.view.ViewGroup
        val cover = android.widget.ImageView(activity).apply { setImageBitmap(shot); scaleType = android.widget.ImageView.ScaleType.FIT_XY }
        decor.addView(cover, android.view.ViewGroup.LayoutParams(-1, -1))
        cover.postDelayed({ cover.animate().alpha(0f).setDuration(220).withEndAction { decor.removeView(cover) }.start() }, 260)
    }

    /** A screen inside the window moves as a new window does (the screen_soft animations), so every screen opens alike. */
    fun create(fragment: Fragment, nextAnim: Int): Animator? {
        val view = fragment.view ?: return null
        if (nextAnim !in listOf(R.anim.settings_enter, R.anim.settings_exit, R.anim.settings_pop_enter, R.anim.settings_pop_exit)) return null
        val dp = view.resources.displayMetrics.density
        fun alpha(from: Float, to: Float, ms: Long) = ObjectAnimator.ofFloat(view, View.ALPHA, from, to).setDuration(ms)
        fun scale(from: Float, to: Float, ms: Long) = listOf(ObjectAnimator.ofFloat(view, View.SCALE_X, from, to).setDuration(ms), ObjectAnimator.ofFloat(view, View.SCALE_Y, from, to).setDuration(ms))
        fun rise(from: Float, to: Float, ms: Long) = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, from * dp, to * dp).setDuration(ms)
        val parts: List<Animator> = when (nextAnim) {
            R.anim.settings_enter -> listOf(alpha(0f, 1f, 200), rise(24f, 0f, 300)) + scale(.985f, 1f, 300)
            R.anim.settings_exit -> listOf(alpha(1f, 0f, 180)) + scale(1f, .97f, 260)
            R.anim.settings_pop_enter -> listOf(alpha(0f, 1f, 200)) + scale(.97f, 1f, 260)
            else -> listOf(alpha(1f, 0f, 160), rise(0f, 24f, 220))
        }
        MobileOrbArtwork.frozen = true
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        return AnimatorSet().apply {
            interpolator = AnimationUtils.loadInterpolator(view.context, android.R.interpolator.fast_out_slow_in)
            playTogether(parts)
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    MobileOrbArtwork.frozen = false
                    view.translationY = 0f; view.scaleX = 1f; view.scaleY = 1f; view.alpha = 1f
                    view.setLayerType(View.LAYER_TYPE_NONE, null)
                }
            })
        }
    }

    @Suppress("DEPRECATION")
    fun open(activity: Activity) {
        val enter = R.anim.screen_soft_enter
        val exit = R.anim.screen_soft_exit
        val popEnter = R.anim.screen_soft_pop_enter
        val popExit = R.anim.screen_soft_pop_exit
        if (Build.VERSION.SDK_INT >= 34) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, enter, exit)
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, popEnter, popExit)
        } else activity.overridePendingTransition(enter, exit)
    }

    @Suppress("DEPRECATION")
    fun close(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 34) return
        activity.overridePendingTransition(R.anim.screen_soft_pop_enter, R.anim.screen_soft_pop_exit)
    }
}
