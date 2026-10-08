package com.reelguard.core.session

import com.reelguard.core.state.SessionState
import com.reelguard.core.state.StateManager
import com.reelguard.core.time.TimeSource

data class SessionSummary(
    val id: Long,
    val platform: String,
    val startWall: Long,
    val endWall: Long,
    val count: Int,
    val interruptions: Int
) { val durationMs: Long get() = (endWall - startWall).coerceAtLeast(0L) }

/** سجل تاريخي (§38: يقابل Room). التنفيذ الافتراضي SharedPreferences خلف هذه الواجهة. */
interface HistoryRepository {
    fun addSession(s: SessionSummary)
    fun sessions(): List<SessionSummary>
    fun clear()
}

class InMemoryHistoryRepository : HistoryRepository {
    private val list = ArrayList<SessionSummary>()
    override fun addSession(s: SessionSummary) { list.add(s) }
    override fun sessions(): List<SessionSummary> = list.toList()
    override fun clear() = list.clear()
}

/**
 * Session مفهوم مستقل عن Cycle (§23). تبدأ عند أول محتوى نشط، وتنتهي بعد فجوة خمول
 * أو عند التقييد. لا يُخزَّن إلا عدد ومدة تقديرية (لا محتوى).
 */
class SessionManager(
    private val sm: StateManager,
    private val time: TimeSource,
    private val history: HistoryRepository,
    private val gapMs: Long = DEFAULT_GAP_MS
) {
    companion object {
        const val DEFAULT_GAP_MS = 3L * 60L * 1000L
        private const val PERSIST_EVERY_MS = 5_000L
    }

    val current: SessionState? get() = sm.state.currentSession

    fun currentDurationMs(): Long = current?.let { (it.lastActivityWall - it.startWall).coerceAtLeast(0L) } ?: 0L

    /** نشاط محتوى فعّال على المنصة. */
    fun touch(platform: String) {
        val now = time.wallMs()
        var cur = current
        if (cur != null && (now - cur.lastActivityWall > gapMs || cur.platform != platform)) {
            end(); cur = null
        }
        if (cur == null) {
            sm.update {
                it.copy(currentSession = SessionState(it.nextSessionId, platform, now, now), nextSessionId = it.nextSessionId + 1)
            }
        } else if (now - cur.lastActivityWall >= PERSIST_EVERY_MS) {
            sm.update { it.copy(currentSession = cur.copy(lastActivityWall = now)) }
        }
    }

    fun onConsumed() {
        val cur = current ?: return
        sm.update { it.copy(currentSession = cur.copy(count = cur.count + 1, lastActivityWall = time.wallMs())) }
    }

    fun onInterrupted() {
        val cur = current ?: return
        sm.update { it.copy(currentSession = cur.copy(interruptions = cur.interruptions + 1)) }
    }

    /** يُستدعى دوريًا: ينهي الجلسة إذا طال الخمول. */
    fun tick() {
        val cur = current ?: return
        if (time.wallMs() - cur.lastActivityWall > gapMs) end()
    }

    fun end() {
        val cur = current ?: return
        if (cur.count > 0 || cur.lastActivityWall > cur.startWall) {
            history.addSession(SessionSummary(cur.id, cur.platform, cur.startWall, cur.lastActivityWall, cur.count, cur.interruptions))
        }
        sm.update { it.copy(currentSession = null) }
    }
}
