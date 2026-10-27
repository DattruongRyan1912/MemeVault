package com.memevault.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 * Media-first library grid. Tiles are square; names and metadata are shown only in the
 * two-column layout. Selection state is owned by the activity and rebound via payloads.
 */
internal class LibraryGridAdapter(
    private val activity: MainActivity,
    private val previewExecutor: ExecutorService,
    private val previewHandler: Handler,
    private val isSelected: (String) -> Boolean,
    private val selectionActive: () -> Boolean,
    private val onClick: (Asset) -> Unit,
    private val onLongClick: (Asset) -> Unit,
) : ListAdapter<Asset, LibraryGridAdapter.Holder>(DIFF) {
    private val stableIds = HashMap<String, Long>()
    private var nextStableId = 1L

    var columns: Int = LibraryPreferences.DEFAULT_COLUMNS
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    /** Target decode size in pixels, set from the measured grid width. */
    var tilePx: Int = activity.dp(140)

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long =
        stableIds.getOrPut(getItem(position).id) { nextStableId++ }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            isLongClickable = true
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val media = SquareFrameLayout(context).apply {
            background = context.roundedBackground(context.mvSurfaceAlt, MV_RADIUS_SMALL)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        val preview = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        media.addView(preview, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        val badge = TextView(context).apply {
            applyMvMonoStyle(10f, context.mvOnOverlay)
            gravity = Gravity.CENTER
            setPadding(context.dp(5), 0, context.dp(5), 0)
            background = context.roundedBackground(context.mvOverlay, MV_RADIUS_SMALL)
        }
        media.addView(badge, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            context.dp(18),
            Gravity.BOTTOM or Gravity.START,
        ).apply { setMargins(context.dp(5), 0, 0, context.dp(5)) })
        val favorite = ImageView(context).apply {
            setMvIcon(R.drawable.ic_star, context.mvFavorite)
            background = context.roundedBackground(context.mvOverlay, MV_RADIUS_SMALL)
            setPadding(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        media.addView(favorite, FrameLayout.LayoutParams(
            context.dp(22),
            context.dp(22),
            Gravity.TOP or Gravity.END,
        ).apply { setMargins(0, context.dp(5), context.dp(5), 0) })
        val selectionFrame = View(context).apply { visibility = View.GONE }
        media.addView(selectionFrame, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        val check = ImageView(context).apply {
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setPadding(context.dp(2), context.dp(2), context.dp(2), context.dp(2))
        }
        media.addView(check, FrameLayout.LayoutParams(
            context.dp(24),
            context.dp(24),
            Gravity.TOP or Gravity.START,
        ).apply { setMargins(context.dp(6), context.dp(6), 0, 0) })
        card.addView(media, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        val name = TextView(context).apply {
            applyMvTextStyle(13f, context.mvText, 1)
            setPadding(context.dp(2), context.dp(6), context.dp(2), 0)
        }
        card.addView(name)
        val meta = TextView(context).apply {
            applyMvMonoStyle(11f)
            setPadding(context.dp(2), context.dp(3), context.dp(2), context.dp(4))
        }
        card.addView(meta)
        return Holder(card, media, preview, badge, favorite, selectionFrame, check, name, meta)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            holder.bindSelection(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.cancelPreview()
        super.onViewRecycled(holder)
    }

    fun notifySelectionChanged() {
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    fun notifySelectionChanged(id: String) {
        val index = currentList.indexOfFirst { it.id == id }
        if (index >= 0) notifyItemChanged(index, PAYLOAD_SELECTION)
    }

    inner class Holder(
        private val card: LinearLayout,
        private val media: FrameLayout,
        private val preview: ImageView,
        private val badge: TextView,
        private val favorite: ImageView,
        private val selectionFrame: View,
        private val check: ImageView,
        private val name: TextView,
        private val meta: TextView,
    ) : RecyclerView.ViewHolder(card) {
        private var pending: Future<*>? = null
        private var boundId: String? = null

        fun bind(asset: Asset) {
            val context = card.context
            boundId = asset.id
            val gap = if (columns == 2) context.dp(5) else context.dp(2)
            (card.layoutParams as RecyclerView.LayoutParams).setMargins(gap, gap, gap, gap)
            card.setOnClickListener { onClick(asset) }
            card.setOnLongClickListener {
                onLongClick(asset)
                true
            }
            val showText = columns == 2
            name.visibility = if (showText) View.VISIBLE else View.GONE
            meta.visibility = if (showText) View.VISIBLE else View.GONE
            name.text = asset.displayName
            meta.text = tileMetadata(asset)
            badge.text = badgeLabel(asset)
            badge.visibility = if (badge.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            favorite.visibility = if (asset.favorite) View.VISIBLE else View.GONE
            if (asset.type == AssetType.STICKER) {
                preview.scaleType = ImageView.ScaleType.FIT_CENTER
                val inset = context.dp(if (columns == 4) 4 else 8)
                preview.setPadding(inset, inset, inset, inset)
                preview.background = CheckerboardDrawable(context.dp(8), context)
            } else {
                preview.scaleType = ImageView.ScaleType.CENTER_CROP
                preview.setPadding(0, 0, 0, 0)
                preview.background = null
            }
            bindSelection(asset)
            loadPreview(asset)
        }

        fun bindSelection(asset: Asset) {
            val context = card.context
            val active = selectionActive()
            val selected = active && isSelected(asset.id)
            check.visibility = if (active) View.VISIBLE else View.GONE
            if (active) {
                if (selected) {
                    check.setMvIcon(R.drawable.ic_check, context.mvOnPrimary)
                    check.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(context.mvAccent)
                    }
                } else {
                    check.setImageDrawable(null)
                    check.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(context.mvOverlay)
                        setStroke(context.dp(2), context.mvOnOverlay)
                    }
                }
            }
            selectionFrame.visibility = if (selected) View.VISIBLE else View.GONE
            if (selected) {
                selectionFrame.background = GradientDrawable().apply {
                    val accent = context.mvAccent
                    setColor(Color.argb(56, Color.red(accent), Color.green(accent), Color.blue(accent)))
                    setStroke(context.dp(3), context.mvAccent)
                    cornerRadius = context.dp(MV_RADIUS_SMALL).toFloat()
                }
            }
            card.isSelected = selected
            card.contentDescription = buildString {
                append(asset.displayName)
                append(", ").append(asset.type.label)
                if (asset.favorite) append(", yêu thích")
                if (active) append(if (selected) ", đã chọn" else ", chưa chọn")
            }
        }

        private fun loadPreview(asset: Asset) {
            cancelPreview()
            preview.setImageDrawable(null)
            val target = tilePx
            val id = asset.id
            pending = runCatching {
                previewExecutor.submit {
                    val bitmap = MediaPreviewLoader.decode(File(asset.originalPath), asset.mimeType, target)
                    previewHandler.post {
                        if (!activity.isUiUnavailable() && boundId == id && bitmap != null) {
                            preview.setImageBitmap(bitmap)
                        }
                    }
                }
            }.getOrNull()
        }

        fun cancelPreview() {
            pending?.cancel(true)
            pending = null
        }
    }

    companion object {
        private const val PAYLOAD_SELECTION = "selection"

        fun badgeLabel(asset: Asset): String = when (asset.type) {
            AssetType.GIF -> "GIF"
            AssetType.VIDEO -> if (asset.durationMs > 0) "VIDEO ${formatDuration(asset.durationMs)}" else "VIDEO"
            AssetType.STICKER -> "STICKER"
            else -> ""
        }

        fun tileMetadata(asset: Asset): String = buildString {
            if (asset.width > 0 && asset.height > 0) append("${asset.width}×${asset.height}")
            if (asset.useCount > 0) {
                if (isNotEmpty()) append(" · ")
                append("dùng ${asset.useCount}")
            }
            if (isEmpty()) append(MediaPolicy.normalizeMime(asset.mimeType))
        }

        fun formatDuration(durationMs: Long): String {
            val totalSeconds = (durationMs + 500) / 1000
            return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
        }

        private val DIFF = object : DiffUtil.ItemCallback<Asset>() {
            override fun areItemsTheSame(oldItem: Asset, newItem: Asset): Boolean = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Asset, newItem: Asset): Boolean = oldItem == newItem
        }
    }
}
