package com.reelguard.core.state

import com.reelguard.core.model.ProtectionBasis
import com.reelguard.core.model.ProtectionMode
import com.reelguard.core.time.Countdown

enum class LockState { UNLOCKED, PENDING_RESTRICTION, LOCKED, RECOVERING }

/** آلة الحالة الموحدة (§35). */
enum class FlowState {
    IDLE, PLATFORM_ACTIVE, REEL_DETECTING, REEL_ACTIVE, WATCHING, CONSUMED, WAITING_FOR_NEXT,
    LIMIT_REACHED, CURRENT_CONTENT_ALLOWED, NEW_REEL_ATTEMPT, LOCKED,
    UNKNOWN, SERVICE_UNAVAILABLE, RECOVERY
}

data class Stats(
    val lockCount: Int = 0,
    val limitReached: Int = 0,
    val cycles: Int = 0,
    val totalLockMs: Long = 0L,
    /** yyyy-MM-dd -> عدد الوحدات المحتسبة (آخر 14 يومًا) */
    val daily: Map<String, Int> = emptyMap(),
    /** yyyy-MM-dd -> عدد مرات التقييد */
    val dailyLocks: Map<String, Int> = emptyMap(),
    /** yyyy-MM-dd -> مللي ثانية استخدام التطبيقات المحميّة (وضع الوقت) */
    val dailyMs: Map<String, Long> = emptyMap()
)

data class SessionState(
    val id: Long,
    val platform: String,
    val startWall: Long,
    val lastActivityWall: Long,
    val count: Int = 0,
    val interruptions: Int = 0
)

/**
 * تخفيف مؤجَّل: كل تغيير يُضعف الحماية (وضع أخفّ، حد أعلى، إزالة تطبيق) ينتظر حتى ينتهي المؤقت.
 * التشديد يسري فورًا ولا يمرّ من هنا.
 */
data class PendingChange(
    val mode: ProtectionMode? = null,
    val limit: Int? = null,
    val removePlatforms: Set<String> = emptySet(),
    val timer: Countdown,
    val basis: ProtectionBasis? = null,
    val timeMin: Int? = null,
    val lockMin: Int? = null
) {
    /** هل فيه تغيير غير تبديل الأساس؟ */
    val hasNonBasis: Boolean get() = mode != null || limit != null || timeMin != null || lockMin != null || removePlatforms.isNotEmpty()
}

/** الحالة الدائمة (§39). المصدر الوحيد للحقيقة (§37). */
data class AppState(
    val consentGiven: Boolean = false,
    val onboardingDone: Boolean = false,
    /** بعد التأكيد الأول تصبح أي إعدادات مُخفِّفة مؤجَّلة. قبله الإعداد حرّ. */
    val configLocked: Boolean = false,
    /** التطبيقات المحميّة (معرّفات AppPlatform). */
    val enabledPlatforms: Set<String> = DEFAULT_PLATFORMS,
    val pending: PendingChange? = null,
    val mode: ProtectionMode = ProtectionMode.BUDGET_LOCK,
    /** أساس الاحتساب: عدد الـReels أو الوقت. لا يُبدَّل أثناء الإيقاف. */
    val basis: ProtectionBasis = ProtectionBasis.COUNT,
    /** وضع الوقت: المدة المسموحة (دقائق) المُهيَّأة، ومدة الدورة الجارية، والمتبقي منها (ms). */
    val configuredTimeMin: Int = 30,
    val cycleTimeMs: Long = 30L * 60_000L,
    val timeRemainingMs: Long = 30L * 60_000L,
    /** مدة الإيقاف (دقائق): مدة القفل، وفي وضع «تنبيه فقط» مدة إعادة تعبئة الرصيد. */
    val lockMinutes: Int = 60,
    /** وضع «تنبيه فقط»: مؤقت إعادة التعبئة بعد نفاد الرصيد. */
    val refill: Countdown? = null,
    val configuredLimit: Int = 30,
    val cycleLimit: Int = 30,
    val remaining: Int = 30,
    val cycleId: Long = 1L,
    val cycleStart: Long = 0L,
    val cycleEnd: Long = 0L,
    val countedIds: Set<String> = emptySet(),
    /** المحتوى الذي يُسمح بإكماله بعد بلوغ الصفر (§29) */
    val allowedContentKey: String? = null,
    val lockState: LockState = LockState.UNLOCKED,
    val lockReason: String = "",
    val lockStart: Long = 0L,
    val lockEnd: Long = 0L,
    val lockStartElapsed: Long = 0L,
    val lockBootCount: Int = -1,
    val debugLogging: Boolean = false,
    val showCounter: Boolean = true,
    val currentSession: SessionState? = null,
    val nextSessionId: Long = 1L,
    val stats: Stats = Stats()
) {
    val isLockedState: Boolean
        get() = lockState == LockState.LOCKED || lockState == LockState.RECOVERING

    companion object {
        val DEFAULT_PLATFORMS = setOf("instagram", "facebook", "youtube")
    }

    /** يصلح أي حالة تالفة/غير متسقة (§41: State partially unavailable). */
    fun sanitized(): AppState {
        val cl = configuredLimit.coerceIn(1, 999)
        val cyl = cycleLimit.coerceIn(1, 999)
        val ctm = configuredTimeMin.coerceIn(1, 720)
        val cyt = cycleTimeMs.coerceIn(60_000L, 720L * 60_000L)
        var ls = if (lockState == LockState.PENDING_RESTRICTION) LockState.UNLOCKED else lockState
        if ((ls == LockState.LOCKED || ls == LockState.RECOVERING) && lockEnd <= 0L) ls = LockState.UNLOCKED
        return copy(
            configuredLimit = cl, cycleLimit = cyl, remaining = remaining.coerceIn(0, cyl),
            configuredTimeMin = ctm, cycleTimeMs = cyt, timeRemainingMs = timeRemainingMs.coerceIn(0L, cyt),
            lockMinutes = lockMinutes.coerceIn(1, 1440),
            lockState = ls,
            enabledPlatforms = enabledPlatforms.intersect(DEFAULT_PLATFORMS).ifEmpty { DEFAULT_PLATFORMS }
        )
    }
}

interface StateRepository {
    fun load(): AppState
    fun save(state: AppState)
}

/** Native State Manager (§37): يملك الحالة؛ الـUI والـOverlay يقرآن فقط. */
class StateManager(private val repo: StateRepository) {
    var state: AppState = try { repo.load().sanitized() } catch (e: Exception) { AppState() }
        private set

    fun update(f: (AppState) -> AppState) {
        state = f(state)
        repo.save(state)
    }
}
