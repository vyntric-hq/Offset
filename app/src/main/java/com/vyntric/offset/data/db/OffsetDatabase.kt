/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * A radio image with its metadata. Raw bytes are the family's image layout (CHIRP's, where CHIRP
 * supports the radio) without the trailer. [radio] is the RadioDriver id; [identHex] holds the
 * image identity (the handshake ident in hex for the UV-5R family, the model for others).
 */
@Entity(tableName = "images", indices = [Index("identHex"), Index("kind")])
data class ImageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String,
    @ColumnInfo(defaultValue = "uv5r") val radio: String,
    val identHex: String,
    val model: String,
    val firmware: String?,
    val channelCount: Int,
    val notes: String,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val bytes: ByteArray,
) {
    override fun equals(other: Any?) = other is ImageEntity && other.id == id && other.updatedAt == updatedAt
    override fun hashCode() = id.hashCode() * 31 + updatedAt.hashCode()
}

/** List projection without the image bytes. */
data class ImageSummaryRow(
    val id: Long,
    val name: String,
    val kind: String,
    val radio: String,
    val identHex: String,
    val model: String,
    val firmware: String?,
    val channelCount: Int,
    val notes: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** The staging list is one ordered document, written atomically, with the set's name. */
@Entity(tableName = "staging")
data class StagingEntity(
    @PrimaryKey val id: Int = 0,
    val json: String,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "") val name: String = "",
    /** The library image staging was loaded from, which a set is saved over when no radio was read. */
    val baseImageId: Long? = null,
    /** The set's zone names and add-to zone as JSON, on radios with named zones; null without. */
    val zones: String? = null,
)

/**
 * Reach inventory: radios, antennas, stations and places, one JSON document per row with its
 * kind, so the shapes can grow without a migration per field. Decoded only by InventoryRepository.
 */
@Entity(tableName = "reach_items", indices = [Index("kind")])
data class ReachItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val json: String,
    val updatedAt: Long,
)

@Dao
interface ReachItemDao {
    /** Bounded well above what Reach allows (500 places plus a user's equipment and stations). */
    @Query("SELECT * FROM reach_items ORDER BY id LIMIT 5000")
    fun observeAll(): Flow<List<ReachItemEntity>>

    @Insert
    suspend fun insert(entity: ReachItemEntity): Long

    @Query("UPDATE reach_items SET json = :json, updatedAt = :now WHERE id = :id")
    suspend fun update(id: Long, json: String, now: Long)

    @Query("DELETE FROM reach_items WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM reach_items WHERE kind = :kind LIMIT 1")
    suspend fun firstOfKind(kind: String): ReachItemEntity?
}

@Dao
interface ImageDao {
    @Query(
        "SELECT id, name, kind, radio, identHex, model, firmware, channelCount, notes, createdAt, updatedAt " +
            "FROM images ORDER BY updatedAt DESC LIMIT :limit"
    )
    fun observeSummaries(limit: Int): Flow<List<ImageSummaryRow>>

    @Query("SELECT * FROM images WHERE id = :id")
    suspend fun get(id: Long): ImageEntity?

    @Query("SELECT * FROM images WHERE id = :id")
    fun observe(id: Long): Flow<ImageEntity?>

    /** Names of user images named [name] or starting with [prefix] (the caller keeps exact numbered copies). */
    @Query("SELECT name FROM images WHERE kind = 'USER' AND (name = :name OR substr(name, 1, length(:prefix)) = :prefix) LIMIT 20000")
    suspend fun userNamesLike(name: String, prefix: String): List<String>

    /** The most recent user images among [names], with their bytes, for comparing channels. */
    @Query("SELECT * FROM images WHERE kind = 'USER' AND name IN (:names) ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun userImagesIn(names: List<String>, limit: Int): List<ImageEntity>

    @Query("SELECT COUNT(*) FROM images WHERE identHex = :identHex AND kind = 'FACTORY'")
    suspend fun countFactory(identHex: String): Int

    @Insert
    suspend fun insert(entity: ImageEntity): Long

    @Query("UPDATE images SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long)

    @Query("UPDATE images SET notes = :notes, updatedAt = :now WHERE id = :id")
    suspend fun updateNotes(id: Long, notes: String, now: Long)

    @Query("UPDATE images SET bytes = :bytes, channelCount = :channelCount, updatedAt = :now WHERE id = :id")
    suspend fun updateBytes(id: Long, bytes: ByteArray, channelCount: Int, now: Long)

    @Query("DELETE FROM images WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM images WHERE id IN (:ids)")
    suspend fun deleteChunk(ids: List<Long>)

    /** Deletes [ids] in one transaction, in chunks under SQLite's bound-parameter limit. */
    @Transaction
    suspend fun deleteAll(ids: List<Long>) = ids.chunked(500).forEach { deleteChunk(it) }
}

@Dao
interface StagingDao {
    @Query("SELECT * FROM staging WHERE id = 0")
    fun observe(): Flow<StagingEntity?>

    @Query("SELECT * FROM staging WHERE id = 0")
    suspend fun get(): StagingEntity?

    @Upsert
    suspend fun put(entity: StagingEntity)
}

@Database(entities = [ImageEntity::class, StagingEntity::class, ReachItemEntity::class], version = 6, exportSchema = true)
abstract class OffsetDatabase : RoomDatabase() {
    abstract fun images(): ImageDao
    abstract fun staging(): StagingDao
    abstract fun reachItems(): ReachItemDao

    companion object {
        /** 0.3.0 adds the radio family; every earlier image is a UV-5R family image. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE images ADD COLUMN radio TEXT NOT NULL DEFAULT 'uv5r'")
            }
        }

        /** 0.4.0 adds Reach: the station inventory and places. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reach_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `json` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reach_items_kind` ON `reach_items` (`kind`)")
            }
        }

        /** 0.7.5 names the staged set. Existing staging keeps its channels and starts unnamed. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE staging ADD COLUMN name TEXT NOT NULL DEFAULT ''")
            }
        }

        /** 0.7.6 remembers the image staging was loaded from. Existing staging has none. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE staging ADD COLUMN baseImageId INTEGER")
            }
        }

        /** 0.7.7 keeps the staged set's zones (RT-950 Pro). Existing staging has none until the next read. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE staging ADD COLUMN zones TEXT")
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
    }
}
