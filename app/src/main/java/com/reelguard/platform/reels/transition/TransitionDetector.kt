package com.reelguard.platform.reels.transition

import com.reelguard.core.events.ChangeReason
import com.reelguard.core.model.ContentIdentity
import com.reelguard.core.model.IdentityConfidence

sealed class TransitionOutcome {
    /** انتقال فعلي إلى محتوى جديد. */
    data class Changed(val from: String?, val to: String, val by: String) : TransitionOutcome()
    /** ظهرت هوية لنفس المحتوى (كان بمفتاح مؤقت): ليس انتقالًا. */
    data class IdUpdated(val from: String, val to: String) : TransitionOutcome()
}

/**
 * Transition Detector (§19). وحدة مستقلة عن الرصيد.
 * يجمع: تغيّر الهوية الموثوقة + تلميح تمرير مستقر زمنيًا.
 *  - هوية مختلفة ⇒ انتقال.
 *  - بلا هوية موثوقة: تلميح تمرير مستقر ⇒ مفتاح مؤقت جديد (u1, u2…) بلا dedupe.
 *  - هوية STABLE (بصمة) لم تتغيّر رغم تمريرة مستقرة ⇒ اشتباه تصادم بين Reelين: يُعتبر محتوى جديدًا
 *    بمفتاح "sig:…#n" (لا يُطبَّق عليه dedupe). EXACT لا يُعامَل هكذا لأن هويته مؤكدة.
 *  - تمريرات تصل بعد انتقال مباشرةً (بقايا حركة نفس السحبة) تُهمَل خلال QUIET_MS.
 */
class TransitionDetector(private val settleMs: Long, private val hintWindowMs: Long = 2500L) {
    companion object { const val QUIET_MS = 700L }

    var currentKey: String? = null
        private set
    private var epoch = 0
    private var hintAt = 0L
    private var hintPending = false
    private var lastChangeAt: Long? = null

    private fun isTemp(k: String) = k.startsWith("u")
    private fun base(k: String) = k.substringBefore('#')

    fun onScrollHint(now: Long) { hintAt = now; hintPending = true }

    fun reset() { currentKey = null; hintPending = false; lastChangeAt = null }

    private fun changed(now: Long, to: String, from: String?, by: String): TransitionOutcome.Changed {
        currentKey = to; hintPending = false; lastChangeAt = now
        return TransitionOutcome.Changed(from, to, by)
    }

    fun evaluate(identity: ContentIdentity, now: Long): TransitionOutcome? {
        val id = if (identity.dedupable) identity.key else null
        val prev = currentKey
        if (hintPending && now - hintAt > hintWindowMs) hintPending = false
        // بقايا حركة السحبة التي سبقت آخر انتقال: تُهمَل
        val last = lastChangeAt
        if (hintPending && last != null && hintAt - last < QUIET_MS) hintPending = false
        val settledHint = hintPending && now - hintAt >= settleMs

        if (id != null) {
            if (prev == null) { currentKey = id; hintPending = false; return null }
            if (base(prev) == id) {
                if (settledHint && identity.confidence == IdentityConfidence.STABLE)
                    return changed(now, "$id#${++epoch}", prev, ChangeReason.SAME_IDENTITY_SCROLL)
                if (settledHint) hintPending = false
                return null
            }
            val to = id
            val by = if (isTemp(prev)) "scroll+id" else "identity"
            if (isTemp(prev) && !settledHint) {
                currentKey = to; hintPending = false
                return TransitionOutcome.IdUpdated(prev, to)
            }
            return changed(now, to, prev, by)
        }
        // لا هوية موثوقة حاليًا
        if (prev == null) { currentKey = "u${++epoch}"; return null }
        if (settledHint) return changed(now, "u${++epoch}", prev, "scroll")
        return null // نحتفظ بالمفتاح الحالي (لا نفترض تغيّرًا بلا دليل)
    }
}
