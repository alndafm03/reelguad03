package com.reelguard.ui

import android.widget.LinearLayout

object StatsScreen {
    fun build(a: MainActivity, col: LinearLayout): () -> Unit {
        val ui = a.ui
        col.addView(ui.tv("الإحصائيات", 22f, true))
        val body = ui.tv("", 15f); col.addView(body)
        col.addView(ui.tv("المراجعة الأسبوعية", 18f, true))
        val week = ui.tv("", 15f); col.addView(week)
        col.addView(ui.tv("الأرقام المعلَّمة «تقدير» تقريبية. البيانات محلية بالكامل.", 12f))
        col.addView(ui.btn("رجوع") { a.show(MainActivity.Screen.DASHBOARD) })
        return {
            val s = a.app.stats.snapshot()
            body.text = "وقت الاستخدام اليوم (نظام الوقت): ${ui.minutes(s.todayUseMs)}\n" +
                "Reels اليوم: ${s.today}\nReels هذا الأسبوع: ${s.week}\n" +
                "الجلسات (الأسبوع): ${s.sessionsWeek} — اليوم: ${s.sessionsToday}\n" +
                "متوسط الجلسة: ${ui.minutes(s.avgSessionMs)}\nأطول جلسة: ${ui.minutes(s.longestSessionMs)}\n" +
                "مرات بلوغ الحد: ${s.limitsReached}\nالتقييدات: ${s.restrictions}\n" +
                "إجمالي مدة التقييد: ${ui.minutes(s.totalRestrictionMs)}\n" +
                "انقطاعات الكشف: ${a.app.diag.get(com.reelguard.core.diag.Metric.UNKNOWN)}\n" +
                "وقت مُستعاد (تقدير): ${ui.minutes(s.estimatedRecoveredMs)}"
            val d = s.week - s.previousWeek
            week.text = "هذا الأسبوع: ${s.week} • الأسبوع السابق: ${s.previousWeek}\n" +
                when { s.previousWeek == 0 -> "لا بيانات للمقارنة بعد."; d == 0 -> "بلا تغيير."
                    d < 0 -> "أقل بـ${-d} من الأسبوع السابق."; else -> "أكثر بـ$d من الأسبوع السابق." }
        }
    }
}
