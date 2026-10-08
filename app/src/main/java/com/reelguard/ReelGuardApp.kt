package com.reelguard

import android.app.Application
import com.reelguard.core.budget.BudgetEngine
import com.reelguard.core.engine.CoreEngine
import com.reelguard.core.health.HealthMonitor
import com.reelguard.core.policy.PolicySettings
import com.reelguard.core.recovery.RecoveryManager
import com.reelguard.core.restriction.RestrictionEngine
import com.reelguard.core.session.SessionManager
import com.reelguard.core.state.StateManager
import com.reelguard.core.stats.StatsCalculator
import com.reelguard.data.repositories.Diagnostics
import com.reelguard.data.repositories.PrefsHistoryRepository
import com.reelguard.data.repositories.PrefsStateRepository
import com.reelguard.data.repositories.SystemTimeSource

/** تركيب الاعتماديات (يدويًا لتجنب أي مكتبة DI). الخدمة والواجهة تشتركان في نفس الـCore. */
class ReelGuardApp : Application() {
    lateinit var diag: Diagnostics; private set
    lateinit var stateManager: StateManager; private set
    lateinit var budget: BudgetEngine; private set
    lateinit var restriction: RestrictionEngine; private set
    lateinit var sessions: SessionManager; private set
    lateinit var health: HealthMonitor; private set
    lateinit var core: CoreEngine; private set
    lateinit var settings: PolicySettings; private set
    lateinit var stats: StatsCalculator; private set
    val calibration = com.reelguard.platform.reels.calibration.CalibrationSession()
    lateinit var history: PrefsHistoryRepository; private set

    override fun onCreate() {
        super.onCreate()
        val time = SystemTimeSource(this)
        diag = Diagnostics(this)
        history = PrefsHistoryRepository(this)
        stateManager = StateManager(PrefsStateRepository(this))
        budget = BudgetEngine(stateManager, time)
        restriction = RestrictionEngine(stateManager, budget, time)
        sessions = SessionManager(stateManager, time, history)
        health = HealthMonitor(time)
        val recovery = RecoveryManager(restriction, sessions, time, diag)
        core = CoreEngine(stateManager, budget, restriction, sessions, health, recovery, time, diag)
        settings = PolicySettings(stateManager, budget, time)
        stats = StatsCalculator(stateManager, budget, history, time)
        diag.fileLogging = stateManager.state.debugLogging
    }

    fun recordReplay(line: String) = diag.replayLine(line)

    /** صيانة دورية موحّدة: انتهاء القفل/الجلسة وسريان التخفيفات المجدولة المستحقة. */
    fun tick() { core.tick(); settings.applyDue() }
}
