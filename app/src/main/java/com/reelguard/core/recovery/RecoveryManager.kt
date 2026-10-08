package com.reelguard.core.recovery

import com.reelguard.core.diag.Diag
import com.reelguard.core.diag.Metric
import com.reelguard.core.restriction.RestrictionEngine
import com.reelguard.core.session.SessionManager
import com.reelguard.core.time.TimeSource

/**
 * Recovery Manager (§41): استعادة الحالة بعد فقدان الخدمة/الـOverlay/إقلاع الجهاز.
 * الحالة المحفوظة هي المرجع؛ هذا الصنف يعيد مزامنة الواجهة معها فقط.
 */
class RecoveryManager(
    private val restriction: RestrictionEngine,
    private val sessions: SessionManager,
    private val time: TimeSource,
    private val diag: Diag
) {
    private var lastOverlayRetry = 0L

    /** عند (إعادة) اتصال الخدمة. يعيد true إن كان هناك قفل محفوظ يلزم استعادته. */
    fun onServiceConnected(): Boolean {
        sessions.tick()
        if (restriction.refresh()) { diag.log("RECOVERY lock expired while service was down"); return false }
        val restore = restriction.markRecovering() || restriction.isLocked()
        if (restore) { diag.inc(Metric.RECOVERIES); diag.log("RECOVERY lock restored from persisted state") }
        return restore
    }

    fun onServiceDisconnected() { diag.log("SERVICE_DISCONNECTED") }

    /** يعيد true إذا وجب إعادة إنشاء الـOverlay (مع حد للمحاولات كي لا نُغرق النظام). */
    fun onOverlayLost(): Boolean {
        diag.log("OVERLAY_LOST")
        val now = time.elapsedMs()
        if (now - lastOverlayRetry < 1000L) return false
        lastOverlayRetry = now
        if (!restriction.isLocked()) return false
        diag.inc(Metric.RECOVERIES)
        return true
    }

    fun onOverlayRestored() {
        if (restriction.markRestored()) diag.log("RECOVERY overlay restored -> LOCKED")
    }
}
