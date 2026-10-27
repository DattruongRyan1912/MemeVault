package com.memevault.app

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies that an installed v1 library can open with the v3 recipe/lineage/crop schema. */
@RunWith(AndroidJUnit4::class)
class AssetDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MemeVaultDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrateV1ToV3() {
        val v1 = helper.createDatabase(TEST_DB, 1)
        v1.execSQL(
            """
            INSERT INTO assets(
                id, original_path, mime_type, display_name, type, content_hash,
                width, height, duration_ms, favorite, created_at, last_used_at, use_count
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "source",
                "/data/user/0/test/files/media/originals/source.jpg",
                "image/jpeg",
                "source.jpg",
                "MEME",
                "source-hash",
                320,
                320,
                0L,
                0,
                1_000L,
                null,
                0,
            ),
        )
        v1.execSQL(
            """
            INSERT INTO assets(
                id, original_path, mime_type, display_name, type, content_hash,
                width, height, duration_ms, favorite, created_at, last_used_at, use_count
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "sticker",
                "/data/user/0/test/files/media/originals/sticker.png",
                "image/png",
                "sticker.png",
                "STICKER",
                "sticker-hash",
                320,
                320,
                0L,
                0,
                2_000L,
                null,
                0,
            ),
        )
        v1.execSQL("INSERT INTO asset_variants(asset_id, profile, path, mime_type, file_size, created_at) VALUES (?, ?, ?, ?, ?, ?)", arrayOf<Any?>("sticker", "legacy", "/data/user/0/test/files/media/variants/sticker/legacy.png", "image/png", 42L, 2_000L))
        v1.execSQL("INSERT INTO tags(id, name) VALUES (?, ?)", arrayOf("tag-1", "Dog"))
        v1.execSQL("INSERT INTO tags(id, name) VALUES (?, ?)", arrayOf("tag-2", " dog "))
        v1.execSQL("INSERT INTO tags(id, name) VALUES (?, ?)", arrayOf("tag-3", "#DOG"))
        v1.execSQL("INSERT INTO asset_tags(asset_id, tag_id) VALUES (?, ?)", arrayOf("sticker", "tag-1"))
        v1.execSQL("INSERT INTO asset_tags(asset_id, tag_id) VALUES (?, ?)", arrayOf("sticker", "tag-2"))
        v1.execSQL("INSERT INTO asset_tags(asset_id, tag_id) VALUES (?, ?)", arrayOf("sticker", "tag-3"))
        v1.close()

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB,
            3,
            true,
            AssetDatabase.MIGRATION_1_2,
            AssetDatabase.MIGRATION_2_3,
        )
        try {
            val dedup = migrated.query("SELECT dedup_key FROM assets WHERE id = 'source'")
            dedup.use {
                assertTrue(it.moveToFirst())
                assertEquals("source-hash", it.getString(0))
            }
        } finally {
            migrated.close()
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, MemeVaultDatabase::class.java, TEST_DB)
            .addMigrations(AssetDatabase.MIGRATION_1_2, AssetDatabase.MIGRATION_2_3)
            .build()
        try {
            database.assetDao().normalizeLegacyTags()
            val tags = database.assetDao().allTags()
            assertEquals(1, tags.size)
            assertEquals("dog", tags.single().id)
            assertEquals("dog", TagNormalizer.normalize(tags.single().name)?.key)
            val relations = database.assetDao().assetTagsForAsset("sticker")
            assertEquals(1, relations.size)
            assertEquals("dog", relations.single().tagId)
            assertEquals(TagSource.MANUAL.name, relations.single().source)
            assertNotNull(database.assetDao().findAssetTag("sticker", relations.single().tagId))
            assertEquals(
                1,
                database.assetDao().listAssets(
                    favoriteOnly = false,
                    type = null,
                    search = "dog",
                    tagSearch = "dog",
                ).size,
            )
            listOf("%", "_", "\\").forEach { literal ->
                val escaped = SqlLikeEscaper.escape(literal)
                assertEquals(
                    0,
                    database.assetDao().listAssets(
                        favoriteOnly = false,
                        type = null,
                        search = escaped,
                        tagSearch = escaped,
                    ).size,
                )
            }
            assertTrue(
                database.assetDao().replaceAssetTags(
                    assetId = "sticker",
                    tags = listOf("#DOG"),
                    source = TagSource.MANUAL.name,
                ),
            )
            assertEquals("dog", database.assetDao().assetTagsForAsset("sticker").single().tagId)

            val columns = database.openHelper.writableDatabase.query("PRAGMA table_info(sticker_recipes)")
            val cropColumns = mutableSetOf<String>()
            columns.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) cropColumns += it.getString(nameIndex)
            }
            assertTrue(
                cropColumns.containsAll(
                    setOf(
                        "crop_left",
                        "crop_top",
                        "crop_right",
                        "crop_bottom",
                        "crop_source_width",
                        "crop_source_height",
                    ),
                ),
            )

            val sql = database.openHelper.writableDatabase
            sql.execSQL("UPDATE assets SET source_asset_id = ? WHERE id = ?", arrayOf("source", "sticker"))
            sql.execSQL("DELETE FROM assets WHERE id = ?", arrayOf("source"))
            val lineage = sql.query("SELECT source_asset_id FROM assets WHERE id = 'sticker'")
            lineage.use {
                assertTrue(it.moveToFirst())
                assertNull(it.getString(0))
            }
            val variants = sql.query("SELECT COUNT(*) FROM asset_variants WHERE asset_id = 'sticker'")
            variants.use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun migrateV2ToV3AddsCropColumns() {
        val v2 = helper.createDatabase(TEST_DB_V2, 2)
        v2.execSQL(
            """
            INSERT INTO assets(
                id, original_path, mime_type, display_name, type, content_hash,
                width, height, duration_ms, favorite, created_at, last_used_at, use_count,
                source_asset_id, dedup_key
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "v2-sticker",
                "/data/user/0/test/files/media/originals/v2-sticker.png",
                "image/png",
                "v2-sticker.png",
                "STICKER",
                "v2-sticker-hash",
                320,
                320,
                0L,
                0,
                1_000L,
                null,
                0,
                null,
                "v2-sticker-hash",
            ),
        )
        v2.execSQL(
            """
            INSERT INTO sticker_recipes(
                sticker_asset_id, renderer_id, renderer_version, export_policy_version,
                source_hash, mask_relative_path, mask_format, mask_width, mask_height,
                mask_hash, working_width, working_height, output_width, output_height,
                threshold, padding_fraction, border_color_argb, border_thickness_px,
                created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "v2-sticker",
                "renderer",
                "1",
                "policy",
                "v2-sticker-hash",
                null,
                "gray8-png",
                320,
                320,
                null,
                320,
                320,
                320,
                320,
                0.5f,
                0.05f,
                null,
                0,
                1_000L,
                1_000L,
            ),
        )
        v2.close()

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB_V2,
            3,
            true,
            AssetDatabase.MIGRATION_2_3,
        )
        try {
            val columns = migrated.query("PRAGMA table_info(sticker_recipes)")
            val cropColumns = mutableSetOf<String>()
            columns.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) cropColumns += it.getString(nameIndex)
            }
            assertTrue(
                cropColumns.containsAll(
                    setOf(
                        "crop_left",
                        "crop_top",
                        "crop_right",
                        "crop_bottom",
                        "crop_source_width",
                        "crop_source_height",
                    ),
                ),
            )
            val recipe = migrated.query(
                "SELECT crop_left, crop_top, crop_right, crop_bottom, crop_source_width, crop_source_height " +
                    "FROM sticker_recipes WHERE sticker_asset_id = 'v2-sticker'",
            )
            recipe.use {
                assertTrue(it.moveToFirst())
                repeat(6) { index -> assertEquals(0, it.getInt(index)) }
            }
        } finally {
            migrated.close()
        }
    }

    companion object {
        private const val TEST_DB = "memevault-migration-test"
        private const val TEST_DB_V2 = "memevault-migration-v2-v3-test"
    }
}
