package com.reelguard

import com.reelguard.core.events.DomainEvent
import com.reelguard.core.health.HealthMonitor
import com.reelguard.core.model.*
import com.reelguard.core.policy.PolicyFactory
import com.reelguard.core.policy.PolicySettings
import com.reelguard.core.session.SessionManager
import com.reelguard.core.session.InMemoryHistoryRepository
import com.reelguard.core.state.AppState
import com.reelguard.core.state.StateManager
import com.reelguard.core.stats.StatsCalculator
import com.reelguard.platform.common.RawScan
import com.reelguard.platform.common.AppPlatform
import com.reelguard.platform.reels.ReelsAdapter
import com.reelguard.testing.replay.ReplayFrame
import com.reelguard.testing.replay.ReplayRunner
import org.junit.Assert.*
import org.junit.Test

class PolicyTest {
    @Test fun two_modes_map_to_two_policies() {
        fun p(m: ProtectionMode) = PolicyFactory.from(AppState(mode = m))
        assertEquals(RestrictionType.WARNING, p(ProtectionMode.WARNING_ONLY).restriction.type)
        assertEquals(RestrictionType.LOCK, p(ProtectionMode.BUDGET_LOCK).restriction.type)
        assertEquals(0L, p(ProtectionMode.BUDGET_LOCK).restriction.graceMs)          // تقييد فوري
        assertEquals(4000L, p(ProtectionMode.BUDGET_LOCK).consumption.minimumActiveDurationMs)
    }

    @Test fun there_is_no_stop_or_pause_api_and_protection_follows_config_lock() {
        val r = Rig()
        assertTrue(r.core.isProtectionActive())
        assertTrue(PolicySettings::class.java.methods.none { it.name.contains("pause", true) || it.name.contains("ProtectionEnabled", true) })
    }
}

class SessionStatsTest {
    @Test fun sessions_split_by_idle_gap_and_feed_statistics() {
        val r = Rig(limit = 50)
        r.watch("u1"); r.swipeTo("u2"); r.host.advance(4100)
        assertEquals(1, r.sessions.current!!.id)
        r.time.advance(SessionManager.DEFAULT_GAP_MS + 1000)
        r.sessions.tick()
        assertNull(r.sessions.current)
        assertEquals(1, r.history.sessions().size); assertEquals(2, r.history.sessions()[0].count)
        r.core.handle(DomainEvent.PlatformActive("instagram"))
        r.watch("u9")
        val st = StatsCalculator(r.sm, r.budget, r.history, r.time).snapshot()
        assertEquals(3, st.today); assertEquals(2, st.sessionsToday)
    }

    @Test fun session_ends_when_restriction_starts() {
        val r = Rig(limit = 1)
        r.watch("u1"); r.swipeTo("u2"); r.host.advance(400)
        assertNull(r.sessions.current); assertEquals(1, r.history.sessions().size)
        assertEquals(1, r.sm.state.stats.lockCount); assertEquals(1, r.sm.state.stats.limitReached)
    }
}

class HealthTest {
    @Test fun persistent_unknown_raises_attention_but_single_unknown_does_not() {
        val r = Rig()
        r.core.handle(DomainEvent.DetectionUnknown("x"))
        assertFalse(r.health.needsAttention)
        repeat(25) { r.core.handle(DomainEvent.DetectionUnknown("x")) }
        assertTrue(r.health.needsAttention)
        r.reel("u1")
        assertFalse(r.health.needsAttention)
    }

    @Test fun detection_test_progress_for_onboarding() {
        val r = Rig()
        assertTrue(r.health.test.platformDetected); assertFalse(r.health.test.ready)
        r.reel("u1"); r.core.handle(DomainEvent.ContentChanged("u1", "u2", "scroll"))
        assertTrue(r.health.test.ready)
    }
}

class ReplayTest {
    private fun scan(a: String) = com.reelguard.reelScan(author = a, caption = "cap-$a")

    @Test fun replay_runs_recorded_frames_through_detector_without_the_real_app() {
        val frames = listOf(
            ReplayFrame(0, scan("A")), ReplayFrame(1000, scan("A")),
            ReplayFrame(2000, scan("B")), ReplayFrame(3000, null),
            ReplayFrame(4000, RawScan(30, setOf("feed"), emptySet()))
        )
        val res = ReplayRunner(ReelsAdapter(AppPlatform.INSTAGRAM, TEST_PROFILE)).run(frames)
        assertEquals(5, res.size)
        assertTrue(res[0].events.last() is DomainEvent.ReelConfirmed)
        assertTrue(res[2].events.first() is DomainEvent.ContentChanged)
        assertTrue(res[3].events.single() is DomainEvent.DetectionUnknown)
        assertTrue(res[4].events.single() is DomainEvent.ReelRejected)
        assertEquals(1, ReplayRunner.summary(res).transitions)
    }

    @Test fun replay_compares_two_detector_versions() {
        val frames = listOf(ReplayFrame(0, com.reelguard.reelScan(frac = 0.5f, tab = false)))
        val strict = ReplayRunner(ReelsAdapter(AppPlatform.INSTAGRAM, TEST_PROFILE)).run(frames)
        val loose = ReplayRunner(ReelsAdapter(AppPlatform.INSTAGRAM, TEST_PROFILE.copy(minScreenFraction = 0.4f))).run(frames)
        assertEquals(0, ReplayRunner.summary(strict).activeConfirmed)
        assertEquals(1, ReplayRunner.summary(loose).activeConfirmed)
    }
}
