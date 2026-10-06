package com.mtgscanbuild.deck

import com.mtgscanbuild.data.CollectionCard
import com.mtgscanbuild.data.DeckCardEntity
import com.mtgscanbuild.data.legalityMap

object DeckTools {
    private val basics = setOf("Plains", "Island", "Swamp", "Mountain", "Forest", "Wastes",
        "Snow-Covered Plains", "Snow-Covered Island", "Snow-Covered Swamp", "Snow-Covered Mountain", "Snow-Covered Forest")

    fun isBasic(name: String) = name in basics

    /** Arena/MTGO-style text list. */
    fun exportText(cards: List<DeckCardEntity>): String = buildString {
        val cmd = cards.filter { it.section == "commander" }
        val main = cards.filter { it.section == "main" }
        val side = cards.filter { it.section == "side" }
        if (cmd.isNotEmpty()) {
            appendLine("Commander")
            cmd.forEach { appendLine("${it.quantity} ${it.name}") }
            appendLine()
        }
        appendLine("Deck")
        main.sortedBy { it.name }.forEach { appendLine("${it.quantity} ${it.name}") }
        if (side.isNotEmpty()) {
            appendLine()
            appendLine("Sideboard")
            side.forEach { appendLine("${it.quantity} ${it.name}") }
        }
    }.trimEnd()

    /** Checks a saved deck against the format rules and what you own. */
    fun validate(format: Format, cards: List<DeckCardEntity>, collection: List<CollectionCard>): List<String> {
        val issues = mutableListOf<String>()
        val total = cards.filter { it.section != "side" }.sumOf { it.quantity }
        if (format.hasCommander || format.singleton) {
            if (total != format.deckSize) issues += "Deck has $total cards (needs exactly ${format.deckSize})"
        } else if (total < format.deckSize) issues += "Deck has $total cards (needs at least ${format.deckSize})"
        if (format.hasCommander && cards.none { it.section == "commander" }) issues += "No commander selected"

        val owned = collection.groupBy { it.card.name }
        val byName = cards.groupBy { it.name }.mapValues { e -> e.value.sumOf { it.quantity } }
        for ((name, qty) in byName) {
            val items = owned[name]
            if (items == null) {
                if (!isBasic(name)) issues += "$name: not in your collection"
                continue
            }
            val have = items.sumOf { it.quantity }
            if (qty > have && !isBasic(name)) issues += "$name: using $qty but you own $have"
            val leg = items.first().card.legalityMap()[format.id] ?: "not_legal"
            val text = items.first().card.oracleText.lowercase()
            val unlimited = isBasic(name) || text.contains("a deck can have any number of cards named")
            when {
                leg == "banned" -> issues += "$name is banned in ${format.name}"
                leg == "not_legal" -> issues += "$name is not legal in ${format.name}"
                leg == "restricted" && format.id == "vintage" && qty > 1 -> issues += "$name is restricted (max 1)"
            }
            val max = when {
                unlimited -> Int.MAX_VALUE
                text.contains("a deck can have up to seven cards named") -> 7
                format.singleton -> 1
                else -> 4
            }
            if (qty > max) issues += "$name: $qty copies (max $max)"
        }
        return issues
    }
}
