package com.reelguard.accessibility.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.reelguard.ReelGuardApp
import com.reelguard.accessibility.nodes.AccessibilityScanner
import com.reelguard.accessibility.nodes.recycleNode
import com.reelguard.accessibility.overlay.OverlayManager
import com.reelguard.core.diag.Metric
import com.reelguard.core.engine.CoreHost
import com.reelguard.core.engine.OverlayCommand
import com.reelguard.core.events.DomainEvent
import com.reelguard.core.model.ProtectionBasis
import com.reelguard.platform.common.AppPlatform
import com.reelguard.platform.reels.ReelsAdapter
import com.reelguard.platform.reels.rules.ProfileLoader
import com.reelguard.testing.replay.ReplayFrame
import com.reelguard.testing.replay.ReplayJson
import java.util.Locale

/**
 * الخدمة تنقل الأحداث فقط: Accessibility → Adapter → Domain Events → Core.
 * لكل تطبيق (Instagram / Facebook / YouTube) Adapter خاص به؛ يُستعمل فقط إن كان التطبيق ضمن المحميّة.
 * القرار كله للـCore (§37). تدفق evaluate():
 *   المنصة؟ → قفل/حماية؟ → مسح الشجرة → Adapter.process → Core.handle.
 */
class ReelGuardService : AccessibilityService() {

    companion object {
        @Volatile var instance: ReelGuardService? = null
        private const val SYSTEM_UI = "com.android.systemui"
        private const val DEFAULT_DEBOUNCE_MS = 200L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val app get() = application as ReelGuardApp
    private val core get() = app.core
    private val diag get() = app.diag

    private var adapters: List<ReelsAdapter> = emptyList()
    /** آخر Adapter نشط (لإعادة ضبطه عند الانتقال لتطبيق آخر). */
    private var current: ReelsAdapter? = null
    private lateinit var overlay: OverlayManager
    private val timers = HashMap<String, Runnable>()
    private var lastSummary = ""

    private val evalRunnable = Runnable { evaluate() }
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { core.onScreenOff() }
    }

    /** ما يقدّمه Android للـCore. */
    private val host = object : CoreHost {
        override fun schedule(tag: String, delayMs: Long, action: () -> Unit) {
            cancel(tag)
            val r = Runnable { timers.remove(tag); action() }
            timers[tag] = r
            handler.postDelayed(r, delayMs)
        }
        override fun cancel(tag: String) { timers.remove(tag)?.let { handler.removeCallbacks(it) } }
        override fun isInteractive() = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        override fun requestRevalidation() { handler.post { evaluate() } }
        override fun command(cmd: OverlayCommand) { if (::overlay.isInitialized) overlay.apply(cmd) }
    }

    // ------------------------------------------------------------ lifecycle
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        loadAdapters()
        overlay = OverlayManager(this, core)
        core.host = host
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        diag.log("SERVICE_CONNECTED " + adapters.joinToString { "${it.platformId}=${it.profile.profileId}.v${it.profile.version}" })
        core.handle(DomainEvent.ServiceConnected)                  // الحالة تُستعاد من التخزين المحلي (§40)
        scheduleEvaluate(0)
    }

    private fun loadAdapters() {
        adapters = AppPlatform.values().map { ReelsAdapter(it, ProfileLoader.load(this, it)) }
        current = null
    }

    /** يُستدعى بعد حفظ/حذف الـProfile المُعايَر. */
    fun reloadProfile() { if (adapters.isNotEmpty()) loadAdapters() }

    /** Adapter التطبيق إن كان ضمن التطبيقات المحميّة، وإلا null. */
    private fun adapterFor(pkg: String?): ReelsAdapter? {
        if (pkg == null) return null
        val enabled = core.state.enabledPlatforms
        return adapters.firstOrNull { it.platformId in enabled && it.isPlatformPackage(pkg) }
    }

    override fun onInterrupt() { core.onScreenOff() }

    override fun onUnbind(intent: Intent?): Boolean { cleanup(); return super.onUnbind(intent) }
    override fun onDestroy() { cleanup(); super.onDestroy() }

    private fun cleanup() {
        instance = null
        handler.removeCallbacksAndMessages(null); timers.clear()
        try { unregisterReceiver(screenOff) } catch (_: Exception) {}
        if (::overlay.isInitialized) overlay.hideAll()
        core.handle(DomainEvent.ServiceDisconnected)
        core.host = null
    }

    // ------------------------------------------------------------ events
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!core.state.consentGiven || adapters.isEmpty()) return     // لا عمل قبل موافقة المستخدم
        val pkg = event.packageName?.toString() ?: return
        val ad = adapterFor(pkg)
        val debounce = ad?.profile?.debounceMs ?: DEFAULT_DEBOUNCE_MS
        // وضع الوقت لا يحتاج محتوى الشاشة: تكفي أحداث تغيّر النافذة (والنبض الدوري من الـCore)
        val needContent = core.state.basis == ProtectionBasis.COUNT || app.calibration.active
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> if (ad != null && needContent) onScroll(ad, event)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (ad != null && needContent) scheduleEvaluate(debounce)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                if (ad != null || (pkg != SYSTEM_UI && pkg != packageName)) scheduleEvaluate(debounce)
        }
    }

    private fun onScroll(ad: ReelsAdapter, event: AccessibilityEvent) {
        val src = event.source
        val srcId = src?.viewIdResourceName?.substringAfter(":id/", "")
        val now = SystemClock.uptimeMillis()
        var hinted = ad.onScrollHint(srcId, now)
        if (!hinted && ad.profile.scrollFallback && src != null) {
            val r = android.graphics.Rect(); src.getBoundsInScreen(r)
            val dm = resources.displayMetrics
            val total = dm.widthPixels.toLong() * dm.heightPixels
            if (total > 0 && r.width().toLong() * r.height().toFloat() / total >= ad.profile.minScreenFraction) hinted = ad.onLargeScrollHint(now)
        }
        recycleNode(src)
        if (hinted) {
            core.handle(DomainEvent.NewContentCandidate)          // محاولة وصول لمحتوى جديد (§28)
            scheduleEvaluate(ad.profile.settleMs + 50)
        }
    }

    private fun scheduleEvaluate(delay: Long) {
        handler.removeCallbacks(evalRunnable)
        handler.postDelayed(evalRunnable, delay)
    }

    // ------------------------------------------------------------ core loop
    private fun evaluate() {
        if (!core.state.consentGiven || adapters.isEmpty()) return
        app.tick()

        val root = rootInActiveWindow
        if (root == null) { diag.inc(Metric.UNKNOWN); return }       // حالة عابرة: لا نغيّر شيئًا
        val pkg = root.packageName?.toString()

        if (pkg == SYSTEM_UI || pkg == packageName) { recycleNode(root); return }
        val adapter = adapterFor(pkg)
        if (adapter == null) {
            recycleNode(root)
            core.handle(DomainEvent.PlatformInactive)
            overlay.hideCalibration()
            adapters.forEach { it.reset() }
            current = null
            return
        }
        if (current !== adapter) { current?.reset(); current = adapter }   // انتقال بين تطبيقين: لا يُورَّث السياق
        core.handle(DomainEvent.PlatformActive(adapter.platformId))

        // وضع المعايرة: نجمع عيّنات موسومة ولا نُشغّل الـCore (لا عدّ ولا قفل أثناء المعايرة)
        val cal = app.calibration
        if (cal.active && !core.isLocked() && cal.platform == adapter.platform) {
            val dm0 = resources.displayMetrics
            val s0 = try { AccessibilityScanner(adapter.profile, dm0.widthPixels, dm0.heightPixels, collect = true).scan(root) } catch (e: Exception) { null }
            recycleNode(root)
            cal.offer(s0, SystemClock.uptimeMillis())
            overlay.showCalibration(cal)
            return
        } else if (!cal.active || cal.platform != adapter.platform) overlay.hideCalibration()

        // لا داعي لمسح الشجرة إن كانت الحماية متوقفة ولا قفل (§73)
        if (!core.scanningNeeded() || core.isLocked() || !core.usesReelDetection()) { recycleNode(root); return }

        val dm = resources.displayMetrics
        val scan = try { AccessibilityScanner(adapter.profile, dm.widthPixels, dm.heightPixels).scan(root) } catch (e: Exception) { null }
        recycleNode(root)

        val now = SystemClock.uptimeMillis()
        val events = try { adapter.process(scan, now) } catch (e: Exception) {
            diag.log("ADAPTER_ERROR ${e.javaClass.simpleName}")           // §76: لا يسقط الـCore
            listOf(DomainEvent.DetectionUnknown("adapter_error"))
        }
        if (core.state.debugLogging) { debugLog(adapter); record(now, scan) }
        events.forEach { core.handle(it) }
    }

    // ------------------------------------------------------------ debug (Phase 0)
    private fun fmt(f: Float) = String.format(Locale.US, "%.2f", f)

    private fun debugLog(adapter: ReelsAdapter) {
        val s = adapter.lastSnapshot ?: return
        val d = s.detection
        val sum = "${adapter.platformId} ${d.state}/${d.contentType} conf=${d.confidence} active=${s.active} f=${fmt(s.fraction)} " +
            "id=${s.identity.confidence} signals=${d.signals}"
        if (sum == lastSummary) return
        lastSummary = sum
        diag.log(sum)
        diag.log("IDS(${s.allIds.size}): " + s.allIds.sorted().take(80).joinToString(","))
    }

    /** تسجيل إطار Replay مجهول (hash فقط) — يُفعَّل فقط في وضع التسجيل التفصيلي. */
    private fun record(now: Long, scan: com.reelguard.platform.common.RawScan?) {
        try { app.recordReplay(ReplayJson.encode(ReplayFrame(now, scan))) } catch (_: Exception) {}
    }
}
