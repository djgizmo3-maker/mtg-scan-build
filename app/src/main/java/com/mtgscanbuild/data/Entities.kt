package com.mtgscanbuild.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import android.content.Context
import kotlinx.coroutines.flow.Flow

/** Card data as returned by Scryfall (one specific printing). Colors are stored as e.g. "WU". */
data class CardData(
    val scryfallId: String,
    val oracleId: String,
    val name: String,
    val setCode: String,
    val setName: String,
    val collectorNumber: String,
    val rarity: String,
    val manaCost: String,
    val cmc: Double,
    val typeLine: String,
    val oracleText: String,
    val colors: String,
    val colorIdentity: String,
    val keywords: String,
    val producedMana: String,
    val power: String?,
    val toughness: String?,
    val loyalty: String?,
    val legalities: String,
    val imageUrl: String?,
    val imageUrlLarge: String?,
    val edhrecRank: Int?,
)

fun CardData.legalityMap(): Map<String, String> =
    legalities.split(';').mapNotNull {
        val i = it.indexOf('=')
        if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
    }.toMap()

/** Front face name ("Delver of Secrets" for "Delver of Secrets // Insectile Aberration"). */
val CardData.frontName: String get() = name.substringBefore(" // ")
val CardData.frontType: String get() = typeLine.substringBefore(" // ")

@Entity(
    tableName = "collection",
    indices = [Index(value = ["scryfallId", "foil"], unique = true), Index("name")]
)
data class CollectionCard(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @Embedded val card: CardData,
    val quantity: Int,
    val foil: Boolean,
    val addedAt: Long,
)

@Entity(tableName = "decks")
data class DeckEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val format: String,
    val colors: String,
    val commander: String?,
    val description: String,
    val createdAt: Long,
)

@Entity(tableName = "deck_cards", indices = [Index("deckId")])
data class DeckCardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deckId: Long,
    val name: String,
    val quantity: Int,
    /** "commander", "main" or "side" */
    val section: String,
    val typeLine: String,
    val manaCost: String,
    val cmc: Double,
    val imageUrl: String?,
)

@Dao
interface CollectionDao {
    @Query("SELECT * FROM collection ORDER BY name")
    fun observeAll(): Flow<List<CollectionCard>>

    @Query("SELECT * FROM collection")
    suspend fun getAll(): List<CollectionCard>

    @Query("SELECT * FROM collection WHERE id = :id")
    fun observe(id: Long): Flow<CollectionCard?>

    @Query("SELECT * FROM collection WHERE scryfallId = :sid AND foil = :foil LIMIT 1")
    suspend fun find(sid: String, foil: Boolean): CollectionCard?

    @Insert
    suspend fun insert(c: CollectionCard): Long

    @Update
    suspend fun update(c: CollectionCard)

    @Delete
    suspend fun delete(c: CollectionCard)

    @Query("DELETE FROM collection")
    suspend fun clear()
}

@Dao
interface DeckDao {
    @Query("SELECT * FROM decks ORDER BY createdAt DESC")
    fun observeDecks(): Flow<List<DeckEntity>>

    @Query("SELECT * FROM decks WHERE id = :id")
    fun observeDeck(id: Long): Flow<DeckEntity?>

    @Query("SELECT * FROM deck_cards WHERE deckId = :id ORDER BY cmc, name")
    fun observeCards(id: Long): Flow<List<DeckCardEntity>>

    @Query("SELECT * FROM deck_cards WHERE deckId = :id")
    suspend fun getCards(id: Long): List<DeckCardEntity>

    @Insert
    suspend fun insertDeck(d: DeckEntity): Long

    @Insert
    suspend fun insertCards(c: List<DeckCardEntity>)

    @Update
    suspend fun updateDeck(d: DeckEntity)

    @Update
    suspend fun updateCard(c: DeckCardEntity)

    @Delete
    suspend fun deleteCard(c: DeckCardEntity)

    @Query("DELETE FROM decks WHERE id = :id")
    suspend fun deleteDeckRow(id: Long)

    @Query("DELETE FROM deck_cards WHERE deckId = :id")
    suspend fun deleteDeckCards(id: Long)

    @Transaction
    suspend fun saveDeck(d: DeckEntity, cards: List<DeckCardEntity>): Long {
        val id = insertDeck(d)
        insertCards(cards.map { it.copy(id = 0, deckId = id) })
        return id
    }

    @Transaction
    suspend fun deleteDeck(id: Long) {
        deleteDeckCards(id)
        deleteDeckRow(id)
    }
}

@Database(
    entities = [CollectionCard::class, DeckEntity::class, DeckCardEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun collection(): CollectionDao
    abstract fun decks(): DeckDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "mtg.db").build()
    }
}
