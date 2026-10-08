package com.reelguard.core.stats

import com.reelguard.core.budget.BudgetEngine
import com.reelguard.core.session.HistoryRepository
import com.reelguard.core.session.SessionSummary
import com.reelguard.core.state.StateManager
import com.reelguard.core.time.TimeSource
import java.time.Instant
import java.time.ZoneId

data class StatsSnapshot(
    val today: Int,
    val week: Int,
    val previousWeek: Int,
    val sessionsToday: Int,
    val sessionsWeek: Int,
    val avgSessionMs: Long,
    val longestSessionMs: Long,
    val todaySessionMs: Long,
    val limitsReached: Int,
    val restrictions: Int,
    val restrictionsToday: Int,
    val totalRestrictionMs: Long,
    /** تقدير فقط: مجموع مدد التقييد (§53) */
    val estimatedRecoveredMs: Long,
    /** وضع الوقت: وقت استخدام التطبيقات المحميّة اليوم (كاملًا لا Reels فقط). */
    val todayUseMs: Long
)

/** إحصاءات محلية مشتقة من الحالة والسجل (§53-54). */
class StatsCalculator(
    private val sm: StateManager,
    private val budget: BudgetEngine,
    private val history: HistoryRepository,
    private val time: TimeSource
) {
    fun snapshot(): StatsSnapshot {
        val s = sm.state
        val now = time.wallMs()
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val all = history.sessions().toMutableList()
        s.currentSession?.let { all.add(SessionSummary(it.id, it.platform, it.startWall, it.lastActivityWall, it.count, it.interruptions)) }
        fun daysAgo(x: SessionSummary) = java.time.temporal.ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(x.startWall).atZone(zone).toLocalDate(), today)
        val week = all.filter { daysAgo(it) in 0..6 }
        val todays = all.filter { daysAgo(it) == 0L }
        return StatsSnapshot(
            today = budget.todayCount(),
            week = budget.weekCount(),
            previousWeek = budget.previousWeekCount(),
            sessionsToday = todays.size,
            sessionsWeek = week.size,
            avgSessionMs = if (week.isEmpty()) 0L else week.sumOf { it.durationMs } / week.size,
            longestSessionMs = week.maxOfOrNull { it.durationMs } ?: 0L,
            todaySessionMs = todays.sumOf { it.durationMs },
            limitsReached = s.stats.limitReached,
            restrictions = s.stats.lockCount,
            restrictionsToday = s.stats.dailyLocks[budget.dayKey(now)] ?: 0,
            totalRestrictionMs = s.stats.totalLockMs,
            estimatedRecoveredMs = s.stats.totalLockMs,
            todayUseMs = s.stats.dailyMs[budget.dayKey(now)] ?: 0L
        )
    }
}
