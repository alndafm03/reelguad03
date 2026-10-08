package com.reelguard.core.model

/** نماذج عامة يفهمها الـCore دون معرفة أي منصة (§6). */

enum class DetectionState { UNKNOWN, NOT_REEL, POSSIBLE_REEL, PROBABLE_REEL, CONFIRMED_REEL }
enum class ContentType { REEL, STORY, OTHER, UNKNOWN }
enum class ActiveContentState { ACTIVE, INACTIVE, UNKNOWN }
enum class IdentityConfidence { EXACT, STABLE, PROBABLE, UNKNOWN }

/** نتيجة الكشف مع سبب القرار (§11). */
data class DetectionResult(
    val state: DetectionState,
    val contentType: ContentType,
    val confidence: Int,
    val signals: List<String>,
    val timestamp: Long
)

/** هوية المحتوى (§16-18). key هو hash مختصر فقط، لا نص. */
data class ContentIdentity(val key: String?, val confidence: IdentityConfidence) {
    /** فقط EXACT/STABLE تصلح لمنع التكرار؛ PROBABLE/UNKNOWN لا تُستعمل لمنع الخصم. */
    val dedupable: Boolean
        get() = key != null && (confidence == IdentityConfidence.EXACT || confidence == IdentityConfidence.STABLE)

    companion object { val UNKNOWN = ContentIdentity(null, IdentityConfidence.UNKNOWN) }
}

/** وضعان فقط: تنبيه عند بلوغ الحد، أو رصيد + تقييد (قفل) فوري. strength يُستعمل لتمييز التشديد عن التخفيف. */
enum class ProtectionMode(val strength: Int) { WARNING_ONLY(0), BUDGET_LOCK(1) }
/** أساس احتساب الحماية: عدد الـReels، أو وقت استخدام التطبيق كاملًا. */
enum class ProtectionBasis { COUNT, TIME }
enum class RestrictionType { WARNING, LOCK }
enum class DuplicatePolicy { TRUSTED_IDENTITY, NONE }
