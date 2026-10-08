package com.reelguard.core.policy

import com.reelguard.core.model.DuplicatePolicy
import com.reelguard.core.model.ProtectionMode
import com.reelguard.core.model.RestrictionType
import com.reelguard.core.state.AppState

/** سياسة الاستهلاك (§21): لا تُثبَّت مدة المشاهدة داخل الـCore. */
data class ConsumptionPolicy(
    val minimumActiveDurationMs: Long,
    val duplicatePolicy: DuplicatePolicy = DuplicatePolicy.TRUSTED_IDENTITY
)

/** سياسة التقييد. graceMs = 0 ⇒ التقييد فوري عند محاولة محتوى جديد بعد نفاد الرصيد. */
data class RestrictionPolicy(
    val type: RestrictionType,
    val lockDurationMs: Long,
    val graceMs: Long
)

data class ProtectionPolicy(
    val mode: ProtectionMode,
    val limit: Int,
    val consumption: ConsumptionPolicy,
    val restriction: RestrictionPolicy
)

object PolicyFactory {
    /** القيمة الافتراضية لمدة الإيقاف؛ الفعلية من الإعدادات (AppState.lockMinutes). */
    const val LOCK_MS = 60L * 60L * 1000L
    const val ACTIVE_MS = 4000L

    fun from(s: AppState): ProtectionPolicy {
        val consumption = ConsumptionPolicy(ACTIVE_MS)
        val lockMs = s.lockMinutes * 60_000L
        return when (s.mode) {
            ProtectionMode.WARNING_ONLY ->
                ProtectionPolicy(s.mode, s.cycleLimit, consumption, RestrictionPolicy(RestrictionType.WARNING, 0L, 0L))
            ProtectionMode.BUDGET_LOCK ->
                ProtectionPolicy(s.mode, s.cycleLimit, consumption, RestrictionPolicy(RestrictionType.LOCK, lockMs, 0L))
        }
    }
}
