package com.reelguard.testing.replay

import com.reelguard.core.events.DomainEvent
import com.reelguard.platform.common.RawScan
import com.reelguard.platform.common.ShortVideoPlatformAdapter

/** إطار مسجَّل: لقطة Accessibility مجرّدة عند زمن معيّن (§59). */
data class ReplayFrame(val atMs: Long, val scan: RawScan?, val scrollHint: String? = null)

data class FrameResult(val frame: ReplayFrame, val events: List<DomainEvent>)

data class ReplaySummary(
    val frames: Int, val confirmed: Int, val candidates: Int, val rejected: Int,
    val unknown: Int, val transitions: Int, val idUpdates: Int,
    /** Reels مؤكدة ونشطة (هي وحدها القابلة للاحتساب) */
    val activeConfirmed: Int
)

/**
 * يعيد تشغيل تسلسل مسجّل عبر أي Adapter دون فتح Instagram، ما يسمح بمقارنة
 * Detector V1 مقابل V2 على نفس المدخلات (§59).
 */
class ReplayRunner(private val adapter: ShortVideoPlatformAdapter) {
    fun run(frames: List<ReplayFrame>): List<FrameResult> {
        adapter.reset()
        return frames.map { f ->
            f.scrollHint?.let { adapter.onScrollHint(it, f.atMs) }
            FrameResult(f, adapter.process(f.scan, f.atMs))
        }
    }

    companion object {
        fun summary(results: List<FrameResult>): ReplaySummary {
            val ev = results.flatMap { it.events }
            return ReplaySummary(
                frames = results.size,
                confirmed = ev.count { it is DomainEvent.ReelConfirmed },
                candidates = ev.count { it is DomainEvent.ReelCandidate },
                rejected = ev.count { it is DomainEvent.ReelRejected },
                unknown = ev.count { it is DomainEvent.DetectionUnknown },
                transitions = ev.count { it is DomainEvent.ContentChanged },
                idUpdates = ev.count { it is DomainEvent.ContentIdUpdated },
                activeConfirmed = ev.count { it is DomainEvent.ReelConfirmed && it.active }
            )
        }
    }
}
