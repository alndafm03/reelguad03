package com.reelguard.core.time

/**
 * مؤقت محفوظ يقاوم تغيير الساعة: داخل نفس الإقلاع يُحسب بـelapsedRealtime،
 * وبعد إعادة التشغيل بالساعة الفعلية مقابل نهاية المدة (نفس منطق القفل).
 */
data class Countdown(val startWall: Long, val endWall: Long, val startElapsed: Long, val bootCount: Int) {
    fun remainingMs(time: TimeSource): Long {
        val duration = (endWall - startWall).coerceAtLeast(0L)
        val sameBoot = bootCount >= 0 && bootCount == time.bootCount() && time.elapsedMs() >= startElapsed
        val remaining = if (sameBoot) duration - (time.elapsedMs() - startElapsed) else endWall - time.wallMs()
        return remaining.coerceIn(0L, duration)
    }

    companion object {
        fun start(durationMs: Long, time: TimeSource) =
            Countdown(time.wallMs(), time.wallMs() + durationMs, time.elapsedMs(), time.bootCount())
    }
}
