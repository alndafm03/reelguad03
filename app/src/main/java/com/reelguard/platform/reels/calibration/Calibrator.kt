package com.reelguard.platform.reels.calibration

import com.reelguard.platform.common.RawScan
import com.reelguard.platform.reels.detector.ReelDetector
import com.reelguard.platform.reels.rules.DetectionProfile

enum class CalLabel { REEL, NOT_REEL, STORY }

data class CalSuggestion(val profile: DetectionProfile, val notes: List<String>)
data class CalResult(val suggestion: CalSuggestion?, val error: String?)

/**
 * معايرة تلقائية (Phase 0 آلي): يتعلّم من لقطات موسومة يدويًا (Reel / ليس Reel / Story) أي معرّفات
 * واجهة التطبيق (Instagram / Facebook / YouTube) تميّز Reels على جهاز المستخدم ونسخته من التطبيق، ثم يبني Detection Profile مُعايَرًا
 * ويتحقق منه على نفس العيّنات. لا نصوص تُخزَّن: بصمات hash فقط.
 */
class Calibrator {
    companion object { const val MIN_REEL = 8; const val MIN_OTHER = 8; const val MIN_STORY = 4; private const val CAP = 300 }

    private val reel = ArrayList<RawScan>()
    private val other = ArrayList<RawScan>()
    private val story = ArrayList<RawScan>()

    fun add(l: CalLabel, s: RawScan) {
        val list = when (l) { CalLabel.REEL -> reel; CalLabel.NOT_REEL -> other; CalLabel.STORY -> story }
        if (list.size >= CAP) list.removeAt(0)
        list.add(s)
    }
    fun count(l: CalLabel) = when (l) { CalLabel.REEL -> reel.size; CalLabel.NOT_REEL -> other.size; CalLabel.STORY -> story.size }
    fun clear() { reel.clear(); other.clear(); story.clear() }

    private fun freq(scans: List<RawScan>, sel: (RawScan) -> Set<String>): Map<String, Double> {
        if (scans.isEmpty()) return emptyMap()
        val m = HashMap<String, Int>()
        scans.forEach { s -> sel(s).forEach { m[it] = (m[it] ?: 0) + 1 } }
        return m.mapValues { it.value.toDouble() / scans.size }
    }

    fun suggest(base: DetectionProfile): CalResult {
        if (reel.size < MIN_REEL || other.size < MIN_OTHER)
            return CalResult(null, "عيّنات غير كافية: Reel ${reel.size}/$MIN_REEL، ليس Reel ${other.size}/$MIN_OTHER.")
        val notes = ArrayList<String>()
        val notReel = other + story
        val fR = freq(reel) { it.allIds }
        val fN = freq(notReel) { it.allIds }

        var strong = fR.filter { it.value >= 0.85 && (fN[it.key] ?: 0.0) <= 0.05 }.keys
        if (strong.isEmpty()) strong = fR.filter { it.value >= 0.70 && (fN[it.key] ?: 0.0) <= 0.15 }.keys
        if (strong.isEmpty()) return CalResult(null, "لم أجد معرّفات تميّز Reels عن باقي الشاشات. أعد المعايرة مع لقطات أكثر تنوعًا (Feed، Profile، Search).")
        val strongL = strong.sortedByDescending { (fR[it] ?: 0.0) - (fN[it] ?: 0.0) }.take(6)
        notes.add("معرّفات Reels: ${strongL.joinToString()}")

        val fTabR = freq(reel) { it.selectedIds }; val fTabN = freq(notReel) { it.selectedIds }
        val tab = fTabR.filter { it.value >= 0.85 && (fTabN[it.key] ?: 0.0) <= 0.10 }.keys.toList()
        notes.add(if (tab.isEmpty()) "تبويب Reels: لم يُكتشف (لا مشكلة)." else "تبويب Reels: ${tab.joinToString()}")

        var storyIds = base.storyIds
        if (story.size >= MIN_STORY) {
            val fS = freq(story) { it.allIds }; val fNS = freq(reel + other) { it.allIds }
            val found = fS.filter { it.value >= 0.85 && (fNS[it.key] ?: 0.0) <= 0.05 }.keys
            if (found.isNotEmpty()) { storyIds = found; notes.add("معرّفات Stories: ${found.joinToString()}") }
        } else notes.add("Stories: عيّنات قليلة، أبقيت القواعد الافتراضية.")

        val fScR = freq(reel) { it.scrollableIds }; val fScN = freq(notReel) { it.scrollableIds }
        val scrollCands = fScR.filter { it.value >= 0.70 && (fScN[it.key] ?: 0.0) <= 0.15 }.keys
        val scroll = (scrollCands.intersect(strongL.toSet()).ifEmpty { scrollCands }).toList()
        notes.add(if (scroll.isEmpty()) "مصدر التمرير: لم يُكتشف؛ سيعتمد الانتقال على تغيّر الهوية فقط." else "مصدر التمرير: ${scroll.joinToString()}")

        // الحاوية الكبيرة ⇒ شرط النشاط
        val avgFrac = strongL.associateWith { id -> reel.map { it.idFractions[id] ?: 0f }.average().toFloat() }
        var minFrac = base.minScreenFraction
        var container = avgFrac.filter { it.value >= minFrac }.keys.toList()
        if (container.isEmpty()) {
            val best = avgFrac.maxByOrNull { it.value }
            if (best != null && best.value >= 0.25f) {
                minFrac = (best.value * 0.85f).coerceIn(0.25f, 0.6f); container = listOf(best.key)
                notes.add("أكبر حاوية تشغل ${(best.value * 100).toInt()}% من الشاشة؛ خفّضت حد النشاط إلى ${(minFrac * 100).toInt()}%.")
            } else notes.add("لم أجد حاوية كبيرة؛ قد لا يُعتبر Reel «نشطًا» (لن يُحتسب).")
        }

        // الهوية: نصوص تتغير بين الـReels
        var author = base.authorIds; var caption = base.captionIds; var extra = base.extraIdentityIds
        val fTxtN = freq(other) { it.textInfo.keys }
        val cands = HashMap<String, MutableList<Pair<String, Int>>>()
        reel.forEach { s -> s.textInfo.forEach { (id, v) ->
            val h = v.substringBefore(':'); val len = v.substringAfter(':').toIntOrNull() ?: 0
            cands.getOrPut(id) { ArrayList() }.add(h to len) } }
        val idc = cands.filter { (id, l) -> l.size >= reel.size * 0.6 && (fTxtN[id] ?: 0.0) <= 0.10 && l.map { it.first }.toSet().size >= 3 }
            .mapValues { it.value.map { p -> p.second }.average() }
        // لا نتبنّى معرّف هوية إلا إذا دلّ اسمه على حساب/وصف؛ وإلا قد نلتقط عدّاد إعجابات يتغيّر أثناء المشاهدة
        // فتصبح الهوية غير مستقرة وتُقطَع المشاهدة بانتقال مزيّف.
        val authorHint = Regex("user|author|owner|profile_name|handle", RegexOption.IGNORE_CASE)
        val captionHint = Regex("caption|description|desc|title|comment_text", RegexOption.IGNORE_CASE)
        val a = idc.filter { authorHint.containsMatchIn(it.key) && it.value >= 3 && it.value <= 40 }.minByOrNull { it.value }
        val c = idc.filter { captionHint.containsMatchIn(it.key) && it.key != a?.key }.maxByOrNull { it.value }
        if (a != null && c != null) {
            author = setOf(a.key); caption = setOf(c.key); notes.add("هوية المحتوى: حساب=${a.key} ، وصف=${c.key}")
            // عنصر ثالث مستقر (مقطع الصوت/الموسيقى) يقلّل تصادم البصمة بين Reelين لحساب واحد.
            // لا يُتبنّى إلا إن ظهر في ≥90% من العيّنات، وإلا قد تتذبذب البصمة لنفس المحتوى.
            val audioHint = Regex("audio|music|sound|song|track|attribution", RegexOption.IGNORE_CASE)
            val e3 = cands.filter { (id, l) -> audioHint.containsMatchIn(id) && id != a.key && id != c.key &&
                l.size >= reel.size * 0.9 && (fTxtN[id] ?: 0.0) <= 0.10 && l.map { it.first }.toSet().size >= 3 }.keys
            if (e3.isNotEmpty()) { extra = e3.take(1).toSet(); notes.add("عنصر الهوية الثالث (صوت): ${extra.joinToString()}") }
            else notes.add("عنصر الهوية الثالث: لم يُكتشف؛ يُعتمد على كشف التصادم أثناء التمرير.")
        } else notes.add("هوية المحتوى: لم أجد عنصرين موثوقين (حساب + وصف) — لن يُمنع التكرار A→B→A، لكن العدّ يعمل.")

        var prof = base.copy(
            reelStrongIds = strongL.toSet(), clipsTabIds = if (tab.isEmpty()) base.clipsTabIds else tab.toSet(),
            storyIds = storyIds, contentContainerIds = container.toSet().ifEmpty { base.contentContainerIds },
            scrollSourceIds = scroll.toSet().ifEmpty { base.scrollSourceIds },
            authorIds = author, captionIds = caption, extraIdentityIds = extra, minScreenFraction = minFrac)

        // ضبط العتبة على العيّنات ثم التحقق
        val trial = ReelDetector(prof.copy(confirmScore = 0, probableScore = 0, possibleScore = 0))
        val rc = reel.map { trial.detect(it, 0).result.confidence }.sorted()
        val p10 = rc[(rc.size * 0.1).toInt().coerceAtMost(rc.size - 1)]
        val oc = notReel.map { trial.detect(it, 0).result }.filter { it.contentType != com.reelguard.core.model.ContentType.STORY }.map { it.confidence }
        val maxOther = oc.maxOrNull() ?: 0
        var confirm = minOf(base.confirmScore, p10).coerceAtLeast(20)
        if (maxOther >= confirm) {
            if (maxOther + 1 <= p10) confirm = maxOther + 1
            else notes.add("⚠️ تداخل: بعض الشاشات غير Reel تصل درجتها إلى $maxOther بينما Reels تبدأ من $p10. قد تحدث أخطاء؛ أعد المعايرة بلقطات أوضح.")
        }
        prof = prof.copy(confirmScore = confirm, probableScore = (confirm - 1).coerceAtMost(base.probableScore).coerceAtLeast(2),
            possibleScore = (confirm - 2).coerceAtMost(base.possibleScore).coerceAtLeast(1), version = base.version + 1, profileId = base.profileId.substringBefore('-') + "-calibrated")
        val det = ReelDetector(prof)
        val tp = reel.count { det.detect(it, 0).result.state == com.reelguard.core.model.DetectionState.CONFIRMED_REEL }
        val fp = notReel.count { det.detect(it, 0).result.state == com.reelguard.core.model.DetectionState.CONFIRMED_REEL }
        val active = reel.count { det.detect(it, 0).active == com.reelguard.core.model.ActiveContentState.ACTIVE }
        notes.add("التحقق على العيّنات: Reels مكتشفة $tp/${reel.size} (نشطة $active)، إيجابيات كاذبة $fp/${notReel.size}، العتبة=$confirm")
        return CalResult(CalSuggestion(prof, notes), null)
    }
}

/** جلسة معايرة حيّة (في الذاكرة). الخدمة تُضيف لقطات، والواجهة تعرض العدّ. */
class CalibrationSession {
    val calibrator = Calibrator()
    /** التطبيق الذي تجري معايرته الآن. */
    @Volatile var platform: com.reelguard.platform.common.AppPlatform = com.reelguard.platform.common.AppPlatform.INSTAGRAM
    @Volatile var active = false
    @Volatile var label: CalLabel? = null
    var lastSampleAt = 0L
    var result: CalResult? = null

    fun start(p: com.reelguard.platform.common.AppPlatform) { platform = p; calibrator.clear(); result = null; label = null; active = true }
    fun stop() { active = false; label = null }
    fun offer(scan: RawScan?, now: Long) {
        val l = label ?: return
        if (scan == null || scan.allIds.isEmpty() || now - lastSampleAt < 400) return
        lastSampleAt = now; calibrator.add(l, scan)
    }
    fun summary() = "Reel ${calibrator.count(CalLabel.REEL)} • ليس Reel ${calibrator.count(CalLabel.NOT_REEL)} • Story ${calibrator.count(CalLabel.STORY)}"
}
