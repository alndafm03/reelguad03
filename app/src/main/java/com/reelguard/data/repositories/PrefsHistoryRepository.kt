package com.reelguard.data.repositories

import android.content.Context
import com.reelguard.core.session.HistoryRepository
import com.reelguard.core.session.SessionSummary
import org.json.JSONArray
import org.json.JSONObject

/** سجل الجلسات المحلي (§38: يقابل Room). محدود بـ200 جلسة. لا يحتوي محتوى. */
class PrefsHistoryRepository(ctx: Context) : HistoryRepository {
    private val prefs = ctx.getSharedPreferences("history", Context.MODE_PRIVATE)
    private val cache: MutableList<SessionSummary> by lazy { read().toMutableList() }

    override fun addSession(s: SessionSummary) {
        cache.add(s)
        while (cache.size > 200) cache.removeAt(0)
        prefs.edit().putString("sessions", JSONArray().also { a -> cache.forEach { a.put(enc(it)) } }.toString()).apply()
    }

    override fun sessions(): List<SessionSummary> = cache.toList()

    override fun clear() { cache.clear(); prefs.edit().remove("sessions").apply() }

    private fun enc(s: SessionSummary) = JSONObject().put("id", s.id).put("p", s.platform).put("s", s.startWall)
        .put("e", s.endWall).put("c", s.count).put("i", s.interruptions)

    private fun read(): List<SessionSummary> = try {
        val a = JSONArray(prefs.getString("sessions", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it)
            SessionSummary(o.getLong("id"), o.getString("p"), o.getLong("s"), o.getLong("e"), o.getInt("c"), o.getInt("i")) }
    } catch (e: Exception) { emptyList() }
}
