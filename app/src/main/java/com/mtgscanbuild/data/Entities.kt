package com.mtgscanbuild.data

import androidx.room.ColumnInfo
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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    /** TCGplayer Market Price in USD (as published by Scryfall), null when unknown. */
    val priceUsd: Double? = null,
    val priceUsdFoil: Double? = null,
    val tcgplayerUrl: String? = null,
    /** Set release date, "yyyy-mm-dd". */
    @ColumnInfo(defaultValue = "''") val releasedAt: String = "",
)

/** Best-known TCGplayer market price for one copy of this printing in the given finish. */
fun CardData.price(foil: Boolean): Double? = if (foil) priceUsdFoil ?: priceUsd else priceUsd ?: priceUsdFoil

val CollectionCard.unitPrice: Double? get() = card.price(foil)
val CollectionCard.totalPrice: Double get() = (unitPrice ?: 0.0) * quantity

fun formatUsd(v: Double?): String = if (v == null) "—" else "$" + String.format(java.util.Locale.US, "%,.2f", v)

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
    /** Where the list came from (e.g. a Moxfield deck URL), null for generated decks. */
    val sourceUrl: String? = null,
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
    /** TCGplayer market price of one copy, used for cards you don't own. */
    val priceUsd: Double? = null,
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
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun collection(): CollectionDao
    abstract fun decks(): DeckDao

    companion object {
        /** v2: TCGplayer prices, release dates and deck source links. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE collection ADD COLUMN priceUsd REAL")
                db.execSQL("ALTER TABLE collection ADD COLUMN priceUsdFoil REAL")
                db.execSQL("ALTER TABLE collection ADD COLUMN tcgplayerUrl TEXT")
                db.execSQL("ALTER TABLE collection ADD COLUMN releasedAt TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE decks ADD COLUMN sourceUrl TEXT")
                db.execSQL("ALTER TABLE deck_cards ADD COLUMN priceUsd REAL")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "mtg.db").addMigrations(MIGRATION_1_2).build()
    }
}
