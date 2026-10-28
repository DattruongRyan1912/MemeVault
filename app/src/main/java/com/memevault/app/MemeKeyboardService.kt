package com.memevault.app

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.InputMethodManager
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

/**
 * Optional system keyboard surface. Apps that advertise compatible MIME types receive the
 * app-private content URI through InputConnection.commitContent; all other editors get a
 * clipboard fallback so the MVP remains useful without an IME integration.
 */
class MemeKeyboardService : InputMethodService() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val previewExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var repository: AssetRepository? = null
    private var inputRoot: LinearLayout? = null
    private var allAssets: List<Asset> = emptyList()
    private var activeTab = KeyboardTab.RECENT
    private var adapter: KeyboardAssetAdapter? = null
    private var tabViews: Map<KeyboardTab, TextView> = emptyMap()
    @Volatile
    private var destroyed = false
    @Volatile
    private var inputSessionGeneration = 0L
    @Volatile
    private var sensitiveEditor = false

    override fun onCreate() {
        super.onCreate()
        repository = AssetRepository(this)
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(mvBackground)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        inputRoot = root
        renderLoading(root)
        loadAssets(root)
        return root
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        sensitiveEditor = isSensitiveEditor(info)
        inputSessionGeneration++
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        inputRoot?.let { root ->
            renderLoading(root)
            loadAssets(root)
        }
    }

    private fun renderLoading(root: LinearLayout) {
        root.removeAllViews()
        addKeyboardToolbar(root)
        root.addView(TextView(this).apply {
            text = getString(R.string.keyboard_loading)
            applyMvTextStyle(13f, context.mvMuted, 2)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(14), dp(8), dp(14))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun loadAssets(root: LinearLayout) {
        val currentRepository = repository ?: return
        executor.execute {
            // Archived sticker sources live under "Ảnh nguồn" in the app only.
            val assets = runCatching { currentRepository.list().filterNot(Asset::archived) }.getOrDefault(emptyList())
            mainHandler.post {
                if (inputRoot !== root) return@post
                allAssets = assets
                renderAssets(root)
            }
        }
    }

    private fun renderAssets(root: LinearLayout) {
        root.removeAllViews()
        addKeyboardToolbar(root)

        if (sensitiveEditor) {
            root.addView(TextView(this).apply {
                text = getString(R.string.keyboard_sensitive_note)
                applyMvTextStyle(13f, context.mvMuted, 2)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(20), dp(16), dp(20))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            return
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.keyboard_search_unavailable)
            applyMvTextStyle(11f, context.mvMuted, 2)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(30),
        ))

        val tabsScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabViews = KeyboardTab.values().associateWith { tab ->
            TextView(this).apply {
                text = tab.label
                gravity = Gravity.CENTER
                applyMvTextStyle(12f, context.mvText, 1)
                isClickable = true
                isFocusable = true
                setPadding(dp(14), 0, dp(14), 0)
                contentDescription = "Lọc ${tab.label}"
                setOnClickListener {
                    activeTab = tab
                    updateTabStyles()
                    refreshResults()
                }
            }.also { view ->
                tabs.addView(view, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(48),
                ).apply { marginEnd = dp(6) })
            }
        }
        tabsScroll.addView(tabs, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        root.addView(tabsScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48),
        ))
        updateTabStyles()

        val recycler = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MemeKeyboardService, KEYBOARD_COLUMNS)
            clipToPadding = false
            setPadding(0, dp(2), 0, dp(2))
            contentDescription = getString(R.string.keyboard_content_description)
        }
        adapter = KeyboardAssetAdapter(
            context = this,
            previewExecutor = previewExecutor,
            previewHandler = mainHandler,
            onClick = ::commitAsset,
        ).also { recycler.adapter = it }
        root.addView(recycler, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        root.addView(TextView(this).apply {
            text = getString(R.string.keyboard_fallback_note)
            applyMvTextStyle(11f, context.mvMuted, 2)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)))
        refreshResults()
    }

    private fun addKeyboardToolbar(root: LinearLayout) {
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toolbar.addView(TextView(this).apply {
            text = getString(R.string.keyboard_name)
            applyMvTextStyle(13f, context.mvText, 1)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        if (!sensitiveEditor) {
            toolbar.addView(mvButton(
                getString(R.string.keyboard_create_sticker),
                { openStickerPicker() },
            ).apply {
                contentDescription = getString(R.string.keyboard_create_sticker_content_description)
                textSize = 12f
                setPadding(dp(8), 0, dp(8), 0)
            }, LinearLayout.LayoutParams(dp(112), dp(48)))
        }
        toolbar.addView(mvButton(
            getString(R.string.keyboard_switch),
            { switchKeyboard() },
        ).apply {
            contentDescription = getString(R.string.keyboard_switch_content_description)
            textSize = 12f
            setPadding(dp(10), 0, dp(10), 0)
        }, LinearLayout.LayoutParams(dp(120), dp(48)))
        root.addView(toolbar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48),
        ))
    }

    private fun updateTabStyles() {
        tabViews.forEach { (tab, view) ->
            val selected = tab == activeTab
            view.background = roundedBackground(
                if (selected) mvColor(R.color.mv_accent_soft) else mvSurface,
                radiusDp = 6,
                strokeColor = if (selected) mvAccent else mvLine,
            )
            view.setTextColor(if (selected) mvAccentText else mvText)
            view.contentDescription = if (selected) "Đang chọn ${tab.label}" else "Lọc ${tab.label}"
        }
    }

    private fun refreshResults() {
        val filtered = allAssets
            .asSequence()
            .filter { asset ->
                when (activeTab) {
                    KeyboardTab.RECENT -> true
                    KeyboardTab.FAVORITES -> asset.favorite
                    KeyboardTab.STICKERS -> asset.type == AssetType.STICKER
                    KeyboardTab.ANIMATED -> asset.isAnimatedMedia
                    KeyboardTab.ALL -> true
                }
            }
            .let { sequence ->
                when (activeTab) {
                    KeyboardTab.RECENT -> sequence.sortedWith(compareByDescending { it.lastUsedAt ?: it.createdAt })
                    KeyboardTab.STICKERS -> sequence.sortedWith(
                        compareByDescending<Asset> { it.lastUsedAt ?: it.createdAt },
                    )
                    KeyboardTab.ANIMATED -> sequence.sortedWith(
                        compareByDescending<Asset> { it.lastUsedAt ?: it.createdAt },
                    )
                    KeyboardTab.FAVORITES, KeyboardTab.ALL -> sequence.sortedWith(
                        compareByDescending<Asset> { it.favorite }
                            .thenByDescending { it.lastUsedAt ?: it.createdAt },
                    )
                }
            }
            .take(MAX_KEYBOARD_ASSETS)
            .toList()
        adapter?.submit(filtered)
    }

    /**
     * Return to the keyboard used before MemeVault when the platform can do so directly. The
     * picker remains available when there is no remembered/eligible keyboard or on older Android.
     */
    private fun switchKeyboard() {
        val switched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { switchToPreviousInputMethod() }.getOrDefault(false) ||
                runCatching { switchToNextInputMethod(false) }.getOrDefault(false)
        } else {
            val token = runCatching { getWindow().window?.attributes?.token }.getOrNull()
            token != null && runCatching {
                getSystemService(InputMethodManager::class.java)?.switchToLastInputMethod(token) == true
            }.getOrDefault(false)
        }
        if (!switched) {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            showToast(getString(R.string.keyboard_switch_picker))
        }
    }

    private fun openStickerPicker() {
        if (sensitiveEditor) return
        runCatching {
            startActivity(Intent(this, AssetPickerActivity::class.java).apply {
                action = AssetPickerActivity.ACTION_PICK_STICKER_SOURCE
                putExtra(AssetPickerActivity.EXTRA_RETURN_TO_HOST, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure { showToast("Không thể mở picker tạo sticker") }
    }

    private fun isSensitiveEditor(info: EditorInfo?): Boolean {
        val variation = info?.inputType?.and(InputType.TYPE_MASK_VARIATION) ?: return false
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun commitAsset(asset: Asset) {
        // Fail closed even if a stale tile click is dispatched after the editor changed to a
        // password/sensitive field and before the UI has been rebuilt.
        if (sensitiveEditor || isSensitiveEditor(currentInputEditorInfo)) return
        val currentRepository = repository ?: return
        val sessionGeneration = inputSessionGeneration
        val editor = currentInputEditorInfo
        val connection = currentInputConnection
        val editorPackage = editor?.packageName
        val editorFieldId = editor?.fieldId ?: 0
        val destination = destinationForEditor(editorPackage)
        val useAnimatedWebp = destination != Destination.TIKTOK &&
            MediaPolicy.canUseAnimatedStickerWebp(asset, editor?.contentMimeTypes)
        val needsPreparation = useAnimatedWebp ||
            (destination == Destination.TIKTOK && asset.type == AssetType.GIF) ||
            MediaPolicy.needsStickerCompatibilityProfile(asset, destination)
        if (needsPreparation) {
            val preparationFailureMessage = when {
                useAnimatedWebp -> getString(R.string.share_animated_sticker_failed)
                destination == Destination.TIKTOK && asset.type == AssetType.GIF ->
                    getString(R.string.keyboard_video_failed)
                else -> getString(R.string.keyboard_compat_failed)
            }
            showToast(
                when {
                    useAnimatedWebp -> getString(R.string.keyboard_preparing_sticker_webp)
                    destination == Destination.TIKTOK && asset.type == AssetType.GIF ->
                        getString(R.string.keyboard_preparing_video)
                    else -> getString(R.string.keyboard_preparing_compat)
                },
            )
            runCatching {
                executor.execute {
                    val webpAttempt = if (useAnimatedWebp) {
                        runCatching { currentRepository.sharedAnimatedSticker(asset) }
                    } else {
                        null
                    }
                    val shared = runCatching {
                        webpAttempt?.getOrElse { currentRepository.sharedAsset(asset, destination) }
                            ?: currentRepository.sharedAsset(asset, destination)
                    }
                    mainHandler.post {
                        if (!isCurrentInputSession(sessionGeneration, editorPackage, editorFieldId, connection) ||
                            repository !== currentRepository
                        ) return@post
                        shared.onSuccess {
                            if (useAnimatedWebp && it.mimeType != MediaPolicy.WEBP_MIME) {
                                showToast(
                                    getString(
                                        R.string.share_animated_sticker_fallback,
                                        webpAttempt?.exceptionOrNull()?.message
                                            ?: getString(R.string.share_animated_sticker_failed),
                                    ),
                                )
                            }
                            commitSharedAsset(
                                asset,
                                it,
                                currentRepository,
                                sessionGeneration,
                                editorPackage,
                                editorFieldId,
                                connection,
                                editor,
                            )
                        }
                            .onFailure { showToast(it.message ?: preparationFailureMessage) }
                    }
                }
            }.onFailure {
                showToast(it.message ?: preparationFailureMessage)
            }
            return
        }
        val shared = runCatching { currentRepository.sharedAsset(asset, destination) }
            .getOrElse {
                showToast(it.message ?: "Không thể mở media")
                return
            }
        commitSharedAsset(
            asset,
            shared,
            currentRepository,
            sessionGeneration,
            editorPackage,
            editorFieldId,
            connection,
            editor,
        )
    }

    private fun commitSharedAsset(
        asset: Asset,
        shared: SharedAsset,
        currentRepository: AssetRepository,
        sessionGeneration: Long,
        editorPackage: String?,
        editorFieldId: Int,
        connection: InputConnection?,
        editor: EditorInfo?,
    ) {
        // The preparation callback can outlive the previous input view. Never send a URI or copy
        // anything to the clipboard once the current editor is sensitive.
        if (sensitiveEditor || isSensitiveEditor(currentInputEditorInfo)) return
        if (!isCurrentInputSession(sessionGeneration, editorPackage, editorFieldId, connection)) return
        val canCommit = connection != null &&
            MediaPolicy.canCommitContent(editor?.contentMimeTypes, shared.mimeType)

        if (canCommit) {
            // End any composing text before committing rich content so an IME switch does not
            // leave the host editor with a dangling composing span.
            runCatching { connection!!.finishComposingText() }
            val content = InputContentInfo(
                shared.uri,
                ClipDescription(shared.displayName, arrayOf(shared.mimeType)),
                null,
            )
            val committed = runCatching {
                connection!!.commitContent(
                    content,
                    InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                    null,
                )
            }.getOrDefault(false)
            if (committed) {
                markUsed(currentRepository, asset.id)
                showToast("Đã chèn ${asset.displayName}")
                return
            }
        }

        copyToClipboard(asset, shared)
    }

    private fun destinationForEditor(editorPackage: String?): Destination = when (editorPackage) {
        ZALO_PACKAGE -> Destination.ZALO
        TIKTOK_PACKAGE, TIKTOK_LEGACY_PACKAGE -> Destination.TIKTOK
        else -> Destination.SYSTEM
    }

    private fun isCurrentInputSession(
        generation: Long,
        editorPackage: String?,
        editorFieldId: Int,
        connection: InputConnection?,
    ): Boolean {
        if (destroyed || generation != inputSessionGeneration) return false
        val currentEditor = currentInputEditorInfo ?: return editorPackage == null && connection == null
        if (currentEditor.packageName != editorPackage || currentEditor.fieldId != editorFieldId) return false
        return connection == null || currentInputConnection === connection
    }

    private fun copyToClipboard(asset: Asset, shared: SharedAsset) {
        if (sensitiveEditor || isSensitiveEditor(currentInputEditorInfo)) return
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        if (clipboard == null) {
            showToast("Clipboard không khả dụng")
            return
        }
        clipboard.setPrimaryClip(ClipData.newUri(contentResolver, shared.displayName, shared.uri))
        repository?.let { markUsed(it, asset.id) }
        showToast("Đã copy ${asset.displayName} • dán vào ô nhập")
    }

    private fun markUsed(currentRepository: AssetRepository, id: String) {
        runCatching { executor.execute { currentRepository.touchUsed(id) } }
    }

    private fun showToast(message: String) {
        if (destroyed) return
        mainHandler.post {
            if (!destroyed && repository != null) {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        destroyed = true
        inputSessionGeneration++
        inputRoot = null
        val currentRepository = repository
        repository = null
        executor.shutdown()
        previewExecutor.shutdownNow()
        Thread {
            runCatching { executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS) }
            runCatching { currentRepository?.close() }
        }.apply {
            name = "MemeVault-keyboard-repository-close"
            isDaemon = true
            start()
        }
        super.onDestroy()
    }

    private enum class KeyboardTab(val label: String) {
        RECENT("Gần đây"),
        FAVORITES("Yêu thích"),
        STICKERS("Sticker"),
        ANIMATED("Động"),
        ALL("Tất cả"),
    }

    private class KeyboardAssetAdapter(
        private val context: MemeKeyboardService,
        private val previewExecutor: ExecutorService,
        private val previewHandler: Handler,
        private val onClick: (Asset) -> Unit,
    ) : ListAdapter<Asset, KeyboardAssetAdapter.Holder>(DIFF) {
        init {
            setHasStableIds(true)
        }

        fun submit(next: List<Asset>) = submitList(next)

        override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val tile = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
                background = context.roundedBackground(context.mvSurface, radiusDp = 6, strokeColor = context.mvLine)
                isClickable = true
                isFocusable = true
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    context.dp(88),
                ).apply {
                    setMargins(context.dp(2), context.dp(2), context.dp(2), context.dp(2))
                }
            }
            val image = android.widget.ImageView(context).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setBackgroundColor(context.mvSurfaceAlt)
            }
            tile.addView(image, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.dp(62),
            ))
            val label = TextView(context).apply {
                applyMvTextStyle(10f, context.mvText, 1)
                gravity = Gravity.CENTER
                setPadding(context.dp(2), context.dp(3), context.dp(2), 0)
            }
            tile.addView(label, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.dp(20),
            ))
            return Holder(tile, image, label)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(getItem(position))
        }

        override fun onViewRecycled(holder: Holder) {
            holder.image.tag = null
            holder.image.setImageDrawable(null)
            super.onViewRecycled(holder)
        }

        inner class Holder(
            private val tile: LinearLayout,
            val image: android.widget.ImageView,
            private val label: TextView,
        ) : RecyclerView.ViewHolder(tile) {
            fun bind(asset: Asset) {
                tile.tag = asset
                tile.contentDescription = "Chèn ${asset.displayName}"
                tile.setOnClickListener { (it.tag as? Asset)?.let(onClick) }
                label.text = asset.displayName
                image.tag = asset.id
                image.setImageDrawable(null)
                image.scaleType = if (asset.type == AssetType.STICKER) {
                    android.widget.ImageView.ScaleType.CENTER_INSIDE
                } else {
                    android.widget.ImageView.ScaleType.CENTER_CROP
                }
                image.background = if (asset.type == AssetType.STICKER) {
                    CheckerboardDrawable(context.dp(7), context)
                } else {
                    context.roundedBackground(context.mvSurfaceAlt, radiusDp = 6)
                }
                previewExecutor.execute {
                    val bitmap = MediaPreviewLoader.decode(File(asset.originalPath), asset.mimeType, context.dp(120))
                    previewHandler.post {
                        if (image.tag == asset.id && bitmap != null) {
                            image.setImageBitmap(bitmap)
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
        private const val KEYBOARD_COLUMNS = 4
        private const val MAX_KEYBOARD_ASSETS = 40
        private const val ZALO_PACKAGE = "com.zing.zalo"
        private const val TIKTOK_PACKAGE = "com.zhiliaoapp.musically"
        private const val TIKTOK_LEGACY_PACKAGE = "com.ss.android.ugc.trill"
    }
}
