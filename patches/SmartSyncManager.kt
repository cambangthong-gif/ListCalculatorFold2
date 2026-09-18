package com.alad.app.core.sync

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

class SmartSyncManager(
    private val context: Context,
    private val client: OkHttpClient
) {
    data class CaptionCue(
        val startMs: Long,
        val durationMs: Long,
        val text: String
    ) {
        val endMs: Long get() = startMs + durationMs
    }

    data class Match(
        val cueIndex: Int,
        val positionMs: Long,
        val score: Double,
        val jumpDetected: Boolean
    )

    companion object {
        private const val TAG = "SmartSyncManager"
        private const val PREFS = "alad_smart_sync"
        private const val KEY_VIDEO_URL = "video_url"

        @Volatile var status: String = "Live Sync"
            private set
        @Volatile var loadedCueCount: Int = 0
            private set
        @Volatile var estimatedPositionMs: Long = -1L
            private set

        fun setSharedVideoUrl(context: Context, url: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_VIDEO_URL, url.trim())
                .apply()
            status = "Smart Sync link saved"
        }

        fun getSharedVideoUrl(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_VIDEO_URL, "")
                .orEmpty()

        fun clearSharedVideoUrl(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_VIDEO_URL)
                .apply()
            status = "Live Sync"
            loadedCueCount = 0
            estimatedPositionMs = -1L
        }
    }

    private val sourceCues = ArrayList<CaptionCue>()
    private val targetCues = ArrayList<CaptionCue>()
    private var anchorPositionMs = -1L
    private var anchorElapsedMs = 0L
    private var playing = true
    private var lastMatchedIndex = -1
    private var sourceLanguage = ""

    val hasTimeline: Boolean
        get() = sourceCues.isNotEmpty()

    val hasTargetTimeline: Boolean
        get() = targetCues.isNotEmpty()

    suspend fun prepareSharedVideo(targetLanguage: String): Boolean {
        val url = getSharedVideoUrl(context)
        if (url.isBlank()) {
            status = "Live Sync"
            return false
        }
        return prepare(url, targetLanguage)
    }

    suspend fun prepare(url: String, targetLanguage: String): Boolean = withContext(Dispatchers.IO) {
        try {
            status = "Smart Sync · loading subtitles"
            sourceCues.clear()
            targetCues.clear()
            anchorPositionMs = -1L
            lastMatchedIndex = -1
            estimatedPositionMs = -1L

            val videoId = extractVideoId(url)
                ?: throw IllegalArgumentException("Unsupported YouTube link")
            val watchUrl = "https://www.youtube.com/watch?v=" + videoId + "&hl=en"
            val html = client.newCall(
                Request.Builder()
                    .url(watchUrl)
                    .header("User-Agent", "Mozilla/5.0 (Android) ALAD/3.9")
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("YouTube HTTP " + response.code)
                }
                response.body?.string().orEmpty()
            }

            val tracks = extractCaptionTracks(html)
            if (tracks.length() == 0) {
                status = "Smart Sync · no subtitles"
                return@withContext false
            }

            val track = chooseSourceTrack(tracks)
            val baseUrl = track.optString("baseUrl")
            if (baseUrl.isBlank()) {
                status = "Smart Sync · subtitle URL missing"
                return@withContext false
            }
            sourceLanguage = track.optString("languageCode")

            val sourceJson = fetchTimedTextJson(baseUrl, null)
            sourceCues.addAll(parseJson3(sourceJson))

            val targetCode = targetLanguage.substringBefore('-').lowercase(Locale.ROOT)
            if (sourceLanguage.substringBefore('-').equals(targetCode, ignoreCase = true)) {
                targetCues.addAll(sourceCues)
            } else {
                try {
                    val translatedJson = fetchTimedTextJson(baseUrl, targetCode)
                    targetCues.addAll(parseJson3(translatedJson))
                } catch (t: Throwable) {
                    Log.w(TAG, "Translated captions unavailable; live translation remains active", t)
                }
            }

            loadedCueCount = sourceCues.size
            status = if (sourceCues.isNotEmpty()) {
                if (targetCues.isNotEmpty()) {
                    "Smart Sync ready · " + sourceCues.size + " cues"
                } else {
                    "Smart Sync match-only · " + sourceCues.size + " cues"
                }
            } else {
                "Smart Sync · subtitle empty"
            }
            sourceCues.isNotEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "Smart Sync prepare failed", t)
            status = "Live Sync · subtitle unavailable"
            loadedCueCount = 0
            false
        }
    }

    @Synchronized
    fun setPlaying(value: Boolean) {
        if (playing == value) return
        val now = SystemClock.elapsedRealtime()
        if (!value && anchorPositionMs >= 0L) {
            anchorPositionMs = estimatePositionLocked(now)
            anchorElapsedMs = now
            estimatedPositionMs = anchorPositionMs
        } else if (value && anchorPositionMs >= 0L) {
            anchorElapsedMs = now
        }
        playing = value
    }

    @Synchronized
    fun estimatePosition(): Long {
        if (anchorPositionMs < 0L) return -1L
        val pos = estimatePositionLocked(SystemClock.elapsedRealtime())
        estimatedPositionMs = pos
        return pos
    }

    @Synchronized
    fun matchInputTranscript(transcript: String): Match? {
        if (sourceCues.isEmpty()) return null
        val normalized = normalize(transcript)
        if (normalized.length < 4) return null

        val predicted = if (anchorPositionMs >= 0L) {
            estimatePositionLocked(SystemClock.elapsedRealtime())
        } else {
            -1L
        }
        val centerIndex = when {
            predicted >= 0L -> findCueIndexAt(sourceCues, predicted)
            lastMatchedIndex >= 0 -> lastMatchedIndex
            else -> -1
        }

        var bestIndex = -1
        var bestScore = 0.0

        fun evaluateRange(start: Int, end: Int) {
            if (sourceCues.isEmpty()) return
            val s = start.coerceAtLeast(0)
            val e = end.coerceAtMost(sourceCues.lastIndex)
            if (s > e) return
            for (i in s..e) {
                val combined = buildString {
                    append(sourceCues[i].text)
                    if (i + 1 <= sourceCues.lastIndex) {
                        append(' ')
                        append(sourceCues[i + 1].text)
                    }
                    if (i + 2 <= sourceCues.lastIndex) {
                        append(' ')
                        append(sourceCues[i + 2].text)
                    }
                }
                val score = similarity(normalized, normalize(combined))
                if (score > bestScore) {
                    bestScore = score
                    bestIndex = i
                }
            }
        }

        if (centerIndex >= 0) {
            evaluateRange(centerIndex - 24, centerIndex + 36)
        } else {
            evaluateRange(0, sourceCues.lastIndex)
        }

        if (bestScore < 0.56 && sourceCues.size > 64) {
            bestIndex = -1
            bestScore = 0.0
            evaluateRange(0, sourceCues.lastIndex)
        }

        val minimumScore = if (normalized.split(' ').size >= 4) 0.46 else 0.58
        if (bestIndex < 0 || bestScore < minimumScore) return null

        val matchPosition = sourceCues[bestIndex].startMs
        val oldPosition = predicted
        val jump = oldPosition >= 0L &&
            abs(matchPosition - oldPosition) >= 5_500L &&
            bestScore >= 0.60

        if (anchorPositionMs < 0L || jump || bestScore >= 0.64) {
            anchorPositionMs = matchPosition
            anchorElapsedMs = SystemClock.elapsedRealtime()
            estimatedPositionMs = matchPosition
            lastMatchedIndex = bestIndex
        }

        status = "Smart Sync · " + formatTime(matchPosition) + " · " +
            (bestScore * 100).toInt() + "%"
        return Match(bestIndex, matchPosition, bestScore, jump)
    }

    @Synchronized
    fun targetCueToSpeak(lastStartMs: Long, leadMs: Long = 140L): CaptionCue? {
        if (targetCues.isEmpty() || anchorPositionMs < 0L || !playing) return null
        val pos = estimatePositionLocked(SystemClock.elapsedRealtime())
        estimatedPositionMs = pos
        val desired = pos + leadMs
        var index = findCueIndexAt(targetCues, desired)
        if (index < 0) index = 0

        val start = max(0, index - 1)
        val end = minOf(targetCues.lastIndex, index + 3)
        for (i in start..end) {
            val cue = targetCues[i]
            if (cue.startMs <= lastStartMs) continue
            if (cue.text.isBlank()) continue
            if (cue.startMs < pos - 700L) continue
            if (cue.startMs <= desired + 220L) return cue
        }
        return null
    }

    @Synchronized
    fun isNearEnd(windowMs: Long = 8_000L): Boolean {
        if (sourceCues.isEmpty() || anchorPositionMs < 0L) return false
        val pos = estimatePositionLocked(SystemClock.elapsedRealtime())
        val end = sourceCues.last().endMs
        return end - pos <= windowMs
    }

    @Synchronized
    fun remainingTimelineMs(): Long {
        if (sourceCues.isEmpty() || anchorPositionMs < 0L) return 0L
        val pos = estimatePositionLocked(SystemClock.elapsedRealtime())
        return (sourceCues.last().endMs - pos).coerceAtLeast(0L)
    }

    @Synchronized
    fun remainingTargetCues(
        lastStartMs: Long,
        maxLookaheadMs: Long = 12_000L,
        maxCues: Int = 10
    ): List<CaptionCue> {
        if (targetCues.isEmpty() || anchorPositionMs < 0L) return emptyList()
        val pos = estimatePositionLocked(SystemClock.elapsedRealtime())
        val limit = pos + maxLookaheadMs
        val out = ArrayList<CaptionCue>()
        for (cue in targetCues) {
            if (cue.startMs <= lastStartMs) continue
            if (cue.endMs < pos - 500L) continue
            if (cue.startMs > limit) break
            if (cue.text.isBlank()) continue
            out.add(cue)
            if (out.size >= maxCues) break
        }
        return out
    }

    @Synchronized
    fun resetAfterSeek() {
        lastMatchedIndex = -1
    }

    private fun estimatePositionLocked(now: Long): Long {
        if (anchorPositionMs < 0L) return -1L
        return if (playing) {
            anchorPositionMs + (now - anchorElapsedMs).coerceAtLeast(0L)
        } else {
            anchorPositionMs
        }
    }

    private fun fetchTimedTextJson(baseUrl: String, targetLanguage: String?): String {
        val separator = if (baseUrl.contains("?")) "&" else "?"
        val target = if (targetLanguage.isNullOrBlank()) "" else "&tlang=" + targetLanguage
        val url = baseUrl + separator + "fmt=json3" + target
        return client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android) ALAD/3.9")
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("TimedText HTTP " + response.code)
            }
            response.body?.string().orEmpty()
        }
    }

    private fun parseJson3(raw: String): List<CaptionCue> {
        if (raw.isBlank()) return emptyList()
        val root = JSONObject(raw)
        val events = root.optJSONArray("events") ?: return emptyList()
        val result = ArrayList<CaptionCue>(events.length())
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val start = event.optLong("tStartMs", -1L)
            if (start < 0L) continue
            val duration = event.optLong("dDurationMs", 1_200L).coerceAtLeast(120L)
            val segs = event.optJSONArray("segs") ?: continue
            val text = buildString {
                for (j in 0 until segs.length()) {
                    append(segs.optJSONObject(j)?.optString("utf8").orEmpty())
                }
            }.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
            if (text.isNotBlank()) result.add(CaptionCue(start, duration, text))
        }
        return result
    }

    private fun extractCaptionTracks(html: String): JSONArray {
        val marker = "\"captionTracks\":"
        val markerIndex = html.indexOf(marker)
        if (markerIndex < 0) return JSONArray()
        val arrayStart = html.indexOf('[', markerIndex + marker.length)
        if (arrayStart < 0) return JSONArray()

        var depth = 0
        var inString = false
        var escaped = false
        for (i in arrayStart until html.length) {
            val c = html[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                continue
            }
            if (c == '"') {
                inString = true
                continue
            }
            if (c == '[') depth++
            if (c == ']') {
                depth--
                if (depth == 0) {
                    return JSONArray(html.substring(arrayStart, i + 1))
                }
            }
        }
        return JSONArray()
    }

    private fun chooseSourceTrack(tracks: JSONArray): JSONObject {
        var first: JSONObject? = null
        for (i in 0 until tracks.length()) {
            val item = tracks.optJSONObject(i) ?: continue
            if (first == null) first = item
            if (!item.optString("kind").equals("asr", ignoreCase = true)) return item
        }
        return first ?: JSONObject()
    }

    private fun extractVideoId(url: String): String? {
        val patterns = listOf(
            Regex("""(?:v=)([A-Za-z0-9_-]{11})"""),
            Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
            Regex("""youtube\.com/shorts/([A-Za-z0-9_-]{11})"""),
            Regex("""youtube\.com/embed/([A-Za-z0-9_-]{11})""")
        )
        for (pattern in patterns) {
            val id = pattern.find(url)?.groupValues?.getOrNull(1)
            if (!id.isNullOrBlank()) return id
        }
        return null
    }

    private fun findCueIndexAt(cues: List<CaptionCue>, positionMs: Long): Int {
        if (cues.isEmpty()) return -1
        var lo = 0
        var hi = cues.lastIndex
        var best = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= positionMs) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return best
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (b.contains(a) || a.contains(b)) {
            val ratio = minOf(a.length, b.length).toDouble() /
                maxOf(a.length, b.length).toDouble()
            return 0.72 + 0.28 * ratio
        }

        val aw = a.split(' ').filter { it.length >= 2 }
        val bw = b.split(' ').filter { it.length >= 2 }
        if (aw.size >= 2 && bw.size >= 2) {
            val aset = aw.toSet()
            val bset = bw.toSet()
            val common = aset.intersect(bset).size.toDouble()
            if (common == 0.0) return 0.0
            val containment = common / minOf(aset.size, bset.size).toDouble()
            val dice = 2.0 * common / (aset.size + bset.size).toDouble()
            return containment * 0.62 + dice * 0.38
        }

        val ag = charGrams(a)
        val bg = charGrams(b)
        if (ag.isEmpty() || bg.isEmpty()) return 0.0
        val common = ag.intersect(bg).size.toDouble()
        return 2.0 * common / (ag.size + bg.size).toDouble()
    }

    private fun charGrams(value: String): Set<String> {
        val compact = value.replace(" ", "")
        if (compact.length < 3) return setOf(compact)
        val out = HashSet<String>()
        for (i in 0..compact.length - 3) out.add(compact.substring(i, i + 3))
        return out
    }

    private fun normalize(value: String): String {
        val lower = Normalizer.normalize(
            value.lowercase(Locale.ROOT),
            Normalizer.Form.NFKC
        )
        return lower
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val m = total / 60L
        val s = total % 60L
        return "%d:%02d".format(Locale.ROOT, m, s)
    }
}
