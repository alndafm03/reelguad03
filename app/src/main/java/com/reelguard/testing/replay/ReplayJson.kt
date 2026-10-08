package com.reelguard.testing.replay

import com.reelguard.platform.common.RawScan
import com.reelguard.platform.reels.identity.ContentIdentityProvider
import org.json.JSONArray
import org.json.JSONObject

/**
 * ترميز الإطارات (سطر JSON لكل إطار). خصوصية (§59، §75): النصوص لا تُسجَّل أبدًا؛
 * يُسجَّل hash مختصر لكل نص، وهو يكفي لإعادة حساب الهوية وقياس تطابق A→B→A.
 */
object ReplayJson {
    fun encode(frame: ReplayFrame): String {
        val s = frame.scan
        val o = JSONObject().put("t", frame.atMs)
        frame.scrollHint?.let { o.put("hint", it) }
        if (s != null) o.put("scan", JSONObject().apply {
            put("n", s.nodeCount); put("ids", JSONArray(s.allIds.sorted())); put("sel", JSONArray(s.selectedIds.sorted()))
            put("dh", s.descHit); put("df", s.descFraction.toDouble()); put("cf", s.containerFraction.toDouble()); put("vf", s.videoFraction.toDouble())
            s.author?.let { put("au", h(it)) }; s.caption?.let { put("ca", h(it)) }
            s.extra?.let { put("ex", h(it)) }; s.contentIdText?.let { put("ci", h(it)) }
        })
        return o.toString()
    }

    fun decode(line: String): ReplayFrame {
        val o = JSONObject(line)
        val s = o.optJSONObject("scan")
        fun set(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> it.getString(i) }.toSet() } ?: emptySet()
        val scan = s?.let {
            RawScan(it.getInt("n"), set(it.optJSONArray("ids")), set(it.optJSONArray("sel")),
                it.optBoolean("dh"), it.optDouble("df").toFloat(), it.optDouble("cf").toFloat(), it.optDouble("vf").toFloat(),
                it.optString("au").ifEmpty { null }, it.optString("ca").ifEmpty { null },
                it.optString("ex").ifEmpty { null }, it.optString("ci").ifEmpty { null })
        }
        return ReplayFrame(o.getLong("t"), scan, o.optString("hint").ifEmpty { null })
    }

    private fun h(s: String) = ContentIdentityProvider.sha16(s)
}
