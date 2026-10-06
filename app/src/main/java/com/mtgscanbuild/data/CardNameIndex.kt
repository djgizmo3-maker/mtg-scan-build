package com.mtgscanbuild.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer

/**
 * Local copy of every Magic card name (Scryfall catalog), used to fuzzy-match OCR text
 * quickly on-device without hammering the API.
 */
class CardNameIndex(dir: File, private val api: ScryfallApi) {
    private val file = File(dir, "card-names.txt")
    private val mutex = Mutex()

    private class Entry(val norm: String, val lookup: String)

    @Volatile private var exact: Map<String, Entry> = emptyMap()
    @Volatile private var byLength: Array<List<Entry>> = emptyArray()

    val isLoaded: Boolean get() = exact.isNotEmpty()
    val hasCache: Boolean get() = file.exists()
    val size: Int get() = exact.size

    data class Match(val name: String, val score: Double)

    /** Why the last download attempt failed (shown to the user), or null. */
    @Volatile var lastError: String? = null
        private set

    suspend fun ensureLoaded(): Boolean = mutex.withLock {
        if (isLoaded) return@withLock true
        withContext(Dispatchers.IO) {
            val stale = !file.exists() || System.currentTimeMillis() - file.lastModified() > 14L * 24 * 3600 * 1000
            if (stale) {
                try {
                    val names = api.cardNames()
                    if (names.size > 1000) file.writeText(names.joinToString("\n"))
                    lastError = null
                } catch (e: Exception) {
                    // Offline: fall back to the cached list if we have one.
                    lastError = e.toString()
                    System.err.println("CardNameIndex: download failed: $e")
                }
            }
            if (file.exists()) build(file.readLines())
            isLoaded
        }
    }

    private fun build(names: List<String>) {
        val map = HashMap<String, Entry>(names.size * 2)
        for (full in names) {
            if (full.isBlank()) continue
            // Index each face separately: OCR only sees the front face name on the card.
            val parts = full.split(" // ")
            for (p in parts) {
                val n = normalize(p)
                if (n.length >= 2 && n !in map) map[n] = Entry(n, p)
            }
            val nf = normalize(full)
            if (nf !in map) map[nf] = Entry(nf, parts[0])
        }
        val maxLen = map.keys.maxOfOrNull { it.length } ?: 0
        val buckets = Array(maxLen + 1) { mutableListOf<Entry>() }
        for (e in map.values) buckets[e.norm.length] += e
        byLength = Array(buckets.size) { buckets[it] }
        exact = map
    }

    /** Best match for a single OCR line, or null. */
    fun match(line: String): Match? {
        if (!isLoaded) return null
        val tokens = normalize(line).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        var best: Match? = null
        // Mana symbols at the end of the title line often OCR as junk tokens; try dropping them.
        for (drop in 0..minOf(2, tokens.size - 1)) {
            val q = tokens.subList(0, tokens.size - drop).joinToString(" ")
            if (q.length < 3) continue
            val m = matchNormalized(q) ?: continue
            val score = m.score - drop * 0.03
            if (best == null || score > best.score) best = Match(m.name, score)
            if (score >= 0.99) break
        }
        return best?.takeIf { it.score >= 0.78 }
    }

    private fun matchNormalized(q: String): Match? {
        exact[q]?.let { return Match(it.lookup, 1.0) }
        val maxDist = maxOf(1, q.length / 4)
        var bestEntry: Entry? = null
        var bestDist = maxDist + 1
        val lo = maxOf(0, q.length - maxDist)
        val hi = minOf(byLength.size - 1, q.length + maxDist)
        for (len in lo..hi) {
            for (e in byLength[len]) {
                val d = boundedLevenshtein(q, e.norm, bestDist - 1)
                if (d < bestDist) {
                    bestDist = d; bestEntry = e
                    if (d == 0) break
                }
            }
        }
        val e = bestEntry ?: return null
        val score = 1.0 - bestDist.toDouble() / maxOf(q.length, e.norm.length)
        return Match(e.lookup, score)
    }

    companion object {
        private val nonAlnum = Regex("[^a-z0-9 ]")
        private val spaces = Regex("\\s+")
        private val marks = Regex("\\p{Mn}+")

        fun normalize(s: String): String {
            val d = Normalizer.normalize(s.lowercase().replace("æ", "ae"), Normalizer.Form.NFD)
            return d.replace(marks, "").replace('-', ' ').replace(nonAlnum, "").replace(spaces, " ").trim()
        }

        /** Levenshtein distance, returning max+1 as soon as it is known to exceed [max]. */
        fun boundedLevenshtein(a: String, b: String, max: Int): Int {
            if (max < 0) return 1
            if (kotlin.math.abs(a.length - b.length) > max) return max + 1
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                var rowMin = cur[0]
                val ca = a[i - 1]
                for (j in 1..b.length) {
                    val cost = if (ca == b[j - 1]) 0 else 1
                    val v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                    cur[j] = v
                    if (v < rowMin) rowMin = v
                }
                if (rowMin > max) return max + 1
                val t = prev; prev = cur; cur = t
            }
            return prev[b.length]
        }
    }
}
