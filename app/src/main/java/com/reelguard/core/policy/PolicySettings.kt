package com.reelguard.core.policy

import com.reelguard.core.budget.BudgetEngine
import com.reelguard.core.model.ProtectionBasis
import com.reelguard.core.model.ProtectionMode
import com.reelguard.core.state.AppState
import com.reelguard.core.state.PendingChange
import com.reelguard.core.state.StateManager
import com.reelguard.core.time.Countdown
import com.reelguard.core.time.TimeSource

/** مجموعة الإعدادات القابلة للتعديل كلها. */
data class Config(
    val basis: ProtectionBasis,
    val mode: ProtectionMode,
    val limit: Int,
    val timeMin: Int,
    val lockMin: Int,
    val platforms: Set<String>
)

/**
 * تعديل الإعدادات من الواجهة.
 *  - لا يوجد إيقاف للحماية ولا إيقاف مؤقت.
 *  - قبل التأكيد الأول (lockConfig) الإعداد حرّ.
 *  - بعده: التشديد يسري فورًا، والتخفيف يُجدوَل ويسري بعد WEAKEN_DELAY_MS (حتى أثناء القفل):
 *      تخفيف = وضع أخفّ، عدد ريلز أكبر، وقت مسموح أطول، مدة إيقاف أقصر، إزالة تطبيق، أو تبديل الأساس (عدد ↔ وقت).
 *  - تبديل الأساس ممنوع تمامًا أثناء الإيقاف (القفل)، ولا يسري مجدوله إلا بعد انتهائه.
 */
class PolicySettings(
    private val sm: StateManager,
    private val budget: BudgetEngine,
    private val time: TimeSource
) {
    companion object {
        /** مدة الانتظار قبل سريان أي تخفيف. غيّرها هنا فقط. */
        const val WEAKEN_DELAY_MS = 24L * 60L * 60L * 1000L
    }

    enum class Outcome { NO_CHANGE, APPLIED, QUEUED, BASIS_BLOCKED }

    val state: AppState get() = sm.state

    fun acceptConsent() = sm.update { it.copy(consentGiven = true) }
    fun completeOnboarding() = sm.update { it.copy(onboardingDone = true) }
    fun setShowCounter(on: Boolean) = sm.update { it.copy(showCounter = on) }
    fun setDebug(on: Boolean) = sm.update { it.copy(debugLogging = on) }

    /** يؤكد الإعداد الأولي: من هنا تبدأ الحماية وتُؤجَّل التخفيفات. */
    fun lockConfig() = sm.update { it.copy(configLocked = true) }

    /** الإعدادات المرغوبة: الحالية + ما هو مجدول. تُعرض في نموذج الإعدادات. */
    fun desired(): Config {
        val s = sm.state; val p = s.pending
        return Config(
            basis = p?.basis ?: s.basis,
            mode = p?.mode ?: s.mode,
            limit = p?.limit ?: s.configuredLimit,
            timeMin = p?.timeMin ?: s.configuredTimeMin,
            lockMin = p?.lockMin ?: s.lockMinutes,
            platforms = s.enabledPlatforms - (p?.removePlatforms ?: emptySet())
        )
    }
    fun desiredMode(): ProtectionMode = desired().mode
    fun desiredLimit(): Int = desired().limit
    fun desiredPlatforms(): Set<String> = desired().platforms

    fun pendingRemainingMs(): Long? = sm.state.pending?.timer?.remainingMs(time)

    /** تعديل الوضع/الحد/التطبيقات فقط (يُبقي بقية الإعدادات على قيمها المرغوبة). */
    fun requestChange(mode: ProtectionMode, limit: Int, platforms: Set<String>): Outcome =
        requestChange(desired().copy(mode = mode, limit = limit, platforms = platforms))

    /** يطبّق الطلب كاملًا (الحالة المرغوبة). يعيد ما حدث. */
    fun requestChange(c: Config): Outcome {
        val s = sm.state
        val limit = c.limit.coerceIn(BudgetEngine.MIN_LIMIT, BudgetEngine.MAX_LIMIT)
        val timeMin = c.timeMin.coerceIn(BudgetEngine.MIN_TIME_MIN, BudgetEngine.MAX_TIME_MIN)
        val lockMin = c.lockMin.coerceIn(BudgetEngine.MIN_LOCK_MIN, BudgetEngine.MAX_LOCK_MIN)
        val platforms = c.platforms.intersect(AppState.DEFAULT_PLATFORMS).ifEmpty { s.enabledPlatforms }

        if (!s.configLocked) {                       // الإعداد الأولي حرّ
            sm.update { it.copy(basis = c.basis, mode = c.mode, enabledPlatforms = platforms, lockMinutes = lockMin, pending = null) }
            budget.setConfiguredLimit(limit)
            budget.setConfiguredTimeMin(timeMin)
            return Outcome.APPLIED
        }

        // تبديل الأساس: ممنوع أثناء الإيقاف (يبقى المجدول السابق إن وُجد ولا يُقبل جديد)
        var blocked = false
        var basis = c.basis
        if (s.isLockedState && basis != s.basis) {
            val keep = s.pending?.basis ?: s.basis
            if (basis != keep) blocked = true
            basis = keep
        }
        val delBasis = if (basis != s.basis) basis else null      // أي تبديل أساس يُعدّ تخفيفًا ⇒ مجدول

        var immMode = s.mode; var delMode: ProtectionMode? = null
        if (c.mode != s.mode) { if (c.mode.strength > s.mode.strength) immMode = c.mode else delMode = c.mode }

        var immLimit = s.configuredLimit; var delLimit: Int? = null
        if (limit < s.configuredLimit) immLimit = limit else if (limit > s.configuredLimit) delLimit = limit

        var immTime = s.configuredTimeMin; var delTime: Int? = null
        if (timeMin < s.configuredTimeMin) immTime = timeMin else if (timeMin > s.configuredTimeMin) delTime = timeMin

        var immLock = s.lockMinutes; var delLock: Int? = null
        if (lockMin > s.lockMinutes) immLock = lockMin else if (lockMin < s.lockMinutes) delLock = lockMin

        val added = platforms - s.enabledPlatforms
        val removed = s.enabledPlatforms - platforms

        val old = s.pending
        val pending = if (delBasis != null || delMode != null || delLimit != null || delTime != null || delLock != null || removed.isNotEmpty()) {
            val same = old != null && old.basis == delBasis && old.mode == delMode && old.limit == delLimit &&
                old.timeMin == delTime && old.lockMin == delLock && old.removePlatforms == removed
            PendingChange(
                mode = delMode, limit = delLimit, removePlatforms = removed, basis = delBasis,
                timeMin = delTime, lockMin = delLock,
                timer = if (same) old!!.timer else Countdown.start(WEAKEN_DELAY_MS, time)
            )
        } else null

        val changed = immMode != s.mode || immLimit != s.configuredLimit || immTime != s.configuredTimeMin ||
            immLock != s.lockMinutes || added.isNotEmpty() || pending != old
        sm.update { it.copy(mode = immMode, lockMinutes = immLock, enabledPlatforms = it.enabledPlatforms + added, pending = pending) }
        if (immLimit != s.configuredLimit) budget.setConfiguredLimit(immLimit)
        if (immTime != s.configuredTimeMin) budget.setConfiguredTimeMin(immTime)
        return when {
            blocked -> Outcome.BASIS_BLOCKED
            !changed -> Outcome.NO_CHANGE
            pending != null -> Outcome.QUEUED
            else -> Outcome.APPLIED
        }
    }

    /** إلغاء التخفيف المجدول (إلغاؤه تشديد، فهو مسموح دائمًا). */
    fun cancelPending() = sm.update { it.copy(pending = null) }

    /**
     * يطبّق التخفيف المجدول إن انتهى مؤقته. يعيد true إن طُبِّق شيء.
     * تبديل الأساس لا يُطبَّق أثناء القفل: يبقى مجدولًا وينتظر انتهاءه.
     */
    fun applyDue(): Boolean {
        val p = sm.state.pending ?: return false
        if (p.timer.remainingMs(time) > 0L) return false
        val locked = sm.state.isLockedState
        if (locked && p.basis != null && !p.hasNonBasis) return false     // لا شيء قابل للتطبيق الآن
        val applyBasis = p.basis != null && !locked
        sm.update { st ->
            st.copy(
                mode = p.mode ?: st.mode,
                lockMinutes = p.lockMin ?: st.lockMinutes,
                basis = if (applyBasis) p.basis!! else st.basis,
                enabledPlatforms = (st.enabledPlatforms - p.removePlatforms).ifEmpty { st.enabledPlatforms },
                pending = if (p.basis != null && locked) PendingChange(basis = p.basis, timer = p.timer) else null
            )
        }
        p.limit?.let { budget.setConfiguredLimit(it) }
        p.timeMin?.let { budget.setConfiguredTimeMin(it) }
        return true
    }
}
