package com.reelguard.accessibility.nodes

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.reelguard.platform.common.RawScan
import com.reelguard.platform.reels.identity.ContentIdentityProvider
import com.reelguard.platform.reels.rules.DetectionProfile

@Suppress("DEPRECATION")
fun recycleNode(n: AccessibilityNodeInfo?) {
    if (n != null && Build.VERSION.SDK_INT < 33) try { n.recycle() } catch (_: Exception) {}
}

/**
 * يجمع الحد الأدنى من الإشارات من شجرة Accessibility (§10، §73).
 * حدود صارمة على العقد والعمق. لا يخزّن أي نص؛ النصوص تُسلَّم فقط لحساب hash الهوية.
 */
class AccessibilityScanner(
    private val p: DetectionProfile,
    private val screenW: Int,
    private val screenH: Int,
    private val collect: Boolean = false
) {
    private class Cand(val text: String, val area: Long)

    private var count = 0
    private val ids = HashSet<String>()
    private val selected = HashSet<String>()
    private var descHit = false
    private var descFrac = 0f
    private var contFrac = 0f
    private var videoFrac = 0f
    private var author: Cand? = null
    private var caption: Cand? = null
    private var extra: Cand? = null
    private var contentId: Cand? = null
    private val rect = Rect()
    private val scrollable = HashSet<String>()
    private val idFrac = HashMap<String, Float>()
    private val textInfo = HashMap<String, String>()

    fun scan(root: AccessibilityNodeInfo): RawScan {
        count = 0; ids.clear(); selected.clear(); descHit = false; descFrac = 0f; contFrac = 0f; videoFrac = 0f
        author = null; caption = null; extra = null; contentId = null
        scrollable.clear(); idFrac.clear(); textInfo.clear()
        visit(root, 0)
        return RawScan(count, ids.toSet(), selected.toSet(), descHit, descFrac, contFrac, videoFrac,
            author?.text, caption?.text, extra?.text, contentId?.text,
            scrollable.toSet(), idFrac.toMap(), textInfo.toMap())
    }

    private fun visit(node: AccessibilityNodeInfo, depth: Int) {
        if (count >= p.maxNodes || depth > p.maxDepth) return
        count++
        val visible = node.isVisibleToUser
        val shortId = node.viewIdResourceName?.substringAfter(":id/", "")?.takeIf { it.isNotEmpty() }

        if (shortId != null) {
            ids.add(shortId)
            if (node.isSelected) selected.add(shortId)
            if (visible && collect) {
                if (node.isScrollable) scrollable.add(shortId)
                idFrac[shortId] = maxOf(idFrac[shortId] ?: 0f, fraction(node))
                val t = node.text?.toString()?.trim()
                if (!t.isNullOrEmpty()) textInfo[shortId] = ContentIdentityProvider.sha16(t) + ":" + t.length
            }
            if (visible) {
                if (shortId in p.contentContainerIds) contFrac = maxOf(contFrac, fraction(node))
                if (shortId in p.authorIds) author = better(author, node)
                if (shortId in p.captionIds) caption = better(caption, node)
                if (shortId in p.extraIdentityIds) extra = better(extra, node)
                if (shortId in p.contentIdIds) contentId = better(contentId, node, useDescription = true)
            }
        }
        if (visible) {
            if (p.videoClassNames.isNotEmpty()) {
                val cn = node.className?.toString()
                if (cn != null && cn in p.videoClassNames) videoFrac = maxOf(videoFrac, fraction(node))
            }
            val d = node.contentDescription
            if (!d.isNullOrEmpty() && p.reelDescPatterns.any { it.containsMatchIn(d) }) {
                descHit = true
                descFrac = maxOf(descFrac, fraction(node))
            }
        }
        for (i in 0 until node.childCount) {
            if (count >= p.maxNodes) break
            val c = node.getChild(i) ?: continue
            visit(c, depth + 1)
            recycleNode(c)
        }
    }

    private fun area(node: AccessibilityNodeInfo): Long {
        node.getBoundsInScreen(rect)
        val r = Rect(rect)
        return if (r.intersect(0, 0, screenW, screenH)) r.width().toLong() * r.height() else 0L
    }

    private fun fraction(node: AccessibilityNodeInfo): Float {
        val total = screenW.toLong() * screenH
        return if (total <= 0) 0f else (area(node).toFloat() / total).coerceIn(0f, 1f)
    }

    private fun better(existing: Cand?, node: AccessibilityNodeInfo, useDescription: Boolean = false): Cand? {
        val t = (node.text ?: if (useDescription) node.contentDescription else null)?.toString()?.trim()
        if (t.isNullOrEmpty()) return existing
        val a = area(node)
        return if (existing == null || a > existing.area) Cand(t, a) else existing
    }
}
