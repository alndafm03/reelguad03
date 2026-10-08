package com.reelguard.core.health

import com.reelguard.core.model.ContentType
import com.reelguard.core.model.DetectionResult
import com.reelguard.core.model.DetectionState
import com.reelguard.core.time.TimeSource

enum class Level { OK, WARN, OFF }

data class HealthReport(
    val protection: Level,
    val platform: Level,
    val detection: Level,
    val accessibility: Level,
    val currentReel: Level,
    /** UNKNOWN المستمر وغير الطبيعي (§43) */
    val needsAttention: Boolean
)

/** تقدّم اختبار الكشف في الـOnboarding (§50). */
data class DetectionTest(
    val platformDetected: Boolean = false,
    val reelDetected: Boolean = false,
    val transitionDetected: Boolean = false
) { val ready: Boolean get() = platformDetected && reelDetected && transitionDetected }

/** Detection Health (§42-43). لا يعتبر كل UNKNOWN خطأ؛ فقط الاستمرار. */
class HealthMonitor(private val time: TimeSource, private val unknownThreshold: Int = 20) {
    var serviceConnected = false
    var platformActive = false
        private set
    var consecutiveUnknown = 0
        private set
    var lastDetection: DetectionResult? = null
        private set
    var test = DetectionTest()
        private set

    private var lastConfirmedElapsed = -1L

    fun onPlatformActive() { platformActive = true; test = test.copy(platformDetected = true) }
    fun onPlatformInactive() { platformActive = false; consecutiveUnknown = 0 }

    fun onDetection(d: DetectionResult) {
        lastDetection = d
        if (d.state == DetectionState.UNKNOWN) { consecutiveUnknown++; return }
        consecutiveUnknown = 0
        if (d.state == DetectionState.CONFIRMED_REEL && d.contentType == ContentType.REEL) {
            lastConfirmedElapsed = time.elapsedMs()
            test = test.copy(reelDetected = true)
        }
    }

    fun onUnknown() { consecutiveUnknown++ }
    fun onTransition() { test = test.copy(transitionDetected = true) }
    fun resetTest() { test = DetectionTest() }

    val needsAttention: Boolean get() = platformActive && consecutiveUnknown >= unknownThreshold

    fun report(protectionOn: Boolean, accessibilityEnabled: Boolean): HealthReport {
        val recentReel = lastConfirmedElapsed >= 0 && time.elapsedMs() - lastConfirmedElapsed < 5000L
        return HealthReport(
            protection = if (protectionOn && accessibilityEnabled) Level.OK else Level.OFF,
            platform = if (platformActive) Level.OK else Level.OFF,
            detection = when {
                !platformActive -> Level.OFF
                needsAttention -> Level.WARN
                else -> Level.OK
            },
            accessibility = if (accessibilityEnabled && serviceConnected) Level.OK
                else if (accessibilityEnabled) Level.WARN else Level.OFF,
            currentReel = if (recentReel && platformActive) Level.OK else Level.OFF,
            needsAttention = needsAttention
        )
    }
}
