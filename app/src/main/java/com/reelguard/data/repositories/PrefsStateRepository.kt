package com.reelguard.data.repositories

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.reelguard.core.model.ProtectionBasis
import com.reelguard.core.model.ProtectionMode
import com.reelguard.core.state.AppState
import com.reelguard.core.state.PendingChange
import com.reelguard.core.state.LockState
import com.reelguard.core.state.SessionState
import com.reelguard.core.state.StateRepository
import com.reelguard.core.state.Stats
import com.reelguard.core.time.Countdown
import com.reelguard.core.time.TimeSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * حفظ الحالة الدائمة (§38-39). التنفيذ الحالي SharedPreferences خلف واجهة StateRepository؛
 * استبداله بـDataStore لا يمس الـCore. يقرأ مفاتيح V2 القديمة أيضًا (ترقية بلا فقدان).
 */
class PrefsStateRepository(ctx: Context) : StateRepository {
    private val prefs = ctx.getSharedPreferences("state", Context.MODE_PRIVATE)

    override fun load(): AppState {
        val raw = prefs.getString("s", null) ?: return AppState()
        return try { parse(JSONObject(raw)) } catch (e: Exception) { AppState() }
    }

    override fun save(state: AppState) {
        prefs.edit().putString("s", toJson(state).toString()).apply()
    }

    private fun toJson(s: AppState) = JSONObject().apply {
        put("consent", s.consentGiven); put("onboarded", s.onboardingDone)
        put("cfgLocked", s.configLocked)
        put("platforms", JSONArray(s.enabledPlatforms.sorted()))
        put("mode", s.mode.name)
        put("basis", s.basis.name)
        put("cfgTimeMin", s.configuredTimeMin); put("cycleTimeMs", s.cycleTimeMs); put("timeRemMs", s.timeRemainingMs)
        put("lockMin", s.lockMinutes)
        s.refill?.let { put("refill", countdownJson(it)) }
        s.pending?.let { p ->
            put("pending", countdownJson(p.timer).apply {
                p.mode?.let { put("mode", it.name) }
                p.limit?.let { put("limit", it) }
                p.basis?.let { put("basis", it.name) }
                p.timeMin?.let { put("timeMin", it) }
                p.lockMin?.let { put("lockMin", it) }
                put("remove", JSONArray(p.removePlatforms.sorted()))
            })
        }
        put("configured", s.configuredLimit); put("cycleLimit", s.cycleLimit); put("remaining", s.remaining)
        put("cycleId", s.cycleId); put("cycleStart", s.cycleStart); put("cycleEnd", s.cycleEnd)
        put("counted", JSONArray(s.countedIds.toList()))
        s.allowedContentKey?.let { put("allowed", it) }
        put("lockState", s.lockState.name); put("lockReason", s.lockReason)
        put("lockStart", s.lockStart); put("lockEnd", s.lockEnd)
        put("lockStartElapsed", s.lockStartElapsed); put("lockBoot", s.lockBootCount)
        put("debug", s.debugLogging); put("showCounter", s.showCounter)
        put("nextSession", s.nextSessionId)
        s.currentSession?.let {
            put("session", JSONObject().put("id", it.id).put("p", it.platform).put("s", it.startWall)
                .put("l", it.lastActivityWall).put("c", it.count).put("i", it.interruptions))
        }
        put("lockCount", s.stats.lockCount); put("limitReached", s.stats.limitReached)
        put("cycles", s.stats.cycles); put("totalLockMs", s.stats.totalLockMs)
        put("daily", JSONObject(s.stats.daily)); put("dailyLocks", JSONObject(s.stats.dailyLocks))
        put("dailyMs", JSONObject(s.stats.dailyMs))
    }

    private fun countdownJson(c: Countdown) = JSONObject().apply {
        put("ts", c.startWall); put("te", c.endWall); put("tse", c.startElapsed); put("tb", c.bootCount)
    }
    private fun parseCountdown(it: JSONObject) = Countdown(it.getLong("ts"), it.getLong("te"), it.getLong("tse"), it.getInt("tb"))

    private fun intMap(j: JSONObject?): Map<String, Int> = j?.let { o -> o.keys().asSequence().associateWith { o.getInt(it) } } ?: emptyMap()
    private fun longMap(j: JSONObject?): Map<String, Long> = j?.let { o -> o.keys().asSequence().associateWith { o.getLong(it) } } ?: emptyMap()
    private inline fun <reified E : Enum<E>> enumOf(name: String?, default: E): E =
        try { name?.let { enumValueOf<E>(it) } ?: default } catch (e: Exception) { default }

    /** يقرأ أسماء الأوضاع القديمة (V3 السابق: Gentle/Balanced/Strict/Deep Focus/Custom). */
    private fun parseMode(name: String?): ProtectionMode = when (name) {
        "WARNING_ONLY", "GENTLE" -> ProtectionMode.WARNING_ONLY
        else -> ProtectionMode.BUDGET_LOCK
    }

    private fun strSet(a: JSONArray?): Set<String> = a?.let { (0 until it.length()).map { i -> it.getString(i) }.toSet() } ?: emptySet()

    private fun parsePending(o: JSONObject?): PendingChange? = o?.let {
        PendingChange(
            mode = if (it.has("mode")) parseMode(it.getString("mode")) else null,
            limit = if (it.has("limit")) it.getInt("limit") else null,
            removePlatforms = strSet(it.optJSONArray("remove")),
            timer = parseCountdown(it),
            basis = if (it.has("basis")) enumOf(it.getString("basis"), ProtectionBasis.COUNT) else null,
            timeMin = if (it.has("timeMin")) it.getInt("timeMin") else null,
            lockMin = if (it.has("lockMin")) it.getInt("lockMin") else null
        )
    }

    private fun parse(o: JSONObject): AppState {
        val counted = o.optJSONArray("counted")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
        // توافق V2: lockActive:Boolean
        val lockState = if (o.has("lockState")) enumOf(o.optString("lockState"), LockState.UNLOCKED)
        else if (o.optBoolean("lockActive", false)) LockState.LOCKED else LockState.UNLOCKED
        val session = o.optJSONObject("session")?.let {
            SessionState(it.getLong("id"), it.getString("p"), it.getLong("s"), it.getLong("l"), it.optInt("c"), it.optInt("i"))
        }
        return AppState(
            consentGiven = o.optBoolean("consent", false),
            onboardingDone = o.optBoolean("onboarded", o.optBoolean("consent", false)),
            // حالات V3 السابقة التي وافق صاحبها كانت مفعّلة فعليًا ⇒ تُعتبر مؤكَّدة الإعداد
            configLocked = o.optBoolean("cfgLocked", o.optBoolean("consent", false)),
            enabledPlatforms = strSet(o.optJSONArray("platforms")).ifEmpty { AppState.DEFAULT_PLATFORMS },
            pending = try { parsePending(o.optJSONObject("pending")) } catch (e: Exception) { null },
            mode = parseMode(o.optString("mode")),
            basis = enumOf(o.optString("basis"), ProtectionBasis.COUNT),
            configuredTimeMin = o.optInt("cfgTimeMin", 30),
            cycleTimeMs = o.optLong("cycleTimeMs", 30L * 60_000L),
            timeRemainingMs = o.optLong("timeRemMs", 30L * 60_000L),
            lockMinutes = o.optInt("lockMin", 60),
            refill = try { o.optJSONObject("refill")?.let { parseCountdown(it) } } catch (e: Exception) { null },
            configuredLimit = o.optInt("configured", 30), cycleLimit = o.optInt("cycleLimit", 30),
            remaining = o.optInt("remaining", 30),
            cycleId = o.optLong("cycleId", 1L), cycleStart = o.optLong("cycleStart", 0L), cycleEnd = o.optLong("cycleEnd", 0L),
            countedIds = counted,
            allowedContentKey = if (o.has("allowed")) o.getString("allowed") else null,
            lockState = lockState, lockReason = o.optString("lockReason", ""),
            lockStart = o.optLong("lockStart", 0L), lockEnd = o.optLong("lockEnd", 0L),
            lockStartElapsed = o.optLong("lockStartElapsed", 0L), lockBootCount = o.optInt("lockBoot", -1),
            debugLogging = o.optBoolean("debug", false), showCounter = o.optBoolean("showCounter", true),
            currentSession = session, nextSessionId = o.optLong("nextSession", 1L),
            stats = Stats(
                lockCount = o.optInt("lockCount", 0), limitReached = o.optInt("limitReached", 0),
                cycles = o.optInt("cycles", 0), totalLockMs = o.optLong("totalLockMs", 0L),
                daily = intMap(o.optJSONObject("daily")), dailyLocks = intMap(o.optJSONObject("dailyLocks")),
                dailyMs = longMap(o.optJSONObject("dailyMs"))
            )
        )
    }
}

class SystemTimeSource(private val ctx: Context) : TimeSource {
    override fun wallMs() = System.currentTimeMillis()
    override fun elapsedMs() = SystemClock.elapsedRealtime()
    override fun bootCount(): Int =
        try { Settings.Global.getInt(ctx.contentResolver, "boot_count", -1) } catch (e: Exception) { -1 }
}
