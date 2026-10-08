package com.reelguard.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import com.reelguard.core.budget.BudgetEngine
import com.reelguard.core.policy.PolicySettings
import com.reelguard.platform.common.AppPlatform

/**
 * التشديد يسري فورًا؛ التخفيف يُجدوَل ويسري بعد مهلة (PolicySettings).
 * تبديل نظام الحماية (عدد الريلز ↔ الوقت) معطّل تمامًا أثناء الإيقاف.
 */
object SettingsScreen {
    fun build(a: MainActivity, col: LinearLayout): () -> Unit {
        val ui = a.ui; val s = a.app.settings
        col.addView(ui.tv("الإعدادات", 22f, true))

        var cfg = s.desired()

        col.addView(ui.tv("التطبيقات المحميّة", 17f, true))
        col.addView(ui.checks(AppPlatform.values().map { it.label to it.id }, cfg.platforms) { cfg = cfg.copy(platforms = it) })

        col.addView(ui.tv("نظام الحماية", 17f, true))
        val basisGroup: RadioGroup = ui.radio(Onboarding.bases(), cfg.basis) { cfg = cfg.copy(basis = it) }
        col.addView(basisGroup)
        val basisNote = ui.tv("", 12f); col.addView(basisNote)

        col.addView(ui.tv("وضع الحماية", 17f, true))
        col.addView(ui.radio(Onboarding.modes(), cfg.mode) { cfg = cfg.copy(mode = it) })

        col.addView(ui.tv("عدد الريلز لكل دورة (${BudgetEngine.MIN_LIMIT}-${BudgetEngine.MAX_LIMIT})", 15f, true))
        val limit = ui.edit("عدد الـReels", cfg.limit.toString()); col.addView(limit)
        col.addView(ui.tv("الوقت المسموح بالدقائق (${BudgetEngine.MIN_TIME_MIN}-${BudgetEngine.MAX_TIME_MIN})", 15f, true))
        val timeMin = ui.edit("الوقت بالدقائق", cfg.timeMin.toString()); col.addView(timeMin)
        col.addView(ui.tv("مدة الإيقاف بالدقائق (${BudgetEngine.MIN_LOCK_MIN}-${BudgetEngine.MAX_LOCK_MIN})", 15f, true))
        val lockMin = ui.edit("مدة الإيقاف", cfg.lockMin.toString()); col.addView(lockMin)
        col.addView(ui.tv(
            "التشديد يسري فورًا. التخفيف (رفع الريلز أو الوقت، تقصير الإيقاف، وضع أخفّ، إزالة تطبيق، تبديل النظام) يسري بعد " +
            "${PolicySettings.WEAKEN_DELAY_MS / 3_600_000L} ساعة، حتى أثناء القفل. رفع الحد يبدأ من الدورة التالية.", 12f))

        val msg = ui.tv("", 13f, true)
        val pending = ui.tv("", 13f)
        val cancel = ui.btn("إلغاء التغيير المجدول") { s.cancelPending(); a.render() }
        col.addView(ui.btn("حفظ") {
            val l = limit.text.toString().toIntOrNull()
            val t = timeMin.text.toString().toIntOrNull()
            val k = lockMin.text.toString().toIntOrNull()
            when {
                l == null || l !in BudgetEngine.MIN_LIMIT..BudgetEngine.MAX_LIMIT -> msg.text = "عدد الريلز: أدخل رقمًا بين ${BudgetEngine.MIN_LIMIT} و${BudgetEngine.MAX_LIMIT}."
                t == null || t !in BudgetEngine.MIN_TIME_MIN..BudgetEngine.MAX_TIME_MIN -> msg.text = "الوقت: أدخل رقمًا بين ${BudgetEngine.MIN_TIME_MIN} و${BudgetEngine.MAX_TIME_MIN}."
                k == null || k !in BudgetEngine.MIN_LOCK_MIN..BudgetEngine.MAX_LOCK_MIN -> msg.text = "مدة الإيقاف: أدخل رقمًا بين ${BudgetEngine.MIN_LOCK_MIN} و${BudgetEngine.MAX_LOCK_MIN}."
                cfg.platforms.isEmpty() -> msg.text = "اختر تطبيقًا واحدًا على الأقل."
                else -> {
                    msg.text = when (s.requestChange(cfg.copy(limit = l, timeMin = t, lockMin = k))) {
                        PolicySettings.Outcome.NO_CHANGE -> "لا تغيير."
                        PolicySettings.Outcome.APPLIED -> "تم الحفظ."
                        PolicySettings.Outcome.QUEUED -> "طُبّق التشديد فورًا، وجُدوِل التخفيف."
                        PolicySettings.Outcome.BASIS_BLOCKED -> "لا يمكن تبديل نظام الحماية أثناء الإيقاف. حُفظت بقية التغييرات."
                    }
                    cfg = s.desired()
                }
            }
        })
        col.addView(msg); col.addView(pending); col.addView(cancel)

        col.addView(Switch(a).apply {
            text = "عرض العدّاد فوق التطبيق"; isChecked = s.state.showCounter
            setOnCheckedChangeListener { _, on -> s.setShowCounter(on) }
        })
        col.addView(ui.btn("رجوع") { a.show(MainActivity.Screen.DASHBOARD) })

        return {
            val locked = s.state.isLockedState
            for (i in 0 until basisGroup.childCount) basisGroup.getChildAt(i).isEnabled = !locked
            basisNote.text = if (locked) "🔒 تبديل نظام الحماية معطّل أثناء الإيقاف." else ""
            val rem = s.pendingRemainingMs()
            pending.text = rem?.let { ui.pendingText(s.state, it) } ?: ""
            cancel.visibility = if (rem != null) View.VISIBLE else View.GONE
        }
    }
}
