package com.reelguard.core.events

import com.reelguard.core.model.ContentIdentity
import com.reelguard.core.model.DetectionResult

/**
 * الأحداث العامة بين الـAdapter والـCore (§6، §36). لا يوجد أي ذكر لمنصة بعينها هنا.
 * الواردة للـCore: من الـAdapter والخدمة. الصادرة: يبثّها الـCore للمراقبين (UI/اختبارات/سجل).
 */
/** أسباب ContentChanged المعروفة للـCore (لا ارتباط بمنصة). */
object ChangeReason {
    /** تمريرة مستقرة لكن بصمة الهوية لم تتغيّر ⇒ اشتباه تصادم؛ يُعامَل كمحتوى جديد بلا dedupe. */
    const val SAME_IDENTITY_SCROLL = "scroll+same-identity"
}

sealed class DomainEvent {
    // ---- واردة من Adapter ----
    data class PlatformActive(val platform: String) : DomainEvent()
    object PlatformInactive : DomainEvent()
    data class ReelCandidate(val detection: DetectionResult) : DomainEvent()
    data class ReelConfirmed(
        val detection: DetectionResult,
        val contentKey: String,
        val identity: ContentIdentity,
        val active: Boolean
    ) : DomainEvent()
    data class ReelRejected(val detection: DetectionResult) : DomainEvent()
    data class DetectionUnknown(val reason: String) : DomainEvent()
    data class ContentChanged(val from: String?, val to: String, val by: String) : DomainEvent()
    /** ظهرت هوية لمحتوى كان بمفتاح مؤقت: نفس المحتوى، لا انتقال. */
    data class ContentIdUpdated(val from: String, val to: String) : DomainEvent()
    /** تلميح أن محتوى جديدًا قد يُطلَب (تمرير). */
    object NewContentCandidate : DomainEvent()

    // ---- واردة من الخدمة ----
    object ServiceConnected : DomainEvent()
    object ServiceDisconnected : DomainEvent()
    object OverlayLost : DomainEvent()
    object OverlayRestored : DomainEvent()

    // ---- صادرة من Core ----
    data class WatchStarted(val key: String) : DomainEvent()
    data class WatchInterrupted(val reason: String) : DomainEvent()
    data class WatchCompleted(val key: String) : DomainEvent()
    data class ContentConsumed(val key: String, val identityKnown: Boolean) : DomainEvent()
    object LimitReached : DomainEvent()
    object NewReelAttempted : DomainEvent()
    data class RestrictionStarted(val reason: String) : DomainEvent()
    object RestrictionEnded : DomainEvent()
}
