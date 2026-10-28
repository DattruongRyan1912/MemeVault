package com.memevault.app

import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * Full-screen asset detail rendered as a layer inside [MainActivity]. It reuses the activity's
 * repository and executors (no second Room instance) and persists edits immediately:
 * rename on Done/focus loss, tags on every add/remove, favorite on tap.
 */
internal class AssetDetailPanel(
    private val activity: MainActivity,
    private val host: FrameLayout,
    initial: Asset,
) {
    var asset: Asset = initial
        private set

    private val context = activity
    private val root = FrameLayout(context).apply {
        setBackgroundColor(context.mvBackground)
        elevation = context.dp(MV_ELEVATION_DETAIL).toFloat()
        isClickable = true
        isFocusableInTouchMode = true
    }
    private val title = TextView(context)
    private val favoriteButton = context.mvIconButton(R.drawable.ic_star_border, "", { toggleFavorite() })
    private val preview = ImageView(context)
    private val nameInput = EditText(context)
    private val extensionLabel = TextView(context)
    private val tagRow = FlowLayout(context)
    private val tagInput = EditText(context)
    private val suggestionRow = FlowLayout(context)
    private val suggestionLabel = context.mvSectionLabel("GỢI Ý")
    private val sizeValue = TextView(context)
    private val sourceValue = TextView(context)
    private val sourceRow = LinearLayout(context)
    private var studioButton: View? = null
    private val archiveBanner = LinearLayout(context)
    private val infoValues = linkedMapOf<String, TextView>()
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.forLanguageTag("vi"))
    private var stickerSourceMissing = false

    val isShowing: Boolean get() = root.parent != null

    fun show() {
        build()
        host.addView(root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(120).start()
        render()
        loadPreview()
        loadAsyncInfo()
    }

    fun close() {
        if (!isShowing) return
        commitRename()
        hideKeyboard()
        MediaPreviewLoader.stopIfAnimated(preview.drawable)
        preview.tag = null
        host.removeView(root)
    }

    /** Called by the activity after a list refresh so the panel never shows stale data. */
    fun update(next: Asset) {
        if (next.id != asset.id) return
        asset = next
        render()
    }

    private fun build() {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(4), 0, context.dp(4), 0)
        }
        topBar.addView(context.mvIconButton(R.drawable.ic_arrow_back, context.getString(R.string.back), {
            activity.closeDetail()
        }))
        title.apply {
            applyMvTextStyle(16f, context.mvText, 1)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(context.dp(4), 0, context.dp(8), 0)
        }
        topBar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(favoriteButton)
        val more = context.mvIconButton(R.drawable.ic_more_vert, "Thêm thao tác", {})
        more.setOnClickListener { showMoreMenu(it) }
        topBar.addView(more)
        column.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(56)))
        column.addView(context.mvDivider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(1)))

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
        }
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(32))
        }
        scroll.addView(content)

        val metrics = context.resources.displayMetrics
        val previewHeight = min(metrics.widthPixels - context.dp(32), (metrics.heightPixels * 0.42f).toInt())
        preview.apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = if (asset.type == AssetType.STICKER) {
                CheckerboardDrawable(context.dp(12), context)
            } else {
                context.roundedBackground(context.mvSurfaceAlt, MV_RADIUS, context.mvLine)
            }
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            contentDescription = asset.displayName
        }
        content.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, previewHeight))

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(context.mvButton(context.getString(R.string.share), { activity.shareAsset(asset) },
            primary = true, iconRes = R.drawable.ic_share), LinearLayout.LayoutParams(0, context.dp(MV_TOUCH), 1f))
        actions.addView(context.mvButton(context.getString(R.string.copy), { activity.copyAsset(asset) },
            iconRes = R.drawable.ic_copy), LinearLayout.LayoutParams(0, context.dp(MV_TOUCH), 1f).apply {
            marginStart = context.dp(8)
        })
        // Background removal only applies to still images; GIF/video rows keep two wide actions.
        if (asset.type == AssetType.STICKER || asset.type == AssetType.MEME) {
            actions.addView(context.mvButton(
                if (asset.type == AssetType.STICKER) "Chỉnh sửa" else "Tách nền",
                { activity.openStickerStudio(asset) },
                iconRes = R.drawable.ic_cut,
            ).apply {
                maxLines = 1
                contentDescription = context.getString(
                    if (asset.type == AssetType.STICKER) R.string.edit_sticker_short else R.string.create_sticker_short,
                )
                studioButton = this
            }, LinearLayout.LayoutParams(0, context.dp(MV_TOUCH), 1f).apply { marginStart = context.dp(8) })
        }
        for (index in 0 until actions.childCount) {
            (actions.getChildAt(index) as? TextView)?.apply {
                // maxLines (not singleLine): auto-size is ignored on single-line TextViews.
                maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            }
        }
        content.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(12)
        })

        // Archived sources are only reachable from the "Ảnh nguồn" chip; say why and offer the way back.
        archiveBanner.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = context.roundedBackground(context.mvWarningSurface, MV_RADIUS, context.mvLine)
            setPadding(context.dp(12), context.dp(6), context.dp(6), context.dp(6))
            visibility = View.GONE
            addView(TextView(context).apply {
                text = context.getString(R.string.archived_banner)
                applyMvTextStyle(13f, context.mvWarningInk, 3)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(context.mvButton(context.getString(R.string.restore_from_sources), {
                activity.setArchived(asset, archived = false)
            }), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(MV_TOUCH)).apply {
                marginStart = context.dp(8)
            })
        }
        content.addView(archiveBanner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = context.dp(12) })

        content.addView(context.mvSectionLabel("TÊN"), sectionLabelParams())
        val nameRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = context.roundedBackground(context.mvSurface, MV_RADIUS, context.mvLine)
            setPadding(context.dp(12), 0, context.dp(12), 0)
        }
        nameInput.apply {
            setSingleLine(true)
            textSize = 15f
            setTextColor(context.mvText)
            background = null
            setPadding(0, 0, 0, 0)
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            contentDescription = "Tên media"
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_DONE || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    commitRename()
                    hideKeyboard()
                    clearFocus()
                    true
                } else {
                    false
                }
            }
            setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitRename() }
        }
        nameRow.addView(nameInput, LinearLayout.LayoutParams(0, context.dp(MV_TOUCH), 1f))
        extensionLabel.applyMvMonoStyle(13f)
        nameRow.addView(extensionLabel)
        content.addView(nameRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(MV_TOUCH)))

        content.addView(context.mvSectionLabel("TAG"), sectionLabelParams())
        content.addView(tagRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        tagInput.apply {
            setSingleLine(true)
            hint = "Thêm tag rồi nhấn Xong"
            textSize = 14f
            setTextColor(context.mvText)
            setHintTextColor(context.mvMuted)
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background = context.roundedBackground(context.mvSurface, MV_RADIUS, context.mvLine)
            setPadding(context.dp(12), 0, context.dp(12), 0)
            contentDescription = context.getString(R.string.asset_tags_content_description)
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_DONE || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    addTagsFromInput()
                    true
                } else {
                    false
                }
            }
        }
        content.addView(tagInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(MV_TOUCH)).apply {
            topMargin = context.dp(4)
        })
        content.addView(suggestionLabel, sectionLabelParams(top = 12))
        content.addView(suggestionRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        content.addView(context.mvSectionLabel("THÔNG TIN"), sectionLabelParams())
        val info = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.roundedBackground(context.mvSurface, MV_RADIUS, context.mvLine)
        }
        INFO_KEYS.forEachIndexed { index, key ->
            if (index > 0) info.addView(context.mvDivider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(1)))
            val value = if (key == KEY_SIZE) sizeValue else TextView(context)
            info.addView(infoRow(key, value), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(40)))
            infoValues[key] = value
        }
        sourceRow.apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(context.mvDivider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(1)))
            addView(infoRow("Nguồn", sourceValue), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(40)))
        }
        info.addView(sourceRow)
        content.addView(info)

        content.addView(context.mvButton("Xoá khỏi thư viện", { activity.requestDelete(listOf(asset)) },
            iconRes = R.drawable.ic_delete, danger = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(MV_TOUCH)).apply {
                topMargin = context.dp(24)
            })
    }

    private fun sectionLabelParams(top: Int = 20) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = context.dp(top)
        bottomMargin = context.dp(6)
    }

    private fun infoRow(label: String, value: TextView): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(12), 0, context.dp(12), 0)
        addView(TextView(context).apply {
            text = label
            applyMvTextStyle(13f, context.mvMuted, 1)
        }, LinearLayout.LayoutParams(context.dp(116), ViewGroup.LayoutParams.WRAP_CONTENT))
        value.applyMvMonoStyle(13f, context.mvText)
        value.gravity = Gravity.END
        addView(value, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun render() {
        title.text = asset.displayName
        favoriteButton.setMvIcon(
            if (asset.favorite) R.drawable.ic_star else R.drawable.ic_star_border,
            if (asset.favorite) context.mvFavorite else context.mvText,
        )
        favoriteButton.contentDescription = context.getString(
            if (asset.favorite) R.string.remove_favorite else R.string.add_favorite,
        )
        archiveBanner.visibility = if (asset.archived) View.VISIBLE else View.GONE
        if (!nameInput.hasFocus()) nameInput.setText(DisplayNamePolicy.baseName(asset.displayName))
        extensionLabel.text = DisplayNamePolicy.extensionOf(asset.displayName)
        renderTags()
        infoValues["Loại"]?.text = asset.type.label
        infoValues["Định dạng"]?.text = MediaPolicy.normalizeMime(asset.mimeType)
        infoValues["Kích thước"]?.text = if (asset.width > 0 && asset.height > 0) "${asset.width} × ${asset.height} px" else "—"
        infoValues["Thời lượng"]?.text = if (asset.durationMs > 0) LibraryGridAdapter.formatDuration(asset.durationMs) else "—"
        infoValues["Ngày thêm"]?.text = dateFormat.format(Date(asset.createdAt))
        infoValues["Dùng gần nhất"]?.text = asset.lastUsedAt?.let { dateFormat.format(Date(it)) } ?: "Chưa dùng"
        infoValues["Số lần dùng"]?.text = asset.useCount.toString()
    }

    private fun renderTags() {
        tagRow.removeAllViews()
        if (asset.tags.isEmpty()) {
            tagRow.addView(TextView(context).apply {
                text = "Chưa có tag"
                applyMvTextStyle(13f, context.mvMuted, 1)
                gravity = Gravity.CENTER_VERTICAL
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(MV_TOUCH)))
        }
        asset.tags.forEach { tag ->
            tagRow.addView(context.mvTagChip(tag) { saveTags(asset.tags.filterNot { it == tag }) },
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(MV_TOUCH)))
        }
        val currentKeys = asset.tags.mapNotNull(LibraryView::tagKey).toSet()
        val suggestions = activity.topTags(SUGGESTION_LIMIT + currentKeys.size)
            .filter { it.key !in currentKeys }
            .take(SUGGESTION_LIMIT)
        suggestionRow.removeAllViews()
        suggestionLabel.visibility = if (suggestions.isEmpty()) View.GONE else View.VISIBLE
        suggestions.forEach { suggestion ->
            val chip = context.mvChip(suggestion.label) { saveTags(asset.tags + suggestion.label) }
            chip.styleMvChip(false)
            chip.contentDescription = "Thêm tag ${suggestion.label}"
            val plus = context.getDrawable(R.drawable.ic_add)?.mutate()?.apply {
                setTint(context.mvMuted)
                setBounds(0, 0, context.dp(14), context.dp(14))
            }
            chip.setCompoundDrawablesRelative(plus, null, null, null)
            chip.compoundDrawablePadding = context.dp(4)
            suggestionRow.addView(chip, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(MV_TOUCH)))
        }
    }

    private fun addTagsFromInput() {
        val raw = tagInput.text?.toString().orEmpty()
        val added = TagNormalizer.normalizeAll(raw.split(','))
        if (added.isEmpty()) return
        tagInput.setText("")
        saveTags(asset.tags + added.map { it.displayName })
    }

    private fun saveTags(next: List<String>) {
        val id = asset.id
        activity.runDb {
            val updated = activity.repository.updateTags(id, next)
            activity.runOnUi {
                if (updated == null) {
                    activity.showMessage(context.getString(R.string.asset_tags_save_failed))
                } else {
                    asset = updated
                    render()
                    activity.onAssetChanged(updated)
                }
            }
        }
    }

    private fun commitRename() {
        val requested = nameInput.text?.toString().orEmpty()
        if (requested == DisplayNamePolicy.baseName(asset.displayName)) return
        val id = asset.id
        activity.runDb {
            val updated = activity.repository.rename(id, requested)
            activity.runOnUi {
                if (updated == null) {
                    nameInput.setText(DisplayNamePolicy.baseName(asset.displayName))
                    activity.showMessage("Tên không hợp lệ, đã giữ tên cũ.")
                } else {
                    asset = updated
                    render()
                    activity.onAssetChanged(updated)
                    activity.showMessage("Đã đổi tên thành ${updated.displayName}")
                }
            }
        }
    }

    private fun toggleFavorite() {
        val id = asset.id
        val next = !asset.favorite
        activity.runDb {
            val updated = activity.repository.setFavorite(id, next)
            activity.runOnUi {
                if (updated != null) {
                    asset = updated
                    render()
                    activity.onAssetChanged(updated)
                }
            }
        }
    }

    private fun showMoreMenu(anchor: View) {
        val menu = PopupMenu(context, anchor)
        menu.menu.add(0, MENU_RENAME, 0, "Đổi tên")
        val export = menu.menu.addSubMenu(0, MENU_EXPORT, 1, "Xuất nâng cao (thử nghiệm)")
        if (asset.type == AssetType.GIF) export.add(0, MENU_ANIMATED_STICKER, 0, context.getString(R.string.share_animated_sticker))
        export.add(0, MENU_TIKTOK, 1, "Gửi tới TikTok")
        export.add(0, MENU_ZALO, 2, "Gửi tới Zalo")
        if (asset.archived) {
            menu.menu.add(0, MENU_ARCHIVE, 2, context.getString(R.string.restore_from_sources))
        } else if (asset.type == AssetType.MEME) {
            menu.menu.add(0, MENU_ARCHIVE, 2, context.getString(R.string.archive_to_sources))
        }
        menu.menu.add(0, MENU_DELETE, 3, "Xoá khỏi thư viện")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_RENAME -> {
                    nameInput.requestFocus()
                    nameInput.selectAll()
                    context.getSystemService(InputMethodManager::class.java)
                        ?.showSoftInput(nameInput, InputMethodManager.SHOW_IMPLICIT)
                }
                MENU_ANIMATED_STICKER -> activity.shareAnimatedSticker(asset)
                MENU_TIKTOK -> activity.shareToHost(asset, Destination.TIKTOK)
                MENU_ZALO -> activity.shareToHost(asset, Destination.ZALO)
                MENU_ARCHIVE -> activity.setArchived(asset, archived = !asset.archived)
                MENU_DELETE -> activity.requestDelete(listOf(asset))
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        menu.show()
    }

    private fun loadPreview() {
        val target = context.dp(420)
        val file = File(asset.originalPath)
        val id = asset.id
        preview.tag = id
        activity.runPreview {
            val animated = MediaPreviewLoader.decodeAnimatedDrawable(file, asset.mimeType, target)
            val bitmap = if (animated == null) MediaPreviewLoader.decode(file, asset.mimeType, target) else null
            activity.runOnUi {
                if (preview.tag != id) return@runOnUi
                if (animated != null) {
                    preview.setImageDrawable(animated)
                    MediaPreviewLoader.startIfAnimated(animated)
                } else if (bitmap != null) {
                    preview.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun loadAsyncInfo() {
        sizeValue.text = "…"
        val current = asset
        activity.runDb {
            val bytes = activity.repository.originalSize(current)
            val recipeWithoutSource = current.type == AssetType.STICKER && current.sourceAssetId == null &&
                activity.repository.findStickerRecipe(current.id) != null
            activity.runOnUi {
                sizeValue.text = if (bytes > 0) ByteFormat.format(bytes) else "—"
                when {
                    current.sourceAssetId != null -> {
                        sourceRow.visibility = View.VISIBLE
                        sourceValue.text = "Tạo từ ảnh trong kho"
                    }
                    recipeWithoutSource -> {
                        stickerSourceMissing = true
                        studioButton?.isEnabled = false
                        sourceRow.visibility = View.VISIBLE
                        sourceValue.text = "Ảnh gốc đã xoá"
                        sourceValue.setTextColor(context.mvWarningInk)
                    }
                }
            }
        }
    }

    private fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(root.windowToken, 0)
    }

    companion object {
        private const val KEY_SIZE = "Dung lượng"
        private val INFO_KEYS = listOf("Loại", "Định dạng", "Kích thước", KEY_SIZE, "Thời lượng", "Ngày thêm", "Dùng gần nhất", "Số lần dùng")
        private const val SUGGESTION_LIMIT = 8
        private const val MENU_RENAME = 1
        private const val MENU_EXPORT = 2
        private const val MENU_ANIMATED_STICKER = 3
        private const val MENU_TIKTOK = 4
        private const val MENU_ZALO = 5
        private const val MENU_DELETE = 6
        private const val MENU_ARCHIVE = 7
    }
}
