package com.reelguard.data.repositories

import android.content.Context
import com.reelguard.core.diag.Diag
import com.reelguard.core.diag.Metric
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** عدّادات التشخيص (§45) + سجل Phase 0 + تسجيل Replay مجهول. لا يُسجَّل أي نص من محتوى Instagram. */
class Diagnostics(ctx: Context) : Diag {
    private val prefs = ctx.getSharedPreferences("diag", Context.MODE_PRIVATE)
    private val ring = ArrayDeque<String>()
    private val io = Executors.newSingleThreadExecutor()
    private val file = File(ctx.filesDir, "phase0.log")
    private val replayFile = File(ctx.filesDir, "replay.jsonl")
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    @Volatile var fileLogging = false

    init {
        try { if (file.exists()) file.readLines().takeLast(200).forEach { ring.addLast(it) } } catch (_: Exception) {}
    }

    override fun inc(key: String) { prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply() }
    fun get(key: String) = prefs.getInt(key, 0)
    fun counters(): List<Pair<String, Int>> = Metric.ALL.map { it to get(it) }

    override fun log(msg: String) {
        val line = "${fmt.format(Date())} $msg"
        synchronized(ring) { ring.addLast(line); while (ring.size > 400) ring.removeFirst() }
        if (fileLogging) io.execute {
            try { if (file.length() > 400_000) file.delete(); file.appendText(line + "\n") } catch (_: Exception) {}
        }
    }

    fun dump(): String = synchronized(ring) { ring.joinToString("\n") }

    fun clearLog() {
        synchronized(ring) { ring.clear() }
        io.execute { try { file.delete() } catch (_: Exception) {} }
    }

    // ---- Replay (§59) ----
    fun replayLine(line: String) = io.execute {
        try { if (replayFile.length() > 600_000) replayFile.delete(); replayFile.appendText(line + "\n") } catch (_: Exception) {}
    }
    fun dumpReplay(): String = try { if (replayFile.exists()) replayFile.readText() else "" } catch (_: Exception) { "" }
    fun clearReplay() { io.execute { try { replayFile.delete() } catch (_: Exception) {} } }
}
