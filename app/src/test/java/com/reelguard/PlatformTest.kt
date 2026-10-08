package com.reelguard

import com.reelguard.core.events.DomainEvent
import com.reelguard.core.model.*
import com.reelguard.platform.common.RawScan
import com.reelguard.platform.common.AppPlatform
import com.reelguard.platform.reels.ReelsAdapter
import com.reelguard.platform.reels.identity.ContentIdentityProvider
import com.reelguard.platform.reels.rules.DetectionProfile
import com.reelguard.platform.reels.transition.TransitionDetector
import com.reelguard.platform.reels.transition.TransitionOutcome
import org.junit.Assert.*
import org.junit.Test

val TEST_PROFILE = DetectionProfile(
    reelStrongIds = setOf("clips_viewer_view_pager", "clips_video_container"),
    clipsTabIds = setOf("clips_tab"),
    storyIds = setOf("reel_viewer_root"),
    contentContainerIds = setOf("clips_viewer_view_pager"),
    authorIds = setOf("author"), captionIds = setOf("caption"),
    scrollSourceIds = setOf("clips_viewer_view_pager"),
    reelDescPatterns = listOf(Regex("^Reel by ", RegexOption.IGNORE_CASE))
)

fun reelScan(author: String? = "alice", caption: String? = "hello world", frac: Float = 0.95f, tab: Boolean = true) = RawScan(
    nodeCount = 50,
    allIds = setOf("clips_viewer_view_pager", "clips_video_container", "clips_tab", "foo"),
    selectedIds = if (tab) setOf("clips_tab") else emptySet(),
    containerFraction = frac, author = author, caption = caption
)

class DetectorTest {
    private val a = ReelsAdapter(AppPlatform.INSTAGRAM, TEST_PROFILE)

    @Test fun full_screen_reel_is_confirmed_with_signals_and_high_confidence() {
        val s = a.detectContext(reelScan(), 0)
        assertEquals(DetectionState.CONFIRMED_REEL, s.detection.state)
        assertTrue(s.detection.confidence >= 80)
        assertTrue(s.detection.signals.contains("PACKAGE_MATCH"))
        assertEquals(ActiveContentState.ACTIVE, s.active)
    }

    @Test fun reel_in_feed_small_is_not_ACTIVE_so_never_counted_fail_safe() {
        val s = a.detectContext(reelScan(frac = 0.3f, tab = false), 0)
        assertNotEquals(ActiveContentState.ACTIVE, s.active)
        val ev = a.process(reelScan(frac = 0.3f, tab = false), 0)
        assertFalse((ev.last() as DomainEvent.ReelConfirmed).active)
    }

    @Test fun story_is_rejected() {
        val scan = reelScan().copy(allIds = setOf("reel_viewer_root", "clips_viewer_view_pager"))
        val s = a.detectContext(scan, 0)
        assertEquals(ContentType.STORY, s.detection.contentType)
        assertEquals(DetectionState.NOT_REEL, s.detection.state)
    }

    @Test fun opaque_tree_is_unknown_not_rejected() {
        assertEquals(DetectionState.UNKNOWN, a.detectContext(null, 0).detection.state)
        assertEquals(DetectionState.UNKNOWN, a.detectContext(RawScan(2, emptySet(), emptySet()), 0).detection.state)
        assertTrue(a.process(null, 0).single() is DomainEvent.DetectionUnknown)
    }

    @Test fun other_screens_with_only_package_are_not_reel() {
        val scan = RawScan(40, setOf("feed_list", "tab_bar"), emptySet())
        assertEquals(DetectionState.NOT_REEL, a.detectContext(scan, 0).detection.state)
    }

    @Test fun description_pattern_alone_gives_candidate_not_confirmed() {
        val scan = RawScan(40, setOf("x"), emptySet(), descHit = true, descFraction = 0.9f)
        val st = a.detectContext(scan, 0).detection.state
        assertTrue(st == DetectionState.POSSIBLE_REEL || st == DetectionState.PROBABLE_REEL)
    }

    @Test fun identity_levels() {
        assertEquals(IdentityConfidence.EXACT, ContentIdentityProvider.resolve(reelScan().copy(contentIdText = "abc123")).confidence)
        assertEquals(IdentityConfidence.STABLE, ContentIdentityProvider.resolve(reelScan()).confidence)
        assertEquals(IdentityConfidence.PROBABLE, ContentIdentityProvider.resolve(reelScan(caption = null)).confidence)
        assertEquals(IdentityConfidence.UNKNOWN, ContentIdentityProvider.resolve(reelScan(author = null, caption = null)).confidence)
        assertNotEquals(ContentIdentityProvider.resolve(reelScan(author = "a")).key, ContentIdentityProvider.resolve(reelScan(author = "b")).key)
        assertEquals(ContentIdentityProvider.resolve(reelScan()).key, ContentIdentityProvider.resolve(reelScan()).key)
    }

    @Test fun third_element_separates_reels_with_same_author_and_caption_prefix() {
        val a = ContentIdentityProvider.resolve(reelScan(caption = "same slogan").copy(extra = "audio one"))
        val b = ContentIdentityProvider.resolve(reelScan(caption = "same slogan").copy(extra = "audio two"))
        assertNotEquals(a.key, b.key)
        assertEquals(IdentityConfidence.STABLE, a.confidence)
    }

    @Test fun key_is_namespaced_by_platform_and_normalized() {
        val ig = ContentIdentityProvider.resolve(reelScan(), "instagram").key
        val fb = ContentIdentityProvider.resolve(reelScan(), "facebook").key
        assertNotEquals(ig, fb)
        assertEquals(ContentIdentityProvider.resolve(reelScan(caption = "Hello   World"), "instagram").key,
            ContentIdentityProvider.resolve(reelScan(caption = " hello world\u200B"), "instagram").key)
    }

    @Test fun caption_prefix_length_is_a_profile_parameter() {
        val long1 = "x".repeat(80) + "AAA"; val long2 = "x".repeat(80) + "BBB"
        assertEquals(ContentIdentityProvider.resolve(reelScan(caption = long1)).key, ContentIdentityProvider.resolve(reelScan(caption = long2)).key)
        assertNotEquals(ContentIdentityProvider.resolve(reelScan(caption = long1), "p", 120).key, ContentIdentityProvider.resolve(reelScan(caption = long2), "p", 120).key)
    }

    @Test fun hash_only_no_raw_text_in_key() {
        val k = ContentIdentityProvider.resolve(reelScan(author = "secretuser", caption = "private caption")).key!!
        assertFalse(k.contains("secretuser")); assertFalse(k.contains("private"))
    }
}

class TransitionTest {
    private val idA = ContentIdentity("sig:A", IdentityConfidence.STABLE)
    private val idB = ContentIdentity("sig:B", IdentityConfidence.STABLE)

    @Test fun identity_change_is_a_transition_and_A_B_A_is_recognized() {
        val t = TransitionDetector(350)
        assertNull(t.evaluate(idA, 0))
        val c1 = t.evaluate(idB, 100) as TransitionOutcome.Changed
        assertEquals("sig:A", c1.from); assertEquals("sig:B", c1.to)
        val c2 = t.evaluate(idA, 200) as TransitionOutcome.Changed
        assertEquals("sig:A", c2.to)
    }

    @Test fun without_identity_scroll_hint_needs_to_settle() {
        val t = TransitionDetector(350)
        assertNull(t.evaluate(ContentIdentity.UNKNOWN, 0)); assertEquals("u1", t.currentKey)
        t.onScrollHint(1000)
        assertNull(t.evaluate(ContentIdentity.UNKNOWN, 1100))       // لم يستقر
        assertTrue(t.evaluate(ContentIdentity.UNKNOWN, 1400) is TransitionOutcome.Changed)
        assertEquals("u2", t.currentKey)
    }

    @Test fun no_hint_and_no_id_means_no_transition() {
        val t = TransitionDetector(350)
        t.evaluate(ContentIdentity.UNKNOWN, 0)
        repeat(20) { assertNull(t.evaluate(ContentIdentity.UNKNOWN, it * 500L)) }
        assertEquals("u1", t.currentKey)
    }

    @Test fun identity_appearing_for_same_content_is_not_a_transition() {
        val t = TransitionDetector(350)
        t.evaluate(ContentIdentity.UNKNOWN, 0)
        assertTrue(t.evaluate(idA, 500) is TransitionOutcome.IdUpdated)
        assertNull(t.evaluate(idA, 600))
    }

    @Test fun identity_flicker_null_then_same_id_is_stable() {
        val t = TransitionDetector(350)
        t.evaluate(idA, 0)
        assertNull(t.evaluate(ContentIdentity.UNKNOWN, 100))
        assertNull(t.evaluate(idA, 200))
        assertEquals("sig:A", t.currentKey)
    }

    @Test fun same_stable_signature_after_settled_scroll_is_a_collision_and_new_content() {
        val t = TransitionDetector(350)
        t.evaluate(idA, 0)
        t.onScrollHint(5000)
        assertNull(t.evaluate(idA, 5100))                       // لم يستقر بعد
        val c = t.evaluate(idA, 5400) as TransitionOutcome.Changed
        assertEquals(com.reelguard.core.events.ChangeReason.SAME_IDENTITY_SCROLL, c.by)
        assertTrue(c.to.startsWith("sig:A#"))
        assertNull(t.evaluate(idA, 5500))                       // نفس المحتوى الجديد: لا انتقال إضافي
        assertEquals(c.to, t.currentKey)
    }

    @Test fun exact_identity_is_never_treated_as_collision() {
        val exact = ContentIdentity("id:Z", IdentityConfidence.EXACT)
        val t = TransitionDetector(350)
        t.evaluate(exact, 0); t.onScrollHint(5000)
        assertNull(t.evaluate(exact, 5400))
        assertEquals("id:Z", t.currentKey)
    }

    @Test fun residual_scroll_events_right_after_a_change_are_ignored() {
        val t = TransitionDetector(350)
        t.evaluate(idA, 0); t.onScrollHint(5000)
        assertTrue(t.evaluate(idB, 5100) is TransitionOutcome.Changed)   // تغيّرت الهوية أثناء الحركة
        t.onScrollHint(5300)                                            // بقايا نفس السحبة
        assertNull(t.evaluate(idB, 5700))
        assertEquals("sig:B", t.currentKey)
        t.onScrollHint(8000)                                            // سحبة جديدة حقيقية
        assertTrue(t.evaluate(idB, 8400) is TransitionOutcome.Changed)
    }

    @Test fun stale_hint_expires() {
        val t = TransitionDetector(350, 2500)
        t.evaluate(ContentIdentity.UNKNOWN, 0); t.onScrollHint(100)
        assertNull(t.evaluate(ContentIdentity.UNKNOWN, 3000))
    }
}
