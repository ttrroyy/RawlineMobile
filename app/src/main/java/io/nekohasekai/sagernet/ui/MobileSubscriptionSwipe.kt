package app.rawline.mobile.ui

import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/** Horizontal subscription changes also work with a slow drag, without stealing vertical scrolling. */
internal object MobileSubscriptionSwipe {
    fun bind(view: View, tap: (() -> Unit)?, longPress: () -> Unit, doubleTap: () -> Unit, change: (Int) -> Unit) {
        var x = 0f
        var y = 0f
        var horizontal = false
        var cancelled = false
        val slop = ViewConfiguration.get(view.context).scaledTouchSlop
        val distance = 40f * view.resources.displayMetrics.density
        if (tap != null) view.setOnClickListener { tap() }
        val gestures = GestureDetector(view.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean { if (tap != null && view.isAttachedToWindow && !horizontal && !cancelled) view.performClick(); return true }
            override fun onLongPress(event: MotionEvent) { if (!horizontal && !cancelled) { cancelled = true; longPress() } }
            override fun onDoubleTap(event: MotionEvent): Boolean { if (!horizontal && !cancelled) doubleTap(); return true }
        })
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x = event.x; y = event.y; horizontal = false; cancelled = false }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> cancelled = true
                MotionEvent.ACTION_MOVE -> if (!cancelled && abs(event.x - x) > slop && abs(event.x - x) > abs(event.y - y) * 1.2f) {
                    horizontal = true
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            gestures.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (event.actionMasked == MotionEvent.ACTION_UP && horizontal && !cancelled && abs(event.x - x) >= distance && abs(event.x - x) > abs(event.y - y))
                    change(if (event.x < x) 1 else -1)
                view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            true
        }
    }
}
