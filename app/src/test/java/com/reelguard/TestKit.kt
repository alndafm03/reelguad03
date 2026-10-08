package com.reelguard

import com.reelguard.core.budget.BudgetEngine
import com.reelguard.core.diag.Diag
import com.reelguard.core.engine.CoreEngine
import com.reelguard.core.engine.CoreHost
import com.reelguard.core.engine.OverlayCommand
import com.reelguard.core.events.DomainEvent
import com.reelguard.core.health.HealthMonitor
import com.reelguard.core.model.*
import com.reelguard.core.recovery.RecoveryManager
import com.reelguard.core.restriction.RestrictionEngine
import com.reelguard.core.session.InMemoryHistoryRepository
import com.reelguard.core.session.SessionManager
import com.reelguard.core.state.AppState
import com.reelguard.core.state.StateManager
import com.reelguard.core.state.StateRepository
import com.reelguard.core.time.TimeSource

class FakeRepo(var saved: AppState? = null) : StateRepository {
    override fun load() = saved ?: AppState()
    override fun save(state: AppState) { saved = state }
}

class FakeTime : TimeSource {
    var wall = 1_700_000_000_000L
    var elapsed = 1_000_000L
    var boot = 1
    override fun wallMs() = wall
    override fun elapsedMs() = elapsed
    override fun bootCount() = boot
    fun advance(ms: Long) { wall += ms; elapsed += ms }
}

class CountingDiag : Diag {
    val counters = HashMap<String, Int>()
    val lines = ArrayList<String>()
    override fun inc(key: String) { counters[key] = (counters[key] ?: 0) + 1 }
    override fun log(msg: String) { lines.add(msg) }
}

/** مضيف وهمي: مؤقتات يدوية + تسجيل أوامر الـOverlay. */
class FakeHost(private val time: FakeTime) : CoreHost {
    private class T(val at: Long, val tag: String, val action: () -> Unit)
    private val timers = ArrayList<T>()
    val commands = ArrayList<OverlayCommand>()
    var interactive = true
    var revalidations = 0
    var onRevalidate: () -> Unit = {}

    override fun schedule(tag: String, delayMs: Long, action: () -> Unit) {
        cancel(tag); timers.add(T(time.elapsed + delayMs, tag, action))
    }
    override fun cancel(tag: String) { timers.removeAll { it.tag == tag } }
    override fun isInteractive() = interactive
    override fun requestRevalidation() { revalidations++; onRevalidate() }
    override fun command(cmd: OverlayCommand) { commands.add(cmd) }

    /** يمرّر الزمن ويشغّل المؤقتات المستحقة بالترتيب. */
    fun advance(ms: Long) {
        val target = time.elapsed + ms
        while (true) {
            val next = timers.filter { it.at <= target }.minByOrNull { it.at } ?: break
            timers.remove(next)
            time.advance(maxOf(0L, next.at - time.elapsed))
            next.action()
        }
        time.advance(target - time.elapsed)
    }
    fun lastLockShown() = commands.any { it is OverlayCommand.ShowLock }
}

class Rig(limit: Int = 3, mode: ProtectionMode = ProtectionMode.BUDGET_LOCK, repo: FakeRepo = FakeRepo()) {
    val time = FakeTime()
    val diag = CountingDiag()
    val repo = repo
    val sm = StateManager(repo).also { m -> if (repo.saved == null) m.update { it.copy(consentGiven = true, configLocked = true, mode = mode, configuredLimit = limit, cycleLimit = limit, remaining = limit) } }
    val budget = BudgetEngine(sm, time)
    val restriction = RestrictionEngine(sm, budget, time)
    val history = InMemoryHistoryRepository()
    val sessions = SessionManager(sm, time, history)
    val health = HealthMonitor(time)
    val recovery = RecoveryManager(restriction, sessions, time, diag)
    val core = CoreEngine(sm, budget, restriction, sessions, health, recovery, time, diag)
    val host = FakeHost(time)
    val events = ArrayList<DomainEvent>()

    init { core.host = host; core.onEvent = { events.add(it) }; core.handle(DomainEvent.PlatformActive("instagram")) }

    var lastKey = "u1"; var lastIdentity = ContentIdentity.UNKNOWN

    fun det(state: DetectionState = DetectionState.CONFIRMED_REEL) =
        DetectionResult(state, if (state == DetectionState.CONFIRMED_REEL) ContentType.REEL else ContentType.OTHER, 90, listOf("T"), time.wall)

    fun stable(k: String) = ContentIdentity(k, IdentityConfidence.STABLE)

    /** يصف Reel مؤكدًا ونشطًا. */
    fun reel(key: String, identity: ContentIdentity = ContentIdentity.UNKNOWN, active: Boolean = true) {
        lastKey = key; lastIdentity = identity
        core.handle(DomainEvent.ReelConfirmed(det(), key, identity, active))
    }
    fun again(active: Boolean = true) = reel(lastKey, lastIdentity, active)

    /** يجعل إعادة التحقق تُرسل نفس الـReel الحالي (كما تفعل الخدمة الحقيقية). */
    fun revalidateAsCurrent() { host.onRevalidate = { again() } }

    /** يشاهد Reel لمدة كافية ليُحتسب. */
    fun watch(key: String, identity: ContentIdentity = ContentIdentity.UNKNOWN, ms: Long = 4000) {
        revalidateAsCurrent()
        reel(key, identity)
        host.advance(ms + 10)
    }
    fun swipeTo(key: String, identity: ContentIdentity = ContentIdentity.UNKNOWN) {
        core.handle(DomainEvent.NewContentCandidate)
        core.handle(DomainEvent.ContentChanged(lastKey, key, "scroll"))
        reel(key, identity)
    }
}
