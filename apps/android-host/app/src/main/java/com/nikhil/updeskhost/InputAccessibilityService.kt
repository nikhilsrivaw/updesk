package com.nikhil.updeskhost

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * Injects the controller's input into the phone via the Accessibility API — the
 * same non-root technique RustDesk uses. The user enables this once in
 * Settings → Accessibility; after that, taps/swipes/keys from the PC controller
 * are dispatched as real gestures.
 *
 * Controller sends desktop-style events over the `input` data channel:
 *   {kind:"mousedown"|"move"|"mouseup", x, y}   (x,y normalized 0..1)
 *   {kind:"wheel", dy}
 *   {kind:"keydown"|"keyup", key}
 * We reconstruct a tap (down≈up) or a swipe (down→up) and dispatch it.
 */
class InputAccessibilityService : AccessibilityService() {

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var screenW = 0
    private var screenH = 0

    override fun onServiceConnected() {
        instance = this
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
    }

    override fun onDestroy() { super.onDestroy(); if (instance === this) instance = null }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Route one input event from the controller. */
    fun handleInput(e: JSONObject) {
        when (e.optString("kind")) {
            "mousedown" -> {
                downX = (e.optDouble("x").toFloat()) * screenW
                downY = (e.optDouble("y").toFloat()) * screenH
                downTime = System.currentTimeMillis()
            }
            "mouseup" -> {
                val upX = (e.optDouble("x").toFloat()) * screenW
                val upY = (e.optDouble("y").toFloat()) * screenH
                val dt = (System.currentTimeMillis() - downTime).coerceIn(1, 2000)
                val moved = kotlin.math.hypot((upX - downX).toDouble(), (upY - downY).toDouble())
                if (moved < 16 && dt < 500) tap(downX, downY)
                else swipe(downX, downY, upX, upY, dt)
            }
            "wheel" -> {
                // Scroll = a short vertical swipe near screen centre.
                val dy = e.optInt("dy")
                val cx = screenW / 2f
                val cy = screenH / 2f
                val delta = if (dy > 0) screenH * 0.25f else -screenH * 0.25f
                swipe(cx, cy, cx, cy + delta, 200)
            }
            "keydown" -> typeKey(e.optString("key"))
        }
    }

    private fun dispatch(path: Path, durationMs: Long) {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun tap(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y); lineTo(x + 1f, y + 1f) }
        dispatch(p, 40)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatch(p, duration)
    }

    // Basic text entry into the focused editable field. Replaces the field's
    // text (accessibility limitation) — fine for the baseline; a dedicated IME
    // comes later for proper cursor handling.
    private fun typeKey(key: String) {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val current = node.text?.toString() ?: ""
        val next = when (key) {
            "Backspace" -> if (current.isNotEmpty()) current.dropLast(1) else ""
            "Enter" -> current + "\n"
            " ", "Spacebar" -> "$current "
            else -> if (key.length == 1) current + key else return
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    companion object {
        @Volatile var instance: InputAccessibilityService? = null
        val isEnabled: Boolean get() = instance != null
    }
}
