package com.mtgscanbuild.data

import android.content.Context
import android.net.Uri
import com.mtgscanbuild.deck.BuiltDeck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject

class Repository(
    private val context: Context,
    private val db: AppDatabase,
    val api: ScryfallApi,
    val names: CardNameIndex,
    val moxfield: MoxfieldApi = MoxfieldApi(),
) {
    private val cards = db.collection()
    private val decks = db.decks()

    val collection: Flow<List<CollectionCard>> = cards.observeAll()
    fun observeCard(id: Long) = cards.observe(id)
    suspend fun allCards() = cards.getAll()

    suspend fun addCard(card: CardData, qty: Int, foil: Boolean) {
        val existing = cards.find(card.scryfallId, foil)
        if (existing != null) cards.update(existing.copy(quantity = existing.quantity + qty, card = card))
        else cards.insert(CollectionCard(card = card, quantity = qty, foil = foil, addedAt = System.currentTimeMillis()))
    }

    /** Reverses [addCard] (used by the scanner's Undo). */
    suspend fun removeCard(scryfallId: String, qty: Int, foil: Boolean) {
        val existing = cards.find(scryfallId, foil) ?: return
        setQuantity(existing, existing.quantity - qty)
    }

    suspend fun setQuantity(item: CollectionCard, qty: Int) {
        if (qty <= 0) cards.delete(item) else cards.update(item.copy(quantity = qty))
    }

    suspend fun setFoil(item: CollectionCard, foil: Boolean) = moveTo(item, item.card, foil)

    suspend fun changePrinting(item: CollectionCard, newCard: CardData) = moveTo(item, newCard, item.foil)

    /** Updates the row in place, merging into an existing row if that printing/finish is already owned. */
    private suspend fun moveTo(item: CollectionCard, card: CardData, foil: Boolean) {
        if (item.card.scryfallId == card.scryfallId && item.foil == foil) return
        val target = cards.find(card.scryfallId, foil)
        if (target != null) {
            cards.update(target.copy(quantity = target.quantity + item.quantity))
            cards.delete(item)
        } else cards.update(item.copy(card = card, foil = foil))
    }

    suspend fun clearCollection() = cards.clear()

    /** Re-downloads card data (legalities change with bans, TCGplayer prices change daily) for every card. */
    suspend fun refreshCardData(progress: (Int, Int) -> Unit): Int {
        val all = cards.getAll()
        var updated = 0
        all.chunked(75).forEachIndexed { idx, chunk ->
            val res = api.collection(chunk.map { JSONObject().put("id", it.card.scryfallId) })
            chunk.zip(res).forEach { (item, fresh) ->
                if (fresh != null) { cards.update(item.copy(card = fresh)); updated++ }
            }
            progress(minOf((idx + 1) * 75, all.size), all.size)
        }
        return updated
    }

    // ---------- CSV ----------

    suspend fun exportCsv(uri: Uri): Int = withContext(Dispatchers.IO) {
        val all = cards.getAll().sortedBy { it.card.name }
        context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { w ->
            w.write("Quantity,Name,Set,Collector Number,Foil,Scryfall ID\n")
            for (c in all) {
                w.write(listOf(c.quantity.toString(), c.card.name, c.card.setCode, c.card.collectorNumber,
                    if (c.foil) "foil" else "", c.card.scryfallId).joinToString(",") { csvEscape(it) })
                w.write("\n")
            }
        }
        all.size
    }

    data class ImportResult(val imported: Int, val failed: List<String>)

    suspend fun importCsv(uri: Uri, progress: (String) -> Unit): ImportResult {
        val lines = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)!!.bufferedReader().readLines()
        }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return ImportResult(0, emptyList())
        val header = parseCsvLine(lines[0]).map { it.trim().lowercase() }
        fun col(vararg names: String) = header.indexOfFirst { it in names }
        val iQty = col("quantity", "count", "qty", "amount")
        val iName = col("name", "card name", "card")
        val iSet = col("set", "set code", "edition", "set_code")
        val iNum = col("collector number", "collector_number", "card number", "number", "cn")
        val iFoil = col("foil", "printing", "finish")
        val iId = col("scryfall id", "scryfall_id", "scryfallid", "id")
        val hasHeader = iName >= 0 || iId >= 0

        data class Row(val qty: Int, val foil: Boolean, val ident: JSONObject, val label: String)
        val rows = mutableListOf<Row>()
        val dataLines = if (hasHeader) lines.drop(1) else lines
        for (line in dataLines) {
            if (hasHeader) {
                val f = parseCsvLine(line)
                fun get(i: Int) = if (i >= 0 && i < f.size) f[i].trim() else ""
                val qty = get(iQty).toIntOrNull() ?: 1
                val name = get(iName); val set = get(iSet); val num = get(iNum); val id = get(iId)
                val foil = get(iFoil).lowercase().let { it == "foil" || it == "true" || it == "1" || it == "yes" || it == "etched" }
                val ident = JSONObject()
                when {
                    id.length >= 32 -> ident.put("id", id)
                    set.isNotEmpty() && num.isNotEmpty() -> ident.put("set", set.lowercase()).put("collector_number", num)
                    name.isNotEmpty() && set.isNotEmpty() -> ident.put("name", name).put("set", set.lowercase())
                    name.isNotEmpty() -> ident.put("name", name)
                    else -> continue
                }
                rows += Row(qty, foil, ident, name.ifEmpty { "$set $num" })
            } else {
                // Plain decklist style: "4 Lightning Bolt" or "Lightning Bolt"
                val m = Regex("^\\s*(\\d+)x?\\s+(.+)$").find(line)
                val qty = m?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val name = (m?.groupValues?.get(2) ?: line).replace(Regex("\\s*\\(.*$"), "").trim()
                if (name.isEmpty()) continue
                rows += Row(qty, false, JSONObject().put("name", name), name)
            }
        }
        var ok = 0
        val failed = mutableListOf<String>()
        rows.chunked(75).forEachIndexed { idx, chunk ->
            progress("Importing ${minOf((idx + 1) * 75, rows.size)} / ${rows.size}…")
            val res = api.collection(chunk.map { it.ident })
            chunk.zip(res).forEach { (row, card) ->
                if (card == null) failed += row.label
                else { addCard(card, row.qty, row.foil); ok += row.qty }
            }
        }
        return ImportResult(ok, failed)
    }

    // ---------- Decks ----------

    val savedDecks = decks.observeDecks()
    fun observeDeck(id: Long) = decks.observeDeck(id)
    fun observeDeckCards(id: Long) = decks.observeCards(id)
    suspend fun deleteDeck(id: Long) = decks.deleteDeck(id)
    suspend fun renameDeck(d: DeckEntity, name: String) = decks.updateDeck(d.copy(name = name))

    suspend fun saveDeck(built: BuiltDeck): Long {
        val entity = DeckEntity(
            name = built.name, format = built.format.id, colors = built.colors,
            commander = built.commander?.name, description = built.description,
            createdAt = System.currentTimeMillis()
        )
        val rows = built.entries.map {
            DeckCardEntity(
                deckId = 0, name = it.name, quantity = it.quantity, section = it.section,
                typeLine = it.typeLine, manaCost = it.manaCost, cmc = it.cmc, imageUrl = it.imageUrl,
                priceUsd = it.priceUsd
            )
        }
        return decks.saveDeck(entity, rows)
    }

    /** Saves a Moxfield deck list as one of your decks (cards you don't own are flagged in the deck view). */
    suspend fun saveMoxfieldDeck(deck: MoxDeck, appFormatId: String): Long {
        val entity = DeckEntity(
            name = deck.name, format = appFormatId, colors = deck.colorIdentity,
            commander = deck.cards.firstOrNull { it.section == "commander" }?.name,
            description = "Imported from Moxfield" + if (deck.author.isNotEmpty()) " (by ${deck.author})" else "",
            createdAt = System.currentTimeMillis(), sourceUrl = deck.url
        )
        val rows = deck.cards.map {
            DeckCardEntity(
                deckId = 0, name = it.name, quantity = it.quantity, section = it.section, typeLine = it.typeLine,
                manaCost = it.manaCost, cmc = it.cmc, imageUrl = it.imageUrl, priceUsd = it.priceUsd
            )
        }
        return decks.saveDeck(entity, rows)
    }

    suspend fun setDeckCardQuantity(c: DeckCardEntity, qty: Int) {
        if (qty <= 0) decks.deleteCard(c) else decks.updateCard(c.copy(quantity = qty))
    }

    suspend fun addCardToDeck(deckId: Long, card: CardData, section: String = "main") {
        val existing = decks.getCards(deckId).firstOrNull { it.name == card.name && it.section == section }
        if (existing != null) decks.updateCard(existing.copy(quantity = existing.quantity + 1))
        else decks.insertCards(listOf(DeckCardEntity(
            deckId = deckId, name = card.name, quantity = 1, section = section, typeLine = card.typeLine,
            manaCost = card.manaCost, cmc = card.cmc, imageUrl = card.imageUrlLarge ?: card.imageUrl,
            priceUsd = card.priceUsd
        )))
    }

    companion object {
        fun csvEscape(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

        fun parseCsvLine(line: String): List<String> {
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            var inQ = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    inQ && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                    c == '"' -> inQ = !inQ
                    c == ',' && !inQ -> { out += sb.toString(); sb.clear() }
                    else -> sb.append(c)
                }
                i++
            }
            out += sb.toString()
            return out
        }
    }
}
