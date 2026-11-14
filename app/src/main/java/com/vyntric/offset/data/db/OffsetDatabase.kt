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

/** The staging list is one ordered document, written atomically. */
@Entity(tableName = "staging")
data class StagingEntity(
    @PrimaryKey val id: Int = 0,
    val json: String,
    val updatedAt: Long,
)

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

@Database(entities = [ImageEntity::class, StagingEntity::class], version = 2, exportSchema = true)
abstract class OffsetDatabase : RoomDatabase() {
    abstract fun images(): ImageDao
    abstract fun staging(): StagingDao

    companion object {
        /** 0.3.0 adds the radio family; every earlier image is a UV-5R family image. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE images ADD COLUMN radio TEXT NOT NULL DEFAULT 'uv5r'")
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2)
    }
}
