package com.reelguard

import com.reelguard.core.engine.OverlayCommand
import com.reelguard.core.events.DomainEvent
import com.reelguard.core.model.ProtectionBasis
import com.reelguard.core.model.ProtectionMode
import com.reelguard.core.policy.Config
import com.reelguard.core.policy.PolicySettings
import com.reelguard.core.policy.PolicySettings.Outcome
import com.reelguard.core.state.AppState
import org.junit.Assert.*
import org.junit.Test

class TimeModeTest {
    private val MIN = 60_000L
    private val ALL = AppState.DEFAULT_PLATFORMS

    private fun timeRig(timeMin: Int = 10, lockMin: Int = 60, mode: ProtectionMode = ProtectionMode.BUDGET_LOCK): Rig {
        val r = Rig(limit = 3)
        r.sm.update {
            it.copy(basis = ProtectionBasis.TIME, mode = mode, configuredTimeMin = timeMin,
                cycleTimeMs = timeMin * MIN, timeRemainingMs = timeMin * MIN, lockMinutes = lockMin)
        }
        r.core.tick()                       // يبدأ العدّاد: المنصة نشطة والحماية مفعّلة
        return r
    }

    private fun settings(r: Rig) = PolicySettings(r.sm, r.budget, r.time)
    private fun cfg(s: PolicySettings, f: (Config) -> Config) = s.requestChange(f(s.desired()))

    // ---------------------------------------------------------------- المحاسبة
    @Test fun time_counts_only_while_a_protected_app_is_in_foreground() {
        val r = timeRig(10)
        r.host.advance(3 * MIN)
        assertEquals(7 * MIN, r.core.timeRemainingMs())
        r.core.handle(DomainEvent.PlatformInactive)
        r.host.advance(5 * MIN)
        assertEquals("خارج التطبيق لا يُحتسب شيء", 7 * MIN, r.core.timeRemainingMs())
        r.core.handle(DomainEvent.PlatformActive("youtube"))
        r.host.advance(1 * MIN)
        assertEquals(6 * MIN, r.core.timeRemainingMs())
    }

    @Test fun time_is_shared_across_the_selected_apps() {
        val r = timeRig(10)
        r.host.advance(2 * MIN)
        r.core.handle(DomainEvent.PlatformActive("facebook"))      // انتقال مباشر
        r.host.advance(3 * MIN)
        assertEquals(5 * MIN, r.core.timeRemainingMs())
    }

    @Test fun screen_off_stops_counting_and_screen_on_resumes() {
        val r = timeRig(10)
        r.host.advance(1 * MIN)
        r.host.interactive = false
        r.core.onScreenOff()
        r.host.advance(5 * MIN)
        assertEquals(9 * MIN, r.core.timeRemainingMs())
        r.host.interactive = true
        r.core.handle(DomainEvent.PlatformActive("instagram"))
        r.host.advance(1 * MIN)
        assertEquals(8 * MIN, r.core.timeRemainingMs())
    }

    @Test fun counter_overlay_is_updated_every_second() {
        val r = timeRig(10)
        r.host.commands.clear()
        r.host.advance(3_000)
        val shown = r.host.commands.filterIsInstance<OverlayCommand.ShowTimeCounter>().map { it.remainingMs }
        assertEquals(listOf(599_000L, 598_000L, 597_000L), shown)
    }

    @Test fun reels_are_not_counted_in_time_basis() {
        val r = timeRig(10)
        r.watch("u1"); r.swipeTo("u2"); r.host.advance(5000)
        assertEquals(3, r.budget.remaining)
        assertFalse(r.core.isLocked())
    }

    @Test fun usage_is_persisted_and_survives_restart() {
        val r = timeRig(10)
        r.host.advance(4 * MIN + 30_000)
        r.core.handle(DomainEvent.PlatformInactive)               // يحفظ نقطة
        val r2 = Rig(repo = r.repo)                               // إعادة تشغيل بنفس الحالة
        assertEquals(10 * MIN - (4 * MIN + 30_000), r2.sm.state.timeRemainingMs)
        assertEquals(4 * MIN + 30_000, r2.sm.state.stats.dailyMs.values.sum())
    }

    @Test fun clock_changes_do_not_affect_the_time_budget() {
        val r = timeRig(10)
        r.host.advance(1 * MIN)
        r.time.wall += 5 * 3600_000L                              // تقديم الساعة 5 ساعات
        r.host.advance(1 * MIN)
        assertEquals(8 * MIN, r.core.timeRemainingMs())
    }

    // ---------------------------------------------------------------- الإيقاف
    @Test fun exhausting_time_locks_the_app_for_the_custom_stop_duration() {
        val r = timeRig(timeMin = 2, lockMin = 45)
        r.host.advance(2 * MIN + 1500)
        assertTrue(r.core.isLocked())
        assertEquals("time_up", r.sm.state.lockReason)
        assertEquals(45 * MIN, r.sm.state.lockEnd - r.sm.state.lockStart)
        assertTrue(r.host.lastLockShown())
    }

    @Test fun a_new_full_cycle_starts_after_the_stop_and_counting_resumes() {
        val r = timeRig(timeMin = 2, lockMin = 10)
        r.host.advance(2 * MIN + 1500)
        assertTrue(r.core.isLocked())
        r.host.advance(10 * MIN)
        r.core.tick()
        assertFalse(r.core.isLocked())
        assertEquals(2 * MIN, r.sm.state.timeRemainingMs)
        r.host.advance(30_000)
        assertEquals(2 * MIN - 30_000, r.core.timeRemainingMs())
    }

    @Test fun nothing_is_counted_while_locked() {
        val r = timeRig(timeMin = 1, lockMin = 30)
        r.host.advance(MIN + 1500)
        assertTrue(r.core.isLocked())
        val before = r.sm.state.stats.dailyMs.values.sum()
        r.host.advance(10 * MIN)
        assertEquals(before, r.sm.state.stats.dailyMs.values.sum())
    }

    @Test fun warning_mode_only_warns_then_refills_after_the_stop_duration() {
        val r = timeRig(timeMin = 1, lockMin = 5, mode = ProtectionMode.WARNING_ONLY)
        r.host.advance(MIN + 1500)
        assertFalse(r.core.isLocked())
        assertTrue(r.host.commands.any { it is OverlayCommand.ShowWarning })
        assertEquals(0L, r.core.timeRemainingMs())
        r.host.advance(5 * MIN)
        r.core.tick()
        assertTrue("تعبئة جديدة بعد مدة الإيقاف", r.core.timeRemainingMs() > 0L)
        assertNull(r.sm.state.refill)
    }

    @Test fun count_mode_warning_also_refills_after_the_stop_duration() {
        val r = Rig(limit = 1, mode = ProtectionMode.WARNING_ONLY)
        r.sm.update { it.copy(lockMinutes = 5) }
        r.watch("u1")
        assertEquals(0, r.budget.remaining)
        r.host.advance(5 * MIN + 1000)
        r.core.tick()
        assertEquals(1, r.budget.remaining)
    }

    // ---------------------------------------------------------------- الإعدادات
    @Test fun basis_is_free_to_choose_before_config_is_confirmed() {
        val r = Rig(); val s = settings(r)
        r.sm.update { it.copy(configLocked = false) }
        assertEquals(Outcome.APPLIED, cfg(s) { it.copy(basis = ProtectionBasis.TIME, timeMin = 90, lockMin = 20) })
        assertEquals(ProtectionBasis.TIME, r.sm.state.basis)
        assertEquals(90, r.sm.state.configuredTimeMin)
        assertEquals(90 * MIN, r.sm.state.timeRemainingMs)
        assertEquals(20, r.sm.state.lockMinutes)
    }

    @Test fun switching_basis_after_start_is_queued_not_immediate() {
        val r = Rig(); val s = settings(r)
        assertEquals(Outcome.QUEUED, cfg(s) { it.copy(basis = ProtectionBasis.TIME) })
        assertEquals(ProtectionBasis.COUNT, r.sm.state.basis)
        assertEquals(ProtectionBasis.TIME, s.desired().basis)
        r.time.advance(PolicySettings.WEAKEN_DELAY_MS + 1000)
        assertTrue(s.applyDue())
        assertEquals(ProtectionBasis.TIME, r.sm.state.basis)
    }

    @Test fun switching_basis_is_refused_while_locked() {
        val r = Rig(limit = 1); val s = settings(r)
        r.watch("u1"); r.swipeTo("u2")
        assertTrue(r.core.isLocked())
        assertEquals(Outcome.BASIS_BLOCKED, cfg(s) { it.copy(basis = ProtectionBasis.TIME) })
        assertEquals(ProtectionBasis.COUNT, r.sm.state.basis)
        assertNull("لا يُجدوَل أيضًا", r.sm.state.pending)
    }

    @Test fun other_changes_still_apply_when_the_basis_switch_is_refused() {
        val r = Rig(limit = 30); val s = settings(r)
        r.sm.update { it.copy(lockState = com.reelguard.core.state.LockState.LOCKED, lockStart = r.time.wall, lockEnd = r.time.wall + 3600_000L,
            lockStartElapsed = r.time.elapsed, lockBootCount = r.time.boot) }
        assertEquals(Outcome.BASIS_BLOCKED, cfg(s) { it.copy(basis = ProtectionBasis.TIME, limit = 10) })
        assertEquals(10, r.sm.state.configuredLimit)
    }

    @Test fun a_due_basis_switch_waits_for_the_stop_to_end() {
        val r = Rig(limit = 5); val s = settings(r)
        cfg(s) { it.copy(basis = ProtectionBasis.TIME) }
        r.time.advance(PolicySettings.WEAKEN_DELAY_MS - 1000)
        r.sm.update { it.copy(lockState = com.reelguard.core.state.LockState.LOCKED, lockStart = r.time.wall, lockEnd = r.time.wall + 3600_000L,
            lockStartElapsed = r.time.elapsed, lockBootCount = r.time.boot) }
        r.time.advance(2000)                                       // استحق المؤقت أثناء القفل
        assertFalse(s.applyDue())
        assertEquals(ProtectionBasis.COUNT, r.sm.state.basis)
        assertNotNull(r.sm.state.pending)
        r.time.advance(3600_000L)
        r.core.tick()                                              // انتهى القفل
        assertTrue(s.applyDue())
        assertEquals(ProtectionBasis.TIME, r.sm.state.basis)
        assertNull(r.sm.state.pending)
    }

    @Test fun cancelling_a_queued_basis_switch_is_allowed_even_while_locked() {
        val r = Rig(); val s = settings(r)
        cfg(s) { it.copy(basis = ProtectionBasis.TIME) }
        r.sm.update { it.copy(lockState = com.reelguard.core.state.LockState.LOCKED, lockStart = r.time.wall, lockEnd = r.time.wall + 3600_000L,
            lockStartElapsed = r.time.elapsed, lockBootCount = r.time.boot) }
        assertEquals(Outcome.APPLIED, s.requestChange(s.desired().copy(basis = ProtectionBasis.COUNT)))
        assertNull(r.sm.state.pending)
    }

    @Test fun lowering_allowed_time_is_immediate_and_raising_is_queued() {
        val r = timeRig(30); val s = settings(r)
        assertEquals(Outcome.APPLIED, cfg(s) { it.copy(timeMin = 10) })
        assertEquals(10, r.sm.state.configuredTimeMin)
        assertEquals(10 * MIN, minOf(r.sm.state.timeRemainingMs, 10 * MIN))
        assertEquals(Outcome.QUEUED, cfg(s) { it.copy(timeMin = 120) })
        assertEquals(10, r.sm.state.configuredTimeMin)
        r.time.advance(PolicySettings.WEAKEN_DELAY_MS + 1000)
        assertTrue(s.applyDue())
        assertEquals(120, r.sm.state.configuredTimeMin)
    }

    @Test fun stop_duration_longer_is_immediate_shorter_is_queued() {
        val r = Rig(); val s = settings(r)
        assertEquals(Outcome.APPLIED, cfg(s) { it.copy(lockMin = 120) })
        assertEquals(120, r.sm.state.lockMinutes)
        assertEquals(Outcome.QUEUED, cfg(s) { it.copy(lockMin = 15) })
        assertEquals(120, r.sm.state.lockMinutes)
        assertEquals(15, s.desired().lockMin)
        r.time.advance(PolicySettings.WEAKEN_DELAY_MS + 1000)
        s.applyDue()
        assertEquals(15, r.sm.state.lockMinutes)
    }

    @Test fun lock_duration_setting_drives_the_lock_in_count_mode_too() {
        val r = Rig(limit = 1)
        r.sm.update { it.copy(lockMinutes = 25) }
        r.watch("u1"); r.swipeTo("u2")
        assertEquals(25 * MIN, r.sm.state.lockEnd - r.sm.state.lockStart)
    }

    @Test fun basis_switch_does_not_refill_the_other_budget() {
        val r = Rig(limit = 5)
        r.watch("u1")                                              // remaining 4
        val s = settings(r)
        cfg(s) { it.copy(basis = ProtectionBasis.TIME) }
        r.time.advance(PolicySettings.WEAKEN_DELAY_MS + 1000)
        s.applyDue()
        assertEquals(4, r.sm.state.remaining)
    }

    @Test fun stats_expose_todays_use_time() {
        val r = timeRig(10)
        r.host.advance(2 * MIN)
        r.core.handle(DomainEvent.PlatformInactive)
        val st = com.reelguard.core.stats.StatsCalculator(r.sm, r.budget, r.history, r.time).snapshot()
        assertEquals(2 * MIN, st.todayUseMs)
    }
}
