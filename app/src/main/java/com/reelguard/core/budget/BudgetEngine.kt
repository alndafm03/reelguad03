package com.reelguard.core.budget

import com.reelguard.core.state.AppState
import com.reelguard.core.state.StateManager
import com.reelguard.core.time.TimeSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * الرصيد والدورات ومنع التكرار (§24-25). لا يعرف شيئًا عن القفل ولا عن Instagram.
 * الحد حرّ: أي رقم بين MIN_LIMIT وMAX_LIMIT (§24).
 */
class BudgetEngine(private val sm: StateManager, private val time: TimeSource) {

    companion object {
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 999
        const val MIN_TIME_MIN = 1
        const val MAX_TIME_MIN = 720
        const val MIN_LOCK_MIN = 1
        const val MAX_LOCK_MIN = 1440
        const val MAX_DAILY_KEYS = 14
    }

    enum class ConsumeResult { COUNTED, DUPLICATE, BALANCE_EMPTY }

    private val s: AppState get() = sm.state
    val remaining: Int get() = s.remaining
    val limit: Int get() = s.cycleLimit

    /**
     * رفع الحد لا يمس الدورة الجارية إن بدأ فيها الاستهلاك (يسري من الدورة التالية)،
     * أما خفضه فيسري فورًا لأنه تشديد.
     */
    fun setConfiguredLimit(limit: Int) {
        require(limit in MIN_LIMIT..MAX_LIMIT)
        sm.update {
            val untouched = !it.isLockedState && it.remaining == it.cycleLimit
            when {
                untouched -> it.copy(configuredLimit = limit, cycleLimit = limit, remaining = limit)
                limit < it.cycleLimit -> it.copy(configuredLimit = limit, cycleLimit = limit, remaining = minOf(it.remaining, limit))
                else -> it.copy(configuredLimit = limit)
            }
        }
    }

    /** نفس منطق setConfiguredLimit للوقت: خفضه فوري، ورفعه من الدورة التالية. */
    fun setConfiguredTimeMin(min: Int) {
        require(min in MIN_TIME_MIN..MAX_TIME_MIN)
        val ms = min * 60_000L
        sm.update {
            val untouched = !it.isLockedState && it.timeRemainingMs == it.cycleTimeMs
            when {
                untouched -> it.copy(configuredTimeMin = min, cycleTimeMs = ms, timeRemainingMs = ms)
                ms < it.cycleTimeMs -> it.copy(configuredTimeMin = min, cycleTimeMs = ms, timeRemainingMs = minOf(it.timeRemainingMs, ms))
                else -> it.copy(configuredTimeMin = min)
            }
        }
    }

    fun setLockMinutes(min: Int) {
        require(min in MIN_LOCK_MIN..MAX_LOCK_MIN)
        sm.update { it.copy(lockMinutes = min) }
    }

    fun isCounted(identityKey: String?): Boolean = identityKey != null && identityKey in s.countedIds

    /** identityKey == null ⇒ لا تُطبَّق قاعدة عدم التكرار (§18). */
    fun consume(identityKey: String?): ConsumeResult {
        if (isCounted(identityKey)) return ConsumeResult.DUPLICATE
        if (s.remaining <= 0) return ConsumeResult.BALANCE_EMPTY
        val today = dayKey(time.wallMs())
        sm.update { st ->
            val merged = (st.stats.daily + (today to ((st.stats.daily[today] ?: 0) + 1))).toSortedMap()
            val trimmed = merged.keys.toList().takeLast(MAX_DAILY_KEYS).associateWith { merged[it]!! }
            st.copy(
                remaining = st.remaining - 1,
                cycleStart = if (st.remaining == st.cycleLimit) time.wallMs() else st.cycleStart,
                countedIds = if (identityKey != null) st.countedIds + identityKey else st.countedIds,
                stats = st.stats.copy(daily = trimmed)
            )
        }
        return ConsumeResult.COUNTED
    }

    fun setAllowedContent(key: String?) = sm.update { it.copy(allowedContentKey = key) }

    /** يبدأ دورة جديدة بالحد المُهيَّأ. يُستدعى عند انتهاء التقييد (§25). */
    fun startNewCycle() = sm.update {
        it.copy(
            cycleId = it.cycleId + 1,
            cycleLimit = it.configuredLimit,
            remaining = it.configuredLimit,
            cycleTimeMs = it.configuredTimeMin * 60_000L,
            timeRemainingMs = it.configuredTimeMin * 60_000L,
            cycleStart = 0L,
            countedIds = emptySet(),
            allowedContentKey = null,
            stats = it.stats.copy(cycles = it.stats.cycles + 1)
        )
    }

    // ---------- إحصاءات ----------
    fun dayKey(wallMs: Long): String =
        Instant.ofEpochMilli(wallMs).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    private fun today(): LocalDate = Instant.ofEpochMilli(time.wallMs()).atZone(ZoneId.systemDefault()).toLocalDate()

    fun todayCount(): Int = s.stats.daily[today().toString()] ?: 0

    /** مجموع الأيام [fromDaysAgo .. toDaysAgo] شاملة (0 = اليوم). */
    fun countBetween(fromDaysAgo: Int, toDaysAgo: Int): Int {
        val t = today()
        return (fromDaysAgo..toDaysAgo).sumOf { s.stats.daily[t.minusDays(it.toLong()).toString()] ?: 0 }
    }

    fun weekCount(): Int = countBetween(0, 6)
    fun previousWeekCount(): Int = countBetween(7, 13)
}
