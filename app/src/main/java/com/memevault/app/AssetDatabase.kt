package com.memevault.app

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.util.Locale

@Entity(
    tableName = "assets",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_asset_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["content_hash"]),
        Index(value = ["dedup_key"], unique = true),
        Index(value = ["source_asset_id"]),
        Index(value = ["created_at"]),
        Index(value = ["last_used_at"]),
    ],
)
data class AssetEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "original_path") val originalPath: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    val type: String,
    @ColumnInfo(name = "content_hash") val hash: String,
    @ColumnInfo(name = "dedup_key") val dedupKey: String,
    val width: Int,
    val height: Int,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    val favorite: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long?,
    @ColumnInfo(name = "use_count") val useCount: Int,
    @ColumnInfo(name = "source_asset_id") val sourceAssetId: String?,
)

@Entity(
    tableName = "asset_variants",
    primaryKeys = ["asset_id", "profile"],
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["asset_id"])],
)
data class AssetVariantEntity(
    @ColumnInfo(name = "asset_id") val assetId: String,
    val profile: String,
    val path: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "file_size") val fileSize: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "tags",
    indices = [Index(value = ["name"], unique = true)],
)
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
)

@Entity(
    tableName = "asset_tags",
    primaryKeys = ["asset_id", "tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["asset_id"]), Index(value = ["tag_id"])],
)
data class AssetTagCrossRef(
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "tag_id") val tagId: String,
    val source: String = TagSource.MANUAL.name,
    val confidence: Float? = null,
    @ColumnInfo(name = "model_version") val modelVersion: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "sticker_recipes",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["sticker_asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sticker_asset_id"], unique = true)],
)
data class StickerRecipeEntity(
    @PrimaryKey @ColumnInfo(name = "sticker_asset_id") val stickerAssetId: String,
    @ColumnInfo(name = "renderer_id") val rendererId: String,
    @ColumnInfo(name = "renderer_version") val rendererVersion: String,
    @ColumnInfo(name = "export_policy_version") val exportPolicyVersion: String,
    @ColumnInfo(name = "source_hash") val sourceHash: String,
    @ColumnInfo(name = "mask_relative_path") val maskRelativePath: String?,
    @ColumnInfo(name = "mask_format") val maskFormat: String,
    @ColumnInfo(name = "mask_width") val maskWidth: Int,
    @ColumnInfo(name = "mask_height") val maskHeight: Int,
    @ColumnInfo(name = "mask_hash") val maskHash: String?,
    @ColumnInfo(name = "working_width") val workingWidth: Int,
    @ColumnInfo(name = "working_height") val workingHeight: Int,
    @ColumnInfo(name = "output_width") val outputWidth: Int,
    @ColumnInfo(name = "output_height") val outputHeight: Int,
    val threshold: Float,
    @ColumnInfo(name = "padding_fraction") val paddingFraction: Float,
    @ColumnInfo(name = "border_color_argb") val borderColorArgb: Int?,
    @ColumnInfo(name = "border_thickness_px") val borderThicknessPx: Int,
    @ColumnInfo(name = "crop_left") val cropLeft: Int,
    @ColumnInfo(name = "crop_top") val cropTop: Int,
    @ColumnInfo(name = "crop_right") val cropRight: Int,
    @ColumnInfo(name = "crop_bottom") val cropBottom: Int,
    @ColumnInfo(name = "crop_source_width") val cropSourceWidth: Int,
    @ColumnInfo(name = "crop_source_height") val cropSourceHeight: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

data class StoredVariant(
    val assetId: String,
    val profile: String,
    val path: String,
    val mimeType: String,
    val fileSize: Long,
)

data class AssetDeletion(
    val asset: Asset,
    val variants: List<StoredVariant>,
    val recipe: StickerRecipe?,
)

data class TagSuggestionRow(
    val name: String,
    @ColumnInfo(name = "usage_count") val usageCount: Int,
)

internal object SqlLikeEscaper {
    fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
}

@Dao
interface AssetDao {
    @Query(
        """
        SELECT * FROM assets
        WHERE (:favoriteOnly = 0 OR favorite = 1)
          AND (:type IS NULL OR type = :type)
          AND (
              :search = ''
              OR lower(display_name) LIKE '%' || lower(:search) || '%' ESCAPE '\'
              OR lower(mime_type) LIKE '%' || lower(:search) || '%' ESCAPE '\'
              OR id IN (
                  SELECT at.asset_id
                  FROM asset_tags at
                  JOIN tags t ON t.id = at.tag_id
                  WHERE t.id LIKE '%' || :tagSearch || '%' ESCAPE '\'
              )
          )
        ORDER BY COALESCE(last_used_at, created_at) DESC
        """,
    )
    fun listAssets(favoriteOnly: Boolean, type: String?, search: String, tagSearch: String): List<AssetEntity>

    @Query("SELECT * FROM assets WHERE id = :id LIMIT 1")
    fun findById(id: String): AssetEntity?

    @Query("SELECT * FROM assets WHERE dedup_key = :dedupKey LIMIT 1")
    fun findByDedupKey(dedupKey: String): AssetEntity?

    @Query("SELECT * FROM assets WHERE content_hash = :hash AND type = :type LIMIT 1")
    fun findByContentHashAndType(hash: String, type: String): AssetEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertAsset(asset: AssetEntity): Long

    @Query("UPDATE assets SET favorite = :favorite WHERE id = :id")
    fun setFavorite(id: String, favorite: Boolean): Int

    @Query(
        "UPDATE assets SET last_used_at = :now, use_count = use_count + 1 WHERE id = :id",
    )
    fun touchUsed(id: String, now: Long): Int

    @Query("DELETE FROM assets WHERE id = :id")
    fun deleteAsset(id: String): Int

    @Query("UPDATE assets SET source_asset_id = NULL WHERE source_asset_id = :id")
    fun unlinkDerivedAssets(id: String): Int

    @Query("SELECT COUNT(*) FROM assets WHERE source_asset_id = :id")
    fun countDerivedAssets(id: String): Int

    @Query("SELECT COUNT(*) FROM assets")
    fun countAssets(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertVariant(variant: AssetVariantEntity)

    @Query("SELECT * FROM asset_variants WHERE asset_id = :assetId AND profile = :profile LIMIT 1")
    fun findVariant(assetId: String, profile: String): AssetVariantEntity?

    @Query("SELECT * FROM asset_variants WHERE asset_id = :assetId")
    fun variantsForAsset(assetId: String): List<AssetVariantEntity>

    @Query("SELECT path FROM asset_variants")
    fun allVariantPaths(): List<String>

    @Query("SELECT original_path FROM assets")
    fun allOriginalPaths(): List<String>

    @Query("SELECT * FROM assets")
    fun allAssets(): List<AssetEntity>

    @Query("SELECT mask_relative_path FROM sticker_recipes WHERE mask_relative_path IS NOT NULL")
    fun allMaskPaths(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertTag(tag: TagEntity): Long

    @Query("SELECT * FROM tags")
    fun allTags(): List<TagEntity>

    @Query("SELECT * FROM asset_tags WHERE tag_id = :tagId")
    fun assetTagsForTag(tagId: String): List<AssetTagCrossRef>

    @Query("SELECT * FROM asset_tags WHERE asset_id = :assetId AND tag_id = :tagId LIMIT 1")
    fun findAssetTag(assetId: String, tagId: String): AssetTagCrossRef?

    @Query("SELECT * FROM asset_tags WHERE asset_id = :assetId")
    fun assetTagsForAsset(assetId: String): List<AssetTagCrossRef>

    @Query("DELETE FROM asset_tags WHERE asset_id = :assetId AND tag_id = :tagId")
    fun deleteAssetTag(assetId: String, tagId: String): Int

    @Query("DELETE FROM tags WHERE id = :tagId")
    fun deleteTag(tagId: String): Int

    @Query("UPDATE tags SET name = :name WHERE id = :tagId")
    fun updateTagName(tagId: String, name: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAssetTag(crossRef: AssetTagCrossRef)

    /** AI/system suggestions may enrich a tag, but can never replace a manual attribution. */
    fun upsertAssetTagPreservingManual(crossRef: AssetTagCrossRef) {
        val existing = findAssetTag(crossRef.assetId, crossRef.tagId)
        if (existing?.source == TagSource.MANUAL.name && crossRef.source != TagSource.MANUAL.name) {
            return
        }
        insertAssetTag(crossRef)
    }

    @Query(
        """
        SELECT t.name FROM tags t
        INNER JOIN asset_tags at ON at.tag_id = t.id
        WHERE at.asset_id = :assetId ORDER BY t.name COLLATE NOCASE
        """,
    )
    fun tagsForAsset(assetId: String): List<String>

    @Query("DELETE FROM asset_tags WHERE asset_id = :assetId")
    fun deleteAssetTags(assetId: String): Int

    @Query(
        """
        SELECT t.name AS name, COUNT(at.asset_id) AS usage_count
        FROM tags t
        LEFT JOIN asset_tags at ON at.tag_id = t.id
        WHERE :prefix = ''
           OR t.id LIKE :prefix || '%' ESCAPE '\'
        GROUP BY t.id, t.name
        ORDER BY usage_count DESC, t.id
        LIMIT :limit
        """,
    )
    fun suggestTags(prefix: String, limit: Int): List<TagSuggestionRow>

    @Query("SELECT * FROM sticker_recipes WHERE sticker_asset_id = :assetId LIMIT 1")
    fun findStickerRecipe(assetId: String): StickerRecipeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertStickerRecipe(recipe: StickerRecipeEntity)

    @Transaction
    fun insertAssetWithTags(asset: AssetEntity, tags: List<String>) {
        insertAsset(asset)
        replaceAssetTagsInternal(asset.id, tags, TagSource.MANUAL.name)
    }

    @Transaction
    fun insertStickerWithTags(
        asset: AssetEntity,
        recipe: StickerRecipeEntity,
        tags: List<String>,
    ) {
        insertAsset(asset)
        insertStickerRecipe(recipe)
        replaceAssetTagsInternal(asset.id, tags, TagSource.MANUAL.name)
    }

    @Transaction
    fun replaceAssetTags(assetId: String, tags: List<String>, source: String): Boolean {
        deleteAssetTags(assetId)
        replaceAssetTagsInternal(assetId, tags, source)
        return true
    }

    /** Adds manual tags to an existing duplicate without dropping its current attribution. */
    @Transaction
    fun mergeAssetTags(assetId: String, tags: List<String>): Boolean {
        val existing = assetTagsForAsset(assetId)
        val existingKeys = existing.mapTo(mutableSetOf()) { it.tagId }
        var tagCount = existing.size
        TagNormalizer.normalizeAll(tags).forEach { tag ->
            insertTag(TagEntity(id = tag.key, name = tag.displayName))
            val attribution = AssetTagCrossRef(
                assetId = assetId,
                tagId = tag.key,
                source = TagSource.MANUAL.name,
                updatedAt = System.currentTimeMillis(),
            )
            if (tag.key in existingKeys) {
                // A user-entered duplicate is an explicit confirmation of an AI/system tag.
                upsertAssetTagPreservingManual(attribution)
            } else if (tagCount < TagNormalizer.MAX_TAGS_PER_ASSET) {
                upsertAssetTagPreservingManual(attribution)
                existingKeys += tag.key
                tagCount += 1
            }
        }
        return true
    }

    /**
     * Room v1 stored tag display values without a canonical key. Merge those values once after
     * the schema migration so old "Dog", " dog " and "#DOG" relations become one tag while
     * retaining the original relationship attribution.
     */
    @Transaction
    fun normalizeLegacyTags() {
        data class LegacyTagSnapshot(
            val entity: TagEntity,
            val normalized: NormalizedTag?,
            val relations: List<AssetTagCrossRef>,
        )

        val snapshots = allTags().map { tag ->
            LegacyTagSnapshot(
                entity = tag,
                normalized = TagNormalizer.normalize(tag.name),
                relations = assetTagsForTag(tag.id),
            )
        }
        if (snapshots.isEmpty()) return

        // Rebuild the small tag graph from an in-transaction snapshot. Updating one legacy row at
        // a time is unsafe because a legacy id may equal another group's canonical key and the
        // unique name index may still be occupied by a differently-cased duplicate.
        snapshots.forEach { snapshot ->
            snapshot.relations.forEach { relation ->
                deleteAssetTag(relation.assetId, relation.tagId)
            }
        }
        snapshots.forEach { deleteTag(it.entity.id) }

        snapshots
            .filter { it.normalized != null }
            .groupBy { requireNotNull(it.normalized).key }
            .forEach { (canonicalId, group) ->
                val displayName = requireNotNull(group.first().normalized).displayName
                insertTag(TagEntity(id = canonicalId, name = displayName))
                group.flatMap { it.relations }
                    .groupBy { it.assetId }
                    .forEach { (_, candidates) ->
                        val preferred = candidates.maxWithOrNull(
                            compareBy<AssetTagCrossRef> { it.source == TagSource.MANUAL.name }
                                .thenBy { it.updatedAt },
                        ) ?: return@forEach
                        insertAssetTag(preferred.copy(tagId = canonicalId))
                    }
            }
    }

    @Transaction
    fun deleteAssetPreservingDerived(id: String): Int {
        unlinkDerivedAssets(id)
        return deleteAsset(id)
    }

    private fun replaceAssetTagsInternal(assetId: String, tags: List<String>, source: String) {
        val now = System.currentTimeMillis()
        TagNormalizer.normalizeAll(tags).forEach { tag ->
            insertTag(TagEntity(id = tag.key, name = tag.displayName))
            upsertAssetTagPreservingManual(
                AssetTagCrossRef(
                    assetId = assetId,
                    tagId = tag.key,
                    source = source,
                    updatedAt = now,
                ),
            )
        }
    }
}

@Database(
    entities = [
        AssetEntity::class,
        AssetVariantEntity::class,
        TagEntity::class,
        AssetTagCrossRef::class,
        StickerRecipeEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class MemeVaultDatabase : RoomDatabase() {
    abstract fun assetDao(): AssetDao
}

class AssetDatabase(context: Context) : AutoCloseable {
    private val database = Room.databaseBuilder(
        context.applicationContext,
        MemeVaultDatabase::class.java,
        "memevault.db",
    )
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .build()
    private val dao = database.assetDao()
    @Volatile
    private var legacyTagsNormalized = false
    private val tagNormalizationLock = Any()

    fun insert(asset: Asset): Boolean = runCatching {
        ensureTagsCanonical()
        dao.insertAssetWithTags(asset.toEntity(), asset.tags)
        true
    }.getOrDefault(false)

    fun insertSticker(asset: Asset, recipe: StickerRecipe): Boolean = runCatching {
        ensureTagsCanonical()
        dao.insertStickerWithTags(asset.toEntity(), recipe.toEntity(), asset.tags)
        true
    }.getOrDefault(false)

    fun findByHash(hash: String): Asset? = findByDedupKey(hash)

    fun findByDedupKey(dedupKey: String): Asset? {
        ensureTagsCanonical()
        val entity = dao.findByDedupKey(dedupKey) ?: return null
        return entity.toDomain(dao.tagsForAsset(entity.id))
    }

    /**
     * Legacy sticker imports used the raw content hash as their dedup key. Keep this lookup
     * scoped to STICKER so a sticker and a regular meme with identical bytes remain distinct.
     */
    fun findByContentHashAndType(hash: String, type: AssetType): Asset? {
        ensureTagsCanonical()
        val entity = dao.findByContentHashAndType(hash, type.name) ?: return null
        return entity.toDomain(dao.tagsForAsset(entity.id))
    }

    fun findById(id: String): Asset? {
        ensureTagsCanonical()
        return dao.findById(id)?.toDomain(dao.tagsForAsset(id))
    }

    fun list(filter: AssetFilter = AssetFilter.ALL, search: String = ""): List<Asset> {
        ensureTagsCanonical()
        val favoriteOnly = filter == AssetFilter.FAVORITES
        val type = when (filter) {
            AssetFilter.GIFS -> AssetType.GIF.name
            AssetFilter.STICKERS -> AssetType.STICKER.name
            AssetFilter.MEMES -> AssetType.MEME.name
            else -> null
        }
        val normalizedSearch = search.trim()
        val escapedSearch = SqlLikeEscaper.escape(normalizedSearch)
        val escapedTagSearch = SqlLikeEscaper.escape(canonicalTagSearch(normalizedSearch))
        val assets = dao.listAssets(favoriteOnly, type, escapedSearch, escapedTagSearch).map { entity ->
            entity.toDomain(dao.tagsForAsset(entity.id))
        }
        return if (filter == AssetFilter.ANIMATED) {
            assets.filter(Asset::isAnimatedMedia)
        } else {
            assets
        }
    }

    fun setFavorite(id: String, favorite: Boolean): Boolean {
        ensureTagsCanonical()
        return dao.setFavorite(id, favorite) == 1
    }

    fun touchUsed(id: String) {
        ensureTagsCanonical()
        dao.touchUsed(id, System.currentTimeMillis())
    }

    fun count(): Int {
        ensureTagsCanonical()
        return dao.countAssets()
    }

    fun countDerivedAssets(id: String): Int {
        ensureTagsCanonical()
        return dao.countDerivedAssets(id)
    }

    fun delete(id: String): AssetDeletion? {
        ensureTagsCanonical()
        val entity = dao.findById(id) ?: return null
        val asset = entity.toDomain(dao.tagsForAsset(id))
        val variants = dao.variantsForAsset(id).map { it.toStoredVariant() }
        val recipe = dao.findStickerRecipe(id)?.toDomain(entity.sourceAssetId)
        if (dao.deleteAssetPreservingDerived(id) != 1) return null
        return AssetDeletion(asset, variants, recipe)
    }

    fun updateTags(id: String, tags: List<String>): Asset? = runCatching {
        ensureTagsCanonical()
        dao.replaceAssetTags(id, tags, TagSource.MANUAL.name)
        findById(id)
    }.getOrNull()

    fun mergeTags(id: String, tags: List<String>): Asset? = runCatching {
        ensureTagsCanonical()
        dao.mergeAssetTags(id, tags)
        findById(id)
    }.getOrNull()

    fun suggestTags(prefix: String, limit: Int = 12): List<TagSuggestion> {
        ensureTagsCanonical()
        val normalizedPrefix = canonicalTagSearch(prefix.trim())
        return dao.suggestTags(SqlLikeEscaper.escape(normalizedPrefix), limit.coerceIn(1, 50)).map {
            TagSuggestion(it.name, it.usageCount)
        }
    }

    fun findStickerRecipe(id: String): StickerRecipe? {
        ensureTagsCanonical()
        val sourceId = dao.findById(id)?.sourceAssetId
        return dao.findStickerRecipe(id)?.toDomain(sourceId)
    }

    fun saveVariant(
        assetId: String,
        profile: String,
        path: String,
        mimeType: String,
        fileSize: Long,
    ) {
        dao.upsertVariant(
            AssetVariantEntity(
                assetId = assetId,
                profile = profile,
                path = path,
                mimeType = mimeType,
                fileSize = fileSize,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    fun findVariant(assetId: String, profile: String): StoredVariant? =
        dao.findVariant(assetId, profile)?.toStoredVariant()

    fun variantsForAsset(assetId: String): List<StoredVariant> =
        dao.variantsForAsset(assetId).map { it.toStoredVariant() }

    fun referencedOriginalPaths(): List<String> = dao.allOriginalPaths()

    fun referencedVariantPaths(): List<String> = dao.allVariantPaths()

    fun referencedMaskPaths(): List<String> = dao.allMaskPaths()

    /** Remove rows whose original was lost outside the app; derived stickers are unlinked first. */
    fun removeMissingOriginalRows(managedOriginalsRoot: String): Int {
        val root = runCatching { File(managedOriginalsRoot).canonicalPath }.getOrDefault(managedOriginalsRoot)
        val missing = dao.allAssets()
            .filterNot {
                runCatching {
                    val path = File(it.originalPath).canonicalPath
                    path.startsWith(root + File.separator) && File(path).isFile
                }.getOrDefault(false)
            }
            .map { it.id }
        missing.forEach { dao.deleteAssetPreservingDerived(it) }
        return missing.size
    }

    override fun close() {
        database.close()
    }

    private fun ensureTagsCanonical() {
        if (legacyTagsNormalized) return
        synchronized(tagNormalizationLock) {
            if (legacyTagsNormalized) return
            dao.normalizeLegacyTags()
            legacyTagsNormalized = true
        }
    }

    private fun canonicalTagSearch(value: String): String =
        TagNormalizer.normalize(value)?.key ?: value.trim().lowercase(Locale.ROOT)

    private fun Asset.toEntity() = AssetEntity(
        id = id,
        originalPath = originalPath,
        mimeType = mimeType,
        displayName = displayName,
        type = type.name,
        hash = hash,
        dedupKey = dedupKey,
        width = width,
        height = height,
        durationMs = durationMs,
        favorite = favorite,
        createdAt = createdAt,
        lastUsedAt = lastUsedAt,
        useCount = useCount,
        sourceAssetId = sourceAssetId,
    )

    private fun StickerRecipe.toEntity() = StickerRecipeEntity(
        stickerAssetId = stickerAssetId,
        rendererId = rendererId,
        rendererVersion = rendererVersion,
        exportPolicyVersion = exportPolicyVersion,
        sourceHash = sourceHash,
        maskRelativePath = maskRelativePath,
        maskFormat = maskFormat,
        maskWidth = maskWidth,
        maskHeight = maskHeight,
        maskHash = maskHash,
        workingWidth = workingWidth,
        workingHeight = workingHeight,
        outputWidth = outputWidth,
        outputHeight = outputHeight,
        threshold = threshold,
        paddingFraction = paddingFraction,
        borderColorArgb = borderColorArgb,
        borderThicknessPx = borderThicknessPx,
        cropLeft = cropRect?.left ?: 0,
        cropTop = cropRect?.top ?: 0,
        cropRight = cropRect?.right ?: 0,
        cropBottom = cropRect?.bottom ?: 0,
        cropSourceWidth = cropSourceWidth ?: 0,
        cropSourceHeight = cropSourceHeight ?: 0,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun AssetEntity.toDomain(tags: List<String> = emptyList()) = Asset(
        id = id,
        originalPath = originalPath,
        mimeType = mimeType,
        displayName = displayName,
        type = runCatching { AssetType.valueOf(type) }.getOrDefault(AssetType.UNKNOWN),
        hash = hash,
        width = width,
        height = height,
        durationMs = durationMs,
        favorite = favorite,
        createdAt = createdAt,
        lastUsedAt = lastUsedAt,
        useCount = useCount,
        tags = tags,
        sourceAssetId = sourceAssetId,
        dedupKey = dedupKey,
    )

    private fun StickerRecipeEntity.toDomain(sourceAssetId: String?): StickerRecipe = StickerRecipe(
        stickerAssetId = stickerAssetId,
        sourceAssetId = sourceAssetId,
        rendererId = rendererId,
        rendererVersion = rendererVersion,
        exportPolicyVersion = exportPolicyVersion,
        sourceHash = sourceHash,
        maskRelativePath = maskRelativePath,
        maskFormat = maskFormat,
        maskWidth = maskWidth,
        maskHeight = maskHeight,
        maskHash = maskHash,
        workingWidth = workingWidth,
        workingHeight = workingHeight,
        outputWidth = outputWidth,
        outputHeight = outputHeight,
        threshold = threshold,
        paddingFraction = paddingFraction,
        borderColorArgb = borderColorArgb,
        borderThicknessPx = borderThicknessPx,
        cropRect = CropRect(cropLeft, cropTop, cropRight, cropBottom)
            .takeUnless { it.left == 0 && it.top == 0 && it.right == 0 && it.bottom == 0 },
        cropSourceWidth = cropSourceWidth.takeIf { it > 0 },
        cropSourceHeight = cropSourceHeight.takeIf { it > 0 },
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun AssetVariantEntity.toStoredVariant() = StoredVariant(
        assetId = assetId,
        profile = profile,
        path = path,
        mimeType = mimeType,
        fileSize = fileSize,
    )

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE assets ADD COLUMN source_asset_id TEXT REFERENCES assets(id) ON DELETE SET NULL",
                )
                db.execSQL("ALTER TABLE assets ADD COLUMN dedup_key TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE assets SET dedup_key = content_hash WHERE dedup_key = ''")
                db.execSQL("DROP INDEX IF EXISTS index_assets_content_hash")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_assets_content_hash ON assets(content_hash)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_assets_source_asset_id ON assets(source_asset_id)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_assets_dedup_key ON assets(dedup_key)")

                db.execSQL("ALTER TABLE asset_tags ADD COLUMN source TEXT NOT NULL DEFAULT 'MANUAL'")
                db.execSQL("ALTER TABLE asset_tags ADD COLUMN confidence REAL")
                db.execSQL("ALTER TABLE asset_tags ADD COLUMN model_version TEXT")
                db.execSQL("ALTER TABLE asset_tags ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE asset_tags SET updated_at = CAST(strftime('%s','now') AS INTEGER) * 1000 WHERE updated_at = 0",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sticker_recipes (
                        sticker_asset_id TEXT NOT NULL,
                        renderer_id TEXT NOT NULL,
                        renderer_version TEXT NOT NULL,
                        export_policy_version TEXT NOT NULL,
                        source_hash TEXT NOT NULL,
                        mask_relative_path TEXT,
                        mask_format TEXT NOT NULL,
                        mask_width INTEGER NOT NULL,
                        mask_height INTEGER NOT NULL,
                        mask_hash TEXT,
                        working_width INTEGER NOT NULL,
                        working_height INTEGER NOT NULL,
                        output_width INTEGER NOT NULL,
                        output_height INTEGER NOT NULL,
                        threshold REAL NOT NULL,
                        padding_fraction REAL NOT NULL,
                        border_color_argb INTEGER,
                        border_thickness_px INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(sticker_asset_id),
                        FOREIGN KEY(sticker_asset_id) REFERENCES assets(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_sticker_recipes_sticker_asset_id ON sticker_recipes(sticker_asset_id)",
                )
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sticker_recipes ADD COLUMN crop_left INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sticker_recipes ADD COLUMN crop_top INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sticker_recipes ADD COLUMN crop_right INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sticker_recipes ADD COLUMN crop_bottom INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sticker_recipes ADD COLUMN crop_source_width INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sticker_recipes ADD COLUMN crop_source_height INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
