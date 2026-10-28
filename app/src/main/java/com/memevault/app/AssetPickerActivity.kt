package com.memevault.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Small, non-exported picker used by both the library and the IME. Keeping it separate from the
 * launcher activity avoids holding an old InputConnection or exposing a picker contract outside
 * MemeVault.
 */
class AssetPickerActivity : Activity() {
    private lateinit var repository: AssetRepository
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: PickerAdapter
    private lateinit var search: EditText
    private lateinit var status: TextView
    private lateinit var closeButton: android.widget.Button
    private lateinit var stickerFilterButton: android.widget.Button
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val previewExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val searchHandler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null
    private var refreshGeneration = 0L
    private var returnToHost = false
    private var stickerOnly = false
    private var savedAssetName: String? = null
    @Volatile
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = AssetRepository(this)
        returnToHost = savedInstanceState?.getBoolean(KEY_RETURN_TO_HOST)
            ?: intent.getBooleanExtra(EXTRA_RETURN_TO_HOST, false)
        stickerOnly = savedInstanceState?.getBoolean(KEY_STICKER_ONLY, false) ?: false
        savedAssetName = savedInstanceState?.getString(KEY_SAVED_ASSET_NAME)
        configureSystemBars()
        buildUi()
        savedInstanceState?.getString(KEY_QUERY)?.let { search.setText(it) }
        refresh()
        savedAssetName?.let(::showSavedState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_QUERY, search.text?.toString().orEmpty())
        outState.putBoolean(KEY_RETURN_TO_HOST, returnToHost)
        outState.putBoolean(KEY_STICKER_ONLY, stickerOnly)
        outState.putString(KEY_SAVED_ASSET_NAME, savedAssetName)
        super.onSaveInstanceState(outState)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(mvBackground)
            setPadding(0, dp(8), 0, dp(18))
        }
        applySystemBarInsets(root)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(mvIconButton(R.drawable.ic_arrow_back, getString(R.string.back), { finish() }),
            LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(TextView(this).apply {
            text = getString(R.string.sticker_picker_title)
            applyMvTextStyle(21f, context.mvText, 1)
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(header)

        root.addView(TextView(this).apply {
            text = getString(R.string.sticker_picker_subtitle)
            applyMvTextStyle(12f, context.mvMuted, 3)
            setPadding(dp(58), 0, 0, dp(8))
        })

        search = EditText(this).apply {
            hint = getString(R.string.sticker_picker_search_hint)
            setSingleLine(true)
            textSize = 15f
            setTextColor(mvText)
            setHintTextColor(mvMuted)
            setPadding(dp(14), 0, dp(14), 0)
            background = roundedBackground(mvSurface, radiusDp = 6, strokeColor = mvLine)
            contentDescription = getString(R.string.sticker_picker_search_content_description)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    pendingSearch?.let(searchHandler::removeCallbacks)
                    pendingSearch = Runnable { refresh() }.also {
                        searchHandler.postDelayed(it, SEARCH_DEBOUNCE_MS)
                    }
                }
            })
        }
        root.addView(search, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(50),
        ).apply { bottomMargin = dp(8) })

        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        stickerFilterButton = mvButton("", ::toggleStickerFilter).apply {
            contentDescription = getString(R.string.sticker_picker_filter_content_description)
            textSize = 12f
        }
        filterRow.addView(stickerFilterButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(42),
        ))
        filterRow.addView(TextView(this).apply {
            text = getString(R.string.sticker_picker_filter_hint)
            applyMvTextStyle(11f, context.mvMuted, 2)
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        root.addView(filterRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(42),
        ).apply { bottomMargin = dp(4) })
        updateStickerFilterButton()

        status = TextView(this).apply {
            applyMvTextStyle(12f, context.mvMuted, 2)
            setPadding(dp(2), dp(4), 0, dp(6))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        adapter = PickerAdapter(this, previewExecutor, mainHandler, ::selectAsset)
        recycler = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@AssetPickerActivity, 2)
            adapter = this@AssetPickerActivity.adapter
            itemAnimator = null
            clipToPadding = false
            setPadding(0, dp(2), 0, dp(8))
            contentDescription = getString(R.string.sticker_picker_content_description)
        }
        root.addView(recycler, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actionRow.addView(mvButton(getString(R.string.sticker_picker_import), {
            openImportPicker()
        }), LinearLayout.LayoutParams(0, dp(50), 1f))
        closeButton = mvButton(getString(R.string.sticker_picker_close), {
            finishWithResult()
        }, primary = true)
        closeButton.isEnabled = savedAssetName != null
        actionRow.addView(closeButton, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
            marginStart = dp(8)
        })
        root.addView(actionRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })

        setContentView(root)
    }

    private fun refresh() {
        if (destroyed) return
        val generation = ++refreshGeneration
        status.text = getString(R.string.status_loading)
        val query = search.text?.toString().orEmpty()
        executor.execute {
        val assets = runCatching {
                repository.list(AssetFilter.ALL, query)
                    .filter {
                        it.mimeType.startsWith("image/") && it.type != AssetType.GIF &&
                            (!stickerOnly || it.type == AssetType.STICKER)
                    }
            }.getOrDefault(emptyList())
            mainHandler.post {
                if (destroyed || generation != refreshGeneration) return@post
                adapter.submit(assets)
                status.text = resources.getQuantityString(R.plurals.asset_count, assets.size, assets.size)
            }
        }
    }

    /** [importedNow]: the image was imported by this picker only to become a sticker source. */
    private fun selectAsset(asset: Asset, importedNow: Boolean = false) {
        executor.execute {
            val recipe = runCatching { repository.findStickerRecipe(asset.id) }.getOrNull()
            mainHandler.post {
                if (destroyed) return@post
                if (asset.type == AssetType.STICKER && recipe != null && recipe.sourceAssetId == null) {
                    status.text = getString(R.string.sticker_source_missing_detail)
                    return@post
                }
                val intent = Intent(this, StickerStudioActivity::class.java).apply {
                    if (asset.type == AssetType.STICKER && recipe != null) {
                        putExtra(StickerStudioActivity.EXTRA_EDIT_STICKER_ASSET_ID, asset.id)
                    } else {
                        putExtra(StickerStudioActivity.EXTRA_SOURCE_ASSET_ID, asset.id)
                        putExtra(StickerStudioActivity.EXTRA_SOURCE_IMPORTED_NOW, importedNow)
                    }
                    putExtra(StickerStudioActivity.EXTRA_QUICK_MODE, true)
                    putExtra(StickerStudioActivity.EXTRA_ENTRY_POINT, StickerStudioActivity.ENTRY_POINT_PICKER)
                }
                startActivityForResult(intent, REQUEST_STUDIO)
            }
        }
    }

    private fun toggleStickerFilter() {
        stickerOnly = !stickerOnly
        updateStickerFilterButton()
        refresh()
    }

    private fun updateStickerFilterButton() {
        if (!::stickerFilterButton.isInitialized) return
        stickerFilterButton.text = if (stickerOnly) {
            getString(R.string.sticker_picker_filter_on)
        } else {
            getString(R.string.sticker_picker_filter_off)
        }
        stickerFilterButton.background = roundedBackground(
            if (stickerOnly) mvColor(R.color.mv_accent_soft) else mvSurface,
            radiusDp = 6,
            strokeColor = if (stickerOnly) mvAccent else mvLine,
        )
        stickerFilterButton.setTextColor(if (stickerOnly) mvAccentText else mvText)
        stickerFilterButton.contentDescription = if (stickerOnly) {
            getString(R.string.sticker_picker_filter_on_content_description)
        } else {
            getString(R.string.sticker_picker_filter_content_description)
        }
    }

    @Suppress("DEPRECATION")
    private fun openImportPicker() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        }, REQUEST_IMPORT)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (destroyed || resultCode != RESULT_OK) return
        when (requestCode) {
            REQUEST_IMPORT -> {
                val uri = data?.data ?: return
                status.text = getString(R.string.sticker_picker_importing)
                executor.execute {
                    val outcome = repository.importUri(uri, keepArchived = true)
                    mainHandler.post {
                        if (destroyed) return@post
                        if (outcome.asset != null) {
                            selectAsset(outcome.asset, importedNow = !outcome.duplicate)
                        } else {
                            status.text = outcome.error ?: getString(R.string.sticker_picker_import_failed)
                        }
                    }
                }
            }
            REQUEST_STUDIO -> {
                val name = data?.getStringExtra(StickerStudioActivity.EXTRA_SAVED_ASSET_NAME)
                if (name != null) showSavedState(name) else refresh()
            }
        }
    }

    private fun showSavedState(name: String) {
        savedAssetName = name
        closeButton.isEnabled = true
        closeButton.text = if (returnToHost) {
            getString(R.string.sticker_picker_return_to_host)
        } else {
            getString(R.string.sticker_picker_close)
        }
        status.text = getString(R.string.sticker_picker_saved, name)
    }

    private fun finishWithResult() {
        setResult(if (savedAssetName != null) RESULT_OK else RESULT_CANCELED, Intent().apply {
            savedAssetName?.let { putExtra(StickerStudioActivity.EXTRA_SAVED_ASSET_NAME, it) }
        })
        finish()
    }

    private fun configureSystemBars() {
        val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            window.isNavigationBarContrastEnforced = false
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

    private fun applySystemBarInsets(root: View) {
        val left = dp(16)
        val top = dp(8)
        val right = dp(16)
        val bottom = dp(18)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(
                    left + insets.systemWindowInsetLeft,
                    top + insets.systemWindowInsetTop,
                    right + insets.systemWindowInsetRight,
                    bottom + insets.systemWindowInsetBottom,
                )
            }
            insets
        }
        root.requestApplyInsets()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        destroyed = true
        pendingSearch?.let(searchHandler::removeCallbacks)
        // Let an accepted import/query finish before Room is closed. UI callbacks already check
        // destroyed, so draining here preserves data without reviving the old Activity instance.
        executor.shutdown()
        previewExecutor.shutdownNow()
        val currentRepository = repository
        Thread {
            runCatching { executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS) }
            runCatching { currentRepository.close() }
        }.apply {
            name = "MemeVault-picker-repository-close"
            isDaemon = true
            start()
        }
        super.onDestroy()
    }

    private class PickerAdapter(
        private val context: AssetPickerActivity,
        private val previewExecutor: ExecutorService,
        private val previewHandler: Handler,
        private val onClick: (Asset) -> Unit,
    ) : RecyclerView.Adapter<PickerAdapter.Holder>() {
        private var items: List<Asset> = emptyList()

        fun submit(next: List<Asset>) {
            items = next
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
                background = context.roundedBackground(context.mvSurface, radiusDp = 6, strokeColor = context.mvLine)
                isClickable = true
                isFocusable = true
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(context.dp(4), context.dp(4), context.dp(4), context.dp(4)) }
            }
            val preview = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(context.mvSurfaceAlt)
            }
            card.addView(preview, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.dp(150),
            ))
            val label = TextView(context).apply {
                applyMvTextStyle(13f, context.mvText, 1)
                setPadding(0, context.dp(8), 0, 0)
            }
            card.addView(label)
            return Holder(card, preview, label)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

        inner class Holder(
            private val card: LinearLayout,
            private val preview: ImageView,
            private val label: TextView,
        ) : RecyclerView.ViewHolder(card) {
            fun bind(asset: Asset) {
                card.tag = asset
                card.contentDescription = "Chọn ${asset.displayName}"
                card.setOnClickListener { (it.tag as? Asset)?.let(onClick) }
                label.text = asset.displayName
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
                    context.roundedBackground(context.mvSurfaceAlt, radiusDp = 6)
                }
                previewExecutor.execute {
                    val bitmap = MediaPreviewLoader.decode(File(asset.originalPath), asset.mimeType, context.dp(240))
                    previewHandler.post {
                        if (!context.destroyed && preview.tag == asset.id && bitmap != null) {
                            preview.setImageBitmap(bitmap)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_PICK_STICKER_SOURCE = "com.memevault.app.action.PICK_STICKER_SOURCE"
        const val EXTRA_RETURN_TO_HOST = "return_to_host"
        private const val REQUEST_IMPORT = 3001
        private const val REQUEST_STUDIO = 3002
        private const val KEY_QUERY = "picker_query"
        private const val KEY_RETURN_TO_HOST = "picker_return_to_host"
        private const val KEY_STICKER_ONLY = "picker_sticker_only"
        private const val KEY_SAVED_ASSET_NAME = "picker_saved_asset_name"
        private const val SEARCH_DEBOUNCE_MS = 180L
    }
}
