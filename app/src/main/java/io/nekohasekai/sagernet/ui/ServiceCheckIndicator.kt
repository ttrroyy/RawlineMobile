package app.rawline.mobile.ui

import android.content.Context
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatImageView
import app.rawline.mobile.R

internal class ServiceCheckIndicator(context: Context) : AppCompatImageView(context) {
    private val ink = MobilePalette.text
    private var sweep = -0.3f
    private val motion = ValueAnimator.ofFloat(-0.3f, 1.3f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            sweep = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        setImageResource(R.drawable.ic_service_check)
        imageTintList = ColorStateList.valueOf(ink)
        contentDescription = context.getString(R.string.service_check)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        motion.start()
    }

    override fun onDetachedFromWindow() {
        motion.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        drawable?.alpha = 160
        super.onDraw(canvas)
        if (!motion.isRunning) return
        val save = canvas.save()
        val y = sweep * height
        canvas.clipRect(0f, y - height * 0.14f, width.toFloat(), y + height * 0.14f)
        drawable?.alpha = 255
        super.onDraw(canvas)
        canvas.restoreToCount(save)
    }
}
