package com.reelguard.platform.reels.rules

/**
 * Detection Profile (§13) — ملف لكل تطبيق (Instagram / Facebook / YouTube): قواعد الكشف منفصلة عن الكود. الأرقام معايرة أوّلية
 * ويجب ضبطها من نتائج Phase 0 (§11، §66) — لا تُعدَّل الأكواد، بل ملف الـJSON.
 */
data class DetectionProfile(
    val version: Int = 1,
    val profileId: String = "instagram-default",
    // package rules
    val packages: Set<String> = setOf("com.instagram.android"),
    // node / structure rules
    val reelStrongIds: Set<String> = emptySet(),
    val clipsTabIds: Set<String> = emptySet(),
    val storyIds: Set<String> = emptySet(),
    val contentContainerIds: Set<String> = emptySet(),
    val videoClassNames: Set<String> = emptySet(),
    // text rules
    val reelDescPatterns: List<Regex> = emptyList(),
    // identity rules
    val authorIds: Set<String> = emptySet(),
    val captionIds: Set<String> = emptySet(),
    val extraIdentityIds: Set<String> = emptySet(),
    /** عناصر تحمل معرّف محتوى صريحًا (إن اكتُشفت في Phase 0) ⇒ هوية EXACT */
    val contentIdIds: Set<String> = emptySet(),
    // transition rules
    val scrollSourceIds: Set<String> = emptySet(),
    val settleMs: Long = 350,
    val hintWindowMs: Long = 2500,
    /** بعد كم ms من غياب Reel مؤكد نُصفّر مفتاح المحتوى الحالي */
    val leaveMs: Long = 1500,
    val debounceMs: Long = 200,
    // confidence weights (0..100)
    val packageWeight: Int = 10,
    val strongWeight: Int = 35,
    val tabWeight: Int = 20,
    val descWeight: Int = 35,
    val containerWeight: Int = 15,
    val videoWeight: Int = 10,
    val possibleScore: Int = 30,
    val probableScore: Int = 60,
    val confirmScore: Int = 80,
    val minScreenFraction: Float = 0.6f,
    // scan limits
    val maxNodes: Int = 600,
    val maxDepth: Int = 40,
    val minNodes: Int = 5,
    /** false ⇒ لا نعتبر الشجرة بلا view IDs معتمة (مفيد لتطبيقات تعتمد على contentDescription فقط). */
    val requireIds: Boolean = true,
    /** true ⇒ أي تمرير كبير (≥ minScreenFraction) يُعدّ تلميح انتقال إن لم يُطابق scrollSourceIds. شبكة أمان للتطبيقات غامضة المعرّفات. */
    val scrollFallback: Boolean = false,
    /** طول بداية الوصف في بصمة الهوية. لا تزِد دون قياس: الوصف المقتطع قد يختلف عن الموسَّع. */
    val captionPrefix: Int = 80
)
