package com.mtgscanbuild

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mtgscanbuild.data.AppDatabase
import com.mtgscanbuild.data.CardData
import com.mtgscanbuild.data.CardNameIndex
import com.mtgscanbuild.data.CollectionFolder
import com.mtgscanbuild.data.Repository
import com.mtgscanbuild.data.ScryfallApi
import com.mtgscanbuild.data.folderSummary
import com.mtgscanbuild.data.PlanAccess
import com.mtgscanbuild.data.ProRequiredException
import com.mtgscanbuild.data.AppSettings
import com.mtgscanbuild.data.StartPage
import com.mtgscanbuild.data.MoxDeck
import com.mtgscanbuild.deck.BuildOptions
import com.mtgscanbuild.deck.Formats
import android.net.Uri
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CollectionFolderDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "folder-test-${UUID.randomUUID()}.db"
    private var database: AppDatabase? = null

    private fun open(): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
        .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
        .build().also { database = it }

    private fun repository(db: AppDatabase, access: PlanAccess = PlanAccess(false, false)): Repository {
        val api = ScryfallApi()
        return Repository(context, db, api, CardNameIndex(context.cacheDir, api), access = access)
    }

    private fun card() = CardData(
        scryfallId = "test-printing", oracleId = "test-oracle", name = "Test Card",
        setCode = "tst", setName = "Test", collectorNumber = "1", rarity = "common",
        manaCost = "", cmc = 0.0, typeLine = "Creature", oracleText = "", colors = "",
        colorIdentity = "", keywords = "", producedMana = "", power = null, toughness = null,
        loyalty = null, legalities = "", imageUrl = null, imageUrlLarge = null, edhrecRank = null,
        priceUsd = 2.0, priceUsdFoil = 5.0
    )

    @After
    fun cleanup() {
        database?.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun foldersAndAssignmentsPersistAndDeletingFolderKeepsCards() = runBlocking {
        var db = open()
        var repo = repository(db)
        repo.saveFolder(CollectionFolder(name = "  My Set  ", color = 0xFF008877.toInt()))
        val folder = repo.collectionFolders.first().single()
        assertEquals("My Set", folder.name)
        repo.addCard(card(), 3, false)
        val entry = repo.allCards().single()
        repo.assignFolder(listOf(entry.id), folder.id)
        repo.setQuantity(entry, 4)
        assertEquals(folder.id, repo.allCards().single().folderId)
        repo.addCard(card(), 2, false)
        assertEquals(6, repo.allCards().single().quantity)
        repo.saveFolder(folder.copy(name = "Renamed", color = 0xFFAA4422.toInt()))
        db.close()
        db = open()
        repo = repository(db)
        assertEquals("Renamed", repo.collectionFolders.first().single().name)
        assertEquals(0xFFAA4422.toInt(), repo.collectionFolders.first().single().color)
        assertEquals(folder.id, repo.allCards().single().folderId)
        assertEquals(12.0, folderSummary(repo.allCards(), folder.id).estimatedValue, 0.0)
        repo.deleteFolder(folder.id)
        assertTrue(repo.collectionFolders.first().isEmpty())
        assertNull(repo.allCards().single().folderId)
        assertEquals(6, repo.allCards().single().quantity)
    }

    @Test
    fun printingAndFinishChangesPreserveFoldersAndMergesUseTargetFolder() = runBlocking {
        val repo = repository(open())
        repo.saveFolder(CollectionFolder(name = "First", color = 0xFF008877.toInt()))
        repo.saveFolder(CollectionFolder(name = "Second", color = 0xFFAA4422.toInt()))
        val folders = repo.collectionFolders.first()
        repo.addCard(card(), 2, false)
        val original = repo.allCards().single()
        repo.assignFolder(listOf(original.id), folders[0].id)
        repo.changePrinting(original, card().copy(scryfallId = "other-printing"))
        val changed = repo.allCards().single()
        assertEquals(folders[0].id, changed.folderId)
        repo.setFoil(changed, true)
        assertEquals(folders[0].id, repo.allCards().single().folderId)
        repo.addCard(changed.card, 3, false)
        val target = repo.allCards().first { !it.foil }
        repo.assignFolder(listOf(target.id), folders[1].id)
        repo.setFoil(repo.allCards().first { it.foil }, false)
        val merged = repo.allCards().single()
        assertEquals(5, merged.quantity)
        assertEquals(folders[1].id, merged.folderId)
    }

    @Test
    fun basicFolderLimitIsTransactionalAndExistingFoldersStayEditable() = runBlocking {
        val db = open()
        val basic = repository(db)
        val outcomes = coroutineScope {
            (1..8).map { n -> async {
                try {
                    basic.saveFolder(CollectionFolder(name = "Folder $n", color = 0xFF008877.toInt()))
                    true
                } catch (e: ProRequiredException) {
                    false
                }
            } }.awaitAll()
        }
        assertEquals(5, outcomes.count { it })
        assertEquals(5, db.folders().count())
        val existing = basic.collectionFolders.first().first()
        basic.saveFolder(existing.copy(name = "Renamed", color = 0xFFAA4422.toInt()))
        assertEquals(5, db.folders().count())
        basic.deleteFolder(existing.id)
        basic.saveFolder(CollectionFolder(name = "Replacement", color = 0xFF008877.toInt()))
        assertEquals(5, db.folders().count())
    }

    @Test
    fun moreThanFiveExistingFoldersAndTheirCardsRemainAccessibleOnBasic() = runBlocking {
        val db = open()
        val developer = repository(db, PlanAccess(true))
        repeat(8) { developer.saveFolder(CollectionFolder(name = "Set $it", color = 0xFF008877.toInt())) }
        developer.addCard(card(), 4, false)
        val lastFolder = developer.collectionFolders.first().last()
        developer.assignFolder(listOf(developer.allCards().single().id), lastFolder.id)
        val basic = repository(db)
        assertEquals(8, basic.collectionFolders.first().size)
        assertEquals(lastFolder.id, basic.allCards().single().folderId)
        basic.saveFolder(lastFolder.copy(name = "Still editable"))
        basic.deleteFolder(lastFolder.id)
        assertEquals(4, basic.allCards().single().quantity)
        assertNull(basic.allCards().single().folderId)
        try {
            basic.saveFolder(CollectionFolder(name = "Ninth", color = 0))
            fail("Creation must still be gated above five")
        } catch (e: ProRequiredException) {
            assertEquals(7, db.folders().count())
        }
    }

    @Test
    fun basicCanCreateUnlimitedManualDecksAndAddCards() = runBlocking {
        val repo = repository(open())
        repeat(8) { repo.createManualDeck("Deck $it", "modern") }
        val decks = repo.savedDecks.first()
        assertEquals(8, decks.size)
        repo.addCardToDeck(decks.first().id, card())
        assertEquals(1, repo.observeDeckCards(decks.first().id).first().single().quantity)
    }

    @Test
    fun premiumRepositoryCallsAreDeniedBeforeSideEffects() = runBlocking {
        val repo = repository(open())
        val unavailable = Uri.parse("content://does-not-exist/test.csv")
        suspend fun expectPro(block: suspend () -> Unit) {
            try { block(); fail("Expected Pro gate") } catch (e: ProRequiredException) {
                assertTrue(e.message!!.contains("requires Pro"))
            }
        }
        expectPro { repo.exportCsv(unavailable) }
        expectPro { repo.importCsv(unavailable) {} }
        expectPro { repo.generateDecks(Formats.byId("modern"), BuildOptions()) }
        expectPro { repo.saveMoxfieldDeck(MoxDeck("id", "Deck", "modern", "", "", emptyList()), "modern") }
        assertTrue(repo.savedDecks.first().isEmpty())
    }

    @Test
    fun openingPagePreferencePersistsOnlyForUnlockedAccess() {
        val prefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        val previous = prefs.getString("start_page", null)
        try {
            val developer = AppSettings(context, PlanAccess(true))
            developer.startPage = StartPage.COLLECTION
            assertEquals(StartPage.COLLECTION, AppSettings(context, PlanAccess(true)).startPage)
            val basic = AppSettings(context, PlanAccess(false, false))
            assertEquals(StartPage.HOME, basic.startPage)
            try {
                basic.startPage = StartPage.SCAN
                fail("Basic must not change the start page")
            } catch (e: ProRequiredException) {
                assertEquals(StartPage.COLLECTION, AppSettings(context, PlanAccess(true)).startPage)
            }
        } finally {
            if (previous == null) prefs.edit().remove("start_page").commit()
            else prefs.edit().putString("start_page", previous).commit()
        }
    }

    @Test
    fun versionTwoUpgradePreservesInventoryAndDecksAndValidatesRoomSchema() = runBlocking {
        val path = context.getDatabasePath(databaseName)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { legacy ->
            legacy.execSQL("CREATE TABLE collection (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, quantity INTEGER NOT NULL, foil INTEGER NOT NULL, addedAt INTEGER NOT NULL, scryfallId TEXT NOT NULL, oracleId TEXT NOT NULL, name TEXT NOT NULL, setCode TEXT NOT NULL, setName TEXT NOT NULL, collectorNumber TEXT NOT NULL, rarity TEXT NOT NULL, manaCost TEXT NOT NULL, cmc REAL NOT NULL, typeLine TEXT NOT NULL, oracleText TEXT NOT NULL, colors TEXT NOT NULL, colorIdentity TEXT NOT NULL, keywords TEXT NOT NULL, producedMana TEXT NOT NULL, power TEXT, toughness TEXT, loyalty TEXT, legalities TEXT NOT NULL, imageUrl TEXT, imageUrlLarge TEXT, edhrecRank INTEGER, priceUsd REAL, priceUsdFoil REAL, tcgplayerUrl TEXT, releasedAt TEXT NOT NULL DEFAULT '')")
            legacy.execSQL("CREATE UNIQUE INDEX index_collection_scryfallId_foil ON collection (scryfallId, foil)")
            legacy.execSQL("CREATE INDEX index_collection_name ON collection (name)")
            legacy.execSQL("CREATE TABLE decks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, format TEXT NOT NULL, colors TEXT NOT NULL, commander TEXT, description TEXT NOT NULL, createdAt INTEGER NOT NULL, sourceUrl TEXT)")
            legacy.execSQL("CREATE TABLE deck_cards (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, deckId INTEGER NOT NULL, name TEXT NOT NULL, quantity INTEGER NOT NULL, section TEXT NOT NULL, typeLine TEXT NOT NULL, manaCost TEXT NOT NULL, cmc REAL NOT NULL, imageUrl TEXT, priceUsd REAL)")
            legacy.execSQL("CREATE INDEX index_deck_cards_deckId ON deck_cards (deckId)")
            val values = ContentValues().apply {
                put("id", 7L); put("quantity", 4); put("foil", 0); put("addedAt", 100L)
                put("scryfallId", "legacy-card"); put("oracleId", "legacy-oracle")
                put("name", "Legacy Card"); put("cmc", 1.0); put("priceUsd", 2.5)
                listOf("setCode", "setName", "collectorNumber", "rarity", "manaCost", "typeLine",
                    "oracleText", "colors", "colorIdentity", "keywords", "producedMana", "legalities")
                    .forEach { put(it, "") }
            }
            assertEquals(7L, legacy.insertOrThrow("collection", null, values))
            legacy.execSQL("INSERT INTO decks (id, name, format, colors, description, createdAt) VALUES (9, 'Legacy Deck', 'modern', '', '', 100)")
            legacy.execSQL("INSERT INTO deck_cards (deckId, name, quantity, section, typeLine, manaCost, cmc) VALUES (9, 'Legacy Card', 4, 'main', 'Creature', '', 1)")
            legacy.version = 2
        }
        val db = open()
        val entry = db.collection().getAll().single()
        assertEquals(7L, entry.id)
        assertEquals("Legacy Card", entry.card.name)
        assertEquals(4, entry.quantity)
        assertNull(entry.folderId)
        assertEquals("Legacy Deck", db.decks().observeDecks().first().single().name)
        assertEquals(4, db.decks().getCards(9).single().quantity)
        val folderId = db.folders().insert(CollectionFolder(name = "Migrated folder", color = 0xFF008877.toInt()))
        db.collection().assignFolder(listOf(entry.id), folderId)
        db.folders().delete(folderId)
        assertNull(db.collection().getAll().single().folderId)
        assertEquals(4, db.collection().getAll().single().quantity)
    }
}
