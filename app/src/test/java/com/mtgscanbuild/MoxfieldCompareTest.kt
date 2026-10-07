package com.mtgscanbuild

import com.mtgscanbuild.data.CardData
import com.mtgscanbuild.data.CollectionCard
import com.mtgscanbuild.data.MoxCard
import com.mtgscanbuild.data.MoxDeck
import com.mtgscanbuild.data.MoxfieldApi
import com.mtgscanbuild.deck.DeckCompare
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoxfieldCompareTest {
    private fun owned(name: String, set: String, cn: String, qty: Int, price: Double?, foil: Boolean = false) =
        CollectionCard(
            card = CardData(
                scryfallId = "$set-$cn", oracleId = name, name = name, setCode = set, setName = set.uppercase(),
                collectorNumber = cn, rarity = "common", manaCost = "", cmc = 1.0, typeLine = "Instant",
                oracleText = "", colors = "R", colorIdentity = "R", keywords = "", producedMana = "",
                power = null, toughness = null, loyalty = null, legalities = "{}", imageUrl = null,
                imageUrlLarge = null, edhrecRank = null, priceUsd = price,
            ),
            quantity = qty, foil = foil, addedAt = 0,
        )

    private fun mox(name: String, qty: Int, section: String = "main", price: Double? = null) =
        MoxCard(name, qty, section, "Instant", "{R}", 1.0, "xxx", price, null)

    @Test
    fun comparesOwnershipPricesAndSets() {
        val collection = listOf(
            owned("Lightning Bolt", "m10", "146", 2, 1.50),
            owned("Lightning Bolt", "2xm", "141", 1, 0.90),
            owned("Delver of Secrets // Insectile Aberration", "isd", "51", 4, 0.25),
        )
        val deck = MoxDeck("id", "Test", "modern", "me", "R", listOf(
            mox("Lightning Bolt", 4),
            mox("Delver of Secrets", 4),
            mox("Mountain", 18),
            mox("Lightning Bolt", 1, section = "side"),
            mox("Ragavan, Nimble Pilferer", 1, price = 50.0),
        ))
        val cmp = DeckCompare.compare(deck, collection)
        val bolt = cmp.cards.first { it.name == "Lightning Bolt" && it.section == "main" }
        assertEquals(3, bolt.owned)
        assertEquals(1, bolt.missing)
        assertEquals(0.90, bolt.unitPrice!!, 1e-9)
        assertEquals(listOf("2XM #141", "M10 #146 ×2"), bolt.printings.map { it.label })
        // Main deck uses all owned copies, so the sideboard copy is missing.
        assertEquals(1, cmp.cards.first { it.section == "side" }.missing)
        assertEquals(0, cmp.cards.first { it.name == "Delver of Secrets" }.missing)
        assertEquals(0, cmp.cards.first { it.name == "Mountain" }.missing)
        assertEquals(1, cmp.cards.first { it.name.startsWith("Ragavan") }.missing)
        assertEquals(28, cmp.total)
        assertEquals(3, cmp.missing)
        assertEquals(0.90 + 0.90 + 50.0, cmp.missingCost, 1e-9)
    }

    @Test
    fun extractsIdsAndMapsFormats() {
        assertEquals("abc123XYZ_-", MoxfieldApi.extractId("https://www.moxfield.com/decks/abc123XYZ_-?foo=1"))
        assertEquals("abc123XYZ", MoxfieldApi.extractId("  abc123XYZ "))
        assertNull(MoxfieldApi.extractId("not a deck link"))
        assertEquals("duelCommander", MoxfieldApi.formatFor("duel"))
        assertEquals("commander", MoxfieldApi.formatFor("commander"))
        assertEquals("standard", MoxfieldApi.formatFor("future"))
    }

    @Test
    fun parsesDeckJson() {
        val json = JSONObject("""
            {"publicId":"p1","name":" My Deck ","format":"commander","colorIdentity":["W","U"],
             "createdByUser":{"userName":"bob"},
             "boards":{
               "commanders":{"cards":{"a":{"quantity":1,"card":{"name":"Cmdr","set":"cmr","type_line":"Legendary Creature",
                   "mana_cost":"{W}{U}","cmc":2,"scryfall_id":"abcdef","prices":{"usd":3.5}}}}},
               "mainboard":{"cards":{"b":{"quantity":2,"card":{"name":"Foo","set":"neo","prices":{"usd_foil":1.25}}}}}
             }}
        """.trimIndent())
        val d = MoxfieldApi.parseDeck(json)
        assertEquals("My Deck", d.name)
        assertEquals("WU", d.colorIdentity)
        assertEquals("bob", d.author)
        val cmdr = d.cards.first { it.section == "commander" }
        assertEquals(3.5, cmdr.priceUsd!!, 1e-9)
        assertEquals("https://cards.scryfall.io/normal/front/a/b/abcdef.jpg", cmdr.imageUrl)
        val foo = d.cards.first { it.name == "Foo" }
        assertEquals("main", foo.section)
        assertEquals(2, foo.quantity)
        assertEquals(1.25, foo.priceUsd!!, 1e-9)
    }
}
