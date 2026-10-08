package com.reelguard.core.budget

import com.reelguard.core.state.StateManager
import com.reelguard.core.time.TimeSource

/**
 * محاسبة وقت الاستخدام (وضع الوقت). يعدّ فقط بين start() وstop() بالساعة المنقضية (elapsedRealtime)،
 * فلا يتأثر بتغيير ساعة الجهاز. الحالة تُحفظ دوريًا (PERSIST_EVERY_MS) وعند الإيقاف؛
 * والمتبقي الفعلي = المحفوظ − ما انقضى منذ آخر نقطة حفظ.
 */
class TimeBudget(
    private val sm: StateManager,
    private val budget: BudgetEngine,
    private val time: TimeSource
) {
    companion object { const val PERSIST_EVERY_MS = 10_000L }

    private var mark: Long? = null
    val running: Boolean get() = mark != null

    private fun unaccounted(): Long = mark?.let { (time.elapsedMs() - it).coerceAtLeast(0L) } ?: 0L

    fun remainingMs(): Long = (sm.state.timeRemainingMs - unaccounted()).coerceAtLeast(0L)

    /** استخدام اليوم بالمللي ثانية (يشمل غير المحفوظ بعد). */
    fun usedTodayMs(): Long = (sm.state.stats.dailyMs[budget.dayKey(time.wallMs())] ?: 0L) + unaccounted()

    fun start() { if (mark == null) mark = time.elapsedMs() }

    fun persistDue(): Boolean = unaccounted() >= PERSIST_EVERY_MS

    /** يحوّل ما انقضى منذ آخر نقطة إلى الحالة المحفوظة. */
    fun checkpoint() {
        val m = mark ?: return
        val now = time.elapsedMs()
        val delta = (now - m).coerceAtLeast(0L)
        if (delta == 0L) return
        mark = now
        val day = budget.dayKey(time.wallMs())
        sm.update { st ->
            val merged = (st.stats.dailyMs + (day to ((st.stats.dailyMs[day] ?: 0L) + delta))).toSortedMap()
            val trimmed = merged.keys.toList().takeLast(BudgetEngine.MAX_DAILY_KEYS).associateWith { merged[it]!! }
            st.copy(timeRemainingMs = (st.timeRemainingMs - delta).coerceAtLeast(0L), stats = st.stats.copy(dailyMs = trimmed))
        }
    }

    fun stop() { checkpoint(); mark = null }
}
