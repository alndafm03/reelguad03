package com.reelguard.ui

import android.widget.LinearLayout

/** يجب أن يطابق هذا النص التنفيذ الفعلي (§55). */
object PrivacyScreen {
    fun build(a: MainActivity, col: LinearLayout): (() -> Unit)? {
        val ui = a.ui
        col.addView(ui.tv("مركز الخصوصية", 22f, true))
        col.addView(ui.tv(
            "يُخزَّن محليًا على جهازك فقط:\n✓ العدّادات والرصيد\n✓ حالة الدورة والقفل\n✓ الإحصائيات وملخصات الجلسات (أعداد ومدد)\n" +
            "✓ مجاميع التشخيص (أرقام)\n✓ بصمات مجزّأة (hash) لهوية المحتوى لمنع التكرار داخل الدورة فقط — لا تحتوي نصًا يمكن قراءته\n\n" +
            "لا يُخزَّن:\n✓ الرسائل\n✓ الصور\n✓ الفيديوهات\n✓ تسجيلات الشاشة\n✓ شجرة Accessibility الخام\n\n" +
            "لا يُرسَل:\n✓ أي محتوى من Instagram أو Facebook أو YouTube\n✓ أي تسجيل شاشة\nالتطبيق لا يملك إذن الإنترنت.", 15f))
        col.addView(ui.btn("مسح سجل الجلسات") { clearHistory(a) })
        col.addView(ui.btn("رجوع") { a.show(MainActivity.Screen.DASHBOARD) })
        return null
    }

    private fun clearHistory(a: MainActivity) {
        a.app.history.clear()
        android.widget.Toast.makeText(a, "تم مسح سجل الجلسات", android.widget.Toast.LENGTH_SHORT).show()
    }
}
