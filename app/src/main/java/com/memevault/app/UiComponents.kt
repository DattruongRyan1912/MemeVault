package com.memevault.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import kotlin.math.roundToInt

internal fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).roundToInt()

internal fun Context.mvColor(id: Int): Int = getColor(id)

internal val Context.mvPaper: Int
    get() = mvColor(R.color.mv_paper)

internal val Context.mvPaperDeep: Int
    get() = mvColor(R.color.mv_paper_deep)

internal val Context.mvSurface: Int
    get() = mvColor(R.color.mv_surface)

internal val Context.mvInk: Int
    get() = mvColor(R.color.mv_ink)

internal val Context.mvMuted: Int
    get() = mvColor(R.color.mv_muted)

internal val Context.mvLine: Int
    get() = mvColor(R.color.mv_line)

internal val Context.mvOrange: Int
    get() = mvColor(R.color.mv_orange)

internal val Context.mvOrangeDark: Int
    get() = mvColor(R.color.mv_orange_dark)

internal val Context.mvOrangeContent: Int
    get() = mvColor(R.color.mv_orange_content)

internal val Context.mvPurple: Int
    get() = mvColor(R.color.mv_purple)

internal val Context.mvPurpleDark: Int
    get() = mvColor(R.color.mv_purple_dark)

internal val Context.mvLime: Int
    get() = mvColor(R.color.mv_lime)

internal val Context.mvSky: Int
    get() = mvColor(R.color.mv_sky)

internal val Context.mvWarningSurface: Int
    get() = mvColor(R.color.mv_warning_surface)

internal val Context.mvWarningInk: Int
    get() = mvColor(R.color.mv_warning_ink)

internal val Context.mvError: Int
    get() = mvColor(R.color.mv_error)

internal fun Context.roundedBackground(
    color: Int,
    radiusDp: Int = 12,
    strokeColor: Int? = null,
    strokeWidthDp: Int = 1,
): Drawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radiusDp).toFloat()
    if (strokeColor != null) setStroke(dp(strokeWidthDp), strokeColor)
}

internal fun Context.mvButton(
    label: CharSequence,
    onClick: () -> Unit,
    primary: Boolean = false,
): Button = Button(this).apply {
    text = label
    textSize = if (primary) 14f else 13f
    isAllCaps = false
    minHeight = dp(48)
    minimumHeight = dp(48)
    minWidth = 0
    minimumWidth = 0
    setPadding(dp(14), 0, dp(14), 0)
    setTextColor(if (primary) mvOrangeContent else mvInk)
    background = buttonBackground(primary)
    stateListAnimator = null
    includeFontPadding = false
    gravity = Gravity.CENTER
    setOnClickListener { onClick() }
}

internal fun Context.mvIconButton(
    label: CharSequence,
    contentDescriptionText: CharSequence,
    onClick: () -> Unit,
): TextView = TextView(this).apply {
    text = label
    contentDescription = contentDescriptionText
    textSize = 19f
    gravity = Gravity.CENTER
    setTextColor(mvInk)
    background = iconBackground()
    isClickable = true
    isFocusable = true
    includeFontPadding = false
    setOnClickListener { onClick() }
    layoutParams = ViewGroup.LayoutParams(dp(48), dp(48))
}

internal fun Context.mvTagChip(tag: String, onRemove: () -> Unit): TextView = TextView(this).apply {
    text = "$tag  ×"
    textSize = 12f
    gravity = Gravity.CENTER
    setTextColor(mvPurpleDark)
    setPadding(dp(12), 0, dp(10), 0)
    background = roundedBackground(
        mvColor(R.color.mv_tag_surface),
        radiusDp = 16,
        strokeColor = mvPurple,
    )
    isClickable = true
    isFocusable = true
    contentDescription = "Xóa tag $tag"
    includeFontPadding = false
    setOnClickListener { onRemove() }
}

private fun Context.buttonBackground(primary: Boolean): Drawable = StateListDrawable().apply {
    addState(
        intArrayOf(-android.R.attr.state_enabled),
        roundedBackground(mvPaperDeep, radiusDp = 16, strokeColor = mvLine),
    )
    addState(
        intArrayOf(android.R.attr.state_pressed),
        roundedBackground(
            if (primary) mvOrangeDark else mvPaperDeep,
            radiusDp = 16,
            strokeColor = if (primary) null else mvLine,
        ),
    )
    addState(
        intArrayOf(),
        roundedBackground(
            if (primary) mvOrange else mvSurface,
            radiusDp = 16,
            strokeColor = if (primary) null else mvLine,
        ),
    )
}

private fun Context.iconBackground(): Drawable = StateListDrawable().apply {
    addState(
        intArrayOf(-android.R.attr.state_enabled),
        roundedBackground(mvPaperDeep, radiusDp = 14, strokeColor = mvLine),
    )
    addState(
        intArrayOf(android.R.attr.state_pressed),
        roundedBackground(mvPaperDeep, radiusDp = 14, strokeColor = mvPurple),
    )
    addState(
        intArrayOf(),
        roundedBackground(mvSurface, radiusDp = 14, strokeColor = mvLine),
    )
}

internal fun TextView.applyMvTextStyle(
    sizeSp: Float,
    color: Int = Color.rgb(23, 32, 51),
    maxLinesValue: Int = Int.MAX_VALUE,
) {
    textSize = sizeSp
    setTextColor(resolveMvTextColor(color))
    maxLines = maxLinesValue
    includeFontPadding = false
    if (maxLinesValue == 1) ellipsize = android.text.TextUtils.TruncateAt.END
}

private fun TextView.resolveMvTextColor(requested: Int): Int = when (requested) {
    Color.rgb(23, 32, 51), Color.rgb(35, 48, 75) -> context.mvInk
    Color.rgb(91, 101, 124), Color.rgb(126, 135, 157) -> context.mvMuted
    else -> requested
}
