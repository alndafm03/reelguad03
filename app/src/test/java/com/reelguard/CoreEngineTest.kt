package com.reelguard

import com.reelguard.core.events.DomainEvent
import com.reelguard.core.model.*
import com.reelguard.core.state.FlowState
import com.reelguard.core.state.LockState
import org.junit.Assert.*
import org.junit.Test

class CoreEngineTest {

    @Test fun reel_counts_only_after_active_duration() {
        val r = Rig(limit = 3)
        r.revalidateAsCurrent()
        r.reel("u1")
        r.host.advance(3900)
        assertEquals(3, r.budget.remaining)          // < 4s: لا خصم
        r.host.advance(200)
        assertEquals(2, r.budget.remaining)          // ≥ 4s: خصم
        assertTrue(r.events.any { it is DomainEvent.ContentConsumed })
    }

    @Test fun same_reel_is_not_counted_twice_after_blip() {
        val r = Rig(limit = 3)
        r.watch("u1")
        assertEquals(2, r.budget.remaining)
        r.core.handle(DomainEvent.DetectionUnknown("blip"))
        r.reel("u1"); r.host.advance(5000)
        assertEquals(2, r.budget.remaining)
    }

    @Test fun interruption_before_threshold_cancels_and_restarts() {
        val r = Rig(limit = 3)
        r.revalidateAsCurrent()
        r.reel("u1"); r.host.advance(2000)
        r.core.handle(DomainEvent.ReelRejected(r.det(DetectionState.NOT_REEL)))
        r.host.advance(3000)
        assertEquals(3, r.budget.remaining)
        assertEquals(1, r.diag.counters["watchInterruptions"])
        r.reel("u1"); r.host.advance(4100)
        assertEquals(2, r.budget.remaining)
    }

    @Test fun inactive_or_unknown_never_deduct() {
        val r = Rig(limit = 3)
        r.revalidateAsCurrent()
        r.reel("u1", active = false); r.host.advance(10_000)
        r.core.handle(DomainEvent.DetectionUnknown("x")); r.host.advance(10_000)
        r.core.handle(DomainEvent.ReelCandidate(r.det(DetectionState.POSSIBLE_REEL))); r.host.advance(10_000)
        assertEquals(3, r.budget.remaining)
        assertFalse(r.core.isLocked())
    }

    @Test fun A_B_A_with_stable_identity_counts_A_once() {
        val r = Rig(limit = 5)
        val a = r.stable("sig:A"); val b = r.stable("sig:B")
        r.watch("sig:A", a)
        r.swipeTo("sig:B", b); r.host.advance(4100)
        r.swipeTo("sig:A", a); r.host.advance(4100)
        assertEquals(3, r.budget.remaining)
    }

    @Test fun probable_identity_is_never_used_to_dedupe() {
        val r = Rig(limit = 5)
        val p = ContentIdentity("au:X", IdentityConfidence.PROBABLE)
        r.watch("u1", p)
        r.swipeTo("u2", p); r.host.advance(4100)
        assertEquals(3, r.budget.remaining)
    }

    @Test fun id_appearing_mid_watch_does_not_restart_watch() {
        val r = Rig(limit = 3)
        r.revalidateAsCurrent()
        r.reel("u1"); r.host.advance(2000)
        r.core.handle(DomainEvent.ContentIdUpdated("u1", "sig:A"))
        r.reel("sig:A", r.stable("sig:A")); r.host.advance(2100)
        assertEquals(2, r.budget.remaining)
        assertEquals(0, r.diag.counters["watchInterruptions"] ?: 0)
    }

    @Test fun zero_balance_lets_current_content_finish_then_locks_on_new_reel() {
        val r = Rig(limit = 2)
        r.watch("u1"); r.swipeTo("u2"); r.host.advance(4100)
        assertEquals(0, r.budget.remaining)
        assertEquals(FlowState.CURRENT_CONTENT_ALLOWED, r.core.flow)
        assertFalse("لا قفل فوري (§29)", r.core.isLocked())
        r.again(); r.host.advance(20_000); r.again()
        assertFalse("المحتوى الحالي يكمل بحرية", r.core.isLocked())
        // محاولة محتوى جديد ⇒ تقييد فوري
        r.swipeTo("u3")
        assertTrue("تقييد فوري عند محاولة محتوى جديد", r.core.isLocked())
        assertEquals(LockState.LOCKED, r.sm.state.lockState)
        assertTrue(r.host.lastLockShown())
    }

    @Test fun any_scroll_attempt_at_zero_locks_immediately_in_budget_lock_mode() {
        val r = Rig(limit = 1)
        r.watch("u1")
        assertEquals(0, r.budget.remaining)
        r.core.handle(DomainEvent.NewContentCandidate)   // محاولة تمرير
        assertTrue(r.core.isLocked())
        assertEquals(PolicyFactoryLockMs, r.sm.state.lockEnd - r.sm.state.lockStart)
    }
    private val PolicyFactoryLockMs = com.reelguard.core.policy.PolicyFactory.LOCK_MS

    @Test fun lock_survives_unknown_detection_and_expires_after_60_min() {
        val r = Rig(limit = 1)
        r.watch("u1"); r.swipeTo("u2"); r.host.advance(400)
        assertTrue(r.core.isLocked())
        r.core.handle(DomainEvent.DetectionUnknown("blip"))
        assertTrue("فقدان الكشف لا يلغي القفل (§3.3)", r.core.isLocked())
        r.time.advance(59 * 60_000L)
        assertTrue(r.core.isLocked())
        r.time.advance(2 * 60_000L)
        assertFalse(r.core.isLocked())
        assertEquals(1, r.budget.remaining)              // دورة جديدة
        assertTrue(r.events.any { it is DomainEvent.RestrictionEnded })
    }

    @Test fun warning_mode_warns_but_never_locks() {
        val r = Rig(limit = 1, mode = ProtectionMode.WARNING_ONLY)
        r.watch("u1"); r.swipeTo("u2"); r.host.advance(5000)
        assertFalse(r.core.isLocked())
        assertTrue(r.host.commands.any { it is com.reelguard.core.engine.OverlayCommand.ShowWarning })
    }

    @Test fun stories_and_small_feed_reels_are_not_counted() {
        val r = Rig(limit = 3)
        r.core.handle(DomainEvent.ReelRejected(DetectionResultStory(r)))
        r.host.advance(10_000)
        r.revalidateAsCurrent(); r.reel("u1", active = false); r.host.advance(10_000)
        assertEquals(3, r.budget.remaining)
    }
    private fun DetectionResultStory(r: Rig) = DetectionResult(DetectionState.NOT_REEL, ContentType.STORY, 0, listOf("STORY"), r.time.wall)

    @Test fun nothing_counts_until_initial_config_is_confirmed() {
        val r = Rig(limit = 3)
        r.sm.update { it.copy(configLocked = false) }
        r.watch("u1")
        assertEquals(3, r.budget.remaining)
        r.sm.update { it.copy(configLocked = true) }
        r.watch("u2")
        assertEquals(2, r.budget.remaining)
    }

    @Test fun switching_directly_between_apps_drops_the_previous_watch() {
        val r = Rig(limit = 3)
        r.revalidateAsCurrent(); r.reel("u1"); r.host.advance(2000)
        r.core.handle(DomainEvent.PlatformActive("youtube"))     // انتقال مباشر Instagram → YouTube
        assertEquals("youtube", r.core.activePlatform)
        r.host.advance(5000)
        assertEquals(3, r.budget.remaining)                     // لا يُخصم Reel بدأ في تطبيق آخر
        assertEquals(1, r.diag.counters["watchInterruptions"])
    }

    @Test fun lock_blocks_whichever_enabled_app_is_opened() {
        val r = Rig(limit = 1)
        r.watch("u1"); r.swipeTo("u2")
        assertTrue(r.core.isLocked())
        r.host.commands.clear()
        r.core.handle(DomainEvent.PlatformInactive)
        r.core.handle(DomainEvent.PlatformActive("facebook"))
        assertTrue(r.host.lastLockShown())
    }

    @Test fun screen_off_interrupts_watch() {
        val r = Rig(limit = 3)
        r.revalidateAsCurrent(); r.reel("u1"); r.host.advance(2000)
        r.core.onScreenOff(); r.host.advance(5000)
        assertEquals(3, r.budget.remaining)
    }

    @Test fun no_count_when_screen_not_interactive_at_commit() {
        val r = Rig(limit = 3)
        r.revalidateAsCurrent(); r.reel("u1"); r.host.interactive = false
        r.host.advance(4100)
        assertEquals(3, r.budget.remaining)
    }

    // ---------------- الاستعادة (§40-41) ----------------
    @Test fun recovery_after_process_restart_restores_lock_from_persisted_state() {
        val r = Rig(limit = 1)
        r.watch("u1"); r.swipeTo("u2"); r.host.advance(400)
        assertTrue(r.core.isLocked())
        val saved = r.repo.saved!!
        // عملية جديدة (نفس الجهاز، نفس الإقلاع)
        val r2 = Rig(repo = FakeRepo(saved)).also { it.time.wall = r.time.wall; it.time.elapsed = r.time.elapsed }
        r2.core.handle(DomainEvent.ServiceConnected)
        assertEquals(LockState.RECOVERING, r2.sm.state.lockState)
        assertTrue(r2.core.isLocked())
        r2.core.handle(DomainEvent.PlatformActive("instagram"))
        assertTrue(r2.host.lastLockShown())
        r2.core.handle(DomainEvent.OverlayRestored)
        assertEquals(LockState.LOCKED, r2.sm.state.lockState)
    }

    @Test fun recovery_after_reboot_uses_wall_clock_lockEnd() {
        val r = Rig(limit = 1)
        r.watch("u1"); r.swipeTo("u2"); r.host.advance(400)
        val saved = r.repo.saved!!
        val r2 = Rig(repo = FakeRepo(saved))
        r2.time.wall = r.time.wall + 30 * 60_000L; r2.time.elapsed = 5_000L; r2.time.boot = 2   // إقلاع جديد
        assertEquals(true, r2.restriction.lockRemainingMs() in (29 * 60_000L)..(31 * 60_000L))
        r2.time.wall = r.time.wall + 61 * 60_000L
        r2.core.handle(DomainEvent.ServiceConnected)
        assertFalse(r2.core.isLocked())
    }

    @Test fun lock_duration_immune_to_clock_change_within_same_boot() {
        val r = Rig(limit = 1)
        r.watch("u1"); r.swipeTo("u2"); r.host.advance(400)
        r.time.wall += 10 * 3600_000L                   // المستخدم يقدّم الساعة 10 ساعات
        assertTrue("القفل لا ينتهي بتغيير الساعة", r.core.isLocked())
        r.time.elapsed += 61 * 60_000L
        assertFalse(r.core.isLocked())
    }

    @Test fun overlay_lost_recreates_lock_overlay() {
        val r = Rig(limit = 1)
        r.watch("u1"); r.swipeTo("u2"); r.host.advance(400)
        r.host.commands.clear()
        r.time.advance(2000)
        r.core.handle(DomainEvent.OverlayLost)
        assertTrue(r.host.lastLockShown())
    }

    @Test fun allowed_content_persists_so_reopening_ig_at_zero_cannot_grant_free_reel() {
        val r = Rig(limit = 1)
        r.watch("u1")
        assertEquals("u1", r.sm.state.allowedContentKey)
        r.core.handle(DomainEvent.PlatformInactive)
        r.core.handle(DomainEvent.PlatformActive("instagram"))
        r.reel("u5"); r.host.advance(400)
        assertTrue(r.core.isLocked())
    }

    @Test fun corrupt_state_is_sanitized() {
        val bad = com.reelguard.core.state.AppState(remaining = 999, cycleLimit = 30, lockState = LockState.LOCKED, lockEnd = 0)
        val m = com.reelguard.core.state.StateManager(FakeRepo(bad))
        assertEquals(30, m.state.remaining)
        assertEquals(LockState.UNLOCKED, m.state.lockState)
    }
}
