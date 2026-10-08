package com.reelguard

import com.reelguard.core.model.ProtectionMode
import com.reelguard.core.policy.PolicySettings
import com.reelguard.core.policy.PolicySettings.Outcome
import com.reelguard.core.state.AppState
import org.junit.Assert.*
import org.junit.Test

class SettingsFrictionTest {
    private val ALL = AppState.DEFAULT_PLATFORMS
    private fun rig(limit: Int = 30): Pair<Rig, PolicySettings> {
        val r = Rig(limit = limit)
        return r to PolicySettings(r.sm, r.budget, r.time)
    }

    @Test fun initial_setup_is_free_until_config_is_locked() {
        val r = Rig(); val s = PolicySettings(r.sm, r.budget, r.time)
        r.sm.update { it.copy(configLocked = false) }
        assertEquals(Outcome.APPLIED, s.requestChange(ProtectionMode.WARNING_ONLY, 500, setOf("youtube")))
        assertEquals(ProtectionMode.WARNING_ONLY, r.sm.state.mode)
        assertEquals(500, r.sm.state.configuredLimit)
        assertEquals(setOf("youtube"), r.sm.state.enabledPlatforms)
        assertNull(r.sm.state.pending)
    }

    @Test fun raising_the_limit_is_queued_and_lowering_is_immediate() {
        val (r, s) = rig(30)
        assertEquals(Outcome.QUEUED, s.requestChange(ProtectionMode.BUDGET_LOCK, 100, ALL))
        assertEquals(30, r.sm.state.configuredLimit)
        assertEquals(100, s.desiredLimit())
        assertEquals(Outcome.APPLIED, s.requestChange(ProtectionMode.BUDGET_LOCK, 10, ALL))
        assertEquals(10, r.sm.state.configuredLimit)
        assertNull("طلب أشدّ يلغي المجدول", r.sm.state.pending)
    }

    @Test fun weaker_mode_is_queued_and_stronger_is_immediate() {
        val (r, s) = rig()
        assertEquals(Outcome.QUEUED, s.requestChange(ProtectionMode.WARNING_ONLY, 30, ALL))
        assertEquals(ProtectionMode.BUDGET_LOCK, r.sm.state.mode)
        r.sm.update { it.copy(mode = ProtectionMode.WARNING_ONLY, pending = null) }
        assertEquals(Outcome.APPLIED, s.requestChange(ProtectionMode.BUDGET_LOCK, 30, ALL))
        assertEquals(ProtectionMode.BUDGET_LOCK, r.sm.state.mode)
    }

    @Test fun removing_an_app_is_queued_adding_is_immediate() {
        val (r, s) = rig()
        r.sm.update { it.copy(enabledPlatforms = setOf("instagram")) }
        assertEquals(Outcome.APPLIED, s.requestChange(ProtectionMode.BUDGET_LOCK, 30, setOf("instagram", "youtube")))
        assertEquals(setOf("instagram", "youtube"), r.sm.state.enabledPlatforms)
        assertEquals(Outcome.QUEUED, s.requestChange(ProtectionMode.BUDGET_LOCK, 30, setOf("instagram")))
        assertEquals(setOf("instagram", "youtube"), r.sm.state.enabledPlatforms)
        assertEquals(setOf("instagram"), s.desiredPlatforms())
    }

    @Test fun weakening_applies_only_after_the_delay_even_while_locked() {
        val (r, s) = rig(1)
        r.watch("u1"); r.swipeTo("u2")
        assertTrue(r.core.isLocked())
        assertEquals(Outcome.QUEUED, s.requestChange(ProtectionMode.WARNING_ONLY, 999, ALL))
        r.time.advance(PolicySettings.WEAKEN_DELAY_MS - 1000)
        assertFalse(s.applyDue())
        assertEquals(ProtectionMode.BUDGET_LOCK, r.sm.state.mode)
        r.time.advance(2000)
        assertTrue(s.applyDue())
        assertEquals(ProtectionMode.WARNING_ONLY, r.sm.state.mode)
        assertEquals(999, r.sm.state.configuredLimit)
        assertNull(r.sm.state.pending)
    }

    @Test fun delay_is_immune_to_clock_changes_within_the_same_boot() {
        val (r, s) = rig()
        s.requestChange(ProtectionMode.WARNING_ONLY, 30, ALL)
        r.time.wall += 3 * 24 * 3600_000L                     // تقديم الساعة 3 أيام
        assertFalse(s.applyDue())
        r.time.elapsed += PolicySettings.WEAKEN_DELAY_MS + 1
        assertTrue(s.applyDue())
    }

    @Test fun resubmitting_the_same_weakening_keeps_the_original_timer() {
        val (r, s) = rig()
        s.requestChange(ProtectionMode.WARNING_ONLY, 30, ALL)
        val first = r.sm.state.pending!!.timer
        r.time.advance(3600_000L)
        s.requestChange(ProtectionMode.WARNING_ONLY, 30, ALL)
        assertEquals(first, r.sm.state.pending!!.timer)
    }

    @Test fun cancelling_pending_is_always_allowed_and_no_op_request_changes_nothing() {
        val (r, s) = rig()
        assertEquals(Outcome.NO_CHANGE, s.requestChange(ProtectionMode.BUDGET_LOCK, 30, ALL))
        s.requestChange(ProtectionMode.WARNING_ONLY, 30, ALL)
        s.cancelPending()
        assertNull(r.sm.state.pending)
        assertNull(s.pendingRemainingMs())
    }

    @Test fun limit_is_clamped_and_empty_platform_set_is_ignored() {
        val (r, s) = rig(30)
        s.requestChange(ProtectionMode.BUDGET_LOCK, 0, emptySet())
        assertEquals(1, r.sm.state.configuredLimit)
        assertEquals(ALL, r.sm.state.enabledPlatforms)
    }
}
