package com.memevault.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.Window
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var repository: AssetRepository
    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var emptyTitle: TextView
    private lateinit var emptyMessage: TextView
    private lateinit var emptyAction: android.widget.Button
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var filterRow: LinearLayout
    private lateinit var sortButton: TextView
    private lateinit var adapter: AssetAdapter

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val shareExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val previewExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    private val previewHandler = Handler(Looper.getMainLooper())
    private val searchHandler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null
    private var filter = AssetFilter.ALL
    private var sort = SortOrder.RECENT
    private var refreshGeneration = 0L
    @Volatile
    private var destroyed = false
    private val filterChips = linkedMapOf<AssetFilter, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = AssetRepository(this)
        filter = savedInstanceState?.getString(KEY_FILTER)
            ?.let { runCatching { AssetFilter.valueOf(it) }.getOrNull() }
            ?: AssetFilter.ALL
        sort = savedInstanceState?.getString(KEY_SORT)
            ?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() }
            ?: SortOrder.RECENT
        configureSystemBars()
        buildUi()
        savedInstanceState?.getString(KEY_QUERY)?.let { search.setText(it) }
        handleIncomingIntent(intent)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (!destroyed && ::recycler.isInitialized) refresh()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (destroyed) return
        setIntent(intent)
        handleIncomingIntent(intent)
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_QUERY, search.text?.toString().orEmpty())
        outState.putString(KEY_FILTER, filter.name)
        outState.putString(KEY_SORT, sort.name)
        super.onSaveInstanceState(outState)
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(mvPaper) }
        applySystemBarInsets(root)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        val appBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        appBar.addView(TextView(this).apply {
            text = "MV"
            gravity = Gravity.CENTER
            textSize = 12f
            includeFontPadding = false
            setTextColor(mvInk)
            rotation = -4f
            background = roundedBackground(mvOrange, radiusDp = 13)
        }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(10) })
        val titleColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleColumn.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            applyMvTextStyle(24f, Color.rgb(23, 32, 51), 1)
        })
        titleColumn.addView(TextView(this).apply {
            text = getString(R.string.app_subtitle_short)
            applyMvTextStyle(12f, Color.rgb(91, 101, 124), 1)
        })
        appBar.addView(titleColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        appBar.addView(mvIconButton("⌨", getString(R.string.open_keyboard_settings)) {
            openKeyboardSettings()
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) })
        appBar.addView(mvIconButton("＋", getString(R.string.import_media)) {
            showImportOptions()
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) })
        appBar.addView(mvIconButton("⋮", getString(R.string.library_more_actions)) {
            showHelpDialog()
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) })
        content.addView(appBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(56),
        ))

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(6))
        }
        hero.addView(TextView(this).apply {
            text = getString(R.string.library_hero_title)
            applyMvTextStyle(22f, Color.rgb(23, 32, 51), 2)
        })
        hero.addView(TextView(this).apply {
            text = getString(R.string.library_hero_subtitle)
            applyMvTextStyle(12f, Color.rgb(91, 101, 124), 2)
            setPadding(0, dp(4), 0, 0)
        })
        content.addView(hero, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        search = EditText(this).apply {
            hint = getString(R.string.search_hint)
            setSingleLine(true)
            textSize = 15f
            setTextColor(mvInk)
            setHintTextColor(mvMuted)
            setPadding(dp(14), 0, dp(14), 0)
            background = roundedBackground(mvSurface, radiusDp = 18, strokeColor = mvLine)
            contentDescription = getString(R.string.search_content_description)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) { scheduleRefresh() }
            })
        }
        content.addView(search, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(50),
        ).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })

        val filterScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addFilterChip("Tất cả", AssetFilter.ALL)
        addFilterChip("Yêu thích", AssetFilter.FAVORITES)
        addFilterChip("Meme", AssetFilter.MEMES)
        addFilterChip("Sticker", AssetFilter.STICKERS)
        addFilterChip("Động", AssetFilter.ANIMATED)
        addFilterChip("GIF", AssetFilter.GIFS)
        filterScroll.addView(filterRow, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(48),
        ))
        content.addView(filterScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48),
        ))

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        status = TextView(this).apply {
            applyMvTextStyle(12f, Color.rgb(91, 101, 124), 1)
            setPadding(dp(2), dp(6), 0, dp(6))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        statusRow.addView(status, LinearLayout.LayoutParams(0, dp(48), 1f))
        sortButton = TextView(this).apply {
            text = sort.label
            gravity = Gravity.CENTER
            applyMvTextStyle(13f, Color.rgb(35, 48, 75), 1)
            background = roundedBackground(mvSurface, radiusDp = 15, strokeColor = mvLine)
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.sort_content_description)
            setOnClickListener { showSortDialog() }
        }
        statusRow.addView(sortButton, LinearLayout.LayoutParams(dp(112), dp(48)).apply {
            marginStart = dp(8)
        })
        content.addView(statusRow)

        emptyState = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = roundedBackground(mvSurface, radiusDp = 22, strokeColor = mvLine)
            visibility = View.GONE
        }
        emptyState.addView(TextView(this).apply {
            text = "✦"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(mvOrange)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
        emptyTitle = TextView(this).apply {
            applyMvTextStyle(17f, Color.rgb(23, 32, 51), 2)
            gravity = Gravity.CENTER
        }
        emptyState.addView(emptyTitle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) })
        emptyMessage = TextView(this).apply {
            applyMvTextStyle(13f, Color.rgb(91, 101, 124), 4)
            gravity = Gravity.CENTER
        }
        emptyState.addView(emptyMessage, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(4)
            bottomMargin = dp(10)
        })
        emptyAction = mvButton(getString(R.string.import_media), { showImportOptions() }, primary = true)
        emptyState.addView(emptyAction, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(48),
        ))
        content.addView(emptyState, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(8) })

        adapter = AssetAdapter(
            context = this,
            previewExecutor = previewExecutor,
            previewHandler = previewHandler,
            onOpen = ::showAssetDetail,
            onFavorite = ::toggleFavorite,
            onMore = ::showAssetActions,
            onCreateSticker = ::openStickerStudio,
        )
        recycler = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, COLUMN_COUNT)
            adapter = this@MainActivity.adapter
            clipToPadding = false
            // Leave the last cards reachable above both persistent quick actions.
            setPadding(dp(0), dp(0), dp(0), dp(132))
            itemAnimator = null
            contentDescription = getString(R.string.library_content_description)
        }
        content.addView(recycler, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        val createSticker = mvButton(
            getString(R.string.create_sticker_action),
            { openAssetPicker() },
            primary = true,
        ).apply {
            elevation = dp(5).toFloat()
            contentDescription = getString(R.string.create_sticker_action)
        }
        root.addView(createSticker, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(52),
            Gravity.BOTTOM or Gravity.END,
        ).apply {
            marginEnd = dp(16)
            bottomMargin = dp(16)
        })
        setContentView(root)
        updateFilterChipStyles()
    }

    private fun addFilterChip(label: String, value: AssetFilter) {
        val chip = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            applyMvTextStyle(13f, Color.rgb(35, 48, 75), 1)
            isClickable = true
            isFocusable = true
            setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener {
                filter = value
                updateFilterChipStyles()
                refresh()
            }
        }
        filterChips[value] = chip
        filterRow.addView(chip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(48),
        ).apply { marginEnd = dp(8) })
    }

    private fun updateFilterChipStyles() {
        filterChips.forEach { (value, chip) ->
            val selected = value == filter
            chip.background = roundedBackground(
                if (selected) mvColor(R.color.mv_tag_surface) else mvSurface,
                radiusDp = 18,
                strokeColor = if (selected) mvPurple else mvLine,
            )
            chip.setTextColor(if (selected) mvPurpleDark else mvInk)
            chip.contentDescription = if (selected) {
                "Đang lọc ${chip.text}"
            } else {
                "Lọc ${chip.text}"
            }
        }
    }

    private fun refresh() {
        if (destroyed || !::recycler.isInitialized) return
        val generation = ++refreshGeneration
        status.text = getString(R.string.status_loading)
        val query = search.text?.toString().orEmpty()
        submitDbOperation {
            val result = runCatching { repository.list(filter, query).sortedWith(sort.comparator) }
            runOnUiThread {
                if (isUiUnavailable() || generation != refreshGeneration) return@runOnUiThread
                result.onSuccess(::render).onFailure { error ->
                    adapter.submit(emptyList())
                    recycler.visibility = View.GONE
                    setEmptyState(
                        getString(R.string.library_load_error_title),
                        error.message ?: getString(R.string.status_load_error),
                        getString(R.string.retry),
                    ) { refresh() }
                    status.text = getString(R.string.status_load_error)
                }
            }
        }
    }

    private fun scheduleRefresh() {
        if (destroyed) return
        pendingSearch?.let(searchHandler::removeCallbacks)
        pendingSearch = Runnable { refresh() }.also {
            searchHandler.postDelayed(it, SEARCH_DEBOUNCE_MS)
        }
    }

    private fun render(assets: List<Asset>) {
        adapter.submit(assets)
        if (assets.isEmpty()) {
            recycler.visibility = View.GONE
            val hasQuery = search.text?.isNotBlank() == true
            setEmptyState(
                if (hasQuery || filter != AssetFilter.ALL) {
                    getString(R.string.library_filtered_empty_title)
                } else {
                    getString(R.string.library_empty_title)
                },
                if (hasQuery || filter != AssetFilter.ALL) {
                    getString(R.string.library_filtered_empty_message)
                } else {
                    getString(R.string.empty_state)
                },
                if (hasQuery || filter != AssetFilter.ALL) {
                    getString(R.string.clear_filters)
                } else {
                    getString(R.string.import_media)
                },
            ) {
                if (hasQuery || filter != AssetFilter.ALL) {
                    search.setText("")
                    filter = AssetFilter.ALL
                    updateFilterChipStyles()
                    refresh()
                } else {
                    showImportOptions()
                }
            }
        } else {
            emptyState.visibility = View.GONE
            recycler.visibility = View.VISIBLE
        }
        status.text = resources.getQuantityString(R.plurals.asset_count, assets.size, assets.size)
        sortButton.text = sort.label
    }

    private fun setEmptyState(title: String, message: String, action: String, onClick: () -> Unit) {
        emptyTitle.text = title
        emptyMessage.text = message
        emptyAction.text = action
        emptyAction.setOnClickListener { onClick() }
        emptyState.visibility = View.VISIBLE
    }

    private fun showSortDialog() {
        val options = SortOrder.values()
        val checked = options.indexOf(sort).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.sort_title)
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), checked) { dialog, which ->
                sort = options[which]
                sortButton.text = sort.label
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAssetDetail(asset: Asset) {
        if (asset.type == AssetType.STICKER && asset.sourceAssetId == null) {
            submitDbOperation {
                val sourceMissing = repository.findStickerRecipe(asset.id) != null
                runOnUiThread {
                    if (!isUiUnavailable()) renderAssetDetail(asset, sourceMissing)
                }
            }
            return
        }
        renderAssetDetail(asset, sourceMissing = false)
    }

    private fun renderAssetDetail(asset: Asset, sourceMissing: Boolean) {
        if (destroyed) return
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val scroll = android.widget.ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(18))
            background = roundedBackground(mvSurface, radiusDp = 20)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = getString(R.string.asset_detail_title)
            applyMvTextStyle(20f, Color.rgb(23, 32, 51), 1)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(mvIconButton("×", getString(R.string.close), dialog::dismiss), LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)

        val detailPreview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = asset.displayName
            background = CheckerboardDrawable(dp(16), this@MainActivity)
            tag = asset.id
        }
        root.addView(detailPreview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(300),
        ).apply { bottomMargin = dp(12) })

        root.addView(TextView(this).apply {
            text = asset.displayName
            applyMvTextStyle(17f, Color.rgb(23, 32, 51), 2)
        })
        root.addView(TextView(this).apply {
            text = assetMetadata(asset)
            applyMvTextStyle(13f, Color.rgb(91, 101, 124), 2)
            setPadding(0, dp(4), 0, dp(12))
        })

        if (sourceMissing) {
            root.addView(TextView(this).apply {
                text = getString(R.string.sticker_source_missing_detail)
                applyMvTextStyle(12f, mvOrangeDark, 2)
                setPadding(0, 0, 0, dp(8))
            })
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.asset_tags_title)
            applyMvTextStyle(13f, Color.rgb(35, 48, 75), 1)
        })
        val tagChipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tagChipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(tagChipRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
        }
        root.addView(tagChipScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(42),
        ).apply { topMargin = dp(4) })
        val tagsInput = EditText(this).apply {
            hint = getString(R.string.asset_tags_hint)
            setSingleLine(true)
            textSize = 14f
            setTextColor(mvInk)
            setHintTextColor(mvMuted)
            setText(asset.tags.joinToString(", "))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            contentDescription = getString(R.string.asset_tags_content_description)
            background = roundedBackground(mvSurface, radiusDp = 12, strokeColor = mvLine)
        }
        fun renderTagChips() {
            tagChipRow.removeAllViews()
            TagNormalizer.normalizeAll(tagsInput.text.toString().split(','))
                .forEach { normalized ->
                    tagChipRow.addView(
                        mvTagChip(normalized.displayName) {
                            val remaining = TagNormalizer.normalizeAll(tagsInput.text.toString().split(','))
                                .filterNot { it.key == normalized.key }
                            tagsInput.setText(remaining.joinToString(", ") { it.displayName })
                            tagsInput.setSelection(tagsInput.text.length)
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                            marginEnd = dp(6)
                        },
                    )
                }
        }
        tagsInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderTagChips()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        root.addView(tagsInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48),
        ).apply { bottomMargin = dp(4) })
        renderTagChips()
        val tagActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tagActions.addView(mvButton(getString(R.string.asset_tags_suggest), {
            showTagSuggestions(tagsInput)
        }), LinearLayout.LayoutParams(0, dp(48), 1f))
        tagActions.addView(mvButton(getString(R.string.asset_tags_save), {
            val rawTags = tagsInput.text.toString().split(',')
            submitDbOperation {
                val updated = repository.updateTags(asset.id, rawTags)
                runOnUiThread {
                    if (isUiUnavailable()) return@runOnUiThread
                    if (updated != null) {
                        Toast.makeText(this, R.string.asset_tags_saved, Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        refresh()
                    } else {
                        showError(getString(R.string.asset_tags_save_failed))
                    }
                }
            }
        }), LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        root.addView(tagActions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            bottomMargin = dp(10)
        })

        val mainActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        mainActions.addView(mvButton(getString(R.string.share), {
            markUsed(asset.id)
            ShareManager.share(this, repository, asset, Destination.SYSTEM, shareExecutor, ::showError)
        }, primary = true), LinearLayout.LayoutParams(0, dp(50), 1f))
        mainActions.addView(mvButton(getString(R.string.copy), {
            markUsed(asset.id)
            ShareManager.copy(this, repository, asset, ::showError)
        }, primary = false), LinearLayout.LayoutParams(0, dp(50), 1f).apply {
            marginStart = dp(8)
        })
        root.addView(mainActions)

        if (asset.type == AssetType.GIF) {
            val stickerActions = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            stickerActions.addView(mvButton(getString(R.string.share_animated_sticker), {
                markUsed(asset.id)
                ShareManager.shareAnimatedSticker(
                    this,
                    repository,
                    asset,
                    shareExecutor,
                    ::showError,
                )
            }), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50),
            ).apply { topMargin = dp(8) })
            stickerActions.addView(TextView(this).apply {
                text = getString(R.string.share_animated_sticker_note)
                applyMvTextStyle(12f, Color.rgb(91, 101, 124), 3)
                setPadding(dp(4), dp(6), dp(4), 0)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            root.addView(stickerActions)
        }

        val secondaryActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        secondaryActions.addView(mvButton(
            if (asset.type == AssetType.STICKER) getString(R.string.edit_sticker_short)
            else getString(R.string.create_sticker_short),
            {
            dialog.dismiss()
            openStickerStudio(asset)
            },
        ), LinearLayout.LayoutParams(0, dp(50), 1f).apply { topMargin = dp(8) })
        secondaryActions.addView(mvButton(getString(R.string.tiktok), {
            markUsed(asset.id)
            shareToHost(asset, Destination.TIKTOK)
        }), LinearLayout.LayoutParams(0, dp(50), 1f).apply {
            marginStart = dp(8)
            topMargin = dp(8)
        })
        root.addView(secondaryActions)

        val platformActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        platformActions.addView(mvButton(getString(R.string.zalo), {
            markUsed(asset.id)
            shareToHost(asset, Destination.ZALO)
        }), LinearLayout.LayoutParams(0, dp(50), 1f).apply { topMargin = dp(8) })
        platformActions.addView(TextView(this).apply {
            text = getString(R.string.asset_detail_export_note)
            applyMvTextStyle(12f, Color.rgb(91, 101, 124), 4)
            setPadding(dp(10), dp(4), 0, 0)
        }, LinearLayout.LayoutParams(0, dp(50), 2f).apply {
            marginStart = dp(8)
        })
        root.addView(platformActions)

        val bottomActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bottomActions.addView(mvButton(
            if (asset.favorite) getString(R.string.remove_favorite) else getString(R.string.add_favorite),
            {
                toggleFavorite(asset)
                dialog.dismiss()
            },
        ), LinearLayout.LayoutParams(0, dp(50), 1f).apply { topMargin = dp(8) })
        bottomActions.addView(mvButton(getString(R.string.delete), {
            confirmDelete(asset, dialog)
        }), LinearLayout.LayoutParams(0, dp(50), 1f).apply {
            marginStart = dp(8)
            topMargin = dp(8)
        })
        root.addView(bottomActions)

        root.addView(TextView(this).apply {
            text = getString(R.string.asset_detail_fallback_note)
            applyMvTextStyle(12f, Color.rgb(91, 101, 124), 3)
            setPadding(0, dp(14), 0, 0)
        })
        scroll.addView(root)
        dialog.setContentView(scroll)
        dialog.setOnDismissListener {
            MediaPreviewLoader.stopIfAnimated(detailPreview.drawable)
            detailPreview.tag = null
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                (resources.displayMetrics.widthPixels * 0.94f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        previewExecutor.execute {
            val file = File(asset.originalPath)
            val target = dp(360)
            val animated = MediaPreviewLoader.decodeAnimatedDrawable(file, asset.mimeType, target)
            val bitmap = if (animated == null) {
                MediaPreviewLoader.decode(file, asset.mimeType, target)
            } else {
                null
            }
            previewHandler.post {
                if (isUiUnavailable() || detailPreview.tag != asset.id) return@post
                if (animated != null) {
                    detailPreview.setImageDrawable(animated)
                    MediaPreviewLoader.startIfAnimated(animated)
                } else if (bitmap != null) {
                    detailPreview.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun showAssetActions(asset: Asset) {
        if (destroyed) return
        val actions = buildList {
            add(getString(R.string.share) to {
                markUsed(asset.id)
                ShareManager.share(this@MainActivity, repository, asset, Destination.SYSTEM, shareExecutor, ::showError)
            })
            add(getString(R.string.copy) to {
                markUsed(asset.id)
                ShareManager.copy(this@MainActivity, repository, asset, ::showError)
            })
            if (asset.type == AssetType.GIF) {
                add(getString(R.string.share_animated_sticker) to {
                    markUsed(asset.id)
                    ShareManager.shareAnimatedSticker(
                        this@MainActivity,
                        repository,
                        asset,
                        shareExecutor,
                        ::showError,
                    )
                })
            }
            add(getString(R.string.tiktok) to {
                markUsed(asset.id)
                shareToHost(asset, Destination.TIKTOK)
            })
            add(getString(R.string.zalo) to {
                markUsed(asset.id)
                shareToHost(asset, Destination.ZALO)
            })
            add(
                getString(
                    if (asset.type == AssetType.STICKER) R.string.edit_sticker_short
                    else R.string.create_sticker_short,
                ) to { openStickerStudio(asset) },
            )
            add(
                getString(if (asset.favorite) R.string.remove_favorite else R.string.add_favorite) to {
                    toggleFavorite(asset)
                },
            )
            add(getString(R.string.delete) to { confirmDelete(asset) })
        }
        AlertDialog.Builder(this)
            .setTitle(asset.displayName)
            .setItems(actions.map { it.first }.toTypedArray()) { dialog, which ->
                actions[which].second()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Sticker hosts do not expose a stable native-sticker contract through ACTION_SEND. Keep the
     * existing opaque image as a safe default, but let a personal user compare the iPhone-like
     * transparent raster on the same host/version without silently changing the export format.
     */
    private fun shareToHost(asset: Asset, destination: Destination) {
        if (isUiUnavailable()) return
        if (!MediaPolicy.needsStickerCompatibilityProfile(asset, destination)) {
            ShareManager.share(this, repository, asset, destination, shareExecutor, ::showError)
            return
        }

        val options = buildList {
            add(getString(R.string.sticker_share_profile_iphone))
            add(getString(R.string.sticker_share_profile_opaque))
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.sticker_share_profile_title)
            .setItems(options) { _, which ->
                val profile = if (which == 1) {
                    StickerShareProfile.OPAQUE_512
                } else {
                    StickerShareProfile.TRANSPARENT_320
                }
                ShareManager.share(
                    this,
                    repository,
                    asset,
                    destination,
                    shareExecutor,
                    ::showError,
                    profile,
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toggleFavorite(asset: Asset) {
        submitDbOperation {
            repository.setFavorite(asset.id, !asset.favorite)
            runOnUiThread { if (!isUiUnavailable()) refresh() }
        }
    }

    private fun confirmDelete(asset: Asset, dialogToClose: Dialog? = null) {
        submitDbOperation {
            val derivedCount = repository.countDerivedAssets(asset.id)
            runOnUiThread {
                if (isUiUnavailable()) return@runOnUiThread
                val message = if (derivedCount > 0) {
                    getString(R.string.delete_asset_with_derived_message, asset.displayName, derivedCount)
                } else {
                    getString(R.string.delete_asset_message, asset.displayName)
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.delete_asset_title)
                    .setMessage(message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        dialogToClose?.dismiss()
                        submitDbOperation {
                            val deleted = repository.delete(asset.id)
                            runOnUiThread {
                                if (isUiUnavailable()) return@runOnUiThread
                                if (deleted) {
                                    status.text = getString(R.string.asset_deleted)
                                    refresh()
                                } else {
                                    showError(getString(R.string.delete_asset_failed))
                                }
                            }
                        }
                    }
                    .show()
            }
        }
    }

    private fun openPicker() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/mp4"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }, REQUEST_PICK)
    }

    private fun showImportOptions() {
        AlertDialog.Builder(this)
            .setTitle(R.string.import_options_title)
            .setItems(
                arrayOf(
                    getString(R.string.import_from_device),
                    getString(R.string.import_from_tenor),
                ),
            ) { _, which ->
                if (which == 0) openPicker() else showTenorImportDialog()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showTenorImportDialog() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        val input = EditText(this).apply {
            hint = getString(R.string.tenor_import_hint)
            setSingleLine(true)
            textSize = 14f
            setTextColor(mvInk)
            setHintTextColor(mvMuted)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            contentDescription = getString(R.string.import_from_tenor)
            background = roundedBackground(mvSurface, radiusDp = 12, strokeColor = mvLine)
        }
        form.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(52),
        ))
        val progress = TextView(this).apply {
            applyMvTextStyle(12f, Color.rgb(91, 101, 124), 2)
            setPadding(dp(2), dp(8), dp(2), 0)
            visibility = View.GONE
        }
        form.addView(progress, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.tenor_import_title)
            .setMessage(R.string.tenor_import_message)
            .setView(form)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.tenor_import_action, null)
            .create()
        dialog.setOnShowListener {
            val action = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            action.setOnClickListener {
                val rawUrl = input.text?.toString().orEmpty().trim()
                val validation = TenorUrlPolicy.parse(rawUrl)
                if (validation.isFailure) {
                    input.error = validation.exceptionOrNull()?.message
                        ?: getString(R.string.tenor_import_failed)
                    return@setOnClickListener
                }
                action.isEnabled = false
                input.isEnabled = false
                progress.visibility = View.VISIBLE
                progress.text = getString(R.string.tenor_import_loading)
                statusIfReady(getString(R.string.tenor_import_loading))
                submitDbOperation {
                    val outcome = runCatching {
                        val downloaded = TenorMediaDownloader().download(rawUrl, cacheDir)
                        try {
                            repository.importGeneratedFile(
                                source = downloaded.file,
                                displayName = downloaded.displayName,
                                mimeType = downloaded.mimeType,
                                typeOverride = downloaded.typeOverride,
                            )
                        } finally {
                            downloaded.file.delete()
                        }
                    }.getOrElse { error ->
                        ImportOutcome(error = error.message ?: getString(R.string.tenor_import_failed))
                    }
                    runOnUiThread {
                        if (isUiUnavailable()) return@runOnUiThread
                        val asset = outcome.asset
                        if (asset != null) {
                            dialog.dismiss()
                            refresh()
                            status.text = getString(R.string.tenor_import_saved)
                            Toast.makeText(this, R.string.tenor_import_saved, Toast.LENGTH_SHORT).show()
                        } else {
                            action.isEnabled = true
                            input.isEnabled = true
                            progress.text = outcome.error ?: getString(R.string.tenor_import_failed)
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (destroyed) return
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK || data == null) return
        val uris = buildList {
            data.data?.let(::add)
            data.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) add(clip.getItemAt(index).uri)
            }
        }.distinct()
        if (uris.isEmpty()) return
        status.text = resources.getQuantityString(R.plurals.importing_media, uris.size, uris.size)
        submitDbOperation {
            val outcomes = repository.importUris(uris)
            val saved = outcomes.count { it.asset != null && !it.duplicate }
            val duplicate = outcomes.count { it.duplicate }
            val errors = outcomes.mapNotNull { it.error }
            runOnUiThread {
                if (isUiUnavailable()) return@runOnUiThread
                refresh()
                val message = buildString {
                    append("Đã lưu $saved")
                    if (duplicate > 0) append(", trùng $duplicate")
                    if (errors.isNotEmpty()) append(", lỗi ${errors.size}")
                }
                status.text = message
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        if (destroyed) return
        if (incoming?.action != Intent.ACTION_SEND && incoming?.action != Intent.ACTION_SEND_MULTIPLE) return
        val uris = IntentMediaExtractor.uris(incoming)
        if (uris.isEmpty()) return
        statusIfReady(getString(R.string.importing_shared_media))
        submitDbOperation {
            val outcomes = repository.importUris(uris)
            runOnUiThread {
                if (isUiUnavailable()) return@runOnUiThread
                refresh()
                val errors = outcomes.count { it.error != null }
                if (errors > 0) showError("Đã nhận media nhưng có $errors file lỗi")
            }
        }
    }

    private fun statusIfReady(message: String) {
        if (!destroyed && ::status.isInitialized) status.text = message
    }

    private fun openKeyboardSettings() {
        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
    }

    private fun openAssetPicker() {
        startActivity(Intent(this, AssetPickerActivity::class.java))
    }

    private fun openStickerStudio(source: Asset? = null) {
        // A derived sticker whose source was deleted remains sendable, but must not be opened
        // as if its missing source could be reconstructed. Resolve the recipe off the UI thread
        // so manually imported STICKER images without a recipe can still be used as sources.
        if (source?.type == AssetType.STICKER && source.sourceAssetId == null) {
            submitDbOperation {
                val sourceMissing = repository.findStickerRecipe(source.id) != null
                runOnUiThread {
                    if (isUiUnavailable()) return@runOnUiThread
                    if (sourceMissing) {
                        showError(getString(R.string.sticker_source_missing_detail))
                    } else {
                        launchStickerStudio(source)
                    }
                }
            }
            return
        }
        launchStickerStudio(source)
    }

    private fun launchStickerStudio(source: Asset?) {
        startActivity(Intent(this, StickerStudioActivity::class.java).apply {
            if (source?.type == AssetType.STICKER) {
                putExtra(StickerStudioActivity.EXTRA_EDIT_STICKER_ASSET_ID, source.id)
            } else {
                source?.id?.let { putExtra(StickerStudioActivity.EXTRA_SOURCE_ASSET_ID, it) }
            }
            if (source != null) putExtra(StickerStudioActivity.EXTRA_QUICK_MODE, true)
        })
    }

    private fun showHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.library_more_title)
            .setMessage(R.string.library_more_message)
            .setPositiveButton(R.string.open_keyboard_settings) { _, _ -> openKeyboardSettings() }
            .setNeutralButton(R.string.zalo_sync_action) { _, _ -> openZaloSync() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openZaloSync() {
        startActivity(Intent(this, ZaloSyncActivity::class.java))
    }

    private fun assetMetadata(asset: Asset): String = buildString {
        append(asset.type.label)
        append(" • ")
        append(asset.mimeType)
        if (asset.width > 0 && asset.height > 0) append(" • ${asset.width}×${asset.height}")
        if (asset.durationMs > 0) append(" • ${asset.durationMs / 1000}s")
        if (asset.useCount > 0) append(" • dùng ${asset.useCount} lần")
        if (asset.tags.isNotEmpty()) append(" • #${asset.tags.joinToString(" #")}")
    }

    private fun markUsed(id: String) {
        submitDbOperation { repository.touchUsed(id) }
    }

    private fun configureSystemBars() {
        val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            window.isNavigationBarContrastEnforced = false
            // Access through the decor view: Activity.onCreate runs before PhoneWindow has
            // initialized its cached InsetsController, so window.insetsController can throw
            // on Android 11+ when this is called before setContentView.
            val lightBarFlags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.decorView.windowInsetsController?.setSystemBarsAppearance(
                if (night) 0 else lightBarFlags,
                lightBarFlags,
            )
        } else {
            @Suppress("DEPRECATION")
            run {
                window.statusBarColor = mvPaper
                window.navigationBarColor = mvPaper
                window.decorView.systemUiVisibility = if (night) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }
    }

    private fun applySystemBarInsets(root: View) {
        val baseLeft = dp(16)
        val baseTop = dp(8)
        val baseRight = dp(16)
        val baseBottom = dp(8)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(
                    baseLeft + bars.left,
                    baseTop + bars.top,
                    baseRight + bars.right,
                    baseBottom + bars.bottom,
                )
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(
                    baseLeft + insets.systemWindowInsetLeft,
                    baseTop + insets.systemWindowInsetTop,
                    baseRight + insets.systemWindowInsetRight,
                    baseBottom + insets.systemWindowInsetBottom,
                )
            }
            insets
        }
        root.requestApplyInsets()
    }

    private fun showError(message: String) {
        if (isUiUnavailable()) return
        if (::status.isInitialized) status.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showTagSuggestions(input: EditText) {
        val prefix = input.text?.toString()?.substringAfterLast(',')?.trim().orEmpty()
        submitDbOperation {
            val suggestions = repository.suggestTags(prefix)
            runOnUiThread {
                if (isUiUnavailable()) return@runOnUiThread
                if (suggestions.isEmpty()) {
                    Toast.makeText(this, R.string.asset_tags_no_suggestions, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.asset_tags_suggest_title)
                    .setItems(suggestions.map { "${it.name} (${it.usageCount})" }.toTypedArray()) { dialog, which ->
                        val existing = input.text.toString().split(',').map(String::trim).filter(String::isNotEmpty)
                        val next = TagNormalizer.normalizeAll(existing + suggestions[which].name)
                            .joinToString(", ") { it.displayName }
                        input.setText("$next, ")
                        input.setSelection(input.text.length)
                        dialog.dismiss()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun isUiUnavailable(): Boolean = destroyed || isFinishing || isDestroyed

    private fun submitDbOperation(operation: () -> Unit) {
        if (destroyed) return
        runCatching {
            executor.execute task@{
                if (destroyed) return@task
                operation()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        destroyed = true
        refreshGeneration++
        pendingSearch?.let(searchHandler::removeCallbacks)
        executor.shutdown()
        shareExecutor.shutdownNow()
        previewExecutor.shutdownNow()
        Thread {
            runCatching { executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS) }
            runCatching { shareExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS) }
            runCatching { repository.close() }
        }.apply {
            name = "MemeVault-repository-close"
            isDaemon = true
            start()
        }
        super.onDestroy()
    }

    private enum class SortOrder(val label: String, val comparator: Comparator<Asset>) {
        RECENT("Mới nhất", compareByDescending { it.lastUsedAt ?: it.createdAt }),
        MOST_USED("Dùng nhiều", compareByDescending<Asset> { it.useCount }
            .thenByDescending { it.lastUsedAt ?: it.createdAt }),
        NAME("Tên A–Z", compareBy { it.displayName.lowercase() }),
    }

    private class AssetAdapter(
        private val context: MainActivity,
        private val previewExecutor: ExecutorService,
        private val previewHandler: Handler,
        private val onOpen: (Asset) -> Unit,
        private val onFavorite: (Asset) -> Unit,
        private val onMore: (Asset) -> Unit,
        private val onCreateSticker: (Asset) -> Unit,
    ) : ListAdapter<Asset, AssetAdapter.Holder>(DIFF) {
        init {
            setHasStableIds(true)
        }

        fun submit(next: List<Asset>) = submitList(next)

        override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
                background = context.roundedBackground(context.mvSurface, radiusDp = 20, strokeColor = context.mvLine)
                isClickable = true
                isFocusable = true
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    setMargins(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
                }
            }
            val mediaFrame = FrameLayout(context)
            val preview = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(context.mvPaperDeep)
            }
            mediaFrame.addView(preview, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.dp(154),
            ))
            val badge = TextView(context).apply {
                applyMvTextStyle(11f, Color.WHITE, 1)
                gravity = Gravity.CENTER
                setPadding(context.dp(8), 0, context.dp(8), 0)
                background = context.roundedBackground(
                    Color.argb(205, Color.red(context.mvInk), Color.green(context.mvInk), Color.blue(context.mvInk)),
                    radiusDp = 10,
                )
            }
            mediaFrame.addView(badge, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                context.dp(26),
                Gravity.BOTTOM or Gravity.START,
            ).apply { leftMargin = context.dp(8); bottomMargin = context.dp(8) })
            val topActions = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            val favorite = context.mvIconButton("☆", "Đánh dấu yêu thích", {})
            val create = context.mvIconButton("✦", "Tạo sticker từ media", {})
            val more = context.mvIconButton("⋮", "Thêm hành động", {})
            topActions.addView(favorite, LinearLayout.LayoutParams(context.dp(48), context.dp(48)))
            topActions.addView(create, LinearLayout.LayoutParams(context.dp(48), context.dp(48)).apply {
                marginStart = context.dp(4)
            })
            topActions.addView(more, LinearLayout.LayoutParams(context.dp(48), context.dp(48)).apply {
                marginStart = context.dp(4)
            })
            mediaFrame.addView(topActions, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                context.dp(48),
                Gravity.TOP or Gravity.END,
            ).apply { topMargin = context.dp(4); rightMargin = context.dp(4) })
            card.addView(mediaFrame, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.dp(154),
            ))
            val name = TextView(context).apply {
                applyMvTextStyle(14f, Color.rgb(23, 32, 51), 2)
                setPadding(0, context.dp(8), 0, 0)
            }
            card.addView(name, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            val metadata = TextView(context).apply {
                applyMvTextStyle(11f, Color.rgb(91, 101, 124), 1)
                setPadding(0, context.dp(4), 0, context.dp(2))
            }
            card.addView(metadata)
            return Holder(card, preview, badge, name, metadata, favorite, create, more)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(getItem(position))
        }

        override fun onViewRecycled(holder: Holder) {
            holder.preview.tag = null
            holder.preview.setImageDrawable(null)
            super.onViewRecycled(holder)
        }

        inner class Holder(
            private val card: LinearLayout,
            val preview: ImageView,
            private val badge: TextView,
            private val name: TextView,
            private val metadata: TextView,
            private val favorite: TextView,
            private val create: TextView,
            private val more: TextView,
        ) : RecyclerView.ViewHolder(card) {
            fun bind(asset: Asset) {
                card.tag = asset
                favorite.tag = asset
                create.tag = asset
                more.tag = asset
                card.contentDescription = "Mở ${asset.displayName}"
                favorite.text = if (asset.favorite) "★" else "☆"
                favorite.contentDescription = if (asset.favorite) {
                    "Bỏ yêu thích ${asset.displayName}"
                } else {
                    "Đánh dấu yêu thích ${asset.displayName}"
                }
                favorite.setOnClickListener { (it.tag as? Asset)?.let(onFavorite) }
                create.setOnClickListener { (it.tag as? Asset)?.let(onCreateSticker) }
                more.setOnClickListener { (it.tag as? Asset)?.let(onMore) }
                card.setOnClickListener { (it.tag as? Asset)?.let(onOpen) }
                badge.text = buildString {
                    append(asset.type.label)
                    if (asset.durationMs > 0) append(" • ${asset.durationMs / 1000}s")
                }
                name.text = asset.displayName
                metadata.text = buildString {
                    if (asset.width > 0 && asset.height > 0) append("${asset.width}×${asset.height}")
                    if (asset.useCount > 0) append(if (isNotEmpty()) " • " else "")
                    if (asset.useCount > 0) append("dùng ${asset.useCount}")
                    if (isEmpty()) append(asset.mimeType)
                }
                preview.tag = asset.id
                preview.setImageDrawable(null)
                preview.scaleType = if (asset.type == AssetType.STICKER) {
                    ImageView.ScaleType.CENTER_INSIDE
                } else {
                    ImageView.ScaleType.CENTER_CROP
                }
                preview.background = if (asset.type == AssetType.STICKER) {
                    CheckerboardDrawable(context.dp(10), context)
                } else {
                    context.roundedBackground(context.mvPaperDeep, radiusDp = 14)
                }
                val target = context.dp(220)
                previewExecutor.execute {
                    val bitmap = MediaPreviewLoader.decode(File(asset.originalPath), asset.mimeType, target)
                    previewHandler.post {
                        if (!context.isUiUnavailable() && preview.tag == asset.id && bitmap != null) {
                            preview.setImageBitmap(bitmap)
                        }
                    }
                }
            }
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<Asset>() {
                override fun areItemsTheSame(oldItem: Asset, newItem: Asset): Boolean = oldItem.id == newItem.id
                override fun areContentsTheSame(oldItem: Asset, newItem: Asset): Boolean = oldItem == newItem
            }
        }
    }

    companion object {
        private const val REQUEST_PICK = 1001
        private const val COLUMN_COUNT = 2
        private const val KEY_QUERY = "library_query"
        private const val KEY_FILTER = "library_filter"
        private const val KEY_SORT = "library_sort"
        private const val SEARCH_DEBOUNCE_MS = 180L
    }
}
