package com.nikhil.updeskhost

import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DataChannel
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Remote file browser for the controller: list directories and download files
 * off this phone over the `fs` data channel. Requires "All files access"
 * (MANAGE_EXTERNAL_STORAGE) to see the full storage — appropriate for the
 * managed / custody forensics use case.
 *
 * Protocol (JSON strings from controller; JSON + binary chunks back):
 *   controller -> {t:"list", path}          host -> {t:"list-result", path, entries:[{name,dir,size}]}
 *   controller -> {t:"get", path}            host -> {t:"file-begin", name, size} + <binary chunks> + {t:"file-end"}
 *   errors                                   host -> {t:"error", message}
 */
class FileTransfer(private val channel: DataChannel) {

    fun onMessage(json: JSONObject) {
        when (json.optString("t")) {
            // Report failures instead of swallowing them (silent = looks broken).
            "list" -> Thread {
                try { list(json.optString("path")) } catch (e: Throwable) { error("list failed: ${e.message}") }
            }.start()
            "get" -> Thread {
                try { get(json.optString("path")) } catch (e: Throwable) { error("get failed: ${e.message}") }
            }.start()
        }
    }

    private fun list(path: String) {
        // Fall back to the canonical external-storage root if /sdcard is odd.
        val start = path.ifEmpty { "/storage/emulated/0" }
        val dir = File(start)
        if (!dir.exists()) { error("path not found: $start (grant 'All files access'?)"); return }
        if (!dir.isDirectory) { error("not a directory: $start"); return }
        if (dir.listFiles() == null) { error("can't read $start — enable 'All files access' on the phone"); return }
        val arr = JSONArray()
        dir.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?.forEach {
                arr.put(
                    JSONObject().put("name", it.name).put("dir", it.isDirectory).put("size", it.length())
                )
            }
        sendJson(
            JSONObject().put("t", "list-result")
                .put("path", dir.absolutePath)
                .put("parent", dir.parent ?: "")
                .put("entries", arr)
        )
    }

    private fun get(path: String) {
        val f = File(path)
        if (!f.isFile || !f.canRead()) { error("can't read: $path"); return }
        // Forensic integrity: hash the source on the device as it streams, plus
        // carry metadata (path, size, modified-time) for the chain of custody.
        val md = java.security.MessageDigest.getInstance("SHA-256")
        sendJson(
            JSONObject().put("t", "file-begin")
                .put("name", f.name)
                .put("size", f.length())
                .put("path", f.absolutePath)
                .put("mtime", f.lastModified())
        )
        f.inputStream().use { ins ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                while (channel.bufferedAmount() > 8L * 1024 * 1024) Thread.sleep(8)
                sendBinary(buf.copyOf(n))
            }
        }
        val sha256 = md.digest().joinToString("") { "%02x".format(it) }
        sendJson(JSONObject().put("t", "file-end").put("sha256", sha256))
    }

    private fun error(msg: String) = sendJson(JSONObject().put("t", "error").put("message", msg))

    private fun sendJson(o: JSONObject) {
        val bytes = o.toString().toByteArray(StandardCharsets.UTF_8)
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    private fun sendBinary(b: ByteArray) {
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(b), true))
    }
}
