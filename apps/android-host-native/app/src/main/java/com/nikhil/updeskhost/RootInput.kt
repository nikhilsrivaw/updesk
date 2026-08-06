package com.nikhil.updeskhost

import org.json.JSONObject
import java.io.BufferedWriter

/**
 * Root-based input injection for CUSTODY (rooted) devices — an alternative to
 * the Accessibility service that needs no manual enable step. Injects via a
 * persistent `su` shell using Android's `input` command.
 *
 * NOTE: unverified without a rooted test device. Isolated + default-off, so it
 * never affects the working Accessibility path. Enable via the host UI when the
 * device is rooted.
 */
object RootInput {
    @Volatile var enabled = false

    private var w = 0
    private var h = 0
    private var writer: BufferedWriter? = null
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L

    /** Whether `su` grants root here (checked once). */
    val available: Boolean by lazy {
        runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            p.waitFor()
            p.inputStream.bufferedReader().readText().contains("uid=0")
        }.getOrDefault(false)
    }

    /** Open the persistent root shell and record the screen size (px). */
    fun start(screenW: Int, screenH: Int) {
        w = screenW; h = screenH
        if (writer == null) runCatching {
            val p = Runtime.getRuntime().exec("su")
            writer = p.outputStream.bufferedWriter()
        }
    }

    fun stop() { runCatching { writer?.close() }; writer = null }

    private fun cmd(c: String) = runCatching { writer?.apply { write(c); write("\n"); flush() } }

    fun handle(e: JSONObject) {
        when (e.optString("kind")) {
            "mousedown" -> {
                downX = e.optDouble("x").toFloat() * w
                downY = e.optDouble("y").toFloat() * h
                downT = System.currentTimeMillis()
            }
            "mouseup" -> {
                val ux = (e.optDouble("x").toFloat() * w).toInt()
                val uy = (e.optDouble("y").toFloat() * h).toInt()
                val dt = (System.currentTimeMillis() - downT).coerceIn(1, 2000)
                val moved = kotlin.math.hypot((ux - downX).toDouble(), (uy - downY).toDouble())
                if (moved < 16 && dt < 500) cmd("input tap ${downX.toInt()} ${downY.toInt()}")
                else cmd("input swipe ${downX.toInt()} ${downY.toInt()} $ux $uy $dt")
            }
            "wheel" -> {
                val dy = e.optInt("dy")
                val cx = w / 2; val cy = h / 2
                val d = if (dy > 0) 300 else -300
                cmd("input swipe $cx $cy $cx ${cy + d} 100")
            }
            "keydown" -> when (val k = e.optString("key")) {
                "Backspace" -> cmd("input keyevent 67")
                "Enter" -> cmd("input keyevent 66")
                " ", "Spacebar" -> cmd("input keyevent 62")
                else -> if (k.length == 1) cmd("input text ${escape(k)}")
            }
        }
    }

    // input text special chars must be escaped for the shell.
    private fun escape(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            if (c in " &|<>()\$`\\\"'*?~;") sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }
}
