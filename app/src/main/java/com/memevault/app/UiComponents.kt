package com.memevault.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

internal fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).roundToInt()

internal fun Context.mvColor(id: Int): Int = getColor(id)

internal val Context.mvBackground: Int get() = mvColor(R.color.mv_bg)
internal val Context.mvSurface: Int get() = mvColor(R.color.mv_surface)
internal val Context.mvSurfaceAlt: Int get() = mvColor(R.color.mv_surface_alt)
internal val Context.mvLine: Int get() = mvColor(R.color.mv_line)
internal val Context.mvLineStrong: Int get() = mvColor(R.color.mv_line_strong)
internal val Context.mvText: Int get() = mvColor(R.color.mv_text)
internal val Context.mvMuted: Int get() = mvColor(R.color.mv_muted)
internal val Context.mvPrimary: Int get() = mvColor(R.color.mv_primary)
internal val Context.mvPrimaryPressed: Int get() = mvColor(R.color.mv_primary_pressed)
internal val Context.mvOnPrimary: Int get() = mvColor(R.color.mv_on_primary)
internal val Context.mvAccent: Int get() = mvColor(R.color.mv_accent)
internal val Context.mvAccentText: Int get() = mvColor(R.color.mv_accent_text)
internal val Context.mvAccentSoft: Int get() = mvColor(R.color.mv_accent_soft)
internal val Context.mvFavorite: Int get() = mvColor(R.color.mv_favorite)
internal val Context.mvDanger: Int get() = mvColor(R.color.mv_danger)
internal val Context.mvDangerSoft: Int get() = mvColor(R.color.mv_danger_soft)
internal val Context.mvWarningSurface: Int get() = mvColor(R.color.mv_warning_surface)
internal val Context.mvWarningInk: Int get() = mvColor(R.color.mv_warning_ink)
internal val Context.mvOverlay: Int get() = mvColor(R.color.mv_overlay)
internal val Context.mvOnOverlay: Int get() = mvColor(R.color.mv_on_overlay)

/** Mechanical corner radius: 6dp for controls/cards, 4dp for chips and badges. */
internal const val MV_RADIUS = 6
internal const val MV_RADIUS_SMALL = 4

/** Z-order of in-activity layers (dp). Kept explicit so a bar never covers a sheet. */
internal const val MV_ELEVATION_BAR = 2
internal const val MV_ELEVATION_DETAIL = 6
internal const val MV_ELEVATION_SHEET = 10
internal const val MV_ELEVATION_SNACKBAR = 14

/** Minimum interactive height; visual chrome may be smaller via insets. */
internal const val MV_TOUCH = 44

internal fun Context.roundedBackground(
    color: Int,
    radiusDp: Int = MV_RADIUS,
    strokeColor: Int? = null,
    strokeWidthDp: Int = 1,
): Drawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radiusDp).toFloat()
    if (strokeColor != null) setStroke(dp(strokeWidthDp).coerceAtLeast(1), strokeColor)
}

private fun Context.pressable(base: Drawable, radiusDp: Int = MV_RADIUS): Drawable {
    val mask = GradientDrawable().apply {
        setColor(Color.WHITE)
        cornerRadius = dp(radiusDp).toFloat()
    }
    val ripple = Color.argb(40, Color.red(mvText), Color.green(mvText), Color.blue(mvText))
    return RippleDrawable(ColorStateList.valueOf(ripple), base, mask)
}

internal fun Context.mvButton(
    label: CharSequence,
    onClick: () -> Unit,
    primary: Boolean = false,
    iconRes: Int? = null,
    danger: Boolean = false,
): Button = Button(this).apply {
    text = label
    textSize = 14f
    isAllCaps = false
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    minHeight = dp(MV_TOUCH)
    minimumHeight = dp(MV_TOUCH)
    minWidth = 0
    minimumWidth = 0
    setPadding(dp(14), 0, dp(14), 0)
    val content = when {
        primary -> mvOnPrimary
        danger -> mvDanger
        else -> mvText
    }
    setTextColor(ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(mvMuted, content),
    ))
    background = buttonBackground(primary)
    stateListAnimator = null
    elevation = 0f
    includeFontPadding = false
    gravity = Gravity.CENTER
    if (iconRes != null) {
        val icon = getDrawable(iconRes)?.mutate()?.apply {
            setTint(content)
            setBounds(0, 0, dp(18), dp(18))
        }
        setCompoundDrawablesRelative(icon, null, null, null)
        compoundDrawablePadding = dp(8)
    }
    setOnClickListener { onClick() }
}

internal fun Context.mvIconButton(
    iconRes: Int,
    contentDescriptionText: CharSequence,
    onClick: () -> Unit,
    tint: Int = mvText,
): ImageView = ImageView(this).apply {
    setImageResource(iconRes)
    imageTintList = ColorStateList.valueOf(tint)
    scaleType = ImageView.ScaleType.CENTER
    contentDescription = contentDescriptionText
    background = pressable(ColorDrawable(Color.TRANSPARENT))
    isClickable = true
    isFocusable = true
    setOnClickListener { onClick() }
    layoutParams = ViewGroup.LayoutParams(dp(48), dp(48))
}

internal fun ImageView.setMvIcon(iconRes: Int, tint: Int) {
    setImageResource(iconRes)
    imageTintList = ColorStateList.valueOf(tint)
}

/**
 * Selectable filter chip. The visual pill is 32dp tall inside a 44dp touch target so rows stay
 * compact without shrinking the hit area.
 */
internal fun Context.mvChip(label: CharSequence, onClick: () -> Unit): TextView = TextView(this).apply {
    text = label
    textSize = 13f
    gravity = Gravity.CENTER
    maxLines = 1
    includeFontPadding = false
    setPadding(dp(12), 0, dp(12), 0)
    isClickable = true
    isFocusable = true
    setOnClickListener { onClick() }
}

internal fun TextView.styleMvChip(selected: Boolean) {
    val c = context
    val fill = if (selected) c.mvPrimary else c.mvSurface
    val stroke = if (selected) c.mvPrimary else c.mvLine
    background = InsetDrawable(
        c.roundedBackground(fill, MV_RADIUS_SMALL, stroke),
        0, c.dp(6), 0, c.dp(6),
    )
    // An InsetDrawable reports its insets as padding; restore the chip's horizontal padding.
    setPadding(c.dp(12), 0, c.dp(12), 0)
    setTextColor(if (selected) c.mvOnPrimary else c.mvText)
    typeface = Typeface.create(if (selected) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    isSelected = selected
}

internal fun Context.mvTagChip(tag: String, onRemove: () -> Unit): TextView = TextView(this).apply {
    text = tag
    textSize = 13f
    gravity = Gravity.CENTER_VERTICAL
    maxLines = 1
    setTextColor(mvText)
    includeFontPadding = false
    background = InsetDrawable(
        roundedBackground(mvSurfaceAlt, MV_RADIUS_SMALL, mvLine),
        0, dp(6), 0, dp(6),
    )
    setPadding(dp(10), 0, dp(8), 0)
    val close = getDrawable(R.drawable.ic_close)?.mutate()?.apply {
        setTint(mvMuted)
        setBounds(0, 0, dp(14), dp(14))
    }
    setCompoundDrawablesRelative(null, null, close, null)
    compoundDrawablePadding = dp(6)
    isClickable = true
    isFocusable = true
    contentDescription = "Gỡ tag $tag"
    setOnClickListener { onRemove() }
}

private fun Context.buttonBackground(primary: Boolean): Drawable {
    val states = StateListDrawable().apply {
        addState(
            intArrayOf(-android.R.attr.state_enabled),
            roundedBackground(mvSurfaceAlt, strokeColor = mvLine),
        )
        addState(
            intArrayOf(),
            if (primary) roundedBackground(mvPrimary) else roundedBackground(mvSurface, strokeColor = mvLine),
        )
    }
    return pressable(states)
}

internal fun TextView.applyMvTextStyle(
    sizeSp: Float,
    color: Int = context.mvText,
    maxLinesValue: Int = Int.MAX_VALUE,
) {
    textSize = sizeSp
    setTextColor(color)
    maxLines = maxLinesValue
    includeFontPadding = false
    if (maxLinesValue == 1) ellipsize = TextUtils.TruncateAt.END
}

/** Technical metadata (dimensions, size, format, counts) uses the platform monospace face. */
internal fun TextView.applyMvMonoStyle(sizeSp: Float = 12f, color: Int = context.mvMuted) {
    textSize = sizeSp
    setTextColor(color)
    typeface = Typeface.MONOSPACE
    includeFontPadding = false
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
}

internal fun Context.mvDivider(): View = View(this).apply { setBackgroundColor(mvLine) }

internal fun Context.mvSectionLabel(text: CharSequence): TextView = TextView(this).apply {
    this.text = text
    applyMvTextStyle(12f, mvMuted, 1)
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    letterSpacing = 0.04f
}

/** Square container so grid tiles keep a stable 1:1 media frame at every column count. */
internal class SquareFrameLayout(context: Context) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}

/**
 * Non-blocking bottom message bar with one optional action, used instead of modal alerts for
 * results such as "deleted 3 items — Undo". Only one message is visible at a time.
 */
internal class MvSnackbar(private val host: FrameLayout) {
    private val context = host.context
    private val handler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null
    private var onDismiss: (() -> Unit)? = null
    private val message = TextView(context).apply {
        applyMvTextStyle(14f, context.mvOnPrimary, 2)
    }
    private val action = TextView(context).apply {
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(context.mvOnPrimary)
        gravity = Gravity.CENTER
        setPadding(context.dp(12), 0, context.dp(12), 0)
        isClickable = true
        isFocusable = true
    }
    private val bar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(14), 0, context.dp(4), 0)
        background = context.roundedBackground(context.mvPrimary)
        elevation = context.dp(MV_ELEVATION_SNACKBAR).toFloat()
        visibility = View.GONE
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        addView(message, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(action, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(MV_TOUCH)))
    }

    init {
        host.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ).apply {
            setMargins(context.dp(12), 0, context.dp(12), context.dp(12))
        })
        bar.minimumHeight = context.dp(48)
    }

    val view: View get() = bar

    fun show(
        text: CharSequence,
        actionLabel: CharSequence? = null,
        durationMs: Long = 3_000L,
        onAction: (() -> Unit)? = null,
        onDismissed: (() -> Unit)? = null,
    ) {
        dismiss()
        message.text = text
        onDismiss = onDismissed
        if (actionLabel != null && onAction != null) {
            action.text = actionLabel
            action.visibility = View.VISIBLE
            action.setOnClickListener {
                onDismiss = null
                hide()
                onAction()
            }
        } else {
            action.visibility = View.GONE
        }
        bar.visibility = View.VISIBLE
        bar.bringToFront()
        hideRunnable = Runnable { dismiss() }.also { handler.postDelayed(it, durationMs) }
    }

    /** Hides the bar and runs the pending dismiss callback (e.g. committing a deletion). */
    fun dismiss() {
        val callback = onDismiss
        onDismiss = null
        hide()
        callback?.invoke()
    }

    private fun hide() {
        hideRunnable?.let(handler::removeCallbacks)
        hideRunnable = null
        bar.visibility = View.GONE
    }
}

/** Wrapping row container for tag chips; children keep their measured size. */
internal class FlowLayout(context: Context) : ViewGroup(context) {
    var horizontalSpacing = context.dp(6)
    var verticalSpacing = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            measureChild(child, MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST), heightMeasureSpec)
            if (x > 0 && x + child.measuredWidth > maxWidth) {
                x = 0
                y += rowHeight + verticalSpacing
                rowHeight = 0
            }
            x += child.measuredWidth + horizontalSpacing
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
        val height = y + rowHeight + paddingTop + paddingBottom
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxWidth = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            if (x > 0 && x + child.measuredWidth > maxWidth) {
                x = 0
                y += rowHeight + verticalSpacing
                rowHeight = 0
            }
            val left = paddingLeft + if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                maxWidth - x - child.measuredWidth
            } else {
                x
            }
            child.layout(left, paddingTop + y, left + child.measuredWidth, paddingTop + y + child.measuredHeight)
            x += child.measuredWidth + horizontalSpacing
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
    }
}

/**
 * Bottom sheet rendered inside the activity's root (no window, no modal dialog). Tapping the
 * scrim or calling [close] dismisses it; the host routes system back to [close].
 */
internal class MvSheet(private val host: FrameLayout, title: CharSequence) {
    private val context = host.context
    private var onClosed: (() -> Unit)? = null
    private val scrim = View(context).apply {
        setBackgroundColor(Color.argb(110, 0, 0, 0))
        // Layers inside the activity root are ordered by elevation: bars < detail < sheet < snackbar.
        elevation = context.dp(MV_ELEVATION_SHEET).toFloat()
        isClickable = true
        contentDescription = "Đóng bảng"
        setOnClickListener { close() }
    }
    val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        elevation = context.dp(MV_ELEVATION_SHEET).toFloat()
        setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(16))
        background = GradientDrawable().apply {
            setColor(context.mvSurface)
            setStroke(context.dp(1), context.mvLine)
            val r = context.dp(8).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
    }

    init {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = title
            applyMvTextStyle(16f, context.mvText, 1)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(context.mvIconButton(R.drawable.ic_close, context.getString(R.string.close), { close() }))
        panel.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(48)))
        panel.addView(body)
    }

    val isShowing: Boolean get() = scrim.parent != null

    fun show(bottomInsetPx: Int = 0, onClosed: (() -> Unit)? = null) {
        if (isShowing) return
        this.onClosed = onClosed
        panel.setPadding(panel.paddingLeft, panel.paddingTop, panel.paddingRight, context.dp(16) + bottomInsetPx)
        host.addView(scrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        host.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ))
        panel.translationY = context.dp(48).toFloat()
        panel.alpha = 0f
        panel.animate().translationY(0f).alpha(1f).setDuration(160).start()
    }

    fun close() {
        if (!isShowing) return
        host.removeView(scrim)
        host.removeView(panel)
        val callback = onClosed
        onClosed = null
        callback?.invoke()
    }
}
