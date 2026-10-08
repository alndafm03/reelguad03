package com.reelguard.platform.common

/** التطبيقات المدعومة. المعرّف نصّ ثابت يُحفظ في الحالة ويستعمله الـCore دون معرفة المنصة. */
enum class AppPlatform(
    val id: String,
    val label: String,
    val asset: String,
    val packages: Set<String>
) {
    INSTAGRAM("instagram", "Instagram", "detection_profile_instagram.json", setOf("com.instagram.android")),
    FACEBOOK("facebook", "Facebook", "detection_profile_facebook.json", setOf("com.facebook.katana", "com.facebook.lite")),
    YOUTUBE("youtube", "YouTube", "detection_profile_youtube.json", setOf("com.google.android.youtube"));

    /** الحزمة المستعملة لفتح التطبيق من واجهة المعايرة. */
    val launchPackage: String get() = packages.first()

    companion object {
        fun byId(id: String): AppPlatform? = values().firstOrNull { it.id == id }
        val ALL_IDS: Set<String> = values().map { it.id }.toSet()
    }
}
