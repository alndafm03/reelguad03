package com.reelguard.platform.common

import com.reelguard.core.events.DomainEvent
import com.reelguard.core.model.ActiveContentState
import com.reelguard.core.model.ContentIdentity
import com.reelguard.core.model.DetectionResult

/**
 * الحد الأدنى من الإشارات المستخرجة من شجرة Accessibility (§10).
 * كائن بيانات نقي (بلا Android) ⇒ قابل لإعادة التشغيل في الاختبارات (§59).
 * النصوص (author/caption/extra/contentIdText) لا تُخزَّن؛ تُستعمل فقط لحساب hash للهوية.
 */
data class RawScan(
    val nodeCount: Int,
    val allIds: Set<String>,
    val selectedIds: Set<String>,
    val descHit: Boolean = false,
    val descFraction: Float = 0f,
    val containerFraction: Float = 0f,
    val videoFraction: Float = 0f,
    val author: String? = null,
    val caption: String? = null,
    val extra: String? = null,
    val contentIdText: String? = null,
    /** تُملأ فقط أثناء المعايرة: عناصر قابلة للتمرير، نسبة شغل كل id، وبصمة نص كل id (hash:طول) */
    val scrollableIds: Set<String> = emptySet(),
    val idFractions: Map<String, Float> = emptyMap(),
    val textInfo: Map<String, String> = emptyMap()
)

data class ContentSnapshot(
    val detection: DetectionResult,
    val identity: ContentIdentity,
    val active: ActiveContentState,
    val fraction: Float,
    val nodeCount: Int,
    val allIds: Set<String>
)

data class PlatformCapabilities(
    val supportsIdentity: Boolean,
    val supportsScrollHint: Boolean,
    val supportsActiveContent: Boolean
)

/** كل منصة لها Adapter مستقل (§7). يحوّل واجهة المنصة إلى أحداث عامة للـCore. */
interface ShortVideoPlatformAdapter {
    val platformId: String
    fun reportCapabilities(): PlatformCapabilities

    /** getActiveApplication */
    fun isPlatformPackage(pkg: String?): Boolean

    /** detectContext + detectContent + identifyContent */
    fun detectContext(scan: RawScan?, now: Long): ContentSnapshot

    /** تلميح تمرير من مصدر معروف. يعيد true إن كان ذا صلة. */
    fun onScrollHint(sourceId: String?, now: Long): Boolean

    /** detectTransition + تحويل كل شيء إلى أحداث عامة. */
    fun process(scan: RawScan?, now: Long): List<DomainEvent>

    fun reset()
}
