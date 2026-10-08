package com.reelguard.core.diag

/** مقاييس التشخيص المجمّعة (§45). أرقام فقط، لا محتوى. */
object Metric {
    const val ATTEMPTS = "reelDetectionAttempts"
    const val CONFIRMED = "confirmedReels"
    const val UNKNOWN = "unknownStates"
    const val TRANSITIONS = "transitionDetections"
    const val ID_OK = "identitySuccess"
    const val ID_FAIL = "identityFailure"
    const val FALSE_POS = "falsePositiveCandidates"
    const val FALSE_NEG = "falseNegativeCandidates"
    const val WATCH_STARTS = "watchStarts"
    const val WATCH_INTERRUPTIONS = "watchInterruptions"
    const val CONSUMPTIONS = "consumptionEvents"
    const val RESTRICTIONS = "restrictionEvents"
    const val RECOVERIES = "recoveryEvents"
    /** تمريرة مستقرة انتهت بنفس بصمة الهوية: اشتباه تصادم بين Reelين (§18). */
    const val ID_COLLISION = "identityCollisionSuspects"
    val ALL = listOf(ATTEMPTS, CONFIRMED, UNKNOWN, TRANSITIONS, ID_OK, ID_FAIL, FALSE_POS, FALSE_NEG,
        WATCH_STARTS, WATCH_INTERRUPTIONS, CONSUMPTIONS, RESTRICTIONS, RECOVERIES, ID_COLLISION)
}

/** واجهة تسجيل مستقلة عن Android حتى يبقى الـCore قابلًا للاختبار. لا يُسجَّل أي محتوى حسّاس. */
interface Diag {
    fun inc(key: String)
    fun log(msg: String)
}

object NoopDiag : Diag {
    override fun inc(key: String) {}
    override fun log(msg: String) {}
}
