package com.reelguard.accessibility.overlay

import android.accessibilityservice.AccessibilityService
import com.reelguard.core.engine.CoreEngine
import com.reelguard.core.engine.OverlayCommand
import com.reelguard.core.events.DomainEvent

/**
 * Overlay Manager (§33): CounterOverlay / WarningOverlay / LockOverlay / RecoveryOverlay.
 * ينفّذ OverlayCommand القادمة من الـCore ويقرأ منه فقط؛ لا يملك حالة ولا يصل لقاعدة البيانات.
 */
class OverlayManager(private val service: AccessibilityService, private val core: CoreEngine) {

    private val lost = { core.handle(DomainEvent.OverlayLost) }
    private val counter = CounterOverlay(service, lost)
    private val warning = BannerOverlay(service, lost)
    private val recovery = BannerOverlay(service, lost)
    private val lock = LockOverlay(
        service,
        info = { LockInfo(core.state.cycleLimit, core.state.lockReason, (core.state.cycleTimeMs / 60_000L).toInt()) { core.lockRemainingMs() } },
        onExpiredCheck = { core.lockRemainingMs() },
        onExpired = { core.handle(DomainEvent.PlatformActive(core.activePlatform)) },   // يعيد تقييم الحالة بعد انتهاء القفل
        onShown = { core.handle(DomainEvent.OverlayRestored) },
        onLost = lost
    )

    private val calPanel = CalibrationPanel(service, lost)

    val isLockShown get() = lock.isShown

    fun showCalibration(s: com.reelguard.platform.reels.calibration.CalibrationSession) = calPanel.show(s)
    fun hideCalibration() = calPanel.hide()

    fun apply(cmd: OverlayCommand) {
        when (cmd) {
            is OverlayCommand.ShowCounter ->
                if (core.state.showCounter && !lock.isShown) counter.show(cmd.remaining, cmd.limit) else counter.hide()
            is OverlayCommand.ShowTimeCounter ->
                if (core.state.showCounter && !lock.isShown) counter.showText("⏱ " + fmtTime(cmd.remainingMs)) else counter.hide()
            OverlayCommand.HideCounter -> counter.hide()
            is OverlayCommand.ShowWarning -> if (!lock.isShown) warning.show(cmd.text)
            OverlayCommand.ShowLock -> {
                val wasRecovering = core.state.lockState == com.reelguard.core.state.LockState.RECOVERING
                counter.hide(); warning.hide()
                lock.show()
                if (wasRecovering && lock.isShown) recovery.show("تمت استعادة الحماية بعد إعادة التشغيل.", 3000L, "#E6166534")
            }
            OverlayCommand.HideLock -> lock.hide()
            OverlayCommand.HideAll -> hideAll()
        }
    }

    private fun fmtTime(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        val h = t / 3600; val m = (t % 3600) / 60; val sec = t % 60
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(java.util.Locale.US, "%02d:%02d", m, sec)
    }

    fun hideAll() { lock.hide(); counter.hide(); warning.hide(); recovery.hide(); calPanel.hide() }
}
