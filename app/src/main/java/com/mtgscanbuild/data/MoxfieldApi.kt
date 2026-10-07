package com.mtgscanbuild.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** A deck as listed in Moxfield search results (no card list). */
data class MoxDeckSummary(
    val publicId: String,
    val name: String,
    val format: String,
    val colorIdentity: String,
    val author: String,
    val views: Int,
    val likes: Int,
    val cardCount: Int,
) {
    val url get() = MoxfieldApi.deckUrl(publicId)
}

data class MoxCard(
    val name: String,
    val quantity: Int,
    /** "commander", "main" or "side", same as [DeckCardEntity.section]. */
    val section: String,
    val typeLine: String,
    val manaCost: String,
    val cmc: Double,
    val setCode: String,
    val priceUsd: Double?,
    val imageUrl: String?,
)

data class MoxDeck(
    val publicId: String,
    val name: String,
    val format: String,
    val author: String,
    val colorIdentity: String,
    val cards: List<MoxCard>,
) {
    val url get() = MoxfieldApi.deckUrl(publicId)
}

/**
 * Client for Moxfield's public (unofficial, undocumented) deck API. Moxfield rate-limits hard,
 * so requests are spaced out and HTTP 429 responses are retried with back-off.
 */
class MoxfieldApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val mutex = Mutex()
    private var lastCall = 0L

    private suspend fun request(url: String): JSONObject? {
        var backoff = 5_000L
        repeat(4) { attempt ->
            val result = mutex.withLock {
                val wait = MIN_INTERVAL_MS - (System.currentTimeMillis() - lastCall)
                if (wait > 0) delay(wait)
                try {
                    withContext(Dispatchers.IO) {
                        val req = Request.Builder().url(url)
                            .header("User-Agent", "MTGScanBuild/1.0")
                            .header("Accept", "application/json")
                            .build()
                        client.newCall(req).execute().use { r ->
                            when {
                                r.code == 429 -> RATE_LIMITED
                                r.code == 404 || r.code == 400 -> null
                                !r.isSuccessful -> throw IOException("Moxfield error HTTP ${r.code}")
                                else -> JSONObject(r.body!!.string())
                            }
                        }
                    }
                } finally {
                    lastCall = System.currentTimeMillis()
                }
            }
            if (result !== RATE_LIMITED) return result
            if (attempt < 3) { delay(backoff); backoff *= 2 }
        }
        throw IOException("Moxfield is rate-limiting requests. Wait a minute and try again.")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Public decks for a format, most viewed first. [filter] matches deck names / cards on Moxfield. */
    suspend fun search(appFormatId: String, filter: String = "", page: Int = 1, pageSize: Int = 20): List<MoxDeckSummary> {
        val fmt = formatFor(appFormatId)
        var url = "$BASE/v2/decks/search?pageNumber=$page&pageSize=$pageSize&sortType=views&sortDirection=Descending&fmt=${enc(fmt)}"
        if (filter.isNotBlank()) url += "&filter=${enc(filter.trim())}"
        val j = request(url) ?: return emptyList()
        return parseSearch(j)
    }

    /** Full deck list. Accepts a Moxfield deck URL or its public id. */
    suspend fun deck(urlOrId: String): MoxDeck? {
        val id = extractId(urlOrId) ?: return null
        return request("$BASE/v3/decks/all/${enc(id)}")?.let(::parseDeck)
    }

    companion object {
        const val BASE = "https://api2.moxfield.com"
        const val MIN_INTERVAL_MS = 1_200L
        private val RATE_LIMITED = JSONObject()

        fun deckUrl(publicId: String) = "https://moxfield.com/decks/$publicId"

        /** App format id → Moxfield `fmt` value. */
        fun formatFor(appFormatId: String): String = when (appFormatId) {
            "duel" -> "duelCommander"
            "paupercommander" -> "pauperEdh"
            "brawl" -> "historicBrawl"
            "standardbrawl" -> "brawl"
            "penny" -> "pennyDreadful"
            "oldschool" -> "oldSchool"
            "future" -> "standard"
            else -> appFormatId
        }

        private val idRegex = Regex("^[A-Za-z0-9_-]{6,}$")

        fun extractId(input: String): String? {
            val s = input.trim()
            val fromUrl = Regex("moxfield\\.com/decks/([A-Za-z0-9_-]+)").find(s)?.groupValues?.get(1)
            return fromUrl ?: s.takeIf { idRegex.matches(it) }
        }

        private fun JSONObject.str(key: String): String? =
            if (has(key) && !isNull(key)) getString(key) else null

        private fun JSONObject.chars(key: String): String {
            val a = optJSONArray(key) ?: return ""
            return (0 until a.length()).joinToString("") { a.getString(it) }
        }

        fun parseSearch(j: JSONObject): List<MoxDeckSummary> {
            val data = j.optJSONArray("data") ?: return emptyList()
            return (0 until data.length()).map { i ->
                val d = data.getJSONObject(i)
                MoxDeckSummary(
                    publicId = d.getString("publicId"),
                    name = d.optString("name").trim(),
                    format = d.optString("format"),
                    colorIdentity = d.chars("colorIdentity"),
                    author = d.optJSONObject("createdByUser")?.optString("userName") ?: "",
                    views = d.optInt("viewCount"),
                    likes = d.optInt("likeCount"),
                    cardCount = d.optInt("mainboardCount"),
                )
            }
        }

        private val boardSections = linkedMapOf(
            "commanders" to "commander",
            "signatureSpells" to "commander",
            "mainboard" to "main",
            "companions" to "side",
            "sideboard" to "side",
        )

        fun parseDeck(j: JSONObject): MoxDeck {
            val boards = j.optJSONObject("boards")
            val cards = mutableListOf<MoxCard>()
            for ((board, section) in boardSections) {
                val map = boards?.optJSONObject(board)?.optJSONObject("cards") ?: continue
                for (key in map.keys()) {
                    val entry = map.getJSONObject(key)
                    val c = entry.optJSONObject("card") ?: continue
                    val sid = c.str("scryfall_id")
                    cards += MoxCard(
                        name = c.optString("name"),
                        quantity = entry.optInt("quantity", 1),
                        section = section,
                        typeLine = c.optString("type_line"),
                        manaCost = c.optString("mana_cost"),
                        cmc = c.optDouble("cmc", 0.0).let { if (it.isNaN()) 0.0 else it },
                        setCode = c.optString("set"),
                        priceUsd = c.optJSONObject("prices")?.let { p ->
                            p.optDouble("usd").takeIf { !it.isNaN() } ?: p.optDouble("usd_foil").takeIf { !it.isNaN() }
                        },
                        imageUrl = sid?.takeIf { it.length > 2 }?.let { "https://cards.scryfall.io/normal/front/${it[0]}/${it[1]}/$it.jpg" },
                    )
                }
            }
            val author = j.optJSONObject("createdByUser")?.optString("userName") ?: ""
            return MoxDeck(
                publicId = j.getString("publicId"),
                name = j.optString("name").trim(),
                format = j.optString("format"),
                author = author,
                colorIdentity = j.chars("colorIdentity"),
                cards = cards,
            )
        }
    }
}
