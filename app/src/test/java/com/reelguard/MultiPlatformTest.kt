package com.reelguard

import com.reelguard.core.events.DomainEvent
import com.reelguard.core.model.DetectionState
import com.reelguard.platform.common.AppPlatform
import com.reelguard.platform.common.RawScan
import com.reelguard.platform.reels.ReelsAdapter
import com.reelguard.platform.reels.rules.DetectionProfile
import org.junit.Assert.*
import org.junit.Test

/** Adapter حقيقي + Core حقيقي: يقيس أثر تصادم البصمة فعليًا على الرصيد. */
class AdapterCoreTest {
    private val pager = "clips_viewer_view_pager"

    private class Sim(val limit: Int = 10) {
        val r = Rig(limit = limit)
        val a = ReelsAdapter(AppPlatform.INSTAGRAM, TEST_PROFILE)
        var t = 10_000L
        var scan: RawScan = reelScan()
        fun frame() { a.process(scan, t).forEach { r.core.handle(it) } }
        init { r.host.onRevalidate = { frame() } }
        fun watch4s() { t += 4100; r.host.advance(4100) }
        fun swipe() {
            t += 1000; a.onScrollHint("clips_viewer_view_pager", t)
            r.core.handle(DomainEvent.NewContentCandidate)
            t += 450; r.host.advance(450); frame()
        }
    }

    @Test fun consecutive_reels_with_identical_signature_are_each_counted() {
        val s = Sim(); s.frame(); s.watch4s()
        assertEquals(9, s.r.budget.remaining)
        s.swipe(); s.watch4s()                       // نفس الحساب ونفس الوصف تمامًا
        assertEquals("الـReel الثاني يُحتسب رغم تطابق البصمة", 8, s.r.budget.remaining)
        assertEquals(1, s.r.diag.counters["identityCollisionSuspects"])
        s.swipe(); s.watch4s()
        assertEquals(7, s.r.budget.remaining)
        assertEquals(2, s.r.diag.counters["identityCollisionSuspects"])
    }

    @Test fun different_signatures_still_dedupe_A_B_A() {
        val s = Sim()
        s.scan = reelScan("alice", "first"); s.frame(); s.watch4s()
        s.scan = reelScan("bob", "second"); s.swipe(); s.watch4s()
        s.scan = reelScan("alice", "first"); s.swipe(); s.watch4s()
        assertEquals(8, s.r.budget.remaining)        // A لم يُحتسب مرتين
        assertNull(s.r.diag.counters["identityCollisionSuspects"])
    }

    @Test fun staying_on_the_same_reel_without_scroll_is_never_a_collision() {
        val s = Sim(); s.frame(); s.watch4s()
        repeat(20) { s.t += 1500; s.frame() }
        assertEquals(9, s.r.budget.remaining)
        assertNull(s.r.diag.counters["identityCollisionSuspects"])
    }
}

class MultiPlatformTest {
    @Test fun each_adapter_only_claims_its_own_packages() {
        val ig = ReelsAdapter(AppPlatform.INSTAGRAM, DetectionProfile(packages = AppPlatform.INSTAGRAM.packages))
        val fb = ReelsAdapter(AppPlatform.FACEBOOK, DetectionProfile(packages = AppPlatform.FACEBOOK.packages))
        val yt = ReelsAdapter(AppPlatform.YOUTUBE, DetectionProfile(packages = AppPlatform.YOUTUBE.packages))
        assertTrue(ig.isPlatformPackage("com.instagram.android")); assertFalse(ig.isPlatformPackage("com.facebook.katana"))
        assertTrue(fb.isPlatformPackage("com.facebook.katana")); assertTrue(fb.isPlatformPackage("com.facebook.lite"))
        assertTrue(yt.isPlatformPackage("com.google.android.youtube")); assertFalse(yt.isPlatformPackage(null))
        assertEquals(setOf("instagram", "facebook", "youtube"), AppPlatform.ALL_IDS)
    }

    @Test fun description_only_profile_can_confirm_without_any_view_ids() {
        val p = DetectionProfile(
            packages = AppPlatform.FACEBOOK.packages, requireIds = false, descWeight = 70, confirmScore = 80,
            reelDescPatterns = listOf(Regex("^Reels?\\b", RegexOption.IGNORE_CASE)))
        val a = ReelsAdapter(AppPlatform.FACEBOOK, p)
        val scan = RawScan(30, emptySet(), emptySet(), descHit = true, descFraction = 0.95f)
        assertEquals(DetectionState.CONFIRMED_REEL, a.detectContext(scan, 0).detection.state)
        assertEquals("ملء الشاشة بوصف فقط ⇒ نشط", com.reelguard.core.model.ActiveContentState.ACTIVE, a.detectContext(scan, 0).active)
        val small = scan.copy(descFraction = 0.3f)
        assertNotEquals(DetectionState.CONFIRMED_REEL, a.detectContext(small, 0).detection.state)
        val strict = ReelsAdapter(AppPlatform.FACEBOOK, p.copy(requireIds = true))
        assertEquals(DetectionState.UNKNOWN, strict.detectContext(scan, 0).detection.state)
    }

    @Test fun large_unknown_scroll_source_becomes_a_hint_only_when_fallback_is_enabled() {
        val off = ReelsAdapter(AppPlatform.YOUTUBE, DetectionProfile(packages = AppPlatform.YOUTUBE.packages))
        val on = ReelsAdapter(AppPlatform.YOUTUBE, DetectionProfile(packages = AppPlatform.YOUTUBE.packages, scrollFallback = true))
        assertFalse(off.onLargeScrollHint(0)); assertTrue(on.onLargeScrollHint(0))
    }
}
