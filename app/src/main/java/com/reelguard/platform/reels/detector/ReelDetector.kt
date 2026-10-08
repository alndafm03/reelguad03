package com.reelguard.platform.reels.detector

import com.reelguard.core.model.ActiveContentState
import com.reelguard.core.model.ContentType
import com.reelguard.core.model.DetectionResult
import com.reelguard.core.model.DetectionState
import com.reelguard.platform.common.RawScan
import com.reelguard.platform.reels.rules.DetectionProfile

data class ReelDetection(val result: DetectionResult, val fraction: Float, val active: ActiveContentState)

/**
 * Reel Detector (§14): لا يعتمد على إشارة واحدة؛ يجمع إشارات موزونة إلى درجة ثقة 0..100 (§11).
 * أي غموض ⇒ UNKNOWN / POSSIBLE ⇒ لا خصم (Fail-Safe §3.3).
 */
class ReelDetector(private val p: DetectionProfile) {

    companion object {
        const val S_PACKAGE = "PACKAGE_MATCH"
        const val S_TAB = "REELS_TAB_SELECTED"
        const val S_DESC = "REEL_DESC_PATTERN"
        const val S_CONTAINER = "CONTAINER_LARGE"
        const val S_VIDEO = "VIDEO_INDICATOR"
        const val S_STRUCT = "REELS_STRUCTURE:"
        const val S_STORY = "STORY_STRUCTURE:"
    }

    fun detect(scan: RawScan?, now: Long): ReelDetection {
        // شجرة فارغة/معتمة (بلا view IDs، ما لم يُعطَّل requireIds) ⇒ لا نعرف
        if (scan == null || scan.nodeCount < p.minNodes || (p.requireIds && scan.allIds.isEmpty())) {
            return ReelDetection(
                DetectionResult(DetectionState.UNKNOWN, ContentType.UNKNOWN, 0, emptyList(), now),
                0f, ActiveContentState.UNKNOWN)
        }
        val storyHits = scan.allIds.intersect(p.storyIds)
        val strongHits = scan.allIds.intersect(p.reelStrongIds)
        val tabSelected = scan.selectedIds.any { it in p.clipsTabIds }
        val fraction = maxOf(scan.containerFraction, scan.descFraction, scan.videoFraction)
        val descOk = scan.descHit && scan.descFraction >= p.minScreenFraction
        val containerOk = scan.containerFraction >= p.minScreenFraction
        val videoOk = scan.videoFraction >= p.minScreenFraction

        // Stories: لا خصم من ميزانية Reels (§15)
        if (storyHits.isNotEmpty()) {
            return ReelDetection(
                DetectionResult(DetectionState.NOT_REEL, ContentType.STORY, 0,
                    listOf(S_PACKAGE) + storyHits.sorted().map { S_STORY + it }, now),
                fraction, ActiveContentState.INACTIVE)
        }

        val signals = ArrayList<String>()
        var score = p.packageWeight; signals.add(S_PACKAGE)
        strongHits.sorted().forEach { score += p.strongWeight; signals.add(S_STRUCT + it) }
        if (tabSelected) { score += p.tabWeight; signals.add(S_TAB) }
        if (descOk) { score += p.descWeight; signals.add(S_DESC) }
        if (containerOk && strongHits.isNotEmpty()) { score += p.containerWeight; signals.add(S_CONTAINER) }
        if (videoOk) { score += p.videoWeight; signals.add(S_VIDEO) }
        val confidence = score.coerceIn(0, 100)

        // حزمة الـpackage وحدها ليست دليلًا: نتجاهلها إن لم توجد أي إشارة أخرى
        val hasEvidence = signals.size > 1
        val state = when {
            !hasEvidence -> DetectionState.NOT_REEL
            confidence >= p.confirmScore -> DetectionState.CONFIRMED_REEL
            confidence >= p.probableScore -> DetectionState.PROBABLE_REEL
            confidence >= p.possibleScore -> DetectionState.POSSIBLE_REEL
            else -> DetectionState.NOT_REEL
        }
        val type = if (state == DetectionState.CONFIRMED_REEL) ContentType.REEL else ContentType.OTHER
        val active = when {
            state != DetectionState.CONFIRMED_REEL -> ActiveContentState.INACTIVE
            fraction >= p.minScreenFraction -> ActiveContentState.ACTIVE
            else -> ActiveContentState.INACTIVE
        }
        return ReelDetection(DetectionResult(state, type, if (hasEvidence) confidence else 0, signals, now), fraction, active)
    }
}
