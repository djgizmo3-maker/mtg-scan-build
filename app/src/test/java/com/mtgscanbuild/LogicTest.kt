package com.mtgscanbuild

import com.mtgscanbuild.data.CardData
import com.mtgscanbuild.data.CardNameIndex
import com.mtgscanbuild.data.CollectionCard
import com.mtgscanbuild.data.ScryfallApi
import com.mtgscanbuild.data.legalityMap
import com.mtgscanbuild.deck.BuildOptions
import com.mtgscanbuild.deck.DeckBuilder
import com.mtgscanbuild.deck.Formats
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLEncoder

/** Integration tests against live Scryfall data (requires internet). */
class LogicTest {

    @Test
    fun nameIndexMatchesNoisyOcr() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "mtg-test-index").apply { mkdirs() }
        val index = CardNameIndex(dir, ScryfallApi())
        assertTrue(index.ensureLoaded())
        println("Index size: ${index.size}")
        val cases = mapOf(
            "Lightning Bolt" to "Lightning Bolt",
            "Lightnlng Bo1t" to "Lightning Bolt",
            "Jace, the Mind Sculptor 2UU" to "Jace, the Mind Sculptor",
            "Sol Rlng" to "Sol Ring",
            "Delver of Secrets" to "Delver of Secrets",
            "Insectile Aberration" to "Insectile Aberration",
            "Llanowar Elves G" to "Llanowar Elves",
            "SERRA ANGEL" to "Serra Angel",
        )
        val t = System.currentTimeMillis()
        for ((ocr, expected) in cases) {
            val m = index.match(ocr)
            println("'$ocr' -> $m")
            assertEquals(expected, m?.name)
        }
        println("Avg match time: ${(System.currentTimeMillis() - t) / cases.size} ms")
        println("Rules-text line -> ${index.match("When this creature enters, draw a card and lose 1 life.")}")
    }

    private val http = OkHttpClient()

    private fun search(q: String, pages: Int): List<CardData> {
        val out = mutableListOf<CardData>()
        var url: String? = "https://api.scryfall.com/cards/search?order=edhrec&q=" + URLEncoder.encode(q, "UTF-8")
        var p = 0
        while (url != null && p++ < pages) {
            Thread.sleep(120)
            val body = http.newCall(Request.Builder().url(url).header("User-Agent", "MTGScanBuildTest/1.0").header("Accept", "application/json").build())
                .execute().use { it.body!!.string() }
            val j = JSONObject(body)
            val data = j.optJSONArray("data")
            if (data == null) { println("Search '$q' failed: ${body.take(300)}"); break }
            for (i in 0 until data.length()) out += ScryfallApi.parseCard(data.getJSONObject(i))
            url = if (j.optBoolean("has_more")) j.getString("next_page") else null
        }
        return out
    }

    @Test
    fun buildsLegalDecksFromCollection() {
        val cards = (search("f:standard -t:basic", 3) + search("f:modern -f:standard -t:basic", 2) +
            search("is:commander f:commander", 1) + search("f:pauper r:common", 2) +
            search("t:planeswalker f:oathbreaker", 1)).distinctBy { it.name }
        println("Synthetic collection: ${cards.size} unique cards")
        val rnd = java.util.Random(7)
        val collection = cards.mapIndexed { i, c ->
            CollectionCard(id = i.toLong(), card = c, quantity = 1 + rnd.nextInt(4), foil = false, addedAt = 0)
        }
        val owned = collection.groupBy { it.card.name }.mapValues { e -> e.value.sumOf { it.quantity } }
        val builder = DeckBuilder(collection)
        for (fid in listOf("standard", "modern", "pauper", "commander", "paupercommander", "oathbreaker", "standardbrawl", "gladiator", "vintage")) {
            val f = Formats.byId(fid)
            val t = System.currentTimeMillis()
            val decks = builder.build(f, BuildOptions())
            println("\n=== ${f.name}: ${decks.size} decks in ${System.currentTimeMillis() - t} ms")
            for (d in decks) {
                println("  ${d.name} [${d.colors}] complete=${d.complete} cards=${d.cardCount} score=${"%.2f".format(d.score)}")
                println("    ${d.description.replace("\n", " | ")}")
                d.warnings.forEach { println("    ! $it") }
                if (d.complete) assertEquals("${d.name} size", f.deckSize, d.cardCount)
                for (e in d.entries) {
                    if (e.typeLine.contains("Basic")) continue
                    assertTrue("${e.name} not owned", (owned[e.name] ?: 0) >= e.quantity)
                    if (f.singleton) assertEquals("${e.name} singleton", 1, e.quantity)
                    else assertTrue("${e.name} >4", e.quantity <= 4)
                    val card = cards.first { it.name == e.name }
                    val l = card.legalityMap()[f.id]
                    assertTrue("${e.name} not legal in ${f.id}: $l", l == "legal" || l == "restricted")
                    if (d.commander != null) assertTrue("${e.name} outside identity",
                        d.colors.toSet().containsAll(card.colorIdentity.toSet()))
                }
            }
            decks.firstOrNull()?.let { d ->
                println("  -- list of '${d.name}':")
                d.entries.forEach { println("     ${it.quantity} ${it.name} (${it.section})") }
            }
        }
    }
}
