package com.reelguard.ui

import android.content.Intent
import android.provider.Settings
import android.widget.LinearLayout
import com.reelguard.core.budget.BudgetEngine
import com.reelguard.core.model.ProtectionBasis
import com.reelguard.core.model.ProtectionMode
import com.reelguard.core.policy.PolicySettings
import com.reelguard.platform.common.AppPlatform

/** 7 خطوات: مرحبًا ← ماذا يفعل ← التطبيقات ← نظام الحماية ← وضع الحماية ← الرصيد/الوقت ← تفعيل الخدمة. */
object Onboarding {
    const val LAST_STEP = 6

    fun build(a: MainActivity, col: LinearLayout): (() -> Unit)? {
        val ui = a.ui; val s = a.app.settings
        var refresher: (() -> Unit)? = null
        col.addView(ui.tv("ReelGuard — الخطوة ${a.step + 1} من ${LAST_STEP + 1}", 13f))
        fun next(label: String = "التالي", action: () -> Unit = { a.step++; a.render() }) =
            col.addView(ui.btn(label) { action() })

        when (a.step) {
            0 -> { col.addView(ui.tv("مرحبًا بك في ReelGuard", 24f, true))
                col.addView(ui.tv("أداة للتحكم في وقتك مع Instagram وFacebook وYouTube. كل شيء يعمل محليًا على جهازك.", 16f))
                next("ابدأ") }
            1 -> { col.addView(ui.tv("ماذا يفعل ReelGuard؟", 22f, true))
                col.addView(ui.tv("تختار أحد نظامين للحماية:\n" +
                    "• حسب عدد الريلز: يحتسب الـReels / Shorts التي تشاهدها فعلًا.\n" +
                    "• حسب الوقت: يحتسب الوقت الذي تقضيه داخل التطبيق كاملًا.\n\n" +
                    "عند انتهاء الرصيد (أو الوقت) يُقفل التطبيق للمدة التي تحدّدها، ثم تبدأ دورة جديدة.\n" +
                    "لا يمكن تبديل النظام أثناء الإيقاف، ولا إيقاف الحماية بعد بدئها.", 16f)); next() }
            2 -> { col.addView(ui.tv("اختر التطبيقات", 22f, true))
                col.addView(ui.checks(AppPlatform.values().map { it.label to it.id }, s.desiredPlatforms()) { sel ->
                    if (sel.isNotEmpty()) s.requestChange(s.desiredMode(), s.desiredLimit(), sel) })
                col.addView(ui.tv("اختر تطبيقًا واحدًا على الأقل. الرصيد (أو الوقت) واحد مشترك بين كل التطبيقات المختارة.", 13f)); next() }
            3 -> { col.addView(ui.tv("نظام الحماية", 22f, true))
                col.addView(ui.radio(bases(), s.desired().basis) { s.requestChange(s.desired().copy(basis = it)) })
                col.addView(ui.tv("حسب عدد الريلز: تحدّد عدد المقاطع المسموح بها في كل دورة.\n" +
                    "حسب الوقت: تحدّد المدة المسموح لك باستخدام التطبيق فيها (كل ما تفعله داخله).", 13f)); next() }
            4 -> { col.addView(ui.tv("وضع الحماية", 22f, true))
                col.addView(ui.radio(modes(), s.desiredMode()) { s.requestChange(it, s.desiredLimit(), s.desiredPlatforms()) })
                col.addView(ui.tv("تنبيه فقط: يظهر تنبيه عند نفاد الرصيد دون قفل.\n" +
                    "رصيد + تقييد فوري: عند نفاد الرصيد يُقفل التطبيق فورًا (في نظام الريلز تُكمل المقطع الحالي ثم يُقفل عند أي مقطع جديد).", 13f)); next() }
            5 -> { val d = s.desired()
                val time = d.basis == ProtectionBasis.TIME
                col.addView(ui.tv(if (time) "حدّد وقت الاستخدام" else "حدّد رصيد الريلز", 22f, true))
                val amount = ui.edit(if (time) "الوقت المسموح بالدقائق (${BudgetEngine.MIN_TIME_MIN}-${BudgetEngine.MAX_TIME_MIN})"
                    else "عدد الـReels (${BudgetEngine.MIN_LIMIT}-${BudgetEngine.MAX_LIMIT})", (if (time) d.timeMin else d.limit).toString())
                col.addView(amount)
                col.addView(ui.tv("مدة الإيقاف بالدقائق (${BudgetEngine.MIN_LOCK_MIN}-${BudgetEngine.MAX_LOCK_MIN})", 15f, true))
                val lock = ui.edit("مدة الإيقاف", d.lockMin.toString()); col.addView(lock)
                val msg = ui.tv("", 13f); col.addView(msg)
                next {
                    val v = amount.text.toString().toIntOrNull(); val l = lock.text.toString().toIntOrNull()
                    val okAmount = v != null && v in (if (time) BudgetEngine.MIN_TIME_MIN..BudgetEngine.MAX_TIME_MIN else BudgetEngine.MIN_LIMIT..BudgetEngine.MAX_LIMIT)
                    val okLock = l != null && l in BudgetEngine.MIN_LOCK_MIN..BudgetEngine.MAX_LOCK_MIN
                    if (!okAmount || !okLock) msg.text = "أدخل أرقامًا ضمن المدى المسموح."
                    else {
                        s.requestChange(d.copy(limit = if (time) d.limit else v!!, timeMin = if (time) v!! else d.timeMin, lockMin = l!!))
                        a.step++; a.render()
                    }
                } }
            else -> { col.addView(ui.tv("فعّل خدمة الوصول", 22f, true))
                col.addView(ui.tv(DISCLOSURE, 15f))
                col.addView(ui.tv("تنبيه: بعد بدء الحماية لا يمكن إيقافها، ولا تبديل النظام أثناء الإيقاف. وأي تخفيف للإعدادات (رفع الحد أو الوقت، تقصير الإيقاف، وضع أخفّ، إزالة تطبيق، تبديل النظام) يسري بعد ${PolicySettings.WEAKEN_DELAY_MS / 3_600_000L} ساعة. التشديد يسري فورًا.", 14f, true))
                val status = ui.tv("", 16f, true); col.addView(status)
                col.addView(ui.btn("فتح إعدادات الوصول") { a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
                col.addView(ui.tv("Android 13+ مع APK مثبّت يدويًا: معلومات التطبيق ⋮ ← «السماح بالإعدادات المقيّدة» أولًا.", 12f))
                val go = ui.btn("بدء الحماية") {
                    s.acceptConsent(); s.lockConfig(); s.completeOnboarding(); a.show(MainActivity.Screen.DASHBOARD)
                }
                col.addView(go)
                refresher = { val on = a.isServiceEnabled(); status.text = if (on) "✅ الخدمة مفعّلة" else "⚠️ الخدمة غير مفعّلة"; go.isEnabled = on } }
        }
        if (a.step in 1..LAST_STEP) col.addView(ui.btn("رجوع") { a.step--; a.render() })
        return refresher
    }

    fun modes() = listOf(
        "تنبيه فقط" to ProtectionMode.WARNING_ONLY,
        "رصيد + تقييد فوري" to ProtectionMode.BUDGET_LOCK)

    fun bases() = listOf(
        "حسب عدد الريلز" to ProtectionBasis.COUNT,
        "حسب الوقت (استخدام التطبيق كاملًا)" to ProtectionBasis.TIME)

    private const val DISCLOSURE =
        "يستخدم ReelGuard خدمة الوصول في Android لمعرفة التطبيق المفتوح حاليًا (Instagram / Facebook / YouTube)، ولاكتشاف الـReels / Shorts في نظام العدّ.\n\n" +
        "• تُقرأ الحدود الدنيا فقط من واجهة التطبيق، وفقط عندما يكون أحد التطبيقات المختارة هو النشط. في نظام الوقت لا يُقرأ محتوى الشاشة أصلًا.\n" +
        "• لا تسجيل للشاشة ولا لقطات.\n" +
        "• لا يُرسَل أي محتوى إلى خادم؛ التطبيق لا يملك إذن الإنترنت أصلًا.\n" +
        "• يمكنك إيقاف الخدمة من إعدادات الوصول في Android."
}
