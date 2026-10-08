package com.reelguard.core.time

/** مصدر الوقت (§47). ثلاث ساعات: الجدار (يتأثر بتغيير الساعة)، المنقضي منذ الإقلاع، وعدّاد الإقلاعات. */
interface TimeSource {
    fun wallMs(): Long
    fun elapsedMs(): Long
    fun bootCount(): Int
}
