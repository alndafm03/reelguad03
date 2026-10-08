package com.reelguard.platform.reels.identity

import com.reelguard.core.model.ContentIdentity
import com.reelguard.core.model.IdentityConfidence
import com.reelguard.platform.common.RawScan
import java.security.MessageDigest

/**
 * Content Identity Provider (§16-17). مصادر بالترتيب:
 *  1) معرّف محتوى ظاهر (contentIdIds في الـProfile) ⇒ EXACT.
 *  2) بصمة مستقرة من ثلاثة عناصر: اسم الحساب + بداية الوصف + عنصر ثالث (extra: مقطع الصوت/الموسيقى) ⇒ STABLE.
 *  3) اسم الحساب وحده ⇒ PROBABLE (لا يُستعمل لمنع الخصم).
 *  4) غير ذلك ⇒ UNKNOWN.
 * المفتاح مُسبَّق بمعرّف المنصة كي لا يتصادم حساب يحمل الاسم نفسه في تطبيقين.
 * لا يُخزَّن أي نص؛ hash مختصر فقط.
 */
object ContentIdentityProvider {
    const val DEFAULT_CAPTION_PREFIX = 80

    private val INVISIBLE = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\uFEFF]")
    private val SPACES = Regex("\\s+")

    /** توحيد النص كي لا تغيّر المسافات/الأحرف غير المرئية البصمة. */
    internal fun norm(s: String) = INVISIBLE.replace(s, "").let { SPACES.replace(it, " ") }.trim().lowercase()

    fun resolve(scan: RawScan, platformId: String = "", captionPrefix: Int = DEFAULT_CAPTION_PREFIX): ContentIdentity {
        scan.contentIdText?.takeIf { it.isNotBlank() }?.let {
            return ContentIdentity("id:" + sha16("$platformId|$it"), IdentityConfidence.EXACT)
        }
        val author = scan.author?.takeIf { it.isNotBlank() }?.let(::norm)
        val caption = scan.caption?.takeIf { it.isNotBlank() }?.let(::norm)
        if (!author.isNullOrEmpty() && !caption.isNullOrEmpty()) {
            val extra = scan.extra?.let(::norm).orEmpty()
            val raw = "$platformId|$author|${caption.take(captionPrefix.coerceAtLeast(1))}|$extra"
            return ContentIdentity("sig:" + sha16(raw), IdentityConfidence.STABLE)
        }
        if (!author.isNullOrEmpty()) return ContentIdentity("au:" + sha16("$platformId|$author"), IdentityConfidence.PROBABLE)
        return ContentIdentity.UNKNOWN
    }

    fun sha16(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
}
