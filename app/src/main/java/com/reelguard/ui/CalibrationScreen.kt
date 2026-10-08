package com.reelguard.ui

import android.content.Intent
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.Toast
import com.reelguard.accessibility.service.ReelGuardService
import com.reelguard.platform.common.AppPlatform
import com.reelguard.platform.reels.rules.ProfileLoader

/**
 * معايرة الكشف تلقائيًا على جهازك ونسخة كل تطبيق (Instagram / Facebook / YouTube).
 * لا تُحفظ نصوص؛ فقط معرّفات عناصر وبصمات hash. لكل تطبيق Profile مُعايَر مستقل.
 */
object CalibrationScreen {
    fun build(a: MainActivity, col: LinearLayout): () -> Unit {
        val ui = a.ui; val cal = a.app.calibration
        col.addView(ui.tv("معايرة الكشف", 22f, true))
        col.addView(ui.tv("اختر التطبيق ثم اتبع الخطوات:", 15f, true))
        col.addView(ui.radio(AppPlatform.values().map { it.label to it }, cal.platform) { if (!cal.active) { cal.platform = it; a.render() } })
        col.addView(ui.tv(
            "١) اضغط «ابدأ المعايرة» ثم افتح التطبيق.\n" +
            "٢) ستظهر لوحة أسفل الشاشة. داخل Reels/Shorts اضغط «أنا في Reel» وتصفّح ١٥ مقطعًا على الأقل ببطء.\n" +
            "٣) اضغط «ليس Reel» ثم تنقّل في الصفحة الرئيسية والملف الشخصي والبحث (١٥ لقطة على الأقل).\n" +
            "٤) اختياري (Instagram): افتح Stories واضغط «Story».\n" +
            "٥) عد إلى هنا واضغط «احسب الاقتراح» ثم «تطبيق».\n" +
            "أثناء المعايرة لا يُحتسب شيء ولا يُفعَّل قفل.", 14f))
        val status = ui.tv("", 15f, true); col.addView(status)
        col.addView(ui.btn("ابدأ المعايرة") { cal.start(cal.platform) })
        col.addView(ui.btn("إيقاف المعايرة") { cal.stop() })
        col.addView(ui.btn("فتح ${cal.platform.label}") {
            a.packageManager.getLaunchIntentForPackage(cal.platform.launchPackage)?.let { a.startActivity(it) }
                ?: Toast.makeText(a, "التطبيق غير مثبّت", Toast.LENGTH_SHORT).show()
        })
        val res = ui.tv("", 13f); col.addView(res)
        col.addView(ui.btn("احسب الاقتراح") {
            cal.stop()
            cal.result = cal.calibrator.suggest(ProfileLoader.load(a, cal.platform))
        })
        col.addView(ui.btn("تطبيق الاقتراح") {
            val sug = cal.result?.suggestion
            if (sug != null) {
                ProfileLoader.saveOverride(a, cal.platform, sug.profile)
                ReelGuardService.instance?.reloadProfile()
                Toast.makeText(a, "تم تطبيق المعايرة لـ${cal.platform.label}", Toast.LENGTH_SHORT).show()
            }
        })
        col.addView(ui.btn("استعادة الإعدادات الافتراضية") {
            ProfileLoader.clearOverride(a, cal.platform); ReelGuardService.instance?.reloadProfile()
            Toast.makeText(a, "تمت الاستعادة لـ${cal.platform.label}", Toast.LENGTH_SHORT).show()
        })
        col.addView(ui.btn("فتح إعدادات الوصول") { a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        col.addView(ui.btn("رجوع") { cal.stop(); a.show(MainActivity.Screen.DASHBOARD) })
        return {
            val shown = cal.platform
            status.text = (if (cal.active) "🔴 المعايرة جارية (${shown.label}) — " else "⚪ متوقفة — ") + cal.summary() +
                if (ProfileLoader.hasOverride(a, shown)) "\n✅ يوجد Profile مُعايَر لـ${shown.label}" else "\nيُستعمل الـProfile الافتراضي لـ${shown.label}"
            val r = cal.result
            res.text = when { r == null -> ""; r.error != null -> "⚠️ ${r.error}"; else -> r.suggestion!!.notes.joinToString("\n• ", "• ") }
        }
    }
}
