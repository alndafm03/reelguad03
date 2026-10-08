package com.reelguard.accessibility.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * كل الـOverlays تستخدم TYPE_ACCESSIBILITY_OVERLAY المرتبط بالخدمة: لا تحتاج
 * SYSTEM_ALERT_WINDOW (§31). لا حالة هنا؛ الـOverlay يُنشأ من جديد متى أُتلف (§33).
 */
internal abstract class BaseOverlay(protected val service: AccessibilityService, private val onLost: () -> Unit) {
    protected val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
    protected val handler = Handler(Looper.getMainLooper())
    protected var view: View? = null
    private var removingByUs = false

    val isShown get() = view != null

    protected fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), service.resources.displayMetrics).toInt()

    protected fun add(v: View, lp: WindowManager.LayoutParams): Boolean = try {
        v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(x: View) {}
            override fun onViewDetachedFromWindow(x: View) {
                // إزالة غير صادرة منّا (النظام/أخطاء) ⇒ Overlay lost (§41)
                if (!removingByUs && view === x) { view = null; onDestroyed(); handler.post(onLost) }
            }
        })
        wm.addView(v, lp); view = v; true
    } catch (e: Exception) { view = null; false }

    protected open fun onDestroyed() {}

    open fun hide() {
        val v = view ?: return
        removingByUs = true
        try { wm.removeView(v) } catch (_: Exception) {}
        view = null; removingByUs = false
        onDestroyed()
    }

    protected fun tv(text: String, sp: Float, bold: Boolean = false, color: Int = Color.WHITE) = TextView(service).apply {
        this.text = text; textSize = sp; setTextColor(color); gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(24), dp(8), dp(24), dp(8))
    }
}

/** عدّاد الرصيد الصغير. */
internal class CounterOverlay(service: AccessibilityService, onLost: () -> Unit) : BaseOverlay(service, onLost) {
    private var label: TextView? = null

    fun show(remaining: Int, limit: Int) = showText("$remaining / $limit")

    fun showText(text: String) {
        label?.let { it.text = text; return }
        val v = TextView(service).apply {
            this.text = text; textSize = 13f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; y = dp(96); x = dp(8) }
        if (add(v, lp)) label = v
    }

    override fun onDestroyed() { label = null }
}

/** شريط تنبيه/استعادة مؤقت غير قابل للّمس. يُستعمل لـWarning وRecovery. */
internal class BannerOverlay(service: AccessibilityService, onLost: () -> Unit) : BaseOverlay(service, onLost) {
    private val autoHide = Runnable { hide() }

    fun show(text: String, ms: Long = 4500L, color: String = "#E6B45309") {
        handler.removeCallbacks(autoHide)
        hide()
        val v = TextView(service).apply {
            this.text = text; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(Color.parseColor(color))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP; y = dp(72) }
        if (add(v, lp)) handler.postDelayed(autoHide, ms)
    }

    override fun hide() { handler.removeCallbacks(autoHide); super.hide() }
}

/** بيانات تعرضها شاشة القفل (قراءة فقط من الـCore). */
data class LockInfo(val limit: Int, val reason: String, val timeMin: Int, val remainingMs: () -> Long)

/** شاشة القفل الكاملة بعدّ تنازلي. */
internal class LockOverlay(
    service: AccessibilityService,
    private val info: () -> LockInfo,
    private val onExpiredCheck: () -> Long,      // يعيد المتبقي بعد تحديث الـCore
    private val onExpired: () -> Unit,
    private val onShown: () -> Unit,
    onLost: () -> Unit
) : BaseOverlay(service, onLost) {

    private var countdown: TextView? = null

    fun show() {
        if (view != null) return
        val i = info()
        val untilFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val rem = i.remainingMs()
        val timeUp = i.reason == "time_up"
        val reasonText = if (timeUp) "انتهى وقت الاستخدام المحدد لهذه الدورة." else "انتهى رصيد الـReels لهذه الدورة."
        val cd = tv(fmt(rem), 40f, true).also { countdown = it }
        val col = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(tv("ReelGuard", 16f, true, Color.LTGRAY))
            addView(tv(if (timeUp) "⏱ 00:00 / ${i.timeMin} د" else "0 / ${i.limit}", 44f, true))
            addView(tv(reasonText, 18f))
            addView(tv("التطبيق مقفل حتى:", 16f, color = Color.LTGRAY))
            addView(cd)
            addView(tv("ينتهي القفل الساعة ${untilFmt.format(Date(System.currentTimeMillis() + rem))}", 14f, color = Color.LTGRAY))
            addView(tv("ستُستعاد الحماية تلقائيًا بعد انتهاء المدة.", 13f, color = Color.LTGRAY))
            addView(Button(service).apply {
                text = "الخروج من التطبيق"
                setOnClickListener { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }
            })
        }
        val root = FrameLayout(service).apply {
            setBackgroundColor(Color.parseColor("#F2101014"))
            isClickable = true
            setOnTouchListener { _, _ -> true }          // يبتلع اللمس: لا وصول لمحتوى جديد
            addView(col, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (add(root, lp)) { handler.post(tick); onShown() } else countdown = null
    }

    private val tick = object : Runnable {
        override fun run() {
            if (view == null) return
            val rem = onExpiredCheck()
            if (rem <= 0L) { hide(); onExpired(); return }
            countdown?.text = fmt(rem)
            handler.postDelayed(this, 500)
        }
    }

    override fun hide() { handler.removeCallbacks(tick); super.hide() }
    override fun onDestroyed() { countdown = null }

    private fun fmt(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
    }
}


/** لوحة المعايرة العائمة (تعمل فقط أثناء جلسة معايرة يبدؤها المستخدم). قابلة للّمس، أسفل الشاشة. */
internal class CalibrationPanel(service: AccessibilityService, onLost: () -> Unit) : BaseOverlay(service, onLost) {
    private var info: TextView? = null

    fun show(s: com.reelguard.platform.reels.calibration.CalibrationSession) {
        if (view != null) { info?.text = text(s); return }
        val label = tv(text(s), 13f).also { info = it }
        fun b(t: String, l: com.reelguard.platform.reels.calibration.CalLabel?) = Button(service).apply {
            text = t; setOnClickListener { s.label = l; info?.text = text(s) }
        }
        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(b("أنا في Reel", com.reelguard.platform.reels.calibration.CalLabel.REEL))
            addView(b("ليس Reel", com.reelguard.platform.reels.calibration.CalLabel.NOT_REEL))
            addView(b("Story", com.reelguard.platform.reels.calibration.CalLabel.STORY))
            addView(Button(service).apply { text = "إيقاف"; setOnClickListener { s.label = null; info?.text = text(s) } })
        }
        val col = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(Color.parseColor("#F0202024")); setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(label); addView(row)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }
        add(col, lp)
    }

    private fun text(s: com.reelguard.platform.reels.calibration.CalibrationSession): String {
        val cur = when (s.label) { null -> "متوقف (لا تُسجَّل عيّنات)"; com.reelguard.platform.reels.calibration.CalLabel.REEL -> "Reel"
            com.reelguard.platform.reels.calibration.CalLabel.NOT_REEL -> "ليس Reel"; com.reelguard.platform.reels.calibration.CalLabel.STORY -> "Story" }
        return "معايرة ReelGuard — الوسم: $cur\n${s.summary()}"
    }

    override fun onDestroyed() { info = null }
}
