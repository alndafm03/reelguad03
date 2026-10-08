package com.reelguard.core.engine

import com.reelguard.core.budget.BudgetEngine
import com.reelguard.core.budget.BudgetEngine.ConsumeResult
import com.reelguard.core.budget.TimeBudget
import com.reelguard.core.diag.Diag
import com.reelguard.core.diag.Metric
import com.reelguard.core.events.ChangeReason
import com.reelguard.core.events.DomainEvent
import com.reelguard.core.health.HealthMonitor
import com.reelguard.core.model.ContentIdentity
import com.reelguard.core.model.DetectionResult
import com.reelguard.core.model.DetectionState
import com.reelguard.core.model.DuplicatePolicy
import com.reelguard.core.model.IdentityConfidence
import com.reelguard.core.model.ProtectionBasis
import com.reelguard.core.model.RestrictionType
import com.reelguard.core.policy.PolicyFactory
import com.reelguard.core.policy.ProtectionPolicy
import com.reelguard.core.recovery.RecoveryManager
import com.reelguard.core.restriction.RestrictionEngine
import com.reelguard.core.session.SessionManager
import com.reelguard.core.state.AppState
import com.reelguard.core.state.FlowState
import com.reelguard.core.state.StateManager
import com.reelguard.core.time.Countdown
import com.reelguard.core.time.TimeSource

/** أوامر العرض التي يُصدرها الـCore. الـOverlay ينفّذها فقط ولا يملك حالة (§33). */
sealed class OverlayCommand {
    data class ShowCounter(val remaining: Int, val limit: Int) : OverlayCommand()
    /** وضع الوقت: المتبقي والإجمالي بالمللي ثانية. */
    data class ShowTimeCounter(val remainingMs: Long, val totalMs: Long) : OverlayCommand()
    object HideCounter : OverlayCommand()
    data class ShowWarning(val text: String) : OverlayCommand()
    object ShowLock : OverlayCommand()
    object HideLock : OverlayCommand()
    object HideAll : OverlayCommand()
}

/** ما يحتاجه الـCore من البيئة (Android في الإنتاج، وهمي في الاختبار). */
interface CoreHost {
    fun schedule(tag: String, delayMs: Long, action: () -> Unit)
    fun cancel(tag: String)
    fun isInteractive(): Boolean
    /** اطلب من الخدمة إعادة مسح الشاشة فورًا وإرسال أحداث جديدة. */
    fun requestRevalidation()
    fun command(cmd: OverlayCommand)
}

data class DiagnosticView(
    val platformId: String,
    val platformActive: Boolean,
    val flow: FlowState,
    val detection: DetectionResult?,
    val identity: IdentityConfidence,
    val lastTransition: String,
    val timerMs: Long?,
    val remaining: Int,
    val limit: Int,
    val sessionMs: Long,
    val serviceConnected: Boolean,
    val hostAttached: Boolean
)

/**
 * Core Engine (§6): يستقبل أحداثًا عامة فقط ولا يعرف Instagram.
 * State Machine + Consumption + Session + Budget + Restriction + Recovery.
 */
class CoreEngine(
    private val sm: StateManager,
    val budget: BudgetEngine,
    val restriction: RestrictionEngine,
    val sessions: SessionManager,
    val health: HealthMonitor,
    val recovery: RecoveryManager,
    private val time: TimeSource,
    private val diag: Diag
) {
    companion object {
        const val TAG_WATCH = "watch"
        const val TAG_VALIDATE = "validate"
        const val TAG_BEAT = "beat"
        const val BEAT_MS = 1000L
        /** كل كم نبضة نطلب إعادة تحقق من التطبيق النشط (لاكتشاف مغادرته). */
        const val REVALIDATE_EVERY_BEATS = 5
        /** وضع «تنبيه فقط»: إعادة التنبيه أثناء الاستمرار بعد نفاد الوقت. */
        const val OVERUSE_REWARN_MS = 5L * 60_000L
    }

    private val timeBudget = TimeBudget(sm, budget, time)
    private var beats = 0
    private var timeWarned = false
    private var nextRewarnElapsed = 0L

    var host: CoreHost? = null
    /** مراقب للأحداث الصادرة (اختبارات/سجل). */
    var onEvent: ((DomainEvent) -> Unit)? = null

    var flow: FlowState = FlowState.IDLE
        private set
    val state: AppState get() = sm.state

    private var platformActive = false
    private var platformId = ""
    private var reelContext = false
    private var currentKey: String? = null
    private var watchKey: String? = null
    private var watchCompleted = false
    private var awaitingFinal = false
    private var watchStartElapsed = 0L
    private var lastConsumedKey: String? = null
    private var warnedKey: String? = null
    private var lastDetection: DetectionResult? = null
    private var lastIdentity: ContentIdentity = ContentIdentity.UNKNOWN
    private var lastTransition = "NONE"

    // ------------------------------------------------------------ واجهة عامة
    fun policy(): ProtectionPolicy = PolicyFactory.from(sm.state)

    /** الحماية دائمة التشغيل بعد تأكيد الإعداد الأولي؛ لا إيقاف ولا إيقاف مؤقت. */
    fun isProtectionActive(): Boolean = sm.state.configLocked

    /** معرّف المنصة النشطة حاليًا (فارغ إن لم تكن هناك منصة). */
    val activePlatform: String get() = platformId

    fun isLocked(): Boolean { refreshRestriction(); return restriction.isLocked() }

    /** هل الأساس الحالي هو الوقت؟ (لا حاجة حينها لمسح الشاشة بحثًا عن Reels). */
    fun usesReelDetection(): Boolean = sm.state.basis == ProtectionBasis.COUNT

    /** الوقت المتبقي (وضع الوقت) بالمللي ثانية، دقيق لحظيًا. */
    fun timeRemainingMs(): Long = timeBudget.remainingMs()
    fun usedTodayMs(): Long = timeBudget.usedTodayMs()

    /** هل نحتاج فعلًا لمسح شجرة Accessibility؟ (§73: تقليل العمل عند عدم الحاجة). */
    fun scanningNeeded(): Boolean = isProtectionActive() || restriction.isLocked()

    fun lockRemainingMs(): Long { refreshRestriction(); return restriction.lockRemainingMs() }

    /** صيانة دورية: انتهاء القفل، خمول الجلسة. */
    fun tick() { refreshRestriction(); sessions.tick(); refreshRefill(); syncTime() }

    fun onScreenOff() { interrupt("screen_off"); stopTime() }

    fun diagnosticView() = DiagnosticView(
        platformId, platformActive, flow, lastDetection, lastIdentity.confidence, lastTransition,
        if (watchKey != null && !watchCompleted) time.elapsedMs() - watchStartElapsed else null,
        sm.state.remaining, sm.state.cycleLimit, sessions.currentDurationMs(),
        health.serviceConnected, host != null
    )

    // ------------------------------------------------------------ الأحداث
    fun handle(e: DomainEvent) {
        try { dispatch(e) } catch (ex: Exception) {
            // §76: لا يسقط الـCore بسبب خطأ منطقي/Detector
            diag.log("CORE_ERROR ${ex.javaClass.simpleName}: ${ex.message}")
        }
    }

    private fun dispatch(e: DomainEvent) {
        when (e) {
            is DomainEvent.PlatformActive -> onPlatformActive(e.platform)
            is DomainEvent.PlatformInactive -> onPlatformInactive()
            is DomainEvent.ReelCandidate -> {
                noteDetection(e.detection)
                if (enforceLock()) return
                notConfirmed(e.detection.state.name, FlowState.REEL_DETECTING)
            }
            is DomainEvent.ReelRejected -> {
                noteDetection(e.detection)
                if (enforceLock()) return
                currentKey = null
                notConfirmed("rejected:${e.detection.contentType}", FlowState.PLATFORM_ACTIVE)
            }
            is DomainEvent.DetectionUnknown -> {
                diag.inc(Metric.ATTEMPTS); diag.inc(Metric.UNKNOWN); health.onUnknown()
                if (enforceLock()) return
                interrupt("unknown")            // §3.3: لا نعرف ⇒ لا خصم ولا قفل جديد
                cmd(OverlayCommand.HideCounter)
                flow = FlowState.UNKNOWN
            }
            is DomainEvent.ContentIdUpdated -> onIdUpdated(e)
            is DomainEvent.ContentChanged -> onContentChanged(e)
            is DomainEvent.NewContentCandidate -> onNewContentCandidate()
            is DomainEvent.ReelConfirmed -> onReelConfirmed(e)
            is DomainEvent.ServiceConnected -> onServiceConnected()
            is DomainEvent.ServiceDisconnected -> onServiceDisconnected()
            is DomainEvent.OverlayLost -> if (recovery.onOverlayLost() && platformActive) cmd(OverlayCommand.ShowLock)
            is DomainEvent.OverlayRestored -> {
                recovery.onOverlayRestored()
                if (flow == FlowState.RECOVERY) flow = FlowState.LOCKED
            }
            else -> Unit // الأحداث الصادرة لا تُعالَج هنا
        }
    }

    // ------------------------------------------------------------ المنصة
    private fun onPlatformActive(platform: String) {
        // انتقال مباشر بين تطبيقين محميّين: نُنهي سياق الأول بالكامل قبل بدء الثاني
        if (platformActive && platform != platformId) onPlatformInactive()
        platformId = platform
        if (!platformActive) {
            platformActive = true
            health.onPlatformActive()
            diag.log("PLATFORM_ACTIVE $platform")
            if (flow == FlowState.IDLE || flow == FlowState.SERVICE_UNAVAILABLE) flow = FlowState.PLATFORM_ACTIVE
        }
        if (enforceLock()) return
        if (!isProtectionActive()) cmd(OverlayCommand.HideCounter)
        syncTime()
    }

    private fun onPlatformInactive() {
        if (!platformActive && flow == FlowState.IDLE) return
        platformActive = false
        stopTime()
        reelContext = false
        interrupt("platform_inactive")
        restriction.cancelPending()
        host?.cancel(TAG_VALIDATE)
        cmd(OverlayCommand.HideAll)
        sessions.tick()
        health.onPlatformInactive()
        diag.log("PLATFORM_INACTIVE")
        flow = FlowState.IDLE
    }

    private fun notConfirmed(reason: String, next: FlowState) {
        reelContext = false
        interrupt("not_confirmed:$reason")
        restriction.cancelPending()
        cmd(OverlayCommand.HideCounter)
        flow = next
    }

    private fun noteDetection(d: DetectionResult) {
        diag.inc(Metric.ATTEMPTS)
        if (d.state == DetectionState.UNKNOWN) diag.inc(Metric.UNKNOWN)
        health.onDetection(d)
        lastDetection = d
    }

    // ------------------------------------------------------------ القفل
    private fun refreshRestriction() {
        if (restriction.refresh()) onRestrictionEnded()
    }

    private fun onRestrictionEnded() {
        publish(DomainEvent.RestrictionEnded)
        lastConsumedKey = null
        warnedKey = null
        timeWarned = false
        cmd(OverlayCommand.HideLock)
        flow = if (platformActive) FlowState.PLATFORM_ACTIVE else FlowState.IDLE
        diag.log("RESTRICTION_ENDED -> new cycle")
        syncTime()
    }

    /** إن كان القفل ساريًا يُعاد فرض الـOverlay مهما كانت نتيجة الكشف (§30). */
    private fun enforceLock(): Boolean {
        refreshRestriction()
        if (!restriction.isLocked()) return false
        interrupt("locked")
        cmd(OverlayCommand.HideCounter)
        cmd(OverlayCommand.ShowLock)
        if (flow != FlowState.RECOVERY) flow = FlowState.LOCKED
        return true
    }

    private fun startRestriction(reason: String) {
        val pol = policy()
        interrupt("restriction")
        stopTime()
        if (restriction.begin(reason, pol.restriction.lockDurationMs)) {
            diag.inc(Metric.RESTRICTIONS)
            diag.log("RESTRICTION_STARTED reason=$reason")
            publish(DomainEvent.RestrictionStarted(reason))
            sessions.end()
        }
        host?.cancel(TAG_VALIDATE)
        flow = FlowState.LOCKED
        cmd(OverlayCommand.HideCounter)
        cmd(OverlayCommand.ShowLock)
    }

    // ------------------------------------------------------------ المحتوى
    private fun onIdUpdated(e: DomainEvent.ContentIdUpdated) {
        if (watchKey == e.from) watchKey = e.to
        if (currentKey == e.from) currentKey = e.to
        if (lastConsumedKey == e.from) lastConsumedKey = e.to
        if (sm.state.allowedContentKey == e.from) budget.setAllowedContent(e.to)
    }

    private fun onContentChanged(e: DomainEvent.ContentChanged) {
        diag.inc(Metric.TRANSITIONS)
        if (e.by == ChangeReason.SAME_IDENTITY_SCROLL) diag.inc(Metric.ID_COLLISION)
        health.onTransition()
        lastTransition = "${e.from ?: "-"} -> ${e.to} (${e.by})"
        diag.log("TRANSITION $lastTransition")
        interrupt("transition")
        currentKey = e.to
    }

    private fun onNewContentCandidate() {
        if (enforceLock()) return
        if (!isProtectionActive() || sm.state.basis != ProtectionBasis.COUNT) return
        val pol = policy()
        if (pol.restriction.type != RestrictionType.LOCK) return
        if (!reelContext || sm.state.remaining > 0) return
        // الرصيد صفر وهناك محاولة لمحتوى جديد
        if (pol.restriction.graceMs <= 0L) { startRestriction("limit_reached"); return }
        beginPendingIfNeeded(pol)
    }

    private fun onReelConfirmed(e: DomainEvent.ReelConfirmed) {
        noteDetection(e.detection)
        diag.inc(Metric.CONFIRMED)
        lastIdentity = e.identity
        if (enforceLock()) return
        if (sm.state.basis != ProtectionBasis.COUNT) return          // وضع الوقت: الـReels لا تُحتسب
        reelContext = true
        currentKey = e.contentKey
        if (!isProtectionActive()) {
            interrupt("protection_off"); cmd(OverlayCommand.HideCounter); flow = FlowState.REEL_ACTIVE; return
        }
        val pol = policy()

        if (!e.active) { interrupt("not_active"); flow = FlowState.REEL_DETECTING; return }

        sessions.touch(platformId)
        cmd(OverlayCommand.ShowCounter(sm.state.remaining, sm.state.cycleLimit))
        if (flow != FlowState.WATCHING && flow != FlowState.WAITING_FOR_NEXT &&
            flow != FlowState.LIMIT_REACHED && flow != FlowState.CURRENT_CONTENT_ALLOWED &&
            flow != FlowState.NEW_REEL_ATTEMPT) flow = FlowState.REEL_ACTIVE

        if (sm.state.remaining <= 0) { onZeroBalance(e, pol); return }

        val key = e.contentKey
        if (key == lastConsumedKey) { flow = FlowState.WAITING_FOR_NEXT; return }   // احتُسب سابقًا
        if (watchKey == key) { if (awaitingFinal) commit(e, pol); return }
        startWatch(e, pol)
    }

    // ------------------------------------------------------------ الاستهلاك (§22)
    private fun startWatch(e: DomainEvent.ReelConfirmed, pol: ProtectionPolicy) {
        interrupt("switch")
        val trusted = pol.consumption.duplicatePolicy == DuplicatePolicy.TRUSTED_IDENTITY && e.identity.dedupable
        if (trusted && budget.isCounted(e.identity.key)) {         // A → B → A
            lastConsumedKey = e.contentKey
            flow = FlowState.WAITING_FOR_NEXT
            diag.log("DUPLICATE_SKIP")
            return
        }
        watchKey = e.contentKey; watchCompleted = false; awaitingFinal = false
        watchStartElapsed = time.elapsedMs()
        diag.inc(Metric.WATCH_STARTS)
        diag.inc(if (e.identity.dedupable) Metric.ID_OK else Metric.ID_FAIL)
        diag.log("WATCH_STARTED key=${e.contentKey} identity=${e.identity.confidence}")
        publish(DomainEvent.WatchStarted(e.contentKey))
        flow = FlowState.WATCHING
        host?.schedule(TAG_WATCH, pol.consumption.minimumActiveDurationMs) { onWatchTimer() }
    }

    private fun onWatchTimer() {
        if (watchKey == null || watchCompleted) return
        // إعادة تحقق أخيرة من أن الشرط ما زال صالحًا قبل الخصم
        awaitingFinal = true
        host?.requestRevalidation()
    }

    private fun commit(e: DomainEvent.ReelConfirmed, pol: ProtectionPolicy) {
        awaitingFinal = false
        if (host?.isInteractive() == false) { interrupt("not_interactive"); return }
        val idKey = if (pol.consumption.duplicatePolicy == DuplicatePolicy.TRUSTED_IDENTITY && e.identity.dedupable)
            e.identity.key else null
        val r = budget.consume(idKey)
        watchCompleted = true
        lastConsumedKey = e.contentKey
        publish(DomainEvent.WatchCompleted(e.contentKey))
        diag.log("REEL_CONSUME result=$r remaining=${sm.state.remaining}")
        when (r) {
            ConsumeResult.COUNTED -> {
                diag.inc(Metric.CONSUMPTIONS)
                sessions.onConsumed()
                publish(DomainEvent.ContentConsumed(e.contentKey, e.identity.dedupable))
                flow = FlowState.CONSUMED
                cmd(OverlayCommand.ShowCounter(sm.state.remaining, sm.state.cycleLimit))
                if (sm.state.remaining <= 0) onLimitReached(e.contentKey) else flow = FlowState.WAITING_FOR_NEXT
            }
            ConsumeResult.DUPLICATE -> flow = FlowState.WAITING_FOR_NEXT
            ConsumeResult.BALANCE_EMPTY -> flow = FlowState.LIMIT_REACHED
        }
    }

    private fun onLimitReached(key: String) {
        budget.setAllowedContent(key)
        sm.update { it.copy(stats = it.stats.copy(limitReached = it.stats.limitReached + 1)) }
        publish(DomainEvent.LimitReached)
        flow = FlowState.CURRENT_CONTENT_ALLOWED            // §29: لا قفل فوري
        warnedKey = key
        startRefillIfWarning()
        val lim = sm.state.cycleLimit
        val text = if (policy().restriction.type == RestrictionType.WARNING)
            "وصلت إلى حدّك ($lim / $lim). هذا تنبيه فقط."
        else "انتهى رصيدك ($lim / $lim). يمكنك إكمال هذا المحتوى؛ الانتقال إلى غيره سيفعّل التقييد."
        cmd(OverlayCommand.ShowWarning(text))
    }

    // ------------------------------------------------------------ الصفر ومحاولة محتوى جديد (§28-29)
    private fun onZeroBalance(e: DomainEvent.ReelConfirmed, pol: ProtectionPolicy) {
        val key = e.contentKey
        var allowed = sm.state.allowedContentKey
        if (allowed == null) { budget.setAllowedContent(key); allowed = key }
        if (pol.restriction.type == RestrictionType.WARNING) {
            flow = FlowState.LIMIT_REACHED
            startRefillIfWarning()
            if (warnedKey != key) {
                warnedKey = key
                cmd(OverlayCommand.ShowWarning("تجاوزت حدّك. هذا تنبيه فقط."))
            }
            return
        }
        if (key == allowed) {
            restriction.cancelPending()
            host?.cancel(TAG_VALIDATE)
            flow = FlowState.CURRENT_CONTENT_ALLOWED
            return
        }
        // محتوى جديد مؤكد بعد الصفر
        flow = FlowState.NEW_REEL_ATTEMPT
        beginPendingIfNeeded(pol)
        if (pol.restriction.graceMs <= 0L || restriction.pendingElapsedMs() >= pol.restriction.graceMs)
            startRestriction("limit_reached")
    }

    private fun beginPendingIfNeeded(pol: ProtectionPolicy) {
        if (restriction.hasPending) return
        restriction.beginPending()
        diag.log("NEW_REEL_ATTEMPT -> pending")
        publish(DomainEvent.NewReelAttempted)
        host?.schedule(TAG_VALIDATE, pol.restriction.graceMs) { host?.requestRevalidation() }
    }

    // ------------------------------------------------------------ الخدمة والاستعادة
    private fun onServiceConnected() {
        health.serviceConnected = true
        val restore = recovery.onServiceConnected()
        flow = if (restore) FlowState.RECOVERY else FlowState.IDLE
        diag.log("SERVICE_CONNECTED restore=$restore")
    }

    private fun onServiceDisconnected() {
        health.serviceConnected = false
        stopTime()
        interrupt("service_disconnected")
        host?.cancel(TAG_VALIDATE)
        recovery.onServiceDisconnected()
        flow = FlowState.SERVICE_UNAVAILABLE
    }

    // ------------------------------------------------------------ وضع الوقت
    private fun timeShouldRun(): Boolean =
        isProtectionActive() && sm.state.basis == ProtectionBasis.TIME && platformActive &&
            !restriction.isLocked() && host?.isInteractive() != false

    /** يضبط تشغيل/إيقاف عدّاد الوقت حسب الحالة الراهنة. آمن للاستدعاء المتكرر. */
    private fun syncTime() {
        if (timeShouldRun()) {
            if (!timeBudget.running) {
                timeBudget.start()
                beats = 0
                cmd(OverlayCommand.ShowTimeCounter(timeBudget.remainingMs(), sm.state.cycleTimeMs))
                host?.schedule(TAG_BEAT, BEAT_MS) { onBeat() }
                if (timeBudget.remainingMs() <= 0L) onTimeExhausted()
            }
        } else if (timeBudget.running) {
            stopTime()
            if (platformActive) cmd(OverlayCommand.HideCounter)
        }
    }

    private fun stopTime() {
        if (timeBudget.running) timeBudget.stop()
        host?.cancel(TAG_BEAT)
    }

    private fun onBeat() {
        if (!timeBudget.running) return
        if (!timeShouldRun()) { syncTime(); return }
        beats++
        if (timeBudget.persistDue()) timeBudget.checkpoint()
        val rem = timeBudget.remainingMs()
        cmd(OverlayCommand.ShowTimeCounter(rem, sm.state.cycleTimeMs))
        if (rem <= 0L) onTimeExhausted()
        if (!timeBudget.running) return                       // بدأ قفل
        if (beats % REVALIDATE_EVERY_BEATS == 0) host?.requestRevalidation()
        if (timeBudget.running) host?.schedule(TAG_BEAT, BEAT_MS) { onBeat() }
    }

    /** نفد الوقت: قفل فوري (رصيد + تقييد فوري) أو تنبيه فقط. */
    private fun onTimeExhausted() {
        timeBudget.checkpoint()
        val pol = policy()
        if (pol.restriction.type == RestrictionType.LOCK) {
            sm.update { it.copy(stats = it.stats.copy(limitReached = it.stats.limitReached + 1)) }
            publish(DomainEvent.LimitReached)
            startRestriction("time_up")
            return
        }
        startRefillIfWarning()
        val now = time.elapsedMs()
        if (!timeWarned || now >= nextRewarnElapsed) {
            if (!timeWarned) { sm.update { it.copy(stats = it.stats.copy(limitReached = it.stats.limitReached + 1)) }; publish(DomainEvent.LimitReached) }
            timeWarned = true
            nextRewarnElapsed = now + OVERUSE_REWARN_MS
            val min = sm.state.cycleTimeMs / 60_000L
            cmd(OverlayCommand.ShowWarning("انتهى وقتك المحدد ($min دقيقة). هذا تنبيه فقط."))
        }
    }

    /** «تنبيه فقط»: بعد نفاد الرصيد تُعاد التعبئة بعد مدة الإيقاف (لا قفل). */
    private fun startRefillIfWarning() {
        if (policy().restriction.type != RestrictionType.WARNING || sm.state.refill != null) return
        sm.update { it.copy(refill = Countdown.start(it.lockMinutes * 60_000L, time)) }
        diag.log("REFILL_STARTED")
    }

    private fun refreshRefill() {
        val r = sm.state.refill ?: return
        if (r.remainingMs(time) > 0L) return
        timeBudget.checkpoint()
        sm.update { it.copy(refill = null) }
        budget.startNewCycle()
        warnedKey = null; lastConsumedKey = null; timeWarned = false
        diag.log("REFILL_DONE -> new cycle")
        if (timeBudget.running) cmd(OverlayCommand.ShowTimeCounter(timeBudget.remainingMs(), sm.state.cycleTimeMs))
    }

    // ------------------------------------------------------------ مساعدات
    private fun interrupt(reason: String) {
        if (watchKey != null && !watchCompleted) {
            diag.inc(Metric.WATCH_INTERRUPTIONS)
            diag.log("WATCH_INTERRUPTED ($reason) key=$watchKey")
            publish(DomainEvent.WatchInterrupted(reason))
            sessions.onInterrupted()
        }
        host?.cancel(TAG_WATCH)
        watchKey = null; watchCompleted = false; awaitingFinal = false
    }

    private fun cmd(c: OverlayCommand) { host?.command(c) }
    private fun publish(e: DomainEvent) { onEvent?.invoke(e) }
}
