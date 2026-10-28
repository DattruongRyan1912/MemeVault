package com.memevault.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.window.OnBackInvokedCallback
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max
import kotlin.math.roundToInt

class StickerStudioActivity : Activity() {
    private lateinit var repository: AssetRepository
    private lateinit var preview: ImageView
    private lateinit var status: TextView
    private lateinit var thresholdLabel: TextView
    private lateinit var chooseButton: Button
    private lateinit var cropButton: Button
    private lateinit var segmentButton: Button
    private lateinit var saveButton: Button
    private lateinit var shareButton: Button
    private lateinit var copyButton: Button
    private lateinit var cancelButton: Button
    private lateinit var compareButton: Button
    private lateinit var refineButton: Button
    private lateinit var thresholdSeekBar: SeekBar
    private lateinit var borderLabel: TextView
    private lateinit var borderPalette: LinearLayout
    private lateinit var customColorButton: Button
    private lateinit var borderThicknessSeekBar: SeekBar
    private lateinit var tagsInput: EditText
    private lateinit var returnButton: Button
    private lateinit var sourcePrefs: StickerSourcePreferences
    private lateinit var sourceCard: LinearLayout
    private lateinit var sourceChipRow: LinearLayout
    private lateinit var sourceHint: TextView
    private val sourceChips = linkedMapOf<SourceAfterSave, TextView>()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private var engine: MlKitBackgroundRemovalEngine? = null
    private var currentAsset: Asset? = null
    private var editingStickerId: String? = null
    /** Full decoded source; sourceBitmap is the current crop passed to segmentation/rendering. */
    private var originalSourceBitmap: Bitmap? = null
    private var sourceBitmap: Bitmap? = null
    private var cropRect: CropRect? = null
    private var pendingCropRect: CropRect? = null
    private var confidenceMask: FloatArray? = null
    private var renderedSticker: Bitmap? = null
    private var savedAsset: Asset? = null
    private var rendererId: String = MlKitBackgroundRemovalEngine.ENGINE_ID
    private var rendererVersion: String = MlKitBackgroundRemovalEngine.ENGINE_VERSION
    private var sourceMissing = false
    /** The source was imported in this Studio/picker session just to become a sticker. */
    private var sourceImportedNow = false
    /** Stickers already derived from the current source; unknown counts as "some". */
    private var sourceOtherDerived = Int.MAX_VALUE
    /** The source was deleted after a save; the sticker stays shareable but is no longer editable. */
    private var sourceDeleted = false
    private var sourceDeleteBlockedHint = false
    private var refineDialog: Dialog? = null
    private var refineEditor: MaskEditorView? = null
    /** The current mask carries brush edits that re-segmenting or re-cropping would discard. */
    private var maskEdited = false
    private var resegmentConfirmUntil = 0L
    private var reopenRefineAfterRestore = false
    private var threshold = DEFAULT_THRESHOLD
    private var paddingFraction = StickerRenderer.DEFAULT_PADDING_FRACTION
    private var borderColor: Int? = null
    private var borderThickness = StickerBorderPolicy.DEFAULT_THICKNESS
    private var showingSourcePreview = false
    private var busy = false
    private var operationGeneration = 0L
    private var activeTask: Future<*>? = null
    private val runningOperations = Collections.synchronizedSet(mutableSetOf<Long>())
    private val operationLock = Any()
    private val retiredBitmaps = Collections.synchronizedList(mutableListOf<Bitmap>())
    @Volatile
    private var cancelRequested = false
    @Volatile
    private var closeResourcesWhenIdle = false
    private var backCallback: OnBackInvokedCallback? = null
    private lateinit var draftStore: StudioDraftStore
    private var restoringDraftState = false
    private var pendingSavedAssetId: String? = null
    private var shouldClearDraftWhenIdle = false

    private val borderOptions = listOf(
        null to R.string.sticker_border_none,
        Color.WHITE to R.string.sticker_border_white,
        Color.BLACK to R.string.sticker_border_black,
        Color.rgb(239, 68, 68) to R.string.sticker_border_red,
        Color.rgb(245, 158, 11) to R.string.sticker_border_yellow,
        Color.rgb(50, 91, 255) to R.string.sticker_border_blue,
        Color.rgb(236, 72, 153) to R.string.sticker_border_pink,
        Color.rgb(34, 197, 94) to R.string.sticker_border_green,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = AssetRepository(this)
        sourcePrefs = StickerSourcePreferences(this)
        StudioDraftStore.cleanupStale(cacheDir)
        draftStore = StudioDraftStore(
            cacheDir,
            savedInstanceState?.getString(KEY_DRAFT_ID) ?: UUID.randomUUID().toString(),
        )
        restoringDraftState = savedInstanceState != null
        pendingSavedAssetId = savedInstanceState?.getString(KEY_SAVED_ASSET_ID)
        rendererId = savedInstanceState?.getString(KEY_RENDERER_ID) ?: rendererId
        rendererVersion = savedInstanceState?.getString(KEY_RENDERER_VERSION) ?: rendererVersion
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            backCallback = OnBackInvokedCallback { handleBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                backCallback!!,
            )
        }
        configureSystemBars()
        buildUi()
        pendingCropRect = readCropRect(savedInstanceState)
        threshold = savedInstanceState?.getFloat(KEY_THRESHOLD, threshold)
            ?.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
            ?: threshold
        paddingFraction = savedInstanceState?.getFloat(KEY_PADDING_FRACTION, paddingFraction)
            ?.coerceIn(0f, 0.2f)
            ?: paddingFraction
        thresholdSeekBar.progress = (threshold * 100f).roundToInt()
        updateThresholdLabel()
        if (savedInstanceState?.containsKey(KEY_BORDER_COLOR) == true) {
            borderColor = savedInstanceState.getInt(KEY_BORDER_COLOR)
        }
        borderThickness = savedInstanceState?.getInt(KEY_BORDER_THICKNESS, borderThickness)
            ?.let(StickerBorderPolicy::clampThickness)
            ?: borderThickness
        savedInstanceState?.getString(KEY_TAGS)?.let { tagsInput.setText(it) }
        updateBorderControls()
        val editId = savedInstanceState?.getString(KEY_EDIT_STICKER_ID)
            ?: intent.getStringExtra(EXTRA_EDIT_STICKER_ASSET_ID)
        val sourceId = savedInstanceState?.getString(KEY_SOURCE_ASSET_ID)
            ?: intent.getStringExtra(EXTRA_SOURCE_ASSET_ID)
        sourceImportedNow = savedInstanceState?.getBoolean(KEY_SOURCE_IMPORTED_NOW)
            ?: intent.getBooleanExtra(EXTRA_SOURCE_IMPORTED_NOW, false)
        sourceDeleted = savedInstanceState?.getBoolean(KEY_SOURCE_DELETED, false) ?: false
        maskEdited = savedInstanceState?.getBoolean(KEY_MASK_EDITED, false) ?: false
        reopenRefineAfterRestore = savedInstanceState?.getBoolean(KEY_REFINE_OPEN, false) ?: false
        if (sourceDeleted) {
            restoreAfterSourceDeleted(pendingSavedAssetId)
        } else if (editId != null) {
            editingStickerId = editId
            loadExistingSticker(editId)
        } else {
            sourceId?.let(::loadExistingAsset)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        currentAsset?.id?.let { outState.putString(KEY_SOURCE_ASSET_ID, it) }
        editingStickerId?.let { outState.putString(KEY_EDIT_STICKER_ID, it) }
        outState.putFloat(KEY_THRESHOLD, threshold)
        outState.putFloat(KEY_PADDING_FRACTION, paddingFraction)
        borderColor?.let { outState.putInt(KEY_BORDER_COLOR, it) }
        outState.putInt(KEY_BORDER_THICKNESS, borderThickness)
        outState.putString(KEY_TAGS, tagsInput.text.toString())
        outState.putString(KEY_DRAFT_ID, draftStore.id)
        outState.putString(KEY_RENDERER_ID, rendererId)
        outState.putString(KEY_RENDERER_VERSION, rendererVersion)
        savedAsset?.id?.let { outState.putString(KEY_SAVED_ASSET_ID, it) }
        outState.putBoolean(KEY_SOURCE_IMPORTED_NOW, sourceImportedNow)
        outState.putBoolean(KEY_SOURCE_DELETED, sourceDeleted)
        outState.putBoolean(KEY_MASK_EDITED, maskEdited)
        // Rotation recreates the activity and the editor dialog with it; keep the strokes (not the
        // undo history) and reopen the editor once the source and mask are restored.
        refineEditor?.session?.let { session ->
            runCatching { draftStore.saveRefine(session.snapshot(), session.width, session.height) }
                .onSuccess { outState.putBoolean(KEY_REFINE_OPEN, true) }
        }
        // Persist an explicit full-image selection too. Without this third state (no override /
        // explicit full / concrete crop), reopening a cropped recipe would silently reapply its
        // old crop after the user pressed “Toàn ảnh” and the activity was recreated.
        val savedCrop = cropRect ?: pendingCropRect
            ?: originalSourceBitmap?.let { CropRect.full(it.width, it.height) }
        if (savedCrop != null) {
            outState.putBoolean(KEY_CROP_ACTIVE, true)
            outState.putInt(KEY_CROP_LEFT, savedCrop.left)
            outState.putInt(KEY_CROP_TOP, savedCrop.top)
            outState.putInt(KEY_CROP_RIGHT, savedCrop.right)
            outState.putInt(KEY_CROP_BOTTOM, savedCrop.bottom)
        }
        super.onSaveInstanceState(outState)
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(mvBackground)
            setPadding(0, dp(8), 0, dp(18))
        }
        applySystemBarInsets(root)
        scroll.addView(root)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(mvIconButton(R.drawable.ic_arrow_back, getString(R.string.back), {
            if (savedAsset != null) finishWithResult() else finish()
        }),
            LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(TextView(this).apply {
            text = getString(R.string.sticker_studio_title)
            applyMvTextStyle(22f, context.mvText, 1)
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        cancelButton = mvButton(getString(R.string.cancel), { cancelProcessing() })
        cancelButton.isEnabled = false
        header.addView(cancelButton, LinearLayout.LayoutParams(dp(76), dp(48)))
        root.addView(header)
        root.addView(TextView(this).apply {
            text = getString(R.string.sticker_studio_subtitle)
            applyMvTextStyle(13f, context.mvMuted, 3)
            setPadding(dp(58), 0, 0, dp(10))
        })

        status = TextView(this).apply {
            text = getString(R.string.sticker_studio_choose_hint)
            applyMvTextStyle(13f, context.mvText, 3)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBackground(mvColor(R.color.mv_accent_soft), radiusDp = 6)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(12) })

        val previewFrame = FrameLayout(this).apply {
            background = CheckerboardDrawable(dp(16), this@StickerStudioActivity)
        }
        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.sticker_preview_content_description)
        }
        previewFrame.addView(preview, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        root.addView(previewFrame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(320),
        ).apply { bottomMargin = dp(10) })

        val previewTools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        compareButton = mvButton(getString(R.string.sticker_compare), { toggleCompare() })
        compareButton.isEnabled = false
        previewTools.addView(compareButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        refineButton = mvButton(getString(R.string.sticker_refine), { openRefineEditor() }, iconRes = R.drawable.ic_brush)
        refineButton.isEnabled = false
        refineButton.contentDescription = getString(R.string.sticker_refine_content_description)
        previewTools.addView(refineButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginStart = dp(8)
        })
        root.addView(previewTools)
        root.addView(TextView(this).apply {
            text = getString(R.string.sticker_refine_hint)
            applyMvTextStyle(12f, context.mvMuted, 3)
            setPadding(dp(2), dp(4), 0, 0)
        })

        val chooseRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chooseButton = mvButton(getString(R.string.sticker_choose_image), { openImagePicker() })
        chooseRow.addView(chooseButton, LinearLayout.LayoutParams(0, dp(50), 1f))
        cropButton = mvButton(getString(R.string.sticker_crop), { openCropEditor() })
        cropButton.isEnabled = false
        cropButton.contentDescription = getString(R.string.sticker_crop_content_description)
        chooseRow.addView(cropButton, LinearLayout.LayoutParams(0, dp(50), 0.95f).apply {
            marginStart = dp(8)
        })
        segmentButton = mvButton(getString(R.string.sticker_remove_background), { segmentCurrentImage() }, primary = true)
        segmentButton.isEnabled = false
        chooseRow.addView(segmentButton, LinearLayout.LayoutParams(0, dp(50), 1.05f).apply {
            marginStart = dp(8)
        })
        root.addView(chooseRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })

        val thresholdCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(8))
            background = roundedBackground(mvSurface, radiusDp = 6, strokeColor = mvLine)
        }
        thresholdLabel = TextView(this).apply {
            applyMvTextStyle(13f, context.mvText, 1)
        }
        thresholdCard.addView(thresholdLabel)
        thresholdSeekBar = SeekBar(this).apply {
            max = 90
            progress = (threshold * 100f).roundToInt()
            isEnabled = false
            contentDescription = getString(R.string.sticker_threshold_content_description)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    threshold = (progress / 100f).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
                    updateThresholdLabel()
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit

                override fun onStopTrackingTouch(bar: SeekBar?) {
                    rerenderFromMask()
                }
            })
        }
        thresholdCard.addView(thresholdSeekBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(thresholdCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) })
        updateThresholdLabel()

        val borderCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(8))
            background = roundedBackground(mvSurface, radiusDp = 6, strokeColor = mvLine)
        }
        borderLabel = TextView(this).apply {
            applyMvTextStyle(13f, context.mvText, 1)
        }
        borderCard.addView(borderLabel)
        val borderScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        borderPalette = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        borderScroll.addView(borderPalette, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        borderCard.addView(borderScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(58),
        ).apply { topMargin = dp(2) })
        customColorButton = mvButton(getString(R.string.sticker_border_custom), {
            openCustomColorDialog()
        })
        customColorButton.contentDescription = getString(R.string.sticker_border_custom_content_description)
        borderPalette.addView(customColorButton, LinearLayout.LayoutParams(dp(112), dp(48)).apply {
            marginStart = dp(6)
        })
        borderThicknessSeekBar = SeekBar(this).apply {
            max = StickerBorderPolicy.MAX_THICKNESS
            progress = borderThickness
            isEnabled = false
            contentDescription = getString(R.string.sticker_border_thickness_content_description)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    borderThickness = StickerBorderPolicy.clampThickness(progress)
                    updateBorderControls()
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit

                override fun onStopTrackingTouch(bar: SeekBar?) {
                    if (borderColor != null) rerenderFromMask()
                }
            })
        }
        borderCard.addView(borderThicknessSeekBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(borderCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) })
        buildBorderPalette()
        updateBorderControls()

        val tagsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedBackground(mvSurface, radiusDp = 6, strokeColor = mvLine)
        }
        tagsCard.addView(TextView(this).apply {
            text = getString(R.string.asset_tags_title)
            applyMvTextStyle(13f, context.mvText, 1)
        })
        tagsInput = EditText(this).apply {
            hint = getString(R.string.asset_tags_hint)
            setSingleLine(true)
            textSize = 14f
            setTextColor(mvText)
            setHintTextColor(mvMuted)
            contentDescription = getString(R.string.asset_tags_content_description)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBackground(mvSurface, radiusDp = 6, strokeColor = mvLine)
        }
        tagsCard.addView(tagsInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48),
        ).apply { topMargin = dp(6) })
        tagsCard.addView(mvButton(getString(R.string.asset_tags_suggest), {
            showTagSuggestions()
        }), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
            topMargin = dp(6)
        })
        tagsCard.addView(TextView(this).apply {
            text = getString(R.string.asset_tags_hint_detail)
            applyMvTextStyle(11f, context.mvMuted, 2)
            setPadding(dp(2), dp(4), 0, 0)
        })
        root.addView(tagsCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) })

        sourceCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedBackground(mvSurface, radiusDp = 6, strokeColor = mvLine)
            visibility = View.GONE
        }
        sourceCard.addView(TextView(this).apply {
            text = getString(R.string.sticker_source_title)
            applyMvTextStyle(13f, context.mvText, 1)
        })
        sourceChipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(
            SourceAfterSave.KEEP to R.string.sticker_source_keep_chip,
            SourceAfterSave.ARCHIVE to R.string.sticker_source_archive_chip,
            SourceAfterSave.DELETE to R.string.sticker_source_delete_chip,
        ).forEach { (option, labelRes) ->
            val chip = mvChip(getString(labelRes)) { selectSourceAction(option) }
            sourceChips[option] = chip
            sourceChipRow.addView(chip, LinearLayout.LayoutParams(
                0,
                dp(MV_TOUCH),
                1f,
            ).apply { if (option != SourceAfterSave.KEEP) marginStart = dp(6) })
        }
        sourceCard.addView(sourceChipRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(MV_TOUCH),
        ).apply { topMargin = dp(4) })
        sourceHint = TextView(this).apply {
            applyMvTextStyle(12f, context.mvMuted, 4)
            setPadding(dp(2), dp(4), 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        sourceCard.addView(sourceHint)
        root.addView(sourceCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) })

        val saveRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        saveButton = mvButton(getString(R.string.sticker_save), { saveSticker() }, primary = true)
        saveButton.isEnabled = false
        saveRow.addView(saveButton, LinearLayout.LayoutParams(0, dp(50), 1f))
        shareButton = mvButton(getString(R.string.sticker_share), { shareSticker() })
        shareButton.isEnabled = false
        saveRow.addView(shareButton, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
            marginStart = dp(8)
        })
        copyButton = mvButton(getString(R.string.sticker_copy), { copySticker() })
        copyButton.isEnabled = false
        saveRow.addView(copyButton, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
            marginStart = dp(8)
        })
        root.addView(saveRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) })

        returnButton = mvButton(getString(R.string.sticker_return_to_host), {
            finishWithResult()
        })
        returnButton.isEnabled = false
        root.addView(returnButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(50),
        ).apply { topMargin = dp(8) })

        root.addView(TextView(this).apply {
            text = getString(R.string.sticker_studio_privacy_note)
            applyMvTextStyle(12f, context.mvMuted, 3)
            setPadding(0, dp(14), 0, 0)
        })
        setContentView(scroll)
    }

    @Suppress("DEPRECATION")
    private fun openImagePicker() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        }, REQUEST_PICK_IMAGE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        importSource(uri)
    }

    private fun importSource(uri: Uri) {
        val generation = ++operationGeneration
        editingStickerId = null
        maskEdited = false
        pendingCropRect = null
        replaceDraftStore()
        restoringDraftState = false
        pendingSavedAssetId = null
        activeTask?.cancel(true)
        activeTask = null
        clearWorkingState()
        synchronized(operationLock) { cancelRequested = false }
        setBusy(getString(R.string.sticker_importing_original))
        activeTask = submitOperation(generation) task@{
            val outcome = repository.importUri(uri, keepArchived = true)
            val asset = outcome.asset
            if (asset == null) {
                postFailure(generation, outcome.error ?: getString(R.string.sticker_import_failed))
                return@task
            }
            bindSourceAsset(generation, asset, outcome.duplicate, importedNow = !outcome.duplicate)
        }
    }

    private fun loadExistingAsset(id: String) {
        val importedNow = sourceImportedNow
        val generation = ++operationGeneration
        activeTask?.cancel(true)
        activeTask = null
        clearWorkingState()
        synchronized(operationLock) { cancelRequested = false }
        setBusy(getString(R.string.sticker_opening_existing))
        activeTask = submitOperation(generation) load@{
            val asset = repository.findById(id)
            if (asset == null) {
                postFailure(generation, getString(R.string.sticker_source_missing))
                return@load
            }
            if (asset.type == AssetType.GIF || !asset.mimeType.startsWith("image/")) {
                postFailure(generation, getString(R.string.sticker_static_only))
                return@load
            }
            val bitmap = StickerImageLoader.decode(File(asset.originalPath))
            if (bitmap == null) {
                postFailure(generation, getString(R.string.sticker_decode_failed))
                return@load
            }
            bindSourceAsset(
                generation,
                asset,
                duplicate = false,
                importedNow = importedNow,
                bitmap = bitmap,
                tagSeed = asset.tags,
            )
        }
    }

    private fun loadExistingSticker(id: String) {
        val generation = ++operationGeneration
        activeTask?.cancel(true)
        activeTask = null
        clearWorkingState()
        synchronized(operationLock) { cancelRequested = false }
        setBusy(getString(R.string.sticker_opening_existing))
        activeTask = submitOperation(generation) task@{
            val sticker = repository.findById(id)
            val recipe = repository.findStickerRecipe(id)
            val sourceId = recipe?.sourceAssetId ?: sticker?.sourceAssetId
            // Recipe-backed stickers remain editable only while their source asset exists. A
            // manually imported image typed as STICKER has no recipe and can still be used as a
            // new source; deleting a derived sticker's source must not silently use the sticker
            // PNG as a replacement source.
            val source = if (recipe != null) sourceId?.let(repository::findById) else {
                sourceId?.let(repository::findById) ?: sticker
            }
            if (sticker == null || source == null) {
                sourceMissing = true
                postFailure(generation, getString(R.string.sticker_source_missing))
                return@task
            }
            if (recipe != null && !StickerRecipe.supports(recipe)) {
                postFailure(generation, getString(R.string.sticker_recipe_incompatible))
                return@task
            }
            if (recipe != null) {
                val sourceHashMatches = runCatching {
                    Hashing.sha256(File(source.originalPath)) == recipe.sourceHash
                }.getOrDefault(false)
                if (!sourceHashMatches) {
                    postFailure(generation, getString(R.string.sticker_recipe_incompatible))
                    return@task
                }
            }
            if (source.type == AssetType.GIF || !source.mimeType.startsWith("image/")) {
                postFailure(generation, getString(R.string.sticker_static_only))
                return@task
            }
            val decoded = StickerImageLoader.decode(File(source.originalPath))
            if (decoded == null) {
                postFailure(generation, getString(R.string.sticker_decode_failed))
                return@task
            }
            val cropOverride = pendingCropRect
            val recipeCanvasMatches = recipe == null ||
                (recipe.cropSourceWidth == null && recipe.cropSourceHeight == null) ||
                (recipe.cropSourceWidth == decoded.width && recipe.cropSourceHeight == decoded.height)
            val recipeCrop = recipe?.cropRect?.takeIf { recipeCanvasMatches }
            val requestedCrop = cropOverride ?: recipeCrop
            val prepared = runCatching {
                prepareWorkingBitmap(decoded, requestedCrop)
            }.getOrElse { error ->
                releaseBitmap(decoded)
                postFailure(generation, error.message ?: getString(R.string.sticker_crop_invalid))
                return@task
            }
            val workingDecoded = prepared.first
            val appliedCrop = prepared.second
            val restoreState = restoringDraftState
            val draftMask = if (restoreState) {
                draftStore.loadMask(workingDecoded.width, workingDecoded.height)
            } else {
                null
            }
            val cropMatchesRecipe = recipe == null || (recipeCanvasMatches && sameCrop(
                appliedCrop,
                recipe.cropRect,
                decoded.width,
                decoded.height,
            ))
            if (recipe != null && cropMatchesRecipe &&
                (recipe.workingWidth != workingDecoded.width || recipe.workingHeight != workingDecoded.height)
            ) {
                releaseDecodedPair(decoded, workingDecoded)
                postFailure(generation, getString(R.string.sticker_recipe_incompatible))
                return@task
            }
            var restoredMask: FloatArray? = draftMask
            var restoredSticker: Bitmap? = null
            val shouldRestoreMask = restoredMask == null && recipe != null && recipeCanvasMatches &&
                (cropOverride == null || cropMatchesRecipe)
            if (shouldRestoreMask && recipe?.maskRelativePath != null &&
                recipe.maskWidth == workingDecoded.width && recipe.maskHeight == workingDecoded.height
            ) {
                val maskFile = recipe.maskRelativePath?.let(repository::maskFile)
                restoredMask = runCatching {
                    if (maskFile == null) null
                    else if (recipe.maskHash != null && Hashing.sha256(maskFile) != recipe.maskHash) null
                    else MaskCodec.read(maskFile, recipe.maskWidth, recipe.maskHeight)
                }.getOrNull()
            }
            if (restoredMask != null) {
                val renderThreshold = if (restoreState) threshold else recipe?.threshold ?: threshold
                val renderBorderColor = if (restoreState) borderColor else recipe?.borderColorArgb
                val renderBorderThickness = if (restoreState) {
                    borderThickness
                } else {
                    recipe?.borderThicknessPx ?: borderThickness
                }
                val renderPadding = if (restoreState) paddingFraction else recipe?.paddingFraction ?: paddingFraction
                restoredSticker = runCatching {
                    StickerRenderer.render(
                        source = workingDecoded,
                        confidenceMask = restoredMask!!,
                        threshold = renderThreshold,
                        borderColor = renderBorderColor,
                        borderThickness = renderBorderThickness,
                        paddingFraction = renderPadding,
                    )
                }.getOrNull()
            }
            val restoredSavedAsset = if (restoreState) {
                pendingSavedAssetId?.let(repository::findById)
            } else {
                null
            }
            val derived = runCatching { repository.countDerivedAssets(source.id) }.getOrDefault(Int.MAX_VALUE)
            runOnUiThread ui@{
                if (!isCurrent(generation)) {
                    releaseDecodedPair(decoded, workingDecoded)
                    restoredSticker?.takeUnless(Bitmap::isRecycled)?.recycle()
                    return@ui
                }
                currentAsset = source
                sourceImportedNow = false
                sourceOtherDerived = derived
                originalSourceBitmap = decoded
                sourceBitmap = workingDecoded
                cropRect = appliedCrop
                pendingCropRect = null
                sourceMissing = false
                confidenceMask = restoredMask
                if (!restoreState) {
                    rendererId = recipe?.rendererId ?: rendererId
                    rendererVersion = recipe?.rendererVersion ?: rendererVersion
                }
                if (!restoreState) recipe?.let {
                    threshold = it.threshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
                    borderColor = it.borderColorArgb
                    borderThickness = StickerBorderPolicy.clampThickness(it.borderThicknessPx)
                    paddingFraction = it.paddingFraction.coerceIn(0f, 0.2f)
                    thresholdSeekBar.progress = (threshold * 100f).roundToInt()
                }
                if (!restoreState) tagsInput.setText(sticker.tags.joinToString(", "))
                restoringDraftState = false
                pendingSavedAssetId = null
                savedAsset = restoredSavedAsset
                preview.setImageBitmap(restoredSticker ?: workingDecoded)
                if (restoredSticker != null) {
                    replaceRenderedSticker(restoredSticker)
                    compareButton.isEnabled = true
                    compareButton.text = getString(R.string.sticker_compare_source)
                    status.text = getString(R.string.sticker_recipe_restored)
                } else {
                    status.text = if (recipe != null && !recipeCanvasMatches && cropOverride == null) {
                        getString(R.string.sticker_recipe_canvas_changed)
                    } else {
                        getString(R.string.sticker_mask_missing)
                    }
                }
                activeTask = null
                updateThresholdLabel()
                updateBorderControls()
                setIdle()
                reopenRefineEditorIfRestoring()
            }
        }
    }

    private fun bindSourceAsset(
        generation: Long,
        asset: Asset,
        duplicate: Boolean,
        importedNow: Boolean,
        bitmap: Bitmap? = null,
        tagSeed: List<String> = asset.tags,
    ) {
        if (asset.type == AssetType.GIF || !asset.mimeType.startsWith("image/")) {
            postFailure(generation, getString(R.string.sticker_static_only))
            return
        }
        val decoded = bitmap ?: StickerImageLoader.decode(File(asset.originalPath))
        if (decoded == null) {
            postFailure(generation, getString(R.string.sticker_decode_failed))
            return
        }
        val prepared = runCatching {
            prepareWorkingBitmap(decoded, pendingCropRect)
        }.getOrElse { error ->
            releaseBitmap(decoded)
            postFailure(generation, error.message ?: getString(R.string.sticker_crop_invalid))
            return
        }
        val workingDecoded = prepared.first
        val appliedCrop = prepared.second
        val restoreState = restoringDraftState
        val restoredMask = if (restoreState) {
            draftStore.loadMask(workingDecoded.width, workingDecoded.height)
        } else {
            null
        }
        val restoredSticker = restoredMask?.let { mask ->
            runCatching {
                StickerRenderer.render(
                    source = workingDecoded,
                    confidenceMask = mask,
                    threshold = threshold,
                    borderColor = borderColor,
                    borderThickness = borderThickness,
                    paddingFraction = paddingFraction,
                )
            }.getOrNull()
        }
        val restoredSavedAsset = if (restoreState) {
            pendingSavedAssetId?.let(repository::findById)
        } else {
            null
        }
        val derived = runCatching { repository.countDerivedAssets(asset.id) }.getOrDefault(Int.MAX_VALUE)
        runOnUiThread ui@{
            if (!isCurrent(generation)) {
                releaseDecodedPair(decoded, workingDecoded)
                return@ui
            }
            currentAsset = asset
            sourceImportedNow = importedNow
            sourceOtherDerived = derived
            originalSourceBitmap = decoded
            sourceBitmap = workingDecoded
            cropRect = appliedCrop
            pendingCropRect = null
            sourceMissing = false
            confidenceMask = restoredMask
            savedAsset = restoredSavedAsset
            activeTask = null
            showingSourcePreview = false
            if (!restoreState) tagsInput.setText(tagSeed.joinToString(", "))
            restoringDraftState = false
            pendingSavedAssetId = null
            if (restoredSticker != null) {
                replaceRenderedSticker(restoredSticker)
                preview.setImageBitmap(restoredSticker)
                compareButton.isEnabled = true
                compareButton.text = getString(R.string.sticker_compare_source)
                status.text = getString(R.string.sticker_recipe_restored)
            } else {
                compareButton.isEnabled = false
                preview.setImageBitmap(workingDecoded)
                status.text = if (duplicate) {
                    getString(R.string.sticker_original_duplicate)
                } else {
                    getString(R.string.sticker_ready_to_remove)
                }
            }
            setIdle()
            reopenRefineEditorIfRestoring()
        }
    }

    private fun openCropEditor() {
        if (busy || sourceMissing) return
        val fullSource = originalSourceBitmap ?: return
        val cropView = CropEditorView(this).apply {
            setBitmap(fullSource)
            setSelection(cropRect)
            contentDescription = getString(R.string.sticker_crop_content_description)
            minimumHeight = dp(300)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            addView(cropView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(360),
            ))
            addView(TextView(this@StickerStudioActivity).apply {
                text = getString(R.string.sticker_crop_hint)
                applyMvTextStyle(12f, context.mvMuted, 3)
                setPadding(0, dp(8), 0, dp(2))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            if (maskEdited) {
                addView(TextView(this@StickerStudioActivity).apply {
                    text = getString(R.string.sticker_crop_discards_refine)
                    applyMvTextStyle(12f, context.mvWarningInk, 3)
                    setPadding(0, dp(4), 0, dp(2))
                })
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.sticker_crop_title)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.sticker_crop_reset, null)
            .setPositiveButton(R.string.sticker_crop, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                cropView.resetSelection()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = cropView.selectedBitmapRect()
                if (selected == null || !selected.isValidFor(fullSource.width, fullSource.height)) {
                    Toast.makeText(this, R.string.sticker_crop_invalid, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                applyCrop(fullSource, selected)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun applyCrop(fullSource: Bitmap, selected: CropRect) {
        val normalized = selected.takeUnless { it.isFull(fullSource.width, fullSource.height) }
        val working = runCatching {
            if (normalized == null) fullSource
            else Bitmap.createBitmap(
                fullSource,
                normalized.left,
                normalized.top,
                normalized.width,
                normalized.height,
            )
        }.getOrElse {
            Toast.makeText(this, R.string.sticker_crop_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        val previousSource = sourceBitmap
        replaceDraftStore()
        restoringDraftState = false
        pendingSavedAssetId = null
        sourceBitmap = working
        cropRect = normalized
        confidenceMask = null
        maskEdited = false
        savedAsset = null
        clearRenderedPreview()
        if (previousSource != null && previousSource !== originalSourceBitmap && previousSource !== working) {
            releaseBitmap(previousSource)
        }
        preview.setImageBitmap(working)
        status.text = if (normalized == null) {
            getString(R.string.sticker_crop_reset_applied)
        } else {
            getString(R.string.sticker_crop_applied, normalized.width, normalized.height)
        }
        setIdle()
    }

    private fun prepareWorkingBitmap(fullSource: Bitmap, requested: CropRect?): Pair<Bitmap, CropRect?> {
        val selected = requested ?: CropRect.full(fullSource.width, fullSource.height)
        require(selected.isValidFor(fullSource.width, fullSource.height)) {
            getString(R.string.sticker_crop_invalid)
        }
        if (selected.isFull(fullSource.width, fullSource.height)) return fullSource to null
        return Bitmap.createBitmap(
            fullSource,
            selected.left,
            selected.top,
            selected.width,
            selected.height,
        ) to selected
    }

    private fun sameCrop(first: CropRect?, second: CropRect?, sourceWidth: Int, sourceHeight: Int): Boolean {
        val firstFull = first ?: CropRect.full(sourceWidth, sourceHeight)
        val secondFull = second ?: CropRect.full(sourceWidth, sourceHeight)
        return firstFull == secondFull
    }

    private fun releaseDecodedPair(fullSource: Bitmap, workingSource: Bitmap) {
        releaseBitmap(workingSource)
        if (workingSource !== fullSource) releaseBitmap(fullSource)
    }

    private fun segmentCurrentImage() {
        val source = sourceBitmap ?: return
        // Re-segmenting replaces the mask; with brush edits, ask for a second tap instead of a dialog.
        if (maskEdited && SystemClock.uptimeMillis() > resegmentConfirmUntil) {
            resegmentConfirmUntil = SystemClock.uptimeMillis() + CONFIRM_WINDOW_MS
            status.text = getString(R.string.sticker_resegment_confirm)
            return
        }
        resegmentConfirmUntil = 0L
        savedAsset = null
        clearRenderedPreview()
        val generation = ++operationGeneration
        val operationDraftStore = draftStore
        setBusy(getString(R.string.sticker_segmenting))
        activeTask = submitOperation(generation) {
            val result = runCatching {
                (engine ?: MlKitBackgroundRemovalEngine(this@StickerStudioActivity).also { engine = it })
                    .segment(source)
            }
            val rendered = result.mapCatching { segmentation ->
                val sticker = StickerRenderer.render(
                    source = source,
                    confidenceMask = segmentation.confidenceMask,
                    threshold = threshold,
                    borderColor = borderColor,
                    borderThickness = borderThickness,
                    paddingFraction = paddingFraction,
                )
                try {
                    operationDraftStore.saveMask(
                        segmentation.confidenceMask,
                        source.width,
                        source.height,
                    )
                } catch (error: Throwable) {
                    sticker.recycle()
                    throw error
                }
                sticker
            }
            runOnUiThread ui@{
                if (!isCurrent(generation)) {
                    rendered.getOrNull()?.recycle()
                    return@ui
                }
                rendered.onSuccess { sticker ->
                    activeTask = null
                    confidenceMask = result.getOrThrow().confidenceMask
                    maskEdited = false
                    rendererId = result.getOrThrow().engineId
                    rendererVersion = result.getOrThrow().engineVersion
                    replaceRenderedSticker(sticker)
                    preview.setImageBitmap(sticker)
                    showingSourcePreview = false
                    compareButton.isEnabled = true
                    compareButton.text = getString(R.string.sticker_compare_source)
                    segmentButton.isEnabled = true
                    saveButton.isEnabled = true
                    shareButton.isEnabled = false
                    copyButton.isEnabled = false
                    findThresholdSeekBar()?.isEnabled = true
                    chooseButton.isEnabled = true
                    status.text = getString(
                        R.string.sticker_segment_success,
                        result.getOrThrow().elapsedMs,
                    )
                    setIdle()
                }.onFailure { error ->
                    activeTask = null
                    setIdle()
                    status.text = userFacingError(error)
                }
            }
        }
    }

    private fun rerenderFromMask() {
        val source = sourceBitmap ?: return
        val mask = confidenceMask ?: return
        savedAsset = null
        clearRenderedPreview()
        val generation = ++operationGeneration
        setBusy(getString(R.string.sticker_updating_preview))
        activeTask = submitOperation(generation) {
            val result = runCatching {
                StickerRenderer.render(
                    source = source,
                    confidenceMask = mask,
                    threshold = threshold,
                    borderColor = borderColor,
                    borderThickness = borderThickness,
                    paddingFraction = paddingFraction,
                )
            }
            runOnUiThread ui@{
                if (!isCurrent(generation)) {
                    result.getOrNull()?.recycle()
                    return@ui
                }
                result.onSuccess { sticker ->
                    activeTask = null
                    replaceRenderedSticker(sticker)
                    showingSourcePreview = false
                    compareButton.isEnabled = true
                    compareButton.text = getString(R.string.sticker_compare_source)
                    preview.setImageBitmap(sticker)
                    setIdle()
                    status.text = getString(R.string.sticker_preview_updated)
                }.onFailure { error ->
                    activeTask = null
                    setIdle()
                    status.text = userFacingError(error)
                }
            }
        }
    }

    private fun saveSticker() {
        val sticker = renderedSticker ?: return
        val source = currentAsset ?: return
        val mask = confidenceMask ?: return
        val maskWidth = sourceBitmap?.width ?: return
        val maskHeight = sourceBitmap?.height ?: return
        val cropSourceWidth = originalSourceBitmap?.width ?: maskWidth
        val cropSourceHeight = originalSourceBitmap?.height ?: maskHeight
        val tags = tagsInput.text.toString().split(',')
        val selectedThreshold = threshold
        val selectedPaddingFraction = paddingFraction
        val selectedBorderColor = borderColor
        val selectedBorderThickness = borderThickness
        val selectedRendererId = rendererId
        val selectedRendererVersion = rendererVersion
        val importedNow = sourceImportedNow
        val sourceAction = StickerSourcePolicy.effective(
            sourcePrefs.afterSave,
            StickerSourcePolicy.canDelete(importedNow, sourceOtherDerived),
        )
        val generation = ++operationGeneration
        setBusy(getString(R.string.sticker_saving))
        activeTask = submitOperation(generation) {
            val temporary = File(cacheDir, "sticker-${UUID.randomUUID()}.png")
            val outcome = runCatching {
                FileOutputStream(temporary).use { output ->
                    check(sticker.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        "Không mã hóa được PNG"
                    }
                }
                repository.createSticker(
                    StickerSaveRequest(
                        source = source,
                        renderedPng = temporary,
                        confidenceMask = mask,
                        maskWidth = maskWidth,
                        maskHeight = maskHeight,
                        rendererId = selectedRendererId,
                        rendererVersion = selectedRendererVersion,
                        threshold = selectedThreshold,
                        paddingFraction = selectedPaddingFraction,
                        borderColorArgb = selectedBorderColor,
                        borderThicknessPx = selectedBorderThickness,
                        displayName = stickerName(source.displayName),
                        tags = tags,
                        cropRect = cropRect,
                        cropSourceWidth = cropSourceWidth,
                        cropSourceHeight = cropSourceHeight,
                    ),
                )
            }.getOrElse { ImportOutcome(error = it.message ?: getString(R.string.sticker_save_failed)) }
            temporary.delete()
            // The source is touched only after the sticker row exists, so a failed save never
            // costs the user their original.
            val appliedSourceAction = if (outcome.asset != null) {
                applySourceAfterSave(source, sourceAction, importedNow)
            } else {
                SourceAfterSave.KEEP
            }
            runOnUiThread ui@{
                if (!isCurrent(generation)) return@ui
                outcome.asset?.let { asset ->
                    activeTask = null
                    savedAsset = asset
                    if (!outcome.duplicate && sourceOtherDerived != Int.MAX_VALUE) sourceOtherDerived++
                    when (appliedSourceAction) {
                        SourceAfterSave.ARCHIVE -> currentAsset = source.copy(archived = true)
                        SourceAfterSave.DELETE -> {
                            sourceDeleted = true
                            sourceMissing = true
                        }
                        SourceAfterSave.KEEP -> Unit
                    }
                    shareButton.isEnabled = true
                    copyButton.isEnabled = true
                    returnButton.isEnabled = true
                    setIdle()
                    val saved = if (outcome.duplicate) {
                        getString(R.string.sticker_saved_duplicate)
                    } else {
                        getString(R.string.sticker_saved)
                    }
                    status.text = when {
                        appliedSourceAction == SourceAfterSave.ARCHIVE && !source.archived ->
                            "$saved ${getString(R.string.sticker_saved_source_archived)}"
                        appliedSourceAction == SourceAfterSave.DELETE ->
                            "$saved ${getString(R.string.sticker_saved_source_deleted)}"
                        sourceAction != appliedSourceAction ->
                            "$saved ${getString(R.string.sticker_saved_source_kept)}"
                        else -> saved
                    }
                } ?: run {
                    activeTask = null
                    setIdle()
                    status.text = outcome.error ?: getString(R.string.sticker_save_failed)
                }
            }
        }
    }

    /** Runs on the executor right after a successful save; returns what actually happened. */
    private fun applySourceAfterSave(
        source: Asset,
        action: SourceAfterSave,
        importedNow: Boolean,
    ): SourceAfterSave = runCatching {
        when (action) {
            SourceAfterSave.KEEP -> SourceAfterSave.KEEP
            SourceAfterSave.ARCHIVE -> {
                if (source.archived || repository.setArchived(source.id, archived = true) != null) {
                    SourceAfterSave.ARCHIVE
                } else {
                    SourceAfterSave.KEEP
                }
            }
            SourceAfterSave.DELETE -> {
                val derived = repository.countDerivedAssets(source.id)
                if (StickerSourcePolicy.canDeleteAfterSave(importedNow, derived) && repository.delete(source.id)) {
                    SourceAfterSave.DELETE
                } else {
                    SourceAfterSave.KEEP
                }
            }
        }
    }.getOrDefault(SourceAfterSave.KEEP)

    private fun selectSourceAction(option: SourceAfterSave) {
        if (busy || sourceDeleted) return
        if (option == SourceAfterSave.DELETE &&
            !StickerSourcePolicy.canDelete(sourceImportedNow, sourceOtherDerived)
        ) {
            sourceDeleteBlockedHint = true
            renderSourceCard()
            return
        }
        sourceDeleteBlockedHint = false
        sourcePrefs.afterSave = option
        renderSourceCard()
    }

    private fun renderSourceCard() {
        if (!::sourceCard.isInitialized) return
        val source = currentAsset
        sourceCard.visibility = if (source != null || sourceDeleted) View.VISIBLE else View.GONE
        if (sourceDeleted) {
            sourceChipRow.visibility = View.GONE
            sourceHint.text = getString(R.string.sticker_source_deleted_state)
            return
        }
        sourceChipRow.visibility = View.VISIBLE
        val canDelete = StickerSourcePolicy.canDelete(sourceImportedNow, sourceOtherDerived)
        val choice = StickerSourcePolicy.effective(sourcePrefs.afterSave, canDelete)
        sourceChips.forEach { (option, chip) ->
            val selected = option == choice
            chip.styleMvChip(selected)
            // Delete stays tappable when unavailable so the hint can explain why.
            chip.alpha = if (option == SourceAfterSave.DELETE && !canDelete) 0.45f else 1f
            chip.contentDescription = chip.text.toString() + if (selected) ", đang chọn" else ""
        }
        sourceHint.text = when {
            sourceDeleteBlockedHint -> getString(R.string.sticker_source_delete_blocked)
            choice == SourceAfterSave.ARCHIVE && source?.archived == true ->
                getString(R.string.sticker_source_keep_archived)
            choice == SourceAfterSave.ARCHIVE -> getString(R.string.sticker_source_archive_hint)
            choice == SourceAfterSave.DELETE -> getString(R.string.sticker_source_delete_hint)
            source?.archived == true -> getString(R.string.sticker_source_keep_archived)
            else -> getString(R.string.sticker_source_keep_hint)
        }
    }

    /**
     * After process death following "Xoá ảnh gốc" there is no source to reload; restore only the
     * saved sticker so Share, Copy and Return keep working.
     */
    private fun restoreAfterSourceDeleted(savedId: String?) {
        sourceMissing = true
        val generation = ++operationGeneration
        setBusy(getString(R.string.sticker_opening_existing))
        activeTask = submitOperation(generation) {
            val saved = savedId?.let(repository::findById)
            val bitmap = saved?.let { StickerImageLoader.decode(File(it.originalPath)) }
            runOnUiThread ui@{
                if (!isCurrent(generation)) {
                    bitmap?.recycle()
                    return@ui
                }
                activeTask = null
                restoringDraftState = false
                pendingSavedAssetId = null
                savedAsset = saved
                if (bitmap != null) {
                    replaceRenderedSticker(bitmap)
                    preview.setImageBitmap(bitmap)
                }
                setIdle()
                status.text = getString(R.string.sticker_source_deleted_state)
            }
        }
    }

    private fun shareSticker() {
        val asset = savedAsset ?: return
        ShareManager.share(this, repository, asset, Destination.SYSTEM, executor) {
            showError(it)
        }
    }

    private fun copySticker() {
        val asset = savedAsset ?: return
        ShareManager.copy(this, repository, asset, executor, { showError(it) })
    }

    private fun finishWithResult() {
        val asset = savedAsset
        if (asset != null) {
            setResult(RESULT_OK, Intent().apply {
                putExtra(EXTRA_SAVED_ASSET_ID, asset.id)
                putExtra(EXTRA_SAVED_ASSET_NAME, asset.displayName)
            })
        }
        finish()
    }

    private fun toggleCompare() {
        val source = sourceBitmap ?: return
        val sticker = renderedSticker ?: return
        showingSourcePreview = !showingSourcePreview
        preview.setImageBitmap(if (showingSourcePreview) source else sticker)
        compareButton.text = if (showingSourcePreview) {
            getString(R.string.sticker_compare_result)
        } else {
            getString(R.string.sticker_compare_source)
        }
        status.text = if (showingSourcePreview) {
            getString(R.string.sticker_showing_original)
        } else {
            getString(R.string.sticker_showing_result)
        }
    }

    /**
     * Full-screen Keep/Erase editor over the working bitmap. It edits a copy of the mask; the
     * Studio mask is swapped only after Apply re-renders successfully, so a running render never
     * sees a half-painted array.
     */
    private fun openRefineEditor(restoredLevels: ByteArray? = null) {
        if (busy || sourceMissing || refineDialog != null) return
        val source = sourceBitmap ?: return
        val mask = confidenceMask ?: return
        val session = runCatching {
            MaskBrushSession(source.width, source.height, restoredLevels ?: MaskBrushSession.quantize(mask))
        }.getOrElse {
            status.text = getString(R.string.sticker_refine_failed)
            return
        }
        val editor = MaskEditorView(this, source, session, threshold).apply {
            background = CheckerboardDrawable(dp(16), this@StickerStudioActivity)
            contentDescription = getString(R.string.sticker_refine_canvas_description)
        }
        val dialog = Dialog(this, R.style.Theme_MemeVault)
        var discardConfirmUntil = 0L
        val message = TextView(this).apply {
            applyMvTextStyle(12f, context.mvWarningInk, 3)
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        fun showMessage(text: String) {
            message.text = text
            message.visibility = View.VISIBLE
        }
        val cancel = {
            if (session.hasEdits && SystemClock.uptimeMillis() > discardConfirmUntil) {
                discardConfirmUntil = SystemClock.uptimeMillis() + CONFIRM_WINDOW_MS
                showMessage(getString(R.string.sticker_refine_discard_confirm))
            } else {
                closeRefineEditor()
            }
        }
        val apply = apply@{
            if (!session.hasVisibleSubject(threshold)) {
                showMessage(getString(R.string.sticker_refine_empty))
                return@apply
            }
            val levels = if (session.hasEdits) session.snapshot() else null
            closeRefineEditor()
            if (levels != null) commitRefinedMask(levels)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(mvBackground)
        }
        applySystemBarInsets(root)
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topBar.addView(mvIconButton(R.drawable.ic_close, getString(R.string.cancel), { cancel() }),
            LinearLayout.LayoutParams(dp(48), dp(48)))
        topBar.addView(TextView(this).apply {
            text = getString(R.string.sticker_refine_title)
            applyMvTextStyle(18f, context.mvText, 1)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        topBar.addView(mvButton(getString(R.string.sticker_refine_apply), { apply() }, primary = true,
            iconRes = R.drawable.ic_check), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(MV_TOUCH)))
        root.addView(topBar)
        // Landscape moves the tools beside the canvas; stacked below they would leave a strip.
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (!landscape) {
            root.addView(TextView(this).apply {
                text = getString(R.string.sticker_refine_gestures)
                applyMvTextStyle(12f, context.mvMuted, 2)
                setPadding(dp(2), dp(2), 0, dp(8))
            })
        }
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tools.addView(message, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) })

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val modeChips = linkedMapOf<BrushMode, TextView>()
        lateinit var undoButton: ImageView
        lateinit var redoButton: ImageView
        fun renderTools() {
            modeChips.forEach { (mode, chip) ->
                val selected = mode == editor.mode
                chip.styleMvChip(selected)
                chip.contentDescription = chip.text.toString() + if (selected) ", đang chọn" else ""
            }
            undoButton.isEnabled = session.canUndo
            undoButton.alpha = if (session.canUndo) 1f else 0.35f
            redoButton.isEnabled = session.canRedo
            redoButton.alpha = if (session.canRedo) 1f else 0.35f
        }
        listOf(
            BrushMode.ERASE to R.string.sticker_refine_erase,
            BrushMode.KEEP to R.string.sticker_refine_keep,
        ).forEach { (mode, labelRes) ->
            val chip = mvChip(getString(labelRes)) {
                editor.mode = mode
                renderTools()
            }
            modeChips[mode] = chip
            modeRow.addView(chip, LinearLayout.LayoutParams(0, dp(MV_TOUCH), 1f).apply {
                if (mode == BrushMode.KEEP) marginStart = dp(6)
            })
        }
        undoButton = mvIconButton(R.drawable.ic_undo, getString(R.string.sticker_refine_undo), { editor.undo() })
        redoButton = mvIconButton(R.drawable.ic_redo, getString(R.string.sticker_refine_redo), { editor.redo() })
        modeRow.addView(undoButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
        modeRow.addView(redoButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        tools.addView(modeRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })

        val sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val sizeLabel = TextView(this).apply {
            applyMvTextStyle(13f, context.mvText, 1)
        }
        fun renderSize() {
            sizeLabel.text = getString(R.string.sticker_refine_size, editor.brushRadiusDp)
        }
        sizeRow.addView(sizeLabel, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
        sizeRow.addView(SeekBar(this).apply {
            max = MaskEditorView.MAX_RADIUS_DP - MaskEditorView.MIN_RADIUS_DP
            progress = editor.brushRadiusDp - MaskEditorView.MIN_RADIUS_DP
            contentDescription = getString(R.string.sticker_refine_size_content_description)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    editor.brushRadiusDp = MaskEditorView.MIN_RADIUS_DP + progress
                    renderSize()
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit

                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(0, dp(MV_TOUCH), 1f))
        tools.addView(sizeRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        if (landscape) {
            val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            body.addView(editor, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            body.addView(tools, LinearLayout.LayoutParams(dp(LANDSCAPE_TOOLS_DP), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginStart = dp(12)
            })
            root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(4)
            })
        } else {
            root.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(tools, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }

        editor.onHistoryChanged = {
            message.visibility = View.GONE
            discardConfirmUntil = 0L
            renderTools()
        }
        renderTools()
        renderSize()

        dialog.setContentView(root)
        dialog.setCancelable(false)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            dialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) { cancel() }
        } else {
            dialog.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) cancel()
                keyCode == KeyEvent.KEYCODE_BACK
            }
        }
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        refineDialog = dialog
        refineEditor = editor
        dialog.show()
    }

    private fun closeRefineEditor() {
        refineDialog?.dismiss()
        refineDialog = null
        refineEditor?.release()
        refineEditor = null
        draftStore.clearRefine()
    }

    /** Re-renders with the brushed mask; the Studio mask changes only if that render succeeds. */
    private fun commitRefinedMask(levels: ByteArray) {
        val source = sourceBitmap ?: return
        val selectedThreshold = threshold
        val selectedBorderColor = borderColor
        val selectedBorderThickness = borderThickness
        val selectedPadding = paddingFraction
        val operationDraftStore = draftStore
        val generation = ++operationGeneration
        setBusy(getString(R.string.sticker_refine_applying))
        activeTask = submitOperation(generation) {
            val edited = FloatArray(levels.size) { (levels[it].toInt() and 0xFF) / 255f }
            val result = runCatching {
                val sticker = StickerRenderer.render(
                    source = source,
                    confidenceMask = edited,
                    threshold = selectedThreshold,
                    borderColor = selectedBorderColor,
                    borderThickness = selectedBorderThickness,
                    paddingFraction = selectedPadding,
                )
                try {
                    operationDraftStore.saveMask(edited, source.width, source.height)
                } catch (error: Throwable) {
                    sticker.recycle()
                    throw error
                }
                sticker
            }
            runOnUiThread ui@{
                if (!isCurrent(generation)) {
                    result.getOrNull()?.recycle()
                    return@ui
                }
                activeTask = null
                result.onSuccess { sticker ->
                    confidenceMask = edited
                    maskEdited = true
                    savedAsset = null
                    replaceRenderedSticker(sticker)
                    preview.setImageBitmap(sticker)
                    showingSourcePreview = false
                    compareButton.text = getString(R.string.sticker_compare_source)
                    setIdle()
                    status.text = getString(R.string.sticker_refine_applied)
                }.onFailure { error ->
                    setIdle()
                    status.text = userFacingError(error)
                }
            }
        }
    }

    private fun reopenRefineEditorIfRestoring() {
        if (!reopenRefineAfterRestore) return
        reopenRefineAfterRestore = false
        val source = sourceBitmap ?: return
        if (confidenceMask == null) return
        draftStore.loadRefine(source.width, source.height)?.let(::openRefineEditor)
    }

    private fun setBusy(message: String) {
        busy = true
        status.text = message
        chooseButton.isEnabled = false
        cropButton.isEnabled = false
        segmentButton.isEnabled = false
        compareButton.isEnabled = false
        refineButton.isEnabled = false
        saveButton.isEnabled = false
        shareButton.isEnabled = false
        copyButton.isEnabled = false
        returnButton.isEnabled = false
        findThresholdSeekBar()?.isEnabled = false
        updateBorderControls()
        cancelButton.isEnabled = true
    }

    private fun setIdle() {
        busy = false
        chooseButton.isEnabled = true
        cropButton.isEnabled = originalSourceBitmap != null && !sourceMissing
        segmentButton.isEnabled = sourceBitmap != null && !sourceMissing
        compareButton.isEnabled = renderedSticker != null && sourceBitmap != null
        refineButton.isEnabled = confidenceMask != null && sourceBitmap != null && !sourceMissing
        saveButton.isEnabled = renderedSticker != null && !sourceMissing
        shareButton.isEnabled = savedAsset != null
        copyButton.isEnabled = savedAsset != null
        returnButton.isEnabled = savedAsset != null
        findThresholdSeekBar()?.isEnabled = confidenceMask != null && !sourceMissing
        updateBorderControls()
        renderSourceCard()
        cancelButton.isEnabled = false
    }

    private fun submitOperation(generation: Long, operation: () -> Unit): Future<*> {
        return executor.submit {
            // Track only work that actually started. A Future cancelled while queued
            // never executes its finally block, so bookkeeping must not be added before submit.
            synchronized(operationLock) { runningOperations += generation }
            try {
                if (isCurrent(generation)) operation()
            } finally {
                val becameIdle: Boolean
                val shouldReportCancelled: Boolean
                val shouldCloseResources: Boolean
                synchronized(operationLock) {
                    runningOperations -= generation
                    becameIdle = runningOperations.isEmpty()
                    shouldReportCancelled = becameIdle && cancelRequested
                    if (shouldReportCancelled) cancelRequested = false
                    shouldCloseResources = becameIdle && closeResourcesWhenIdle
                    if (shouldCloseResources) closeResourcesWhenIdle = false
                }
                if (becameIdle) {
                    recycleRetiredBitmaps()
                    if (shouldCloseResources) {
                        if (shouldClearDraftWhenIdle) {
                            draftStore.clear()
                            shouldClearDraftWhenIdle = false
                        }
                        runCatching { engine?.close() }
                        engine = null
                        runCatching { repository.close() }
                    }
                    if (shouldReportCancelled) {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                activeTask = null
                                setIdle()
                                status.text = getString(R.string.sticker_canceled)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun releaseBitmap(bitmap: Bitmap?) {
        val candidate = bitmap?.takeUnless(Bitmap::isRecycled) ?: return
        if (!hasRunningOperations()) {
            candidate.recycle()
        } else {
            retiredBitmaps += candidate
        }
    }

    private fun recycleRetiredBitmaps() {
        synchronized(retiredBitmaps) {
            retiredBitmaps.forEach { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            retiredBitmaps.clear()
        }
    }

    private fun cancelProcessing() {
        val task = activeTask
        activeTask = null
        operationGeneration++
        val hasRunning = synchronized(operationLock) {
            if (runningOperations.isEmpty()) {
                cancelRequested = false
                false
            } else {
                cancelRequested = true
                true
            }
        }
        if (!hasRunning) {
            task?.cancel(true)
            status.text = getString(R.string.sticker_canceled)
            setIdle()
            return
        }
        task?.cancel(true)
        chooseButton.isEnabled = false
        cropButton.isEnabled = false
        segmentButton.isEnabled = false
        compareButton.isEnabled = false
        refineButton.isEnabled = false
        saveButton.isEnabled = false
        shareButton.isEnabled = false
        copyButton.isEnabled = false
        returnButton.isEnabled = false
        findThresholdSeekBar()?.isEnabled = false
        cancelButton.isEnabled = false
        status.text = getString(R.string.sticker_canceling)
    }

    private fun clearWorkingState() {
        val previousSource = sourceBitmap
        val previousOriginalSource = originalSourceBitmap
        val previousSticker = renderedSticker
        originalSourceBitmap = null
        sourceBitmap = null
        cropRect = null
        confidenceMask = null
        renderedSticker = null
        savedAsset = null
        currentAsset = null
        sourceMissing = false
        showingSourcePreview = false
        preview.setImageDrawable(null)
        releaseBitmap(previousSource)
        if (previousOriginalSource !== previousSource) releaseBitmap(previousOriginalSource)
        releaseBitmap(previousSticker)
        cropButton.isEnabled = false
        segmentButton.isEnabled = false
        saveButton.isEnabled = false
        shareButton.isEnabled = false
        copyButton.isEnabled = false
        compareButton.isEnabled = false
        compareButton.text = getString(R.string.sticker_compare)
        refineButton.isEnabled = false
        findThresholdSeekBar()?.isEnabled = false
        updateBorderControls()
        cancelButton.isEnabled = false
    }

    private fun clearRenderedPreview() {
        val previous = renderedSticker
        renderedSticker = null
        showingSourcePreview = false
        compareButton.isEnabled = false
        compareButton.text = getString(R.string.sticker_compare)
        preview.setImageBitmap(sourceBitmap)
        if (previous != null && previous !== sourceBitmap) releaseBitmap(previous)
    }

    private fun replaceRenderedSticker(next: Bitmap) {
        val previous = renderedSticker
        renderedSticker = next
        if (previous != null && previous !== sourceBitmap && previous !== next) releaseBitmap(previous)
    }

    private fun postFailure(generation: Long, message: String) {
        runOnUiThread ui@{
            if (!isCurrent(generation)) return@ui
            activeTask = null
            setIdle()
            status.text = message
        }
    }

    private fun isCurrent(generation: Long): Boolean =
        generation == operationGeneration && !isFinishing && !isDestroyed

    private fun findThresholdSeekBar(): SeekBar? {
        return if (::thresholdSeekBar.isInitialized) thresholdSeekBar else null
    }

    private fun updateThresholdLabel() {
        if (!::thresholdLabel.isInitialized) return
        thresholdLabel.text = getString(R.string.sticker_threshold, (threshold * 100).roundToInt())
    }

    private fun buildBorderPalette() {
        borderPalette.removeAllViews()
        borderOptions.forEach { (color, labelRes) ->
            val label = getString(labelRes)
            val swatch = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 17f
                contentDescription = label
                isClickable = true
                isFocusable = true
                setOnClickListener { selectBorderColor(color, label) }
            }
            borderPalette.addView(swatch, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                marginEnd = dp(6)
            })
        }
        borderPalette.addView(customColorButton, LinearLayout.LayoutParams(dp(112), dp(48)).apply {
            marginStart = dp(2)
        })
    }

    private fun updateBorderControls() {
        if (!::borderLabel.isInitialized) return
        val maxDimension = sourceBitmap?.let { max(it.width, it.height) } ?: 0
        val radius = StickerBorderPolicy.radiusPx(maxDimension, borderThickness)
        borderLabel.text = if (borderColor == null || borderThickness == StickerBorderPolicy.MIN_THICKNESS) {
            "${getString(R.string.sticker_border_title)}: ${getString(R.string.sticker_border_none)}"
        } else {
            "${getString(R.string.sticker_border_title)} • " +
                getString(R.string.sticker_border_width, borderThickness, radius)
        }
        if (::borderThicknessSeekBar.isInitialized) {
            borderThicknessSeekBar.progress = borderThickness
            borderThicknessSeekBar.isEnabled = !busy && !sourceMissing && confidenceMask != null && borderColor != null
        }
        if (::borderPalette.isInitialized) {
            val canEdit = !busy && !sourceMissing && confidenceMask != null
            for (index in 0 until borderPalette.childCount) {
                borderPalette.getChildAt(index).apply {
                    isEnabled = canEdit
                    alpha = if (canEdit) 1f else 0.5f
                }
            }
            borderOptions.forEachIndexed { index, (color, labelRes) ->
                val swatch = borderPalette.getChildAt(index) as? TextView ?: return@forEachIndexed
                val selected = color == borderColor
                styleBorderSwatch(swatch, color, selected)
                swatch.contentDescription = if (selected) {
                    getString(R.string.sticker_border_selected_content_description, getString(labelRes))
                } else {
                    getString(labelRes)
                }
            }
            if (::customColorButton.isInitialized) {
                val isCustom = borderColor != null && borderOptions.none { it.first == borderColor }
                customColorButton.isEnabled = canEdit
                customColorButton.text = if (isCustom) {
                    getString(R.string.sticker_border_custom_value, colorHex(borderColor!!))
                } else {
                    getString(R.string.sticker_border_custom)
                }
                customColorButton.contentDescription = if (isCustom) {
                    getString(
                        R.string.sticker_border_custom_selected_content_description,
                        colorHex(borderColor!!),
                    )
                } else {
                    getString(R.string.sticker_border_custom_content_description)
                }
                customColorButton.background = if (isCustom) {
                    roundedBackground(borderColor!!, radiusDp = 6, strokeColor = mvAccent, strokeWidthDp = 3)
                } else {
                    roundedBackground(mvSurface, radiusDp = 6, strokeColor = mvLine)
                }
                customColorButton.setTextColor(if (isCustom) readableTextColor(borderColor!!) else mvText)
            }
        }
    }

    private fun styleBorderSwatch(swatch: TextView, color: Int?, selected: Boolean) {
        val fill = color ?: Color.WHITE
        swatch.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(
                dp(if (selected) 3 else 1),
                if (selected) mvAccent else mvLine,
            )
        }
        swatch.text = when {
            color == null -> "×"
            selected -> "✓"
            else -> ""
        }
        swatch.setTextColor(if (color == null) mvText else readableTextColor(fill))
    }

    private fun selectBorderColor(color: Int?, label: String) {
        borderColor = color
        if (color != null && borderThickness == StickerBorderPolicy.MIN_THICKNESS) {
            borderThickness = StickerBorderPolicy.DEFAULT_THICKNESS
        }
        updateBorderControls()
        if (confidenceMask != null) {
            rerenderFromMask()
        } else if (color == null) {
            status.text = getString(R.string.sticker_border_none)
        } else {
            status.text = getString(R.string.sticker_border_selected, label)
        }
    }

    private fun openCustomColorDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.sticker_border_custom_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            borderColor?.let { setText(colorHex(it)) }
            setSelection(text.length)
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(22), 0, dp(22), 0)
            addView(input, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.sticker_border_custom_title)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.sticker_border_custom_apply, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val normalized = input.text.toString().trim().removePrefix("#")
                if (normalized.length != 6 || normalized.any { it !in "0123456789abcdefABCDEF" }) {
                    Toast.makeText(this, R.string.sticker_border_custom_invalid, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val color = Color.rgb(
                    normalized.substring(0, 2).toInt(16),
                    normalized.substring(2, 4).toInt(16),
                    normalized.substring(4, 6).toInt(16),
                )
                selectBorderColor(color, colorHex(color))
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun colorHex(color: Int): String =
        "#%06X".format(java.util.Locale.US, color and 0xFFFFFF)

    private fun readableTextColor(color: Int): Int {
        val luminance = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
        return if (luminance >= 150) Color.rgb(24, 24, 27) else Color.WHITE
    }

    private fun findBorderThicknessSeekBar(): SeekBar? {
        return if (::borderThicknessSeekBar.isInitialized) borderThicknessSeekBar else null
    }

    private fun stickerName(sourceName: String): String =
        "${sourceName.substringBeforeLast('.', sourceName)}_sticker.png"

    private fun userFacingError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            error is BackgroundRemovalModelException -> getString(R.string.sticker_model_unavailable)
            message.contains("download", ignoreCase = true) ||
                message.contains("model", ignoreCase = true) ->
                getString(R.string.sticker_model_unavailable)
            else -> message.ifBlank { getString(R.string.sticker_segment_failed) }
        }
    }

    private fun showError(message: String) {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showTagSuggestions() {
        val prefix = tagsInput.text?.toString()?.substringAfterLast(',')?.trim().orEmpty()
        executor.execute {
            val suggestions = repository.suggestTags(prefix)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (suggestions.isEmpty()) {
                    Toast.makeText(this, R.string.asset_tags_no_suggestions, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.asset_tags_suggest_title)
                    .setItems(suggestions.map { "${it.name} (${it.usageCount})" }.toTypedArray()) { dialog, which ->
                        val existing = tagsInput.text.toString().split(',').map(String::trim).filter(String::isNotEmpty)
                        val next = TagNormalizer.normalizeAll(existing + suggestions[which].name)
                            .joinToString(", ") { it.displayName }
                        tagsInput.setText("$next, ")
                        tagsInput.setSelection(tagsInput.text.length)
                        dialog.dismiss()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun handleBack() {
        if (savedAsset != null) finishWithResult() else finish()
    }

    @Suppress("DEPRECATION", "GestureBackNavigation")
    override fun onBackPressed() {
        handleBack()
    }

    private fun hasRunningOperations(): Boolean = synchronized(operationLock) {
        runningOperations.isNotEmpty()
    }

    private fun replaceDraftStore() {
        if (::draftStore.isInitialized) draftStore.clear()
        draftStore = StudioDraftStore(cacheDir, UUID.randomUUID().toString())
    }

    private fun readCropRect(state: Bundle?): CropRect? {
        if (state?.getBoolean(KEY_CROP_ACTIVE, false) != true) return null
        val rect = CropRect(
            left = state.getInt(KEY_CROP_LEFT),
            top = state.getInt(KEY_CROP_TOP),
            right = state.getInt(KEY_CROP_RIGHT),
            bottom = state.getInt(KEY_CROP_BOTTOM),
        )
        return rect.takeIf { it.left >= 0 && it.top >= 0 && it.right > it.left && it.bottom > it.top }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun configureSystemBars() {
        val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
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
                window.statusBarColor = mvBackground
                window.navigationBarColor = mvBackground
                window.decorView.systemUiVisibility = if (night) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }
    }

    private fun applySystemBarInsets(root: View) {
        val baseLeft = dp(16)
        val baseTop = dp(8)
        val baseRight = dp(16)
        val baseBottom = dp(18)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
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

    override fun onDestroy() {
        refineDialog?.dismiss()
        refineDialog = null
        refineEditor?.release()
        refineEditor = null
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            backCallback = null
        }
        operationGeneration++
        activeTask?.cancel(true)
        activeTask = null
        executor.shutdownNow()
        if (isFinishing && !isChangingConfigurations && !hasRunningOperations()) {
            draftStore.clear()
        }
        val previousSource = sourceBitmap
        val previousOriginalSource = originalSourceBitmap
        val previousSticker = renderedSticker
        originalSourceBitmap = null
        sourceBitmap = null
        renderedSticker = null
        releaseBitmap(previousSource)
        if (previousOriginalSource !== previousSource) releaseBitmap(previousOriginalSource)
        releaseBitmap(previousSticker)
        val closeNow = synchronized(operationLock) {
            if (runningOperations.isEmpty()) {
                true
            } else {
                closeResourcesWhenIdle = true
                shouldClearDraftWhenIdle = isFinishing && !isChangingConfigurations
                false
            }
        }
        if (closeNow) {
            runCatching { engine?.close() }
            engine = null
            val currentRepository = repository
            Thread {
                runCatching { currentRepository.close() }
            }.apply {
                name = "MemeVault-studio-repository-close"
                isDaemon = true
                start()
            }
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SOURCE_ASSET_ID = "source_asset_id"
        /** Boolean: the picker imported the source just now, so deleting it after save is allowed. */
        const val EXTRA_SOURCE_IMPORTED_NOW = "source_imported_now"
        const val EXTRA_EDIT_STICKER_ASSET_ID = "edit_sticker_asset_id"
        const val EXTRA_QUICK_MODE = "quick_mode"
        const val EXTRA_ENTRY_POINT = "entry_point"
        const val ENTRY_POINT_PICKER = "asset_picker"
        const val EXTRA_SAVED_ASSET_ID = "saved_asset_id"
        const val EXTRA_SAVED_ASSET_NAME = "saved_asset_name"
        private const val KEY_SOURCE_ASSET_ID = "studio_source_asset_id"
        private const val KEY_EDIT_STICKER_ID = "studio_edit_sticker_id"
        private const val KEY_THRESHOLD = "studio_threshold"
        private const val KEY_PADDING_FRACTION = "studio_padding_fraction"
        private const val KEY_BORDER_COLOR = "studio_border_color"
        private const val KEY_BORDER_THICKNESS = "studio_border_thickness"
        private const val KEY_TAGS = "studio_tags"
        private const val KEY_DRAFT_ID = "studio_draft_id"
        private const val KEY_RENDERER_ID = "studio_renderer_id"
        private const val KEY_RENDERER_VERSION = "studio_renderer_version"
        private const val KEY_SAVED_ASSET_ID = "studio_saved_asset_id"
        private const val KEY_SOURCE_IMPORTED_NOW = "studio_source_imported_now"
        private const val KEY_SOURCE_DELETED = "studio_source_deleted"
        private const val KEY_MASK_EDITED = "studio_mask_edited"
        private const val KEY_REFINE_OPEN = "studio_refine_open"
        private const val CONFIRM_WINDOW_MS = 4_000L
        private const val LANDSCAPE_TOOLS_DP = 300
        private const val KEY_CROP_ACTIVE = "studio_crop_active"
        private const val KEY_CROP_LEFT = "studio_crop_left"
        private const val KEY_CROP_TOP = "studio_crop_top"
        private const val KEY_CROP_RIGHT = "studio_crop_right"
        private const val KEY_CROP_BOTTOM = "studio_crop_bottom"
        private const val REQUEST_PICK_IMAGE = 2001
        private const val DEFAULT_THRESHOLD = 0.5f
        private const val MIN_THRESHOLD = 0.1f
        private const val MAX_THRESHOLD = 0.9f
    }
}
