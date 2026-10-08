package com.reelguard.ui

import android.content.Intent
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.reelguard.core.model.ProtectionBasis
import com.reelguard.platform.common.AppPlatform

object Dashboard {
    fun build(a: MainActivity, col: LinearLayout): () -> Unit {
        val ui = a.ui; val app = a.app
        col.addView(ui.tv("ReelGuard", 24f, true))
        val protection = ui.tv("", 18f, true); col.addView(protection)
        val count = ui.tv("", 40f, true); col.addView(count)
        val sub = ui.tv("", 14f); col.addView(sub)
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal); col.addView(bar)
        val lock = ui.tv("", 16f); col.addView(lock)
        val pending = ui.tv("", 13f); col.addView(pending)
        val session = ui.tv("", 14f); col.addView(session)
        val health = ui.tv("", 14f); col.addView(health)
        val attention = ui.tv("", 14f, true); col.addView(attention)
        val today = ui.tv("", 14f); col.addView(today)

        col.addView(ui.btn("الإعدادات") { a.show(MainActivity.Screen.SETTINGS) })
        col.addView(ui.btn("الإحصائيات") { a.show(MainActivity.Screen.STATS) })
        col.addView(ui.btn("معايرة الكشف (موصى بها)") { a.show(MainActivity.Screen.CALIB) })
        col.addView(ui.btn("التشخيص") { a.show(MainActivity.Screen.DIAG) })
        col.addView(ui.btn("الخصوصية") { a.show(MainActivity.Screen.PRIVACY) })
        col.addView(ui.btn("فتح إعدادات الوصول") { a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })

        return {
            val s = app.stateManager.state
            val enabled = a.isServiceEnabled()
            val rep = app.health.report(app.core.isProtectionActive(), enabled)
            protection.text = when {
                !enabled -> "⚠️ خدمة الوصول غير مفعّلة — الحماية متوقفة"
                else -> "🟢 الحماية فعّالة"
            }
            if (s.basis == ProtectionBasis.TIME) {
                val remMs = app.core.timeRemainingMs()
                count.text = ui.fmtMs(remMs)
                sub.text = "وقت متبقٍّ من ${s.cycleTimeMs / 60_000L} دقيقة" +
                    if (s.configuredTimeMin * 60_000L != s.cycleTimeMs) "  (القادم: ${s.configuredTimeMin} د)" else ""
                bar.max = (s.cycleTimeMs / 1000L).toInt().coerceAtLeast(1); bar.progress = (remMs / 1000L).toInt().coerceIn(0, bar.max)
            } else {
                count.text = "${s.remaining} / ${s.cycleLimit}"
                sub.text = "Reels متبقية" + if (s.configuredLimit != s.cycleLimit) "  (الحد القادم: ${s.configuredLimit})" else ""
                bar.max = s.cycleLimit; bar.progress = s.remaining
            }
            lock.text = if (s.isLockedState) "🔒 مقفل — يتبقى ${ui.fmtMs(app.core.lockRemainingMs())}" else "🔓 غير مقفل"
            pending.text = app.settings.pendingRemainingMs()?.let { ui.pendingText(s, it) } ?: ""
            session.text = "الجلسة الحالية: ${app.sessions.currentDurationMs() / 60000} دقيقة"
            val activeLabel = if (rep.platform == com.reelguard.core.health.Level.OK) AppPlatform.byId(app.core.activePlatform)?.label ?: "-" else "-"
            health.text = "التطبيق النشط: $activeLabel   الكشف ${ui.dot(rep.detection)}   الوصول ${ui.dot(rep.accessibility)}   Reel الحالي ${ui.dot(rep.currentReel)}\n" +
                "المحميّة: " + s.enabledPlatforms.mapNotNull { AppPlatform.byId(it)?.label }.joinToString("، ") +
                " • " + ui.basisLabel(s.basis) + " • " + ui.modeLabel(s.mode) + " • إيقاف ${s.lockMinutes} د"
            attention.text = if (s.basis == ProtectionBasis.COUNT && rep.needsAttention) "⚠️ قد يحتاج كشف هذا التطبيق إلى انتباه — جرّب المعايرة." else ""
            val st = app.stats.snapshot()
            today.text = if (s.basis == ProtectionBasis.TIME)
                "اليوم: ${app.core.usedTodayMs() / 60_000L} دقيقة استخدام • تقييدات: ${st.restrictionsToday}"
            else "اليوم: ${st.today} Reels • ~${st.todaySessionMs / 60000} دقيقة (تقدير) • تقييدات: ${st.restrictionsToday}"
        }
    }
}
