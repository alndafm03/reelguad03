package com.reelguard.platform.reels

import com.reelguard.core.events.DomainEvent
import com.reelguard.core.model.ActiveContentState
import com.reelguard.core.model.ContentIdentity
import com.reelguard.core.model.DetectionState
import com.reelguard.core.model.IdentityConfidence
import com.reelguard.platform.common.AppPlatform
import com.reelguard.platform.common.ContentSnapshot
import com.reelguard.platform.common.PlatformCapabilities
import com.reelguard.platform.common.RawScan
import com.reelguard.platform.common.ShortVideoPlatformAdapter
import com.reelguard.platform.reels.detector.ReelDetector
import com.reelguard.platform.reels.identity.ContentIdentityProvider
import com.reelguard.platform.reels.rules.DetectionProfile
import com.reelguard.platform.reels.transition.TransitionDetector
import com.reelguard.platform.reels.transition.TransitionOutcome

/**
 * Adapter موحّد لتطبيقات الفيديو القصير (Instagram Reels / Facebook Reels / YouTube Shorts):
 * الاختلاف بين التطبيقات كله في الـDetectionProfile (JSON)، لا في الكود.
 * يحوّل واجهة التطبيق إلى أحداث عامة فقط؛ لا رصيد ولا قفل هنا.
 */
class ReelsAdapter(val platform: AppPlatform, val profile: DetectionProfile) : ShortVideoPlatformAdapter {

    override val platformId = platform.id
    private val reelDetector = ReelDetector(profile)
    private val transition = TransitionDetector(profile.settleMs, profile.hintWindowMs)
    private var lastConfirmedAt = Long.MIN_VALUE

    /** آخر لقطة (لسجل Phase 0 والتشخيص). */
    var lastSnapshot: ContentSnapshot? = null
        private set

    override fun reportCapabilities() = PlatformCapabilities(
        supportsIdentity = profile.authorIds.isNotEmpty() && profile.captionIds.isNotEmpty() || profile.contentIdIds.isNotEmpty(),
        supportsScrollHint = profile.scrollSourceIds.isNotEmpty(),
        supportsActiveContent = profile.contentContainerIds.isNotEmpty()
    )

    override fun isPlatformPackage(pkg: String?) = pkg != null && pkg in profile.packages

    override fun detectContext(scan: RawScan?, now: Long): ContentSnapshot {
        val d = reelDetector.detect(scan, now)
        val confirmed = d.result.state == DetectionState.CONFIRMED_REEL
        val identity = if (confirmed && scan != null)
            ContentIdentityProvider.resolve(scan, platformId, profile.captionPrefix) else ContentIdentity.UNKNOWN
        val snap = ContentSnapshot(d.result, identity, d.active, d.fraction, scan?.nodeCount ?: 0, scan?.allIds ?: emptySet())
        lastSnapshot = snap
        return snap
    }

    /** تلميح تمرير من مصدر كبير غير معروف المعرّف (يُستعمل فقط إن فُعِّل scrollFallback في الـProfile). */
    fun onLargeScrollHint(now: Long): Boolean {
        if (!profile.scrollFallback) return false
        transition.onScrollHint(now); return true
    }

    override fun onScrollHint(sourceId: String?, now: Long): Boolean {
        if (sourceId == null || sourceId !in profile.scrollSourceIds) return false
        transition.onScrollHint(now)
        return true
    }

    override fun process(scan: RawScan?, now: Long): List<DomainEvent> {
        val snap = detectContext(scan, now)
        val det = snap.detection
        return when (det.state) {
            DetectionState.UNKNOWN -> listOf(DomainEvent.DetectionUnknown("opaque_tree"))   // لا نُصفّر شيئًا (Fail-Safe)
            DetectionState.CONFIRMED_REEL -> {
                lastConfirmedAt = now
                val out = ArrayList<DomainEvent>(2)
                when (val t = transition.evaluate(snap.identity, now)) {
                    is TransitionOutcome.IdUpdated -> out.add(DomainEvent.ContentIdUpdated(t.from, t.to))
                    is TransitionOutcome.Changed -> out.add(DomainEvent.ContentChanged(t.from, t.to, t.by))
                    null -> Unit
                }
                val key = transition.currentKey ?: "u0"
                // مفتاح "…#n" = محتوى اشتُبه بتصادم بصمته مع السابق: لا يُطبَّق عليه منع التكرار أبدًا
                val identity = if ('#' in key) ContentIdentity(null, IdentityConfidence.PROBABLE) else snap.identity
                out.add(DomainEvent.ReelConfirmed(det, key, identity, snap.active == ActiveContentState.ACTIVE))
                out
            }
            DetectionState.POSSIBLE_REEL, DetectionState.PROBABLE_REEL -> { maybeLeave(now); listOf(DomainEvent.ReelCandidate(det)) }
            DetectionState.NOT_REEL -> { maybeLeave(now); listOf(DomainEvent.ReelRejected(det)) }
        }
    }

    /** غياب Reel مؤكد لفترة كافية ⇒ المحتوى التالي لا يُعتبر استمرارًا للسابق. */
    private fun maybeLeave(now: Long) {
        if (lastConfirmedAt != Long.MIN_VALUE && now - lastConfirmedAt > profile.leaveMs) {
            transition.reset(); lastConfirmedAt = Long.MIN_VALUE
        }
    }

    override fun reset() { transition.reset(); lastConfirmedAt = Long.MIN_VALUE; lastSnapshot = null }
}
