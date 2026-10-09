package com.mtgscanbuild.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Minimal Scryfall client. Scryfall asks for 50-100 ms between requests and a custom User-Agent. */
class ScryfallApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val mutex = Mutex()
    private var lastCall = 0L

    private suspend fun throttle() = mutex.withLock {
        val wait = 110 - (System.currentTimeMillis() - lastCall)
        if (wait > 0) delay(wait)
        lastCall = System.currentTimeMillis()
    }

    private suspend fun request(url: String, body: String? = null): JSONObject? = withContext(Dispatchers.IO) {
        throttle()
        val builder = Request.Builder().url(url)
            .header("User-Agent", "MTGScanBuild/1.0")
            .header("Accept", "application/json")
        if (body != null) builder.post(body.toRequestBody("application/json".toMediaType()))
        client.newCall(builder.build()).execute().use { r ->
            when {
                r.code == 404 -> null
                !r.isSuccessful -> throw IOException("Scryfall error HTTP ${r.code}")
                else -> JSONObject(r.body!!.string())
            }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun named(exact: String): CardData? =
        request("$BASE/cards/named?exact=${enc(exact)}")?.let(::parseCard)

    suspend fun fuzzy(name: String): CardData? =
        request("$BASE/cards/named?fuzzy=${enc(name)}")?.let(::parseCard)

    suspend fun bySetNumber(set: String, number: String): CardData? =
        request("$BASE/cards/${enc(set.lowercase())}/${enc(number)}")?.let(::parseCard)

    suspend fun autocomplete(q: String): List<String> {
        val j = request("$BASE/cards/autocomplete?q=${enc(q)}") ?: return emptyList()
        return j.optJSONArray("data").strings()
    }

    suspend fun cardNames(): List<String> {
        val j = request("$BASE/catalog/card-names") ?: return emptyList()
        return j.optJSONArray("data").strings()
    }

    suspend fun bannedAndRestricted(formatId: String): BanLists {
        require(HomeModes.withBanLists.any { it.legalityId == formatId }) { "Unsupported format: $formatId" }
        return loadBanLists(formatId) { request(it) }
    }

    internal suspend fun loadBanLists(formatId: String, fetch: suspend (String) -> JSONObject?): BanLists {
        val cards = mutableListOf<LegalityCard>()
        var url: String? = "$BASE/cards/search?q=${enc("(banned:$formatId or restricted:$formatId)")}&unique=cards&order=name"
        val visited = mutableSetOf<String>()
        while (url != null) {
            val current = url
            val parsed = current.toHttpUrlOrNull()
            check(parsed?.scheme == "https" && parsed.host == "api.scryfall.com" && parsed.encodedPath == "/cards/search") {
                "Scryfall returned an invalid pagination URL"
            }
            check(visited.add(current)) { "Scryfall repeated a search page" }
            val page = fetch(current)
            if (page == null) {
                if (visited.size == 1) return BanLists(emptyList(), emptyList())
                throw IOException("Scryfall search ended before all pages were loaded")
            }
            cards += parseLegalityPage(page, formatId)
            url = if (page.getBoolean("has_more")) page.getString("next_page").also {
                if (it.isBlank()) throw IOException("Scryfall omitted the next search page")
            } else null
        }
        val distinct = cards.distinctBy { it.id }.sortedBy { it.name }
        return BanLists(distinct.filter { it.status == "banned" }, distinct.filter { it.status == "restricted" })
    }

    /** All paper printings of a card, newest first. */
    suspend fun prints(name: String): List<CardData> {
        val out = mutableListOf<CardData>()
        var url: String? = "$BASE/cards/search?q=${enc("!\"$name\" game:paper")}&unique=prints&order=released&dir=desc"
        var pages = 0
        while (url != null && pages < 4) {
            val j = request(url) ?: break
            val data = j.optJSONArray("data") ?: break
            for (i in 0 until data.length()) out += parseCard(data.getJSONObject(i))
            url = if (j.optBoolean("has_more")) j.optString("next_page") else null
            pages++
        }
        return out
    }

    /**
     * Resolves up to 75 identifiers per call via /cards/collection.
     * Returns one entry per identifier (null when not found), in the same order.
     */
    suspend fun collection(identifiers: List<JSONObject>): List<CardData?> {
        val result = ArrayList<CardData?>()
        for (chunk in identifiers.chunked(75)) {
            val body = JSONObject().put("identifiers", JSONArray(chunk)).toString()
            val j = request("$BASE/cards/collection", body)
            if (j == null) { repeat(chunk.size) { result += null }; continue }
            val notFound = j.optJSONArray("not_found")
            val nf = HashSet<String>()
            if (notFound != null) for (i in 0 until notFound.length()) nf += idKey(notFound.getJSONObject(i))
            val data = j.optJSONArray("data") ?: JSONArray()
            var di = 0
            for (id in chunk) {
                if (idKey(id) in nf || di >= data.length()) result += null
                else result += parseCard(data.getJSONObject(di++))
            }
        }
        return result
    }

    private fun idKey(o: JSONObject): String =
        o.keys().asSequence().sorted().joinToString("|") { "$it=${o.optString(it).lowercase()}" }

    companion object {
        const val BASE = "https://api.scryfall.com"

        fun parseLegalityPage(page: JSONObject, formatId: String): List<LegalityCard> {
            val data = page.getJSONArray("data")
            return (0 until data.length()).mapNotNull { index ->
                val card = data.getJSONObject(index)
                val status = card.getJSONObject("legalities").getString(formatId)
                if (status != "banned" && status != "restricted") null else
                    LegalityCard(card.getString("id"), card.getString("name"),
                        card.getString("scryfall_uri"), status)
            }
        }

        private fun JSONArray?.strings(): List<String> {
            if (this == null) return emptyList()
            return (0 until length()).map { getString(it) }
        }

        private fun JSONObject.str(key: String): String? =
            if (has(key) && !isNull(key)) getString(key) else null

        fun parseCard(j: JSONObject): CardData {
            val faces = j.optJSONArray("card_faces")
            val f0 = faces?.optJSONObject(0)
            val images = j.optJSONObject("image_uris") ?: f0?.optJSONObject("image_uris")
            val oracle = j.str("oracle_text") ?: buildString {
                if (faces != null) for (i in 0 until faces.length()) {
                    if (i > 0) append("\n//\n")
                    append(faces.getJSONObject(i).str("oracle_text") ?: "")
                }
            }
            val legal = j.optJSONObject("legalities")
            val legalStr = legal?.keys()?.asSequence()?.joinToString(";") { "$it=${legal.getString(it)}" } ?: ""
            val prices = j.optJSONObject("prices")
            fun price(key: String) = prices?.str(key)?.toDoubleOrNull()
            return CardData(
                scryfallId = j.getString("id"),
                oracleId = j.str("oracle_id") ?: f0?.str("oracle_id") ?: "",
                name = j.getString("name"),
                setCode = j.str("set") ?: "",
                setName = j.str("set_name") ?: "",
                collectorNumber = j.str("collector_number") ?: "",
                rarity = j.str("rarity") ?: "common",
                manaCost = j.str("mana_cost")?.takeIf { it.isNotEmpty() } ?: f0?.str("mana_cost") ?: "",
                cmc = j.optDouble("cmc", 0.0).let { if (it.isNaN()) 0.0 else it },
                typeLine = j.str("type_line") ?: f0?.str("type_line") ?: "",
                oracleText = oracle,
                colors = (j.optJSONArray("colors") ?: f0?.optJSONArray("colors")).strings().joinToString(""),
                colorIdentity = j.optJSONArray("color_identity").strings().joinToString(""),
                keywords = j.optJSONArray("keywords").strings().joinToString("|"),
                producedMana = j.optJSONArray("produced_mana").strings().joinToString(""),
                power = j.str("power") ?: f0?.str("power"),
                toughness = j.str("toughness") ?: f0?.str("toughness"),
                loyalty = j.str("loyalty") ?: f0?.str("loyalty"),
                legalities = legalStr,
                imageUrl = images?.str("small"),
                imageUrlLarge = images?.str("normal"),
                edhrecRank = if (j.has("edhrec_rank") && !j.isNull("edhrec_rank")) j.getInt("edhrec_rank") else null,
                // Scryfall's USD prices are TCGplayer Market Prices.
                priceUsd = price("usd"),
                priceUsdFoil = price("usd_foil") ?: price("usd_etched"),
                tcgplayerUrl = j.optJSONObject("purchase_uris")?.str("tcgplayer"),
                releasedAt = j.str("released_at") ?: "",
            )
        }
    }
}
