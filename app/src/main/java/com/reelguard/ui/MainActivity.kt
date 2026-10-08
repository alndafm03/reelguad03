package com.reelguard.ui

import android.app.Activity
import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.reelguard.ReelGuardApp
import com.reelguard.accessibility.service.ReelGuardService

/**
 * الواجهة تقرأ الحالة فقط من الـCore وتعدّل الإعدادات عبر PolicySettings (§3.4، §72).
 * شاشات: Onboarding / Dashboard / Settings / Statistics / Diagnostics / Privacy.
 */
class MainActivity : Activity() {
    enum class Screen { ONBOARDING, DASHBOARD, SETTINGS, STATS, DIAG, PRIVACY, CALIB }

    val app get() = application as ReelGuardApp
    lateinit var ui: UiKit
    var step = 0
    private var screen = Screen.DASHBOARD
    private var refresher: (() -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { app.tick(); refresher?.invoke(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UiKit(this)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val st = app.stateManager.state
        screen = if (st.onboardingDone) Screen.DASHBOARD else Screen.ONBOARDING
        step = (savedInstanceState?.getInt("step") ?: 0).coerceIn(0, Onboarding.LAST_STEP)
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putInt("step", step) }

    override fun onResume() { super.onResume(); handler.post(ticker) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(ticker) }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (screen != Screen.DASHBOARD && screen != Screen.ONBOARDING) show(Screen.DASHBOARD) else super.onBackPressed()
    }

    fun show(s: Screen) { screen = s; render() }

    fun render() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(40))
        }
        setContentView(ScrollView(this).apply { addView(col) })
        refresher = when (screen) {
            Screen.ONBOARDING -> Onboarding.build(this, col)
            Screen.DASHBOARD -> Dashboard.build(this, col)
            Screen.SETTINGS -> SettingsScreen.build(this, col)
            Screen.STATS -> StatsScreen.build(this, col)
            Screen.DIAG -> DiagnosticsScreen.build(this, col)
            Screen.PRIVACY -> PrivacyScreen.build(this, col)
            Screen.CALIB -> CalibrationScreen.build(this, col)
        }
        refresher?.invoke()
    }

    fun isServiceEnabled(): Boolean {
        val cn = ComponentName(this, ReelGuardService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true) }
    }
}
