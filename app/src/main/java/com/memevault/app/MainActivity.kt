package com.memevault.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    internal lateinit var repository: AssetRepository
    private lateinit var prefs: LibraryPreferences

    private lateinit var root: FrameLayout
    private lateinit var normalBar: LinearLayout
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionTitle: TextView
    private lateinit var search: EditText
    private lateinit var searchClear: ImageView
    private lateinit var typeRow: LinearLayout
    private lateinit var filterBlock: LinearLayout
    private var scrollAccumulator = 0
    private val typeChips = linkedMapOf<LibraryTypeFilter, TextView>()
    private lateinit var tagScroll: HorizontalScrollView
    private lateinit var tagRow: LinearLayout
    private lateinit var countLabel: TextView
    private lateinit var sortButton: TextView
    private lateinit var densityButton: ImageView
    private lateinit var recycler: RecyclerView
    private lateinit var gridLayout: GridLayoutManager
    private lateinit var adapter: LibraryGridAdapter
    private lateinit var emptyState: LinearLayout
    private lateinit var emptyTitle: TextView
    private lateinit var emptyMessage: TextView
    private lateinit var emptyAction: Button
    private lateinit var createStickerButton: Button
    private lateinit var bulkBar: LinearLayout
    private lateinit var bulkFavorite: TextView
    private lateinit var snackbar: MvSnackbar

    private val dbExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val shareExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val networkExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val previewExecutor: ExecutorService = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null

    private var allAssets: List<Asset> = emptyList()
    private var loaded = false
    private var typeFilter = LibraryTypeFilter.ALL
    private var tagScope: TagScope = TagScope.Any
    private val selectedIds = linkedSetOf<String>()
    private var selectionActive = false
    private val pendingDeletion = linkedSetOf<String>()
    private var detail: AssetDetailPanel? = null
    private var sheet: MvSheet? = null
    private var restoreDetailId: String? = null
    private var refreshGeneration = 0L
    private var backCallback: OnBackInvokedCallback? = null

    @Volatile
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = AssetRepository(this)
        prefs = LibraryPreferences(this)
        savedInstanceState?.let(::restoreState)
        configureSystemBars()
        buildUi()
        savedInstanceState?.getString(KEY_QUERY)?.let { search.setText(it) }
        if (savedInstanceState == null) handleIncomingIntent(intent)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (!destroyed && ::recycler.isInitialized && loaded) refresh()
    }

    override fun onStop() {
        // Commit any deletion still waiting for "Undo" before the process can be killed.
        if (::snackbar.isInitialized) snackbar.dismiss()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (destroyed) return
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_QUERY, search.text?.toString().orEmpty())
        outState.putString(KEY_TYPE, typeFilter.name)
        when (val scope = tagScope) {
            TagScope.Untagged -> outState.putString(KEY_TAG_SCOPE, SCOPE_UNTAGGED)
            is TagScope.Tag -> {
                outState.putString(KEY_TAG_SCOPE, scope.key)
                outState.putString(KEY_TAG_LABEL, scope.label)
            }
            TagScope.Any -> Unit
        }
        if (selectionActive) outState.putStringArrayList(KEY_SELECTION, ArrayList(selectedIds))
        detail?.asset?.id?.let { outState.putString(KEY_DETAIL, it) }
        super.onSaveInstanceState(outState)
    }

    private fun restoreState(state: Bundle) {
        typeFilter = state.getString(KEY_TYPE)
            ?.let { runCatching { LibraryTypeFilter.valueOf(it) }.getOrNull() }
            ?: LibraryTypeFilter.ALL
        tagScope = when (val key = state.getString(KEY_TAG_SCOPE)) {
            null -> TagScope.Any
            SCOPE_UNTAGGED -> TagScope.Untagged
            else -> TagScope.Tag(key, state.getString(KEY_TAG_LABEL) ?: key)
        }
        state.getStringArrayList(KEY_SELECTION)?.let {
            selectedIds.addAll(it)
            selectionActive = it.isNotEmpty()
        }
        restoreDetailId = state.getString(KEY_DETAIL)
    }

    // region UI construction

    private fun buildUi() {
        root = FrameLayout(this).apply {
            setBackgroundColor(mvBackground)
            // Keep initial focus off the search field so the keyboard never opens on launch.
            isFocusableInTouchMode = true
        }
        applySystemBarInsets(root)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        val barHost = FrameLayout(this)
        normalBar = buildNormalBar()
        selectionBar = buildSelectionBar()
        barHost.addView(normalBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        barHost.addView(selectionBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        column.addView(barHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        column.addView(buildSearch(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(MV_TOUCH),
        ).apply { setMargins(dp(16), dp(4), dp(16), dp(4)) })

        filterBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val typeScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(16), 0, dp(16), 0)
        }
        typeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        LibraryTypeFilter.values().forEach { option ->
            val chip = mvChip(option.label) {
                typeFilter = option
                applyView(scrollToTop = true)
            }
            typeChips[option] = chip
            // Stays hidden until the first render finds an archived source.
            if (option == LibraryTypeFilter.SOURCES) chip.visibility = View.GONE
            typeRow.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(MV_TOUCH),
            ).apply { marginEnd = dp(6) })
        }
        typeScroll.addView(typeRow)
        filterBlock.addView(typeScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(MV_TOUCH)))

        tagScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(16), 0, dp(16), 0)
            visibility = View.GONE
        }
        tagRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tagScroll.addView(tagRow)
        filterBlock.addView(tagScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(MV_TOUCH)))
        filterBlock.addView(buildToolbar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(MV_TOUCH)).apply {
            topMargin = dp(2)
        })
        filterBlock.addView(mvDivider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        // The filter block overlays the grid and slides away with translationY; the grid only
        // reserves its height as top padding, so hiding it never re-lays out the RecyclerView.
        filterBlock.setBackgroundColor(mvBackground)
        filterBlock.elevation = dp(1).toFloat()

        val gridHost = FrameLayout(this)
        column.addView(gridHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        gridLayout = GridLayoutManager(this, prefs.columns)
        adapter = LibraryGridAdapter(
            activity = this,
            previewExecutor = previewExecutor,
            previewHandler = mainHandler,
            isSelected = { it in selectedIds },
            selectionActive = { selectionActive },
            onClick = ::onTileClick,
            onLongClick = ::onTileLongClick,
        )
        recycler = RecyclerView(this).apply {
            layoutManager = gridLayout
            adapter = this@MainActivity.adapter
            clipToPadding = false
            setPadding(dp(14), dp(180), dp(14), dp(88))
            itemAnimator = null
            setHasFixedSize(true)
            contentDescription = getString(R.string.library_content_description)
            addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) updateSpan()
            }
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    onGridScrolled(dy)
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) onGridScrolled(0)
                }
            })
        }
        gridHost.addView(recycler, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        emptyState = buildEmptyState()
        gridHost.addView(emptyState, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP,
        ).apply { setMargins(dp(16), dp(24), dp(16), 0) })
        gridHost.addView(filterBlock, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP,
        ))
        filterBlock.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val height = bottom - top
            if (height != oldBottom - oldTop) recycler.post { reserveFilterSpace(height) }
        }

        createStickerButton = mvButton(getString(R.string.create_sticker_action), { openAssetPicker() },
            primary = true, iconRes = R.drawable.ic_cut)
        root.addView(createStickerButton, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(MV_TOUCH),
            Gravity.BOTTOM or Gravity.END,
        ).apply { setMargins(0, 0, dp(16), dp(16)) })

        bulkBar = buildBulkBar()
        root.addView(bulkBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(64),
            Gravity.BOTTOM,
        ))
        snackbar = MvSnackbar(root)
        setContentView(root)
        root.requestFocus()
        updateChrome()
    }

    private fun buildNormalBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), 0, dp(4), 0)
        addView(TextView(this@MainActivity).apply {
            text = getString(R.string.app_name)
            applyMvTextStyle(20f, mvText, 1)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val importButton = mvIconButton(R.drawable.ic_add, getString(R.string.import_media), {})
        importButton.setOnClickListener { showImportMenu(it) }
        addView(importButton)
        val more = mvIconButton(R.drawable.ic_more_vert, getString(R.string.library_more_actions), {})
        more.setOnClickListener { showOverflowMenu(it) }
        addView(more)
    }

    private fun buildSelectionBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), 0, dp(4), 0)
        setBackgroundColor(mvSurfaceAlt)
        visibility = View.GONE
        addView(mvIconButton(R.drawable.ic_close, "Thoát chế độ chọn", { exitSelection() }))
        selectionTitle = TextView(this@MainActivity).apply {
            applyMvTextStyle(16f, mvText, 1)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(4), 0, 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        addView(selectionTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(mvIconButton(R.drawable.ic_select_all, "Chọn tất cả đang hiển thị", { selectAllVisible() }))
    }

    private fun buildSearch(): View {
        val frame = FrameLayout(this)
        search = EditText(this).apply {
            hint = getString(R.string.search_hint)
            setSingleLine(true)
            textSize = 15f
            setTextColor(mvText)
            setHintTextColor(mvMuted)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(dp(40), 0, dp(44), 0)
            background = roundedBackground(mvSurface, MV_RADIUS, mvLine)
            contentDescription = getString(R.string.search_content_description)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    searchClear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                    scheduleRefresh()
                }
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    hideKeyboard()
                    true
                } else {
                    false
                }
            }
        }
        frame.addView(search, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(ImageView(this).apply {
            setMvIcon(R.drawable.ic_search, mvMuted)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER_VERTICAL or Gravity.START).apply {
            marginStart = dp(12)
        })
        searchClear = mvIconButton(R.drawable.ic_close, "Xoá từ khoá", {
            search.setText("")
        }, tint = mvMuted).apply { visibility = View.GONE }
        frame.addView(searchClear, FrameLayout.LayoutParams(dp(MV_TOUCH), dp(MV_TOUCH), Gravity.CENTER_VERTICAL or Gravity.END))
        return frame
    }

    private fun buildToolbar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), 0, dp(4), 0)
        countLabel = TextView(this@MainActivity).apply {
            applyMvMonoStyle(12f)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        addView(countLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sortButton = TextView(this@MainActivity).apply {
            applyMvTextStyle(13f, mvText, 1)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
            val icon = getDrawable(R.drawable.ic_sort)?.mutate()?.apply {
                setTint(mvMuted)
                setBounds(0, 0, dp(18), dp(18))
            }
            setCompoundDrawablesRelative(icon, null, null, null)
            compoundDrawablePadding = dp(6)
            isClickable = true
            isFocusable = true
            background = roundedBackground(Color.TRANSPARENT)
            setOnClickListener { showSortMenu(it) }
        }
        addView(sortButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(MV_TOUCH)))
        densityButton = mvIconButton(R.drawable.ic_grid, "", { cycleDensity() }, tint = mvMuted)
        addView(densityButton)
    }

    private fun buildEmptyState(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(20), dp(28), dp(20), dp(24))
        background = roundedBackground(mvSurface, MV_RADIUS, mvLine)
        visibility = View.GONE
        emptyTitle = TextView(this@MainActivity).apply {
            applyMvTextStyle(16f, mvText, 2)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        addView(emptyTitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        emptyMessage = TextView(this@MainActivity).apply {
            applyMvTextStyle(14f, mvMuted, 4)
            gravity = Gravity.CENTER
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        addView(emptyMessage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
            bottomMargin = dp(16)
        })
        emptyAction = mvButton("", {}, primary = true)
        addView(emptyAction, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(MV_TOUCH)))
    }

    private fun buildBulkBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(mvSurface)
        visibility = View.GONE
        isClickable = true
        elevation = dp(MV_ELEVATION_BAR).toFloat()
        bulkFavorite = bulkAction(R.drawable.ic_star_border, "Yêu thích", mvText) { bulkToggleFavorite() }
        addView(bulkFavorite, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        addView(bulkAction(R.drawable.ic_label, "Gắn tag", mvText) { showBulkTagSheet() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        addView(bulkAction(R.drawable.ic_delete, "Xoá", mvDanger) {
            requestDelete(allAssets.filter { it.id in selectedIds })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    }

    private fun bulkAction(iconRes: Int, label: String, tint: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(tint)
            gravity = Gravity.CENTER
            includeFontPadding = false
            val icon = getDrawable(iconRes)?.mutate()?.apply {
                setTint(tint)
                setBounds(0, 0, dp(22), dp(22))
            }
            setCompoundDrawablesRelative(null, icon, null, null)
            compoundDrawablePadding = dp(4)
            isClickable = true
            isFocusable = true
            background = roundedBackground(Color.TRANSPARENT)
            setOnClickListener { onClick() }
        }

    // endregion

    // region Data and view state

    private fun refresh() {
        if (destroyed || !::recycler.isInitialized) return
        val generation = ++refreshGeneration
        val query = search.text?.toString().orEmpty()
        runDb {
            val result = runCatching { repository.list(AssetFilter.ALL, query) }
            runOnUi {
                if (generation != refreshGeneration) return@runOnUi
                result.onSuccess { assets ->
                    allAssets = assets
                    loaded = true
                    applyView()
                    restoreDetailId?.let { id ->
                        restoreDetailId = null
                        assets.firstOrNull { it.id == id }?.let(::openDetail)
                    }
                }.onFailure { error ->
                    allAssets = emptyList()
                    adapter.submitList(emptyList())
                    showEmpty(
                        getString(R.string.library_load_error_title),
                        error.message ?: getString(R.string.status_load_error),
                        getString(R.string.retry),
                    ) { refresh() }
                }
            }
        }
    }

    private fun scheduleRefresh() {
        if (destroyed) return
        pendingSearch?.let(mainHandler::removeCallbacks)
        pendingSearch = Runnable { refresh() }.also { mainHandler.postDelayed(it, SEARCH_DEBOUNCE_MS) }
    }

    private fun applyView(scrollToTop: Boolean = false) {
        if (scrollToTop) setFiltersVisible(true)
        val sort = prefs.sort
        if (tagScope is TagScope.Tag && allAssets.none { LibraryView.matches(it, tagScope) }) {
            tagScope = TagScope.Any
        }
        // The "Ảnh nguồn" chip hides once its last item is restored or deleted.
        if (typeFilter == LibraryTypeFilter.SOURCES && allAssets.none { it.archived && it.id !in pendingDeletion }) {
            typeFilter = LibraryTypeFilter.ALL
        }
        val visible = LibraryView.apply(allAssets, typeFilter.filter, tagScope, sort, pendingDeletion)
        adapter.submitList(visible) {
            if (scrollToTop) recycler.scrollToPosition(0)
        }
        val total = allAssets.count { LibraryView.inLibrary(it, pendingDeletion) }
        val hasQuery = search.text?.isNotBlank() == true
        val narrowed = hasQuery || typeFilter != LibraryTypeFilter.ALL || tagScope != TagScope.Any
        countLabel.text = when {
            // While searching, totals describe the result set, so "x / y" would mislead.
            hasQuery -> "${visible.size} kết quả"
            narrowed -> "${visible.size} / $total mục"
            else -> "$total mục"
        }
        sortButton.text = sort.label
        sortButton.contentDescription = "Sắp xếp: ${sort.label}"
        renderTypeChips()
        renderTagStrip()
        if (visible.isEmpty()) {
            if (narrowed) {
                showEmpty(
                    getString(R.string.library_filtered_empty_title),
                    getString(R.string.library_filtered_empty_message),
                    getString(R.string.clear_filters),
                ) { clearFilters() }
            } else {
                showEmpty(
                    getString(R.string.library_empty_title),
                    getString(R.string.empty_state),
                    getString(R.string.import_media),
                ) { openPicker() }
            }
        } else {
            emptyState.visibility = View.GONE
        }
        selectedIds.retainAll(visible.map { it.id }.toSet())
        if (selectionActive && selectedIds.isEmpty()) exitSelection() else updateChrome()
        detail?.let { panel ->
            val updated = allAssets.firstOrNull { it.id == panel.asset.id && it.id !in pendingDeletion }
            if (updated == null) closeDetail() else panel.update(updated)
        }
    }

    private fun renderTypeChips() {
        val counts = LibraryView.countsByFilter(allAssets, pendingDeletion)
        typeChips.forEach { (option, chip) ->
            val selected = option == typeFilter
            val count = counts[option.filter] ?: 0
            chip.visibility = if (option == LibraryTypeFilter.SOURCES && count == 0 && !selected) {
                View.GONE
            } else {
                View.VISIBLE
            }
            chip.text = labelWithCount(option.label, count, selected)
            chip.styleMvChip(selected)
            chip.contentDescription = "${option.label}, $count mục" + if (selected) ", đang lọc" else ""
        }
    }

    private fun renderTagStrip() {
        tagRow.removeAllViews()
        val tags = LibraryView.topTags(allAssets, TAG_STRIP_LIMIT, pendingDeletion).toMutableList()
        val scope = tagScope
        if (scope is TagScope.Tag && tags.none { it.key == scope.key }) {
            tags.add(0, TagCount(scope.key, scope.label, allAssets.count { LibraryView.matches(it, scope) }))
        }
        val untagged = allAssets.count { LibraryView.inLibrary(it, pendingDeletion) && it.tags.isEmpty() }
        tagScroll.visibility = if (tags.isEmpty() && untagged == 0) View.GONE else View.VISIBLE
        tagRow.addView(ImageView(this).apply {
            setMvIcon(R.drawable.ic_label, mvMuted)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
        if (untagged > 0 && tags.isNotEmpty()) {
            val selected = tagScope == TagScope.Untagged
            addTagChip("Chưa có tag", untagged, selected) {
                tagScope = if (selected) TagScope.Any else TagScope.Untagged
                applyView(scrollToTop = true)
            }
        }
        tags.forEach { tag ->
            val selected = (tagScope as? TagScope.Tag)?.key == tag.key
            addTagChip(tag.label, tag.count, selected) {
                tagScope = if (selected) TagScope.Any else TagScope.Tag(tag.key, tag.label)
                applyView(scrollToTop = true)
            }
        }
        if (tags.isEmpty() && untagged > 0) {
            tagRow.addView(TextView(this).apply {
                text = "Gắn tag để gom media thành bộ sưu tập"
                applyMvTextStyle(13f, mvMuted, 1)
            })
        }
    }

    private fun addTagChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
        val chip = mvChip(labelWithCount(label, count, selected), onClick)
        chip.styleMvChip(selected)
        chip.contentDescription = "Tag $label, $count mục" + if (selected) ", đang lọc" else ""
        tagRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(MV_TOUCH)).apply {
            marginEnd = dp(6)
        })
    }

    private fun labelWithCount(label: String, count: Int, selected: Boolean): CharSequence {
        val builder = SpannableStringBuilder(label).append("  ")
        val start = builder.length
        builder.append(count.toString())
        builder.setSpan(TypefaceSpan("monospace"), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(
            ForegroundColorSpan(if (selected) mvOnPrimary else mvMuted),
            start,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return builder
    }

    private fun showEmpty(title: String, message: String, action: String, onClick: () -> Unit) {
        emptyTitle.text = title
        emptyMessage.text = message
        emptyAction.text = action
        emptyAction.setOnClickListener { onClick() }
        emptyState.visibility = View.VISIBLE
    }

    private fun clearFilters() {
        typeFilter = LibraryTypeFilter.ALL
        tagScope = TagScope.Any
        if (search.text?.isNotEmpty() == true) search.setText("") else applyView(scrollToTop = true)
    }

    /** Recomputes the column count from the density preference and the grid width. */
    private fun updateSpan() {
        val width = recycler.width - recycler.paddingLeft - recycler.paddingRight
        if (width <= 0) return
        val widthDp = (width / resources.displayMetrics.density).toInt()
        val span = LibraryPreferences.spanFor(prefs.columns, widthDp)
        if (gridLayout.spanCount != span) gridLayout.spanCount = span
        adapter.columns = span
        adapter.tilePx = (width / span).coerceAtLeast(dp(64))
    }

    private fun cycleDensity() {
        prefs.columns = LibraryPreferences.nextColumns(prefs.columns)
        updateSpan()
        updateChrome()
    }

    /**
     * Quick-return for the filter block: it collapses after a deliberate downward scroll and
     * returns on any upward scroll or at the top, so media gets the space while browsing.
     */
    /** Keeps the grid's first row below the overlaid filter block, re-anchoring at the top. */
    private fun reserveFilterSpace(height: Int) {
        val top = height + dp(8)
        if (recycler.paddingTop == top) return
        val wasAtTop = gridLayout.findFirstVisibleItemPosition() <= 0
        recycler.setPadding(recycler.paddingLeft, top, recycler.paddingRight, recycler.paddingBottom)
        (emptyState.layoutParams as FrameLayout.LayoutParams).topMargin = height + dp(16)
        emptyState.requestLayout()
        if (wasAtTop) gridLayout.scrollToPositionWithOffset(0, 0)
    }

    private fun onGridScrolled(dy: Int) {
        if (!recycler.canScrollVertically(-1)) {
            scrollAccumulator = 0
            setFiltersVisible(true)
            return
        }
        if (dy == 0) return
        if ((dy > 0) != (scrollAccumulator > 0)) scrollAccumulator = 0
        scrollAccumulator += dy
        when {
            scrollAccumulator < -dp(24) -> setFiltersVisible(true)
            scrollAccumulator > dp(48) -> setFiltersVisible(false)
        }
    }

    private var filtersShown = true

    private fun setFiltersVisible(visible: Boolean) {
        if (filtersShown == visible) return
        filtersShown = visible
        filterBlock.animate().cancel()
        if (visible) {
            filterBlock.visibility = View.VISIBLE
            filterBlock.animate().translationY(0f).setDuration(FILTER_ANIMATION_MS).start()
        } else {
            filterBlock.animate()
                .translationY(-filterBlock.height.toFloat())
                .setDuration(FILTER_ANIMATION_MS)
                .withEndAction { if (!filtersShown) filterBlock.visibility = View.INVISIBLE }
                .start()
        }
    }

    private fun showSortMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        val current = prefs.sort
        LibrarySort.values().forEachIndexed { index, option ->
            menu.menu.add(1, index, index, option.label).apply {
                isCheckable = true
                isChecked = option == current
            }
        }
        menu.menu.setGroupCheckable(1, true, true)
        menu.setOnMenuItemClickListener { item ->
            prefs.sort = LibrarySort.values()[item.itemId]
            applyView(scrollToTop = true)
            true
        }
        menu.show()
    }

    private fun updateChrome() {
        normalBar.visibility = if (selectionActive) View.GONE else View.VISIBLE
        selectionBar.visibility = if (selectionActive) View.VISIBLE else View.GONE
        bulkBar.visibility = if (selectionActive) View.VISIBLE else View.GONE
        createStickerButton.visibility = if (selectionActive) View.GONE else View.VISIBLE
        selectionTitle.text = "${selectedIds.size} đã chọn"
        val selected = allAssets.filter { it.id in selectedIds }
        val allFavorite = selected.isNotEmpty() && selected.all(Asset::favorite)
        bulkFavorite.text = if (allFavorite) "Bỏ yêu thích" else "Yêu thích"
        bulkFavorite.setCompoundDrawablesRelative(null, getDrawable(
            if (allFavorite) R.drawable.ic_star else R.drawable.ic_star_border,
        )?.mutate()?.apply {
            setTint(if (allFavorite) mvFavorite else mvText)
            setBounds(0, 0, dp(22), dp(22))
        }, null, null)
        densityButton.contentDescription = "Mật độ lưới: ${gridLayout.spanCount} cột. Chạm để đổi"
        updateBackCallback()
    }

    // endregion

    // region Selection and bulk actions

    private fun onTileClick(asset: Asset) {
        if (selectionActive) toggleSelection(asset) else openDetail(asset)
    }

    private fun onTileLongClick(asset: Asset) {
        recycler.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (!selectionActive) {
            selectionActive = true
            selectedIds.clear()
            selectedIds.add(asset.id)
            hideKeyboard()
            adapter.notifySelectionChanged()
            updateChrome()
        } else {
            toggleSelection(asset)
        }
    }

    private fun toggleSelection(asset: Asset) {
        if (!selectedIds.add(asset.id)) selectedIds.remove(asset.id)
        if (selectedIds.isEmpty()) {
            exitSelection()
            return
        }
        adapter.notifySelectionChanged(asset.id)
        updateChrome()
    }

    private fun selectAllVisible() {
        selectedIds.addAll(adapter.currentList.map { it.id })
        adapter.notifySelectionChanged()
        updateChrome()
    }

    private fun exitSelection() {
        if (!selectionActive) return
        selectionActive = false
        selectedIds.clear()
        adapter.notifySelectionChanged()
        updateChrome()
    }

    private fun bulkToggleFavorite() {
        val ids = selectedIds.toList()
        val makeFavorite = !allAssets.filter { it.id in selectedIds }.all(Asset::favorite)
        exitSelection()
        runDb {
            val changed = repository.setFavorite(ids, makeFavorite)
            runOnUi {
                refresh()
                showMessage(if (makeFavorite) "Đã thêm $changed mục vào Yêu thích" else "Đã bỏ yêu thích $changed mục")
            }
        }
    }

    private fun showBulkTagSheet() {
        val ids = selectedIds.toList()
        val sheet = MvSheet(root, "Gắn tag cho ${ids.size} mục")
        val input = EditText(this).apply {
            hint = "Nhập tag, phân tách bằng dấu phẩy"
            setSingleLine(true)
            textSize = 15f
            setTextColor(mvText)
            setHintTextColor(mvMuted)
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(dp(12), 0, dp(12), 0)
            background = roundedBackground(mvSurface, MV_RADIUS, mvLine)
            contentDescription = "Tag cần gắn"
        }
        sheet.body.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(MV_TOUCH)))
        val suggestions = topTags(10)
        if (suggestions.isNotEmpty()) {
            sheet.body.addView(mvSectionLabel("TAG ĐANG DÙNG"), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, dp(14), 0, dp(4)) })
            val flow = FlowLayout(this)
            suggestions.forEach { tag ->
                val chip = mvChip(tag.label) {
                    val existing = input.text.toString().split(',').map(String::trim).filter(String::isNotEmpty)
                    input.setText((existing + tag.label).distinct().joinToString(", "))
                    input.setSelection(input.text.length)
                }
                chip.styleMvChip(false)
                flow.addView(chip, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(MV_TOUCH)))
            }
            sheet.body.addView(flow)
        }
        lateinit var apply: Button
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                apply.performClick()
                true
            } else {
                false
            }
        }
        apply = mvButton("Gắn tag", {
            val tags = input.text.toString().split(',')
            if (TagNormalizer.normalizeAll(tags).isEmpty()) {
                input.error = "Nhập ít nhất một tag"
            } else {
                hideKeyboard()
                sheet.close()
                exitSelection()
                runDb {
                    val changed = repository.addTags(ids, tags)
                    runOnUi {
                        refresh()
                        showMessage("Đã gắn tag cho $changed mục")
                    }
                }
            }
        }, primary = true)
        sheet.body.addView(apply, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(MV_TOUCH)).apply {
            topMargin = dp(16)
        })
        openSheet(sheet)
        input.requestFocus()
        getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * Deletion is staged: items disappear immediately, and the files are removed only when the
     * Undo window closes (timeout, another message, or the activity stopping).
     */
    internal fun requestDelete(assets: List<Asset>) {
        if (assets.isEmpty()) return
        val ids = assets.map { it.id }
        if (detail?.asset?.id in ids) closeDetail()
        pendingDeletion.addAll(ids)
        exitSelection()
        applyView()
        // Stickers made from a deleted source stay in the library but lose re-editing; say so.
        val derivedKept = allAssets.count { it.sourceAssetId in ids && it.id !in ids }
        val message = buildString {
            append(if (assets.size == 1) "Đã xoá “${assets.first().displayName}”" else "Đã xoá ${assets.size} mục")
            if (derivedKept > 0) append(" · giữ $derivedKept sticker dẫn xuất")
        }
        snackbar.show(
            text = message,
            actionLabel = "Hoàn tác",
            durationMs = UNDO_WINDOW_MS,
            onAction = {
                pendingDeletion.removeAll(ids.toSet())
                applyView()
            },
            onDismissed = { commitDeletion(ids) },
        )
    }

    private fun commitDeletion(ids: List<String>) {
        runDb {
            val result = repository.delete(ids)
            runOnUiThread {
                pendingDeletion.removeAll(ids.toSet())
                if (isUiUnavailable()) return@runOnUiThread
                refresh()
                if (result.failedIds.isNotEmpty()) {
                    showMessage("Không xoá được ${result.failedIds.size} mục. Hãy thử lại.")
                }
            }
        }
    }

    // endregion

    // region Detail, sheets and back navigation

    private fun openDetail(asset: Asset) {
        if (detail != null) return
        hideKeyboard()
        detail = AssetDetailPanel(this, root, asset).also { it.show() }
        snackbar.view.bringToFront()
        updateBackCallback()
    }

    internal fun closeDetail() {
        detail?.close()
        detail = null
        updateBackCallback()
    }

    internal fun onAssetChanged(asset: Asset) {
        allAssets = allAssets.map { if (it.id == asset.id) asset else it }
        applyView()
    }

    /** Parks an image under "Ảnh nguồn" (hidden from the grid and keyboard) or brings it back. */
    internal fun setArchived(asset: Asset, archived: Boolean) {
        runDb {
            val updated = repository.setArchived(asset.id, archived)
            runOnUi {
                if (updated == null) {
                    showMessage(getString(R.string.archive_failed))
                    return@runOnUi
                }
                onAssetChanged(updated)
                snackbar.show(
                    text = getString(if (archived) R.string.archived_to_sources else R.string.restored_from_sources),
                    actionLabel = "Hoàn tác",
                    durationMs = 5_000L,
                    onAction = { setArchived(updated, !archived) },
                )
            }
        }
    }

    internal fun topTags(limit: Int): List<TagCount> = LibraryView.topTags(allAssets, limit, pendingDeletion)

    private fun openSheet(next: MvSheet) {
        sheet?.close()
        sheet = next
        next.show {
            if (sheet === next) sheet = null
            updateBackCallback()
        }
        snackbar.view.bringToFront()
        updateBackCallback()
    }

    private fun handleBack(): Boolean {
        when {
            sheet?.isShowing == true -> sheet?.close()
            detail != null -> closeDetail()
            selectionActive -> exitSelection()
            else -> return false
        }
        return true
    }

    private fun updateBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val needed = sheet?.isShowing == true || detail != null || selectionActive
        val current = backCallback
        if (needed && current == null) {
            val callback = OnBackInvokedCallback { handleBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backCallback = callback
        } else if (!needed && current != null) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(current)
            backCallback = null
        }
    }

    // Fallback for API 26–32 only. API 33+ (including predictive back on API 36) is handled by
    // the OnBackInvokedCallback registered in updateBackCallback(), so gestures are covered.
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Used below Android 13; newer versions route back through OnBackInvokedCallback.")
    override fun onBackPressed() {
        if (!handleBack()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // endregion

    // region Share, export and studio

    internal fun shareAsset(asset: Asset) {
        markUsed(asset.id)
        ShareManager.share(this, repository, asset, Destination.SYSTEM, shareExecutor, ::showMessage)
    }

    internal fun copyAsset(asset: Asset) {
        markUsed(asset.id)
        ShareManager.copy(this, repository, asset, shareExecutor, ::showMessage, ::showMessage)
    }

    internal fun shareAnimatedSticker(asset: Asset) {
        markUsed(asset.id)
        ShareManager.shareAnimatedSticker(this, repository, asset, shareExecutor, ::showMessage)
    }

    /**
     * Experimental host routes. Sticker hosts expose no stable native-sticker contract, so the
     * user picks the raster profile explicitly; the export format is never changed silently.
     */
    internal fun shareToHost(asset: Asset, destination: Destination) {
        if (isUiUnavailable()) return
        markUsed(asset.id)
        if (!MediaPolicy.needsStickerCompatibilityProfile(asset, destination)) {
            ShareManager.share(this, repository, asset, destination, shareExecutor, ::showMessage)
            return
        }
        val options = arrayOf(
            getString(R.string.sticker_share_profile_iphone),
            getString(R.string.sticker_share_profile_opaque),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.sticker_share_profile_title)
            .setItems(options) { _, which ->
                val profile = if (which == 1) StickerShareProfile.OPAQUE_512 else StickerShareProfile.TRANSPARENT_320
                ShareManager.share(this, repository, asset, destination, shareExecutor, ::showMessage, profile)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    internal fun openStickerStudio(source: Asset) {
        // A derived sticker whose source was deleted stays sendable but cannot be re-edited.
        if (source.type == AssetType.STICKER && source.sourceAssetId == null) {
            runDb {
                val sourceMissing = repository.findStickerRecipe(source.id) != null
                runOnUi {
                    if (sourceMissing) {
                        showMessage(getString(R.string.sticker_source_missing_detail))
                    } else {
                        launchStickerStudio(source)
                    }
                }
            }
            return
        }
        launchStickerStudio(source)
    }

    private fun launchStickerStudio(source: Asset) {
        startActivity(Intent(this, StickerStudioActivity::class.java).apply {
            if (source.type == AssetType.STICKER) {
                putExtra(StickerStudioActivity.EXTRA_EDIT_STICKER_ASSET_ID, source.id)
            } else {
                putExtra(StickerStudioActivity.EXTRA_SOURCE_ASSET_ID, source.id)
            }
            putExtra(StickerStudioActivity.EXTRA_QUICK_MODE, true)
        })
    }

    private fun openAssetPicker() {
        startActivity(Intent(this, AssetPickerActivity::class.java))
    }

    private fun markUsed(id: String) {
        runDb { repository.touchUsed(id) }
    }

    // endregion

    // region Import

    private fun showImportMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 0, 0, getString(R.string.import_from_device))
        menu.menu.add(0, 1, 1, getString(R.string.import_from_tenor))
        menu.setOnMenuItemClickListener { item ->
            if (item.itemId == 0) openPicker() else showLinkImportSheet()
            true
        }
        menu.show()
    }

    private fun openPicker() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/mp4"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }, REQUEST_PICK)
    }

    private fun showLinkImportSheet() {
        val sheet = MvSheet(root, getString(R.string.tenor_import_title))
        sheet.body.addView(TextView(this).apply {
            text = getString(R.string.tenor_import_message)
            applyMvTextStyle(13f, mvMuted, 5)
            setLineSpacing(dp(2).toFloat(), 1f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })
        val input = EditText(this).apply {
            hint = getString(R.string.tenor_import_hint)
            setSingleLine(true)
            textSize = 14f
            setTextColor(mvText)
            setHintTextColor(mvMuted)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(12), 0, dp(12), 0)
            background = roundedBackground(mvSurface, MV_RADIUS, mvLine)
            contentDescription = getString(R.string.import_from_tenor)
        }
        sheet.body.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(MV_TOUCH)))
        val progress = TextView(this).apply {
            applyMvTextStyle(13f, mvMuted, 3)
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        sheet.body.addView(progress)
        lateinit var action: Button
        val submit: () -> Unit = submit@{
            val rawUrl = input.text?.toString().orEmpty().trim()
            val validation = TenorUrlPolicy.parse(rawUrl)
            if (validation.isFailure) {
                input.error = validation.exceptionOrNull()?.message ?: getString(R.string.tenor_import_failed)
                return@submit
            }
            action.isEnabled = false
            input.isEnabled = false
            progress.visibility = View.VISIBLE
            progress.text = getString(R.string.tenor_import_loading)
            progress.setTextColor(mvMuted)
            runCatching {
                networkExecutor.execute {
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
                    runOnUi {
                        if (outcome.asset != null) {
                            hideKeyboard()
                            sheet.close()
                            showImportResult(
                                saved = if (outcome.duplicate) 0 else 1,
                                duplicate = if (outcome.duplicate) 1 else 0,
                                errors = 0,
                                restored = if (outcome.unarchived) 1 else 0,
                            )
                        } else {
                            action.isEnabled = true
                            input.isEnabled = true
                            progress.text = outcome.error ?: getString(R.string.tenor_import_failed)
                            progress.setTextColor(mvDanger)
                        }
                    }
                }
            }
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                submit()
                true
            } else {
                false
            }
        }
        action = mvButton(getString(R.string.tenor_import_action), { submit() }, primary = true, iconRes = R.drawable.ic_link)
        sheet.body.addView(action, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(MV_TOUCH)).apply {
            topMargin = dp(16)
        })
        openSheet(sheet)
        input.requestFocus()
        getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    @Deprecated("Platform Activity result API; this app has no AndroidX Activity dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (destroyed) return
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK || data == null) return
        val uris = buildList {
            data.data?.let(::add)
            data.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) add(clip.getItemAt(index).uri)
            }
        }.distinct()
        importUris(uris)
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_SEND && incoming?.action != Intent.ACTION_SEND_MULTIPLE) return
        importUris(IntentMediaExtractor.uris(incoming).filter { it.scheme == "content" })
    }

    private fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        snackbar.show(resources.getQuantityString(R.plurals.importing_media, uris.size, uris.size), durationMs = 60_000L)
        runDb {
            val outcomes = repository.importUris(uris)
            runOnUi {
                showImportResult(
                    saved = outcomes.count { it.asset != null && !it.duplicate },
                    duplicate = outcomes.count { it.duplicate },
                    errors = outcomes.count { it.error != null },
                    restored = outcomes.count { it.unarchived },
                )
            }
        }
    }

    private fun showImportResult(saved: Int, duplicate: Int, errors: Int, restored: Int = 0) {
        val message = buildString {
            append("Đã thêm $saved")
            if (duplicate > 0) append(" · trùng $duplicate")
            // A duplicate that was parked under "Ảnh nguồn" is back in the grid; say where it went.
            if (restored > 0) append(" · đưa $restored mục từ Ảnh nguồn về thư viện")
            if (errors > 0) append(" · lỗi $errors")
        }
        if (saved > 0) {
            // Show the new items where the user expects them: newest first, no filters.
            snackbar.show(message, "Xem", 5_000L, onAction = {
                prefs.sort = LibrarySort.ADDED
                typeFilter = LibraryTypeFilter.ALL
                tagScope = TagScope.Any
                if (search.text?.isNotEmpty() == true) search.setText("") else applyView(scrollToTop = true)
            })
        } else {
            showMessage(message)
        }
        refresh()
    }

    // endregion

    // region Overflow: stats, keyboard, experimental tools

    private fun showOverflowMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 0, 0, "Thống kê kho")
        menu.menu.add(0, 1, 1, "Bàn phím MemeVault")
        menu.menu.add(0, 2, 2, "Đồng bộ Zalo (thử nghiệm)")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> showStatsSheet()
                1 -> startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                2 -> startActivity(Intent(this, ZaloSyncActivity::class.java))
            }
            true
        }
        menu.show()
    }

    private fun showStatsSheet() {
        val sheet = MvSheet(root, "Thống kê kho")
        val table = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(mvSurface, MV_RADIUS, mvLine)
        }
        table.addView(TextView(this).apply {
            text = "Đang tính dung lượng…"
            applyMvTextStyle(13f, mvMuted, 1)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        })
        sheet.body.addView(table)
        val shortcuts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        shortcuts.addView(mvButton("Xem mục ít dùng", {
            sheet.close()
            prefs.sort = LibrarySort.LEAST_USED
            typeFilter = LibraryTypeFilter.ALL
            applyView(scrollToTop = true)
        }), LinearLayout.LayoutParams(0, dp(MV_TOUCH), 1f))
        shortcuts.addView(mvButton("Xem mục chưa có tag", {
            sheet.close()
            tagScope = TagScope.Untagged
            applyView(scrollToTop = true)
        }), LinearLayout.LayoutParams(0, dp(MV_TOUCH), 1f).apply { marginStart = dp(8) })
        sheet.body.addView(shortcuts, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(MV_TOUCH)).apply {
            topMargin = dp(12)
        })
        openSheet(sheet)
        runDb {
            val stats = runCatching { repository.stats() }
            runOnUi {
                table.removeAllViews()
                stats.onSuccess { value ->
                    val rows = buildList {
                        add("Tổng" to "${value.totalCount} mục · ${ByteFormat.format(value.originalBytes)}")
                        listOf(AssetType.MEME, AssetType.STICKER, AssetType.GIF, AssetType.VIDEO).forEach { type ->
                            val typeStats = value.byType[type]
                            add(type.label to "${typeStats?.count ?: 0} · ${ByteFormat.format(typeStats?.bytes ?: 0L)}")
                        }
                        add("Yêu thích" to "${value.favoriteCount}")
                        add("Chưa dùng lần nào" to "${value.neverUsedCount}")
                        add("Đã gắn tag" to "${value.taggedCount} / ${value.totalCount}")
                        add("Bộ nhớ đệm chia sẻ" to ByteFormat.format(value.variantBytes))
                        add("Mask sticker" to ByteFormat.format(value.maskBytes))
                    }
                    rows.forEachIndexed { index, (label, data) ->
                        if (index > 0) table.addView(mvDivider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
                        table.addView(statsRow(label, data), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
                    }
                }.onFailure { error ->
                    table.addView(TextView(this).apply {
                        text = error.message ?: "Không tính được thống kê"
                        applyMvTextStyle(13f, mvDanger, 3)
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                    })
                }
            }
        }
    }

    private fun statsRow(label: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(12), 0)
        addView(TextView(this@MainActivity).apply {
            text = label
            applyMvTextStyle(13f, mvMuted, 1)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@MainActivity).apply {
            text = value
            applyMvMonoStyle(13f, mvText)
        })
    }

    // endregion

    // region Infrastructure

    internal fun showMessage(message: String) {
        if (isUiUnavailable()) return
        snackbar.show(message)
    }

    /** DB work is never dropped on destroy: queued writes (e.g. staged deletions) still commit. */
    internal fun runDb(operation: () -> Unit) {
        runCatching { dbExecutor.execute(operation) }
    }

    internal fun runPreview(operation: () -> Unit): Future<*>? =
        runCatching { previewExecutor.submit(operation) }.getOrNull()

    internal fun runOnUi(block: () -> Unit) {
        runOnUiThread { if (!isUiUnavailable()) block() }
    }

    internal fun isUiUnavailable(): Boolean = destroyed || isFinishing || isDestroyed

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun configureSystemBars() {
        val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.TRANSPARENT
            window.isNavigationBarContrastEnforced = false
            // Access through the decor view: window.insetsController can throw before
            // setContentView on Android 11+.
            val lightBarFlags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.decorView.windowInsetsController?.setSystemBarsAppearance(
                if (night) 0 else lightBarFlags,
                lightBarFlags,
            )
        } else {
            @Suppress("DEPRECATION")
            run {
                window.statusBarColor = mvBackground
                window.navigationBarColor = mvBackground
                window.decorView.systemUiVisibility = if (night) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }
    }

    /** Pads the root by system bars and the soft keyboard so inputs and sheets stay visible. */
    private fun applySystemBarInsets(view: View) {
        view.setOnApplyWindowInsetsListener { target, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                target.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                target.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
            insets
        }
        view.requestApplyInsets()
    }

    override fun onDestroy() {
        destroyed = true
        refreshGeneration++
        pendingSearch?.let(mainHandler::removeCallbacks)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
        }
        dbExecutor.shutdown()
        shareExecutor.shutdownNow()
        networkExecutor.shutdownNow()
        previewExecutor.shutdownNow()
        Thread {
            runCatching { dbExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS) }
            runCatching { shareExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS) }
            runCatching { networkExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS) }
            runCatching { repository.close() }
        }.apply {
            name = "MemeVault-repository-close"
            isDaemon = true
            start()
        }
        super.onDestroy()
    }

    // endregion

    companion object {
        private const val REQUEST_PICK = 1001
        private const val KEY_QUERY = "library_query"
        private const val KEY_TYPE = "library_type"
        private const val KEY_TAG_SCOPE = "library_tag_scope"
        private const val KEY_TAG_LABEL = "library_tag_label"
        private const val KEY_SELECTION = "library_selection"
        private const val KEY_DETAIL = "library_detail"
        private const val SCOPE_UNTAGGED = "\u0000untagged"
        private const val SEARCH_DEBOUNCE_MS = 180L
        private const val UNDO_WINDOW_MS = 8_000L
        private const val TAG_STRIP_LIMIT = 20
        private const val FILTER_ANIMATION_MS = 160L
    }
}
