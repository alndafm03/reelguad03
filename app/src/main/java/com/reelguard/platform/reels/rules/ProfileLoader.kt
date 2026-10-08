package com.reelguard.platform.reels.rules

import android.content.Context
import com.reelguard.platform.common.AppPlatform
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * تحميل الـProfile لكل تطبيق: المُعايَر (filesDir) يُفضَّل على الافتراضي (assets).
 * بلا Remote Config (§13).
 */
object ProfileLoader {
    private const val LEGACY_OVERRIDE = "profile_override.json"   // V3: Instagram فقط

    private fun overrideFile(ctx: Context, p: AppPlatform) = File(ctx.filesDir, "profile_override_${p.id}.json")

    private fun overrideCandidates(ctx: Context, p: AppPlatform): List<File> =
        if (p == AppPlatform.INSTAGRAM) listOf(overrideFile(ctx, p), File(ctx.filesDir, LEGACY_OVERRIDE)) else listOf(overrideFile(ctx, p))

    fun load(ctx: Context, platform: AppPlatform): DetectionProfile {
        overrideCandidates(ctx, platform).firstOrNull { it.exists() }?.let { f ->
            try { return parse(JSONObject(f.readText()), platform) } catch (_: Exception) {}
        }
        return try { parse(JSONObject(ctx.assets.open(platform.asset).bufferedReader().use { it.readText() }), platform) }
        catch (e: Exception) { DetectionProfile(packages = platform.packages) }
    }

    fun hasOverride(ctx: Context, platform: AppPlatform) = overrideCandidates(ctx, platform).any { it.exists() }
    fun clearOverride(ctx: Context, platform: AppPlatform) { overrideCandidates(ctx, platform).forEach { it.delete() } }
    fun saveOverride(ctx: Context, platform: AppPlatform, p: DetectionProfile) {
        overrideFile(ctx, platform).writeText(toJson(p).toString())
        if (platform == AppPlatform.INSTAGRAM) File(ctx.filesDir, LEGACY_OVERRIDE).delete()
    }

    fun toJson(p: DetectionProfile) = JSONObject().apply {
        put("version", p.version); put("profileId", p.profileId)
        fun a(k: String, v: Collection<String>) = put(k, JSONArray(v.toList()))
        a("packages", p.packages); a("reelStrongIds", p.reelStrongIds); a("clipsTabIds", p.clipsTabIds); a("storyIds", p.storyIds)
        a("contentContainerIds", p.contentContainerIds); a("videoClassNames", p.videoClassNames)
        a("reelDescPatterns", p.reelDescPatterns.map { it.pattern }); a("authorIds", p.authorIds); a("captionIds", p.captionIds)
        a("extraIdentityIds", p.extraIdentityIds); a("contentIdIds", p.contentIdIds); a("scrollSourceIds", p.scrollSourceIds)
        put("settleMs", p.settleMs); put("hintWindowMs", p.hintWindowMs); put("leaveMs", p.leaveMs); put("debounceMs", p.debounceMs)
        put("packageWeight", p.packageWeight); put("strongWeight", p.strongWeight); put("tabWeight", p.tabWeight)
        put("descWeight", p.descWeight); put("containerWeight", p.containerWeight); put("videoWeight", p.videoWeight)
        put("possibleScore", p.possibleScore); put("probableScore", p.probableScore); put("confirmScore", p.confirmScore)
        put("minScreenFraction", p.minScreenFraction.toDouble())
        put("maxNodes", p.maxNodes); put("maxDepth", p.maxDepth); put("minNodes", p.minNodes)
        put("requireIds", p.requireIds); put("scrollFallback", p.scrollFallback); put("captionPrefix", p.captionPrefix)
    }

    private fun arr(a: JSONArray?): List<String> = a?.let { (0 until it.length()).map { i -> it.getString(i) } } ?: emptyList()
    private fun set(o: JSONObject, k: String): Set<String> = arr(o.optJSONArray(k)).toSet()

    fun parse(o: JSONObject, platform: AppPlatform): DetectionProfile {
        val d = DetectionProfile(packages = platform.packages, profileId = "${platform.id}-default")
        return DetectionProfile(
            version = o.optInt("version", d.version),
            profileId = o.optString("profileId", d.profileId),
            packages = set(o, "packages").ifEmpty { d.packages },
            reelStrongIds = set(o, "reelStrongIds"),
            clipsTabIds = set(o, "clipsTabIds"),
            storyIds = set(o, "storyIds"),
            contentContainerIds = set(o, "contentContainerIds"),
            videoClassNames = set(o, "videoClassNames"),
            reelDescPatterns = arr(o.optJSONArray("reelDescPatterns")).map { Regex(it, RegexOption.IGNORE_CASE) },
            authorIds = set(o, "authorIds"),
            captionIds = set(o, "captionIds"),
            extraIdentityIds = set(o, "extraIdentityIds"),
            contentIdIds = set(o, "contentIdIds"),
            scrollSourceIds = set(o, "scrollSourceIds"),
            settleMs = o.optLong("settleMs", d.settleMs),
            hintWindowMs = o.optLong("hintWindowMs", d.hintWindowMs),
            leaveMs = o.optLong("leaveMs", d.leaveMs),
            debounceMs = o.optLong("debounceMs", d.debounceMs),
            packageWeight = o.optInt("packageWeight", d.packageWeight),
            strongWeight = o.optInt("strongWeight", d.strongWeight),
            tabWeight = o.optInt("tabWeight", d.tabWeight),
            descWeight = o.optInt("descWeight", d.descWeight),
            containerWeight = o.optInt("containerWeight", d.containerWeight),
            videoWeight = o.optInt("videoWeight", d.videoWeight),
            possibleScore = o.optInt("possibleScore", d.possibleScore),
            probableScore = o.optInt("probableScore", d.probableScore),
            confirmScore = o.optInt("confirmScore", d.confirmScore),
            minScreenFraction = o.optDouble("minScreenFraction", d.minScreenFraction.toDouble()).toFloat(),
            maxNodes = o.optInt("maxNodes", d.maxNodes),
            maxDepth = o.optInt("maxDepth", d.maxDepth),
            minNodes = o.optInt("minNodes", d.minNodes),
            requireIds = o.optBoolean("requireIds", d.requireIds),
            scrollFallback = o.optBoolean("scrollFallback", d.scrollFallback),
            captionPrefix = o.optInt("captionPrefix", d.captionPrefix).coerceIn(20, 400)
        )
    }
}
