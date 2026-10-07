package com.mtgscanbuild.deck

import com.mtgscanbuild.data.CollectionCard
import com.mtgscanbuild.data.MoxDeck
import com.mtgscanbuild.data.frontName
import com.mtgscanbuild.data.unitPrice

/** One owned printing of a card, used to tell which set your copy came from. */
data class OwnedPrinting(val setCode: String, val setName: String, val collectorNumber: String, val foil: Boolean, val quantity: Int) {
    val label get() = "${setCode.uppercase()} #$collectorNumber${if (foil) " foil" else ""}" + if (quantity > 1) " ×$quantity" else ""
}

data class ComparedCard(
    val name: String,
    val quantity: Int,
    val section: String,
    val typeLine: String,
    val manaCost: String,
    val cmc: Double,
    val imageUrl: String?,
    val owned: Int,
    val printings: List<OwnedPrinting>,
    /** Price of one copy (cheapest owned printing, else the deck list's price). */
    val unitPrice: Double?,
) {
    val missing get() = if (DeckTools.isBasic(name)) 0 else (quantity - owned).coerceAtLeast(0)
    val have get() = quantity - missing
}

data class DeckComparison(val cards: List<ComparedCard>) {
    val total get() = cards.sumOf { it.quantity }
    val have get() = cards.sumOf { it.have }
    val missing get() = cards.sumOf { it.missing }
    val percentOwned get() = if (total == 0) 0 else (100.0 * have / total).toInt()
    val deckValue get() = cards.sumOf { (it.unitPrice ?: 0.0) * it.quantity }
    val missingCost get() = cards.sumOf { (it.unitPrice ?: 0.0) * it.missing }
    val missingCards get() = cards.filter { it.missing > 0 }
}

object DeckCompare {
    /** Collection grouped by card name; double-faced cards are also reachable by front-face name. */
    class OwnedIndex(collection: List<CollectionCard>) {
        private val byName: Map<String, List<CollectionCard>> = buildMap<String, MutableList<CollectionCard>> {
            for (c in collection) {
                getOrPut(c.card.name.lowercase()) { mutableListOf() } += c
                val front = c.card.frontName.lowercase()
                if (front != c.card.name.lowercase()) getOrPut(front) { mutableListOf() } += c
            }
        }

        fun items(name: String): List<CollectionCard> =
            byName[name.lowercase()] ?: byName[name.substringBefore(" // ").lowercase()] ?: emptyList()

        fun count(name: String) = items(name).sumOf { it.quantity }

        fun printings(name: String): List<OwnedPrinting> = items(name)
            .sortedWith(compareBy({ it.card.setName }, { it.card.collectorNumber.padStart(5, '0') }))
            .map { OwnedPrinting(it.card.setCode, it.card.setName, it.card.collectorNumber, it.foil, it.quantity) }

        fun cheapestPrice(name: String): Double? = items(name).mapNotNull { it.unitPrice }.minOrNull()
    }

    fun compare(deck: MoxDeck, collection: List<CollectionCard>): DeckComparison =
        compare(deck, OwnedIndex(collection))

    fun compare(deck: MoxDeck, index: OwnedIndex): DeckComparison {
        // The same card can appear in several boards; ownership is shared between them.
        val remaining = HashMap<String, Int>()
        val cards = deck.cards.sortedBy { if (it.section == "side") 1 else 0 }.map { c ->
            val key = c.name.lowercase()
            val avail = remaining.getOrPut(key) { index.count(c.name) }
            val use = minOf(avail, c.quantity)
            remaining[key] = avail - use
            ComparedCard(
                name = c.name, quantity = c.quantity, section = c.section, typeLine = c.typeLine,
                manaCost = c.manaCost, cmc = c.cmc, imageUrl = c.imageUrl,
                owned = use, printings = index.printings(c.name),
                unitPrice = index.cheapestPrice(c.name) ?: c.priceUsd,
            )
        }
        return DeckComparison(cards)
    }
}
