package com.vyntric.offset

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vyntric.offset.data.ImageRepository
import com.vyntric.offset.data.db.OffsetDatabase
import com.vyntric.offset.protocol.Uv5rDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock

/**
 * A database written by Offset 0.1 and 0.2 (schema 1, exported in app/schemas) opens after the
 * upgrade, with every stored image marked as a UV-5R family image and staging unnamed.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @Test fun version1ImagesBecomeUv5rImages(): Unit = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-test.db"
        ctx.deleteDatabase(name)
        val image = ctx.assets.open("simulated/uv5r-factory.img").use { it.readBytes() }
            .let { com.vyntric.offset.protocol.ChirpImg.decode(it).image }
        SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { db ->
            V1_SCHEMA.forEach(db::execSQL)
            db.execSQL(
                "INSERT INTO images (name, kind, identHex, model, firmware, channelCount, notes, createdAt, updatedAt, bytes) " +
                    "VALUES (?, 'FACTORY', ?, 'UV-5R', ?, ?, '', 1, 1, ?)",
                arrayOf<Any?>("Factory read", image.identity, image.firmwareVersion, image.usedChannelCount, image.bytes()),
            )
            db.execSQL("INSERT INTO staging (id, json, updatedAt) VALUES (0, '[]', 1)")
            db.version = 1
        }

        val room = Room.databaseBuilder(ctx, OffsetDatabase::class.java, name).addMigrations(*OffsetDatabase.MIGRATIONS).build()
        val repo = ImageRepository(room.images(), Clock.systemUTC())
        val summary = repo.observeAll().first().single()
        assertEquals(Uv5rDriver, summary.driver)
        val stored = repo.load(summary.id)!!
        assertEquals(image, stored.image)
        assertEquals(true, repo.hasFactoryImage(image.identity))
        // Schemas 4 to 6: staging kept its row, starts unnamed, with no base image and no zones.
        val staging = com.vyntric.offset.data.StagingRepository(room.staging(), Clock.systemUTC())
        assertEquals("", staging.name())
        assertEquals(null, staging.observeBaseImageId().first())
        assertEquals(null, staging.zones())
        // Bulk delete runs as one transaction on the real database.
        repo.deleteAll(listOf(summary.id))
        assertEquals(0, repo.observeAll().first().size)
        room.close()
        ctx.deleteDatabase(name)
    }

    private companion object {
        /** Schema 1 as Room created it (app/schemas/.../1.json). */
        val V1_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `images` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, `identHex` TEXT NOT NULL, `model` TEXT NOT NULL, `firmware` TEXT, " +
                "`channelCount` INTEGER NOT NULL, `notes` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `bytes` BLOB NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_images_identHex` ON `images` (`identHex`)",
            "CREATE INDEX IF NOT EXISTS `index_images_kind` ON `images` (`kind`)",
            "CREATE TABLE IF NOT EXISTS `staging` (`id` INTEGER NOT NULL, `json` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'f1d9b21afa26057f7c24a17d3516a3e0')",
        )
    }
}
