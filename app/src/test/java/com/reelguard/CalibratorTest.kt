package com.reelguard

import com.reelguard.platform.common.RawScan
import com.reelguard.platform.reels.calibration.CalLabel
import com.reelguard.platform.reels.calibration.Calibrator
import com.reelguard.platform.reels.rules.DetectionProfile
import org.junit.Assert.*
import org.junit.Test

class CalibratorTest {
    private val common = setOf("action_bar", "tab_bar", "bottom_nav", "feed_list", "profile_tab", "search_tab")

    private fun reel(i: Int) = RawScan(
        nodeCount = 60, allIds = common + setOf("pager_x", "video_box", "fab_like"),
        selectedIds = setOf("reels_tab_x"), containerFraction = 0f,
        scrollableIds = setOf("pager_x"), idFractions = mapOf("pager_x" to 0.97f, "video_box" to 0.97f),
        textInfo = mapOf("clips_author_username" to "h$i:9", "clips_caption_component" to "c$i:120", "like_count" to "l$i:4", "static_tv" to "same:5"))
    private fun feed(i: Int) = RawScan(nodeCount = 80, allIds = common + setOf("feed_row", "fab_like"),
        selectedIds = setOf("home_tab"), scrollableIds = setOf("feed_list"), idFractions = mapOf("feed_row" to 0.5f),
        textInfo = mapOf("row_user" to "f$i:8"))
    private fun story(i: Int) = RawScan(nodeCount = 30, allIds = setOf("story_root", "story_bar"), selectedIds = emptySet(),
        idFractions = mapOf("story_root" to 1f))

    @Test fun learns_reel_ids_from_labeled_samples_and_validates_on_them() {
        val c = Calibrator()
        repeat(15) { c.add(CalLabel.REEL, reel(it)); c.add(CalLabel.NOT_REEL, feed(it)) }
        repeat(6) { c.add(CalLabel.STORY, story(it)) }
        val r = c.suggest(DetectionProfile())
        assertNull(r.error)
        val p = r.suggestion!!.profile
        assertTrue(p.reelStrongIds.containsAll(setOf("pager_x", "video_box")))
        assertFalse("fab_like موجود في Feed أيضًا فلا يميّز", "fab_like" in p.reelStrongIds)
        assertEquals(setOf("reels_tab_x"), p.clipsTabIds)
        assertEquals(setOf("pager_x"), p.scrollSourceIds)
        assertEquals(setOf("story_root", "story_bar"), p.storyIds)
        assertEquals(setOf("clips_author_username"), p.authorIds); assertEquals(setOf("clips_caption_component"), p.captionIds)
        assertFalse("عدّاد الإعجابات ليس هوية", "like_count" in p.authorIds + p.captionIds)
        assertTrue(r.suggestion!!.notes.last().contains("15/15"))
        assertTrue(r.suggestion!!.notes.last().contains("إيجابيات كاذبة 0"))
    }

    @Test fun calibrated_profile_actually_detects_through_adapter() {
        val c = Calibrator()
        repeat(12) { c.add(CalLabel.REEL, reel(it)); c.add(CalLabel.NOT_REEL, feed(it)) }
        val p = c.suggest(DetectionProfile()).suggestion!!.profile
        val a = com.reelguard.platform.reels.ReelsAdapter(com.reelguard.platform.common.AppPlatform.INSTAGRAM, p)
        assertEquals(com.reelguard.core.model.DetectionState.CONFIRMED_REEL, a.detectContext(reel(99).copy(containerFraction = 0.97f), 0).detection.state)
        assertNotEquals(com.reelguard.core.model.DetectionState.CONFIRMED_REEL, a.detectContext(feed(99), 0).detection.state)
    }

    @Test fun learns_stable_audio_element_as_third_identity_part() {
        val c = Calibrator()
        repeat(15) { i ->
            c.add(CalLabel.REEL, reel(i).copy(textInfo = reel(i).textInfo + ("clips_audio_attribution" to "a$i:12")))
            c.add(CalLabel.NOT_REEL, feed(i))
        }
        val p = c.suggest(DetectionProfile()).suggestion!!.profile
        assertEquals(setOf("clips_audio_attribution"), p.extraIdentityIds)
    }

    @Test fun does_not_adopt_audio_element_that_is_often_missing() {
        val c = Calibrator()
        repeat(15) { i ->
            val base = reel(i)
            c.add(CalLabel.REEL, if (i % 2 == 0) base.copy(textInfo = base.textInfo + ("clips_audio_attribution" to "a$i:12")) else base)
            c.add(CalLabel.NOT_REEL, feed(i))
        }
        assertTrue(c.suggest(DetectionProfile()).suggestion!!.profile.extraIdentityIds.isEmpty())
    }

    @Test fun profile_id_keeps_the_platform_prefix() {
        val c = Calibrator()
        repeat(12) { c.add(CalLabel.REEL, reel(it)); c.add(CalLabel.NOT_REEL, feed(it)) }
        val p = c.suggest(DetectionProfile(profileId = "youtube-default")).suggestion!!.profile
        assertEquals("youtube-calibrated", p.profileId)
    }

    @Test fun refuses_when_samples_are_insufficient_or_indistinguishable() {
        val c = Calibrator()
        repeat(3) { c.add(CalLabel.REEL, reel(it)) }
        assertNotNull(c.suggest(DetectionProfile()).error)
        val d = Calibrator()
        repeat(10) { d.add(CalLabel.REEL, feed(it)); d.add(CalLabel.NOT_REEL, feed(it + 50)) }
        assertNotNull("لا فرق بين العيّنتين ⇒ لا اقتراح", d.suggest(DetectionProfile()).error)
    }

    @Test fun lowers_active_threshold_when_container_is_smaller_than_default() {
        val c = Calibrator()
        repeat(12) { c.add(CalLabel.REEL, reel(it).copy(idFractions = mapOf("pager_x" to 0.45f, "video_box" to 0.45f))); c.add(CalLabel.NOT_REEL, feed(it)) }
        val p = c.suggest(DetectionProfile()).suggestion!!.profile
        assertTrue(p.minScreenFraction < 0.6f && p.minScreenFraction >= 0.25f)
    }
}
