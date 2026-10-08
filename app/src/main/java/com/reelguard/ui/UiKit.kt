package com.reelguard.ui

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.reelguard.core.health.Level
import java.util.Locale

/** مساعدات بناء الواجهة برمجيًا (بدون XML/مكتبات خارجية). */
class UiKit(private val ctx: Context) {
    fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun tv(text: String, sp: Float = 15f, bold: Boolean = false) = TextView(ctx).apply {
        this.text = text; textSize = sp
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(6))
    }

    fun btn(text: String, onClick: () -> Unit) = Button(ctx).apply { this.text = text; setOnClickListener { onClick() } }

    fun edit(hint: String, value: String) = EditText(ctx).apply {
        this.hint = hint; setText(value); inputType = InputType.TYPE_CLASS_NUMBER
    }

    /** RadioGroup عمودي؛ الـid = index. */
    fun <T> radio(options: List<Pair<String, T>>, selected: T?, horizontal: Boolean = false, onSelect: (T) -> Unit) =
        RadioGroup(ctx).apply {
            orientation = if (horizontal) RadioGroup.HORIZONTAL else RadioGroup.VERTICAL
            options.forEachIndexed { i, (label, _) -> addView(RadioButton(ctx).apply { id = 100 + i; text = label }) }
            val idx = options.indexOfFirst { it.second == selected }
            if (idx >= 0) check(100 + idx)
            setOnCheckedChangeListener { _, id -> options.getOrNull(id - 100)?.let { onSelect(it.second) } }
        }

    /** قائمة مربعات اختيار متعددة؛ options = (نص، معرّف). */
    fun checks(options: List<Pair<String, String>>, selected: Set<String>, onChange: (Set<String>) -> Unit) =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val current = selected.toMutableSet()
            options.forEach { (label, id) ->
                addView(CheckBox(ctx).apply {
                    text = label; isChecked = id in current
                    setOnCheckedChangeListener { _, on -> if (on) current.add(id) else current.remove(id); onChange(current.toSet()) }
                })
            }
        }

    fun dot(l: Level) = when (l) { Level.OK -> "🟢"; Level.WARN -> "🟠"; Level.OFF -> "⚪" }

    fun fmtMs(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
    }

    /** نصّ التخفيف المجدول (إن وُجد). */
    fun pendingText(st: com.reelguard.core.state.AppState, remainingMs: Long): String {
        val p = st.pending ?: return ""
        val parts = ArrayList<String>()
        p.basis?.let { parts.add("النظام ← ${basisLabel(it)}") }
        p.mode?.let { parts.add("الوضع ← ${modeLabel(it)}") }
        p.limit?.let { parts.add("عدد الريلز ← $it") }
        p.timeMin?.let { parts.add("الوقت ← $it د") }
        p.lockMin?.let { parts.add("مدة الإيقاف ← $it د") }
        if (p.removePlatforms.isNotEmpty()) parts.add("إزالة: " + p.removePlatforms.joinToString { id ->
            com.reelguard.platform.common.AppPlatform.byId(id)?.label ?: id })
        return "⏳ تغيير مُخفِّف مجدول (${parts.joinToString(" • ")}) يسري بعد ${fmtMs(remainingMs)}"
    }

    fun modeLabel(m: com.reelguard.core.model.ProtectionMode) = when (m) {
        com.reelguard.core.model.ProtectionMode.WARNING_ONLY -> "تنبيه فقط"
        com.reelguard.core.model.ProtectionMode.BUDGET_LOCK -> "رصيد + تقييد فوري"
    }

    fun basisLabel(b: com.reelguard.core.model.ProtectionBasis) = when (b) {
        com.reelguard.core.model.ProtectionBasis.COUNT -> "حسب عدد الريلز"
        com.reelguard.core.model.ProtectionBasis.TIME -> "حسب الوقت"
    }

    fun minutes(ms: Long) = "${ms / 60000} دقيقة"
}
