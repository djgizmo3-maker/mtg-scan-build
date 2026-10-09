package com.mtgscanbuild

import com.mtgscanbuild.data.CardData
import com.mtgscanbuild.data.CollectionCard
import com.mtgscanbuild.data.FolderSummary
import com.mtgscanbuild.data.folderSummary
import com.mtgscanbuild.data.totalPrice
import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionFoldersTest {
    private fun entry(
        id: Long, folderId: Long?, quantity: Int = 1, foil: Boolean = false,
        price: Double? = 2.0, foilPrice: Double? = 5.0,
    ) = CollectionCard(
        id = id, folderId = folderId, quantity = quantity, foil = foil, addedAt = 0,
        card = CardData(
            scryfallId = "card-$id", oracleId = "oracle-$id", name = "Card $id",
            setCode = "tst", setName = "Test", collectorNumber = "$id", rarity = "common",
            manaCost = "", cmc = 0.0, typeLine = "Creature", oracleText = "", colors = "",
            colorIdentity = "", keywords = "", producedMana = "", power = null, toughness = null,
            loyalty = null, legalities = "", imageUrl = null, imageUrlLarge = null, edhrecRank = null,
            priceUsd = price, priceUsdFoil = foilPrice
        )
    )

    @Test
    fun folderValuesUseQuantitiesAndFinishWithoutIncludingOtherFolders() {
        val cards = listOf(entry(1, 10, 3), entry(2, 10, 2, foil = true),
            entry(3, 20, 9), entry(4, null, 4))
        assertEquals(FolderSummary(5, 16.0, 0), folderSummary(cards, 10))
        assertEquals(FolderSummary(9, 18.0, 0), folderSummary(cards, 20))
        assertEquals(FolderSummary(4, 8.0, 0), folderSummary(cards, null))
    }

    @Test
    fun missingPricesAreExcludedAndCountedByCopies() {
        val cards = listOf(entry(1, 10, 3, price = null, foilPrice = null), entry(2, 10, 2))
        assertEquals(FolderSummary(5, 4.0, 3), folderSummary(cards, 10))
    }

    @Test
    fun estimatesPreserveExistingPriceFallbacks() {
        val cards = listOf(entry(1, 10, 2, foil = true, foilPrice = null),
            entry(2, 10, 3, price = null))
        assertEquals(FolderSummary(5, 19.0, 0), folderSummary(cards, 10))
    }

    @Test
    fun emptyFolderHasZeroValueAndCount() {
        assertEquals(FolderSummary(0, 0.0, 0), folderSummary(listOf(entry(1, null)), 10))
    }

    @Test
    fun movingWholeEntryPreservesTotalInventoryAndValue() {
        val original = listOf(entry(1, 10, 3), entry(2, null, 2, foil = true))
        val moved = original.map { if (it.id == 1L) it.copy(folderId = 20) else it }
        assertEquals(original.sumOf { it.quantity }, moved.sumOf { it.quantity })
        assertEquals(original.sumOf { it.totalPrice }, moved.sumOf { it.totalPrice }, 0.0)
        assertEquals(FolderSummary(0, 0.0, 0), folderSummary(moved, 10))
        assertEquals(FolderSummary(3, 6.0, 0), folderSummary(moved, 20))
    }
}
