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

    private var screenW = 0
    private var screenH = 0

    // Continuous-drag state (RustDesk-style willContinue stroke chaining).
    private var currentStroke: GestureDescription.StrokeDescription? = null
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

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
            "mousedown" -> beginDrag(px(e, "x", screenW), px(e, "y", screenH))
            "move" -> if (dragging) continueDrag(px(e, "x", screenW), px(e, "y", screenH))
            "mouseup" -> endDrag(px(e, "x", screenW), px(e, "y", screenH))
            "wheel" -> wheel(e.optInt("dy"))
            "keydown" -> typeKey(e.optString("key"))
        }
    }

    private fun px(e: JSONObject, k: String, span: Int) = (e.optDouble(k).toFloat()) * span

    // --- continuous drag: down -> (moves) -> up chained into one gesture, so a
    // slow drag scrolls/drags smoothly and a quick tiny one is just a tap. ---

    private fun beginDrag(x: Float, y: Float) {
        lastX = x; lastY = y; dragging = true
        val path = Path().apply { moveTo(x, y); lineTo(x, y) }
        currentStroke = GestureDescription.StrokeDescription(path, 0, SEG_MS, true)
        dispatchStroke(currentStroke!!)
    }

    private fun continueDrag(x: Float, y: Float) {
        val prev = currentStroke ?: return
        val path = Path().apply { moveTo(lastX, lastY); lineTo(x, y) }
        val next = try { prev.continueStroke(path, 0, SEG_MS, true) } catch (_: Throwable) {
            GestureDescription.StrokeDescription(path, 0, SEG_MS, true)
        }
        currentStroke = next
        lastX = x; lastY = y
        dispatchStroke(next)
    }

    private fun endDrag(x: Float, y: Float) {
        if (!dragging) return
        dragging = false
        val prev = currentStroke
        val path = Path().apply { moveTo(lastX, lastY); lineTo(x, y) }
        val finalStroke = try {
            prev?.continueStroke(path, 0, SEG_MS, false)
                ?: GestureDescription.StrokeDescription(path, 0, SEG_MS, false)
        } catch (_: Throwable) {
            GestureDescription.StrokeDescription(path, 0, SEG_MS, false)
        }
        dispatchStroke(finalStroke)
        currentStroke = null
    }

    private fun wheel(dy: Int) {
        // Vertical scroll as a swipe near screen centre (RustDesk WHEEL_STEP/DURATION).
        val cx = screenW / 2f
        val cy = screenH / 2f
        val delta = if (dy > 0) WHEEL_STEP else -WHEEL_STEP
        val path = Path().apply { moveTo(cx, cy); lineTo(cx, cy + delta) }
        dispatchStroke(GestureDescription.StrokeDescription(path, 0, WHEEL_MS))
    }

    private fun dispatchStroke(stroke: GestureDescription.StrokeDescription) {
        try {
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } catch (_: Throwable) { /* transient dispatch races are non-fatal */ }
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
        private const val SEG_MS = 40L      // per drag segment
        private const val WHEEL_STEP = 300f // scroll distance per wheel tick (px)
        private const val WHEEL_MS = 80L
    }
}
