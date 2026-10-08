package com.reelguard.core.restriction

import com.reelguard.core.budget.BudgetEngine
import com.reelguard.core.state.LockState
import com.reelguard.core.state.StateManager
import com.reelguard.core.time.TimeSource

/**
 * التقييد منفصل عن الرصيد (§26). مصدر الحقيقة للقفل هو الوقت المحفوظ لا مؤقت الذاكرة (§30، §47).
 *
 * سياسة الوقت:
 *  - داخل نفس جلسة الإقلاع: تُحسب المدة بـelapsedRealtime (لا تتأثر بتغيير الساعة/المنطقة الزمنية).
 *  - بعد إعادة التشغيل: بالساعة الفعلية مقابل lockEnd.
 */
class RestrictionEngine(
    private val sm: StateManager,
    private val budget: BudgetEngine,
    private val time: TimeSource
) {
    /** بداية نافذة التحقق (elapsed). null = لا محاولة معلّقة. تبقى في الذاكرة فقط. */
    var pendingSinceElapsed: Long? = null
        private set
    val hasPending: Boolean get() = pendingSinceElapsed != null

    fun isLocked(): Boolean = sm.state.isLockedState

    fun lockRemainingMs(): Long {
        val s = sm.state
        if (!s.isLockedState) return 0L
        val duration = (s.lockEnd - s.lockStart).coerceAtLeast(0L)
        val sameBoot = s.lockBootCount >= 0 &&
            s.lockBootCount == time.bootCount() &&
            time.elapsedMs() >= s.lockStartElapsed
        val remaining = if (sameBoot) duration - (time.elapsedMs() - s.lockStartElapsed)
        else s.lockEnd - time.wallMs()
        return remaining.coerceIn(0L, duration)
    }

    /** ينهي القفل إن انتهى وقته ويبدأ دورة جديدة. يعيد true إذا انتهى للتوّ. */
    fun refresh(): Boolean {
        if (sm.state.isLockedState && lockRemainingMs() <= 0L) {
            val dur = (sm.state.lockEnd - sm.state.lockStart).coerceAtLeast(0L)
            sm.update {
                it.copy(
                    lockState = LockState.UNLOCKED, lockReason = "", lockStart = 0, lockEnd = 0,
                    lockStartElapsed = 0, lockBootCount = -1,
                    stats = it.stats.copy(totalLockMs = it.stats.totalLockMs + dur)
                )
            }
            budget.startNewCycle()
            return true
        }
        return false
    }

    // ---------- PENDING_RESTRICTION (§28) ----------
    fun beginPending() {
        if (sm.state.isLockedState) return
        pendingSinceElapsed = time.elapsedMs()
        sm.update { it.copy(lockState = LockState.PENDING_RESTRICTION) }
    }

    fun cancelPending() {
        pendingSinceElapsed = null
        if (sm.state.lockState == LockState.PENDING_RESTRICTION)
            sm.update { it.copy(lockState = LockState.UNLOCKED) }
    }

    fun pendingElapsedMs(): Long = pendingSinceElapsed?.let { time.elapsedMs() - it } ?: 0L

    // ---------- LOCKED ----------
    fun begin(reason: String, durationMs: Long): Boolean {
        if (sm.state.isLockedState) return false
        val now = time.wallMs()
        val day = budget.dayKey(now)
        pendingSinceElapsed = null
        sm.update {
            it.copy(
                lockState = LockState.LOCKED, lockReason = reason,
                lockStart = now, lockEnd = now + durationMs,
                lockStartElapsed = time.elapsedMs(), lockBootCount = time.bootCount(),
                cycleEnd = now,
                stats = it.stats.copy(
                    lockCount = it.stats.lockCount + 1,
                    dailyLocks = (it.stats.dailyLocks + (day to ((it.stats.dailyLocks[day] ?: 0) + 1)))
                        .toSortedMap().let { m -> m.keys.toList().takeLast(BudgetEngine.MAX_DAILY_KEYS).associateWith { k -> m[k]!! } }
                )
            )
        }
        return true
    }

    // ---------- RECOVERING (§30، §41) ----------
    fun markRecovering(): Boolean {
        if (sm.state.lockState != LockState.LOCKED) return false
        sm.update { it.copy(lockState = LockState.RECOVERING) }
        return true
    }

    fun markRestored(): Boolean {
        if (sm.state.lockState != LockState.RECOVERING) return false
        sm.update { it.copy(lockState = LockState.LOCKED) }
        return true
    }
}
