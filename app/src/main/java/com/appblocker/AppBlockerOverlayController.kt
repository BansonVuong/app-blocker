package com.appblocker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat

class AppBlockerOverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val storage: Storage,
    private val isScreenOff: () -> Boolean,
    private val currentTrackedPackage: () -> String?,
    private val dpToPx: (Int) -> Int,
    private val formatRemainingTime: (Int) -> String,
    private val overlayMarginDp: Int
) {
    private var overlayView: TextView? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null

    fun updateOverlay(blockSet: BlockSet?) {
        if (!Settings.canDrawOverlays(context)) {
            removeOverlay()
            return
        }
        if (isScreenOff()) {
            removeOverlay()
            return
        }

        if (blockSet == null) {
            removeViewSafely(overlayView)
            overlayView = null
            overlayLayoutParams = null
            return
        }

        val remainingSeconds = storage.getDisplayRemainingSeconds(blockSet)

        val view = ensureOverlayView()
        view.text = formatRemainingTime(remainingSeconds)
        view.setTextColor(ContextCompat.getColor(context, R.color.white))
    }

    fun updateOverlayWithLocalTracking(blockSet: BlockSet, remainingSeconds: Int) {
        if (!Settings.canDrawOverlays(context)) {
            removeOverlay()
            return
        }
        if (isScreenOff()) {
            removeOverlay()
            return
        }

        val view = ensureOverlayView()
        view.text = formatRemainingTime(remainingSeconds)
        view.setTextColor(ContextCompat.getColor(context, R.color.white))
    }

    fun removeOverlay() {
        removeViewSafely(overlayView)
        overlayView = null
        overlayLayoutParams = null
    }

    fun applyStoredOverlayPosition(packageName: String) {
        val position = storage.getOverlayPosition(packageName) ?: return
        val params = overlayLayoutParams ?: return
        params.gravity = Gravity.TOP or Gravity.START
        params.x = position.first
        params.y = position.second
        overlayView?.let { windowManager.updateViewLayout(it, params) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureOverlayView(): TextView {
        if (overlayView != null) return overlayView!!
        clearStaleOverlayReference()

        val view = TextView(context)
        view.setTextColor(Color.WHITE)
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        view.typeface = Typeface.DEFAULT_BOLD
        view.setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
        view.background = GradientDrawable().apply {
            cornerRadius = dpToPx(12).toFloat()
            setColor(Color.argb(200, 0, 0, 0))
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        val storedPosition = currentTrackedPackage()?.let { storage.getOverlayPosition(it) }
        params.x = storedPosition?.first ?: dpToPx(overlayMarginDp)
        params.y = storedPosition?.second ?: dpToPx(overlayMarginDp)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    persistOverlayPosition(params.x, params.y)
                    true
                }

                else -> false
            }
        }

        windowManager.addView(view, params)
        overlayView = view
        overlayLayoutParams = params
        return view
    }

    private fun persistOverlayPosition(x: Int, y: Int) {
        val packageName = currentTrackedPackage() ?: return
        storage.setOverlayPosition(packageName, x, y)
    }

    private fun removeViewSafely(view: TextView?) {
        if (view == null) return
        try {
            windowManager.removeView(view)
        } catch (_: IllegalArgumentException) {
            // View may already be detached from the regular remove path; force immediate cleanup.
            try {
                windowManager.removeViewImmediate(view)
            } catch (_: IllegalArgumentException) {
                // View already removed or not attached.
            }
        }
    }

    private fun clearStaleOverlayReference() {
        removeViewSafely(overlayView)
        overlayView = null
        overlayLayoutParams = null
    }
}
