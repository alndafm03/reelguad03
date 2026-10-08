package com.reelguard.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.reelguard.core.diag.Metric

/** وضع المطوّر/الاختبار (§44): حالة حيّة + عدّادات + سجل Phase 0 + Replay مجهول. */
object DiagnosticsScreen {
    fun build(a: MainActivity, col: LinearLayout): () -> Unit {
        val ui = a.ui; val app = a.app; val diag = app.diag
        col.addView(ui.tv("التشخيص", 22f, true))
        val live = ui.tv("", 14f).apply { typeface = Typeface.MONOSPACE; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        col.addView(live)
        val counters = ui.tv("", 13f); col.addView(counters)
        col.addView(ui.btn("تسجيل: إيجابي كاذب (خُصم بلا Reel)") { diag.inc(Metric.FALSE_POS); diag.log("MANUAL false_positive") })
        col.addView(ui.btn("تسجيل: سلبي كاذب (لم يُخصم رغم Reel)") { diag.inc(Metric.FALSE_NEG); diag.log("MANUAL false_negative") })

        val dev = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL
            visibility = if (app.stateManager.state.debugLogging) View.VISIBLE else View.GONE }
        val logTv = TextView(a).apply { textSize = 10f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true); layoutDirection = View.LAYOUT_DIRECTION_LTR }
        fun copy(label: String, text: String) {
            (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
            Toast.makeText(a, "تم النسخ", Toast.LENGTH_SHORT).show()
        }
        col.addView(Switch(a).apply {
            text = "وضع التسجيل التفصيلي (Phase 0)"; isChecked = app.stateManager.state.debugLogging
            setOnCheckedChangeListener { _, on -> app.settings.setDebug(on); diag.fileLogging = on
                dev.visibility = if (on) View.VISIBLE else View.GONE }
        })
        dev.addView(ui.tv("السجل: view IDs وحالات فقط. Replay: hash فقط (لا نصوص).", 12f))
        dev.addView(ui.btn("نسخ السجل") { copy("reelguard-log", diag.dump()) })
        dev.addView(ui.btn("نسخ تسجيل Replay") { copy("reelguard-replay", diag.dumpReplay()) })
        dev.addView(ui.btn("مسح السجل والـReplay") { diag.clearLog(); diag.clearReplay() })
        dev.addView(logTv); col.addView(dev)
        col.addView(ui.btn("رجوع") { a.show(MainActivity.Screen.DASHBOARD) })

        var lastLen = -1
        return {
            val d = app.core.diagnosticView()
            val det = d.detection
            val tr = diag.get(Metric.TRANSITIONS); val col2 = diag.get(Metric.ID_COLLISION)
            val rate = if (tr == 0) "-" else String.format(java.util.Locale.US, "%.1f%%", col2 * 100.0 / tr)
            live.text = "App: ${if (d.platformActive) d.platformId.uppercase() else "INACTIVE"}\n" +
                "Context: ${det?.contentType ?: "-"} (${det?.state ?: "-"})\nConfidence: ${det?.confidence ?: 0}\n" +
                "Signals: ${det?.signals?.joinToString(",") ?: "-"}\nIdentity: ${d.identity}\nTransition: ${d.lastTransition}\n" +
                "IdCollision: $col2 / $tr transitions ($rate)\n" +
                "Timer: ${d.timerMs?.let { String.format(java.util.Locale.US, "%.1fs", it / 1000f) } ?: "-"}\n" +
                "Budget: ${d.remaining}/${d.limit}\nSession: ${d.sessionMs / 60000}m\nFlow: ${d.flow}\n" +
                "Service: ${if (a.isServiceEnabled() && d.serviceConnected) "ACTIVE" else "OFF"}\nOverlay: ${if (d.hostAttached) "READY" else "-"}"
            counters.text = diag.counters().joinToString("\n") { "${it.first} = ${it.second}" }
            if (app.stateManager.state.debugLogging) { val t = diag.dump(); if (t.length != lastLen) { lastLen = t.length; logTv.text = t } }
        }
    }
}
