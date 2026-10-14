package app.lawnchair.allapps.suggestions

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import com.android.app.animation.Interpolators
import com.android.launcher3.BubbleTextView
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.keyboard.FocusIndicatorHelper.SimpleFocusIndicatorHelper
import com.android.launcher3.model.data.AppInfo
import kotlin.math.ceil
import kotlin.math.min

/**
 * Rows of app icons laid out like the drawer's A-Z grid: the same icon view (`all_apps_icon`),
 * number of columns, column borders and right-to-left order as the grid's `GridLayoutManager`,
 * spread over the width of the grown search bar that holds them, in shorter rows ([rowHeight]).
 * The icons are direct children, so [com.android.launcher3.allapps.AllAppsStore] can update their
 * notification dots and install progress like any other drawer icon.
 */
class SuggestionIconGrid @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    /** Null outside the launcher's own drawer, where the header never binds this grid. */
    private val launcher = context.findLawnchairLauncher()
    private val focusHelper = SimpleFocusIndicatorHelper(this)

    private var columns = 1
    private var rows = 0
    private var borders = IntArray(2)
    private var boundApps: List<AppInfo> = emptyList()

    /** How far above its place an icon starts its entrance. */
    private val iconDrop = resources.getDimension(R.dimen.drawer_suggestions_icon_entrance_drop)
    private val rowPadding = resources.getDimensionPixelSize(R.dimen.drawer_suggestions_row_padding_vertical)

    /**
     * Shows [apps] in [rows] rows of [columns] icons. Cells without an app stay invisible, so the
     * grid keeps its size.
     */
    fun bind(apps: List<AppInfo>, columns: Int, rows: Int) {
        val launcher = launcher ?: return
        val count = columns * rows
        if (apps == boundApps && columns == this.columns && rows == this.rows && childCount == count) return
        this.columns = columns.coerceAtLeast(1)
        this.rows = rows
        boundApps = apps
        ensureCells(launcher.appsView?.layoutInflater ?: LayoutInflater.from(context), count)
        for (i in 0 until childCount) {
            val icon = getChildAt(i) as BubbleTextView
            icon.reset()
            if (i < apps.size) {
                icon.applyFromApplicationInfo(apps[i])
                icon.visibility = VISIBLE
            } else {
                icon.visibility = INVISIBLE
            }
        }
        requestLayout()
    }

    /** Drops the icon views, for example after a device profile change, so the next bind creates new ones. */
    fun clearCells() {
        removeAllViews()
        boundApps = emptyList()
    }

    /**
     * How long the staggered entrance of the icons takes, in milliseconds: each icon fades in,
     * drops into place and grows to its size with a slight overshoot, one after another in reading
     * order, so from the right in right-to-left layouts.
     */
    fun entranceDuration(): Long = ICON_ENTRANCE_DURATION_MS + ICON_ENTRANCE_STAGGER_MS * (visibleIconCount() - 1).coerceAtLeast(0)

    /**
     * Shows the icons [time] milliseconds into their entrance (see [entranceDuration]): before it
     * starts, they are hidden; past its end, as usual. Only render properties change, so nothing
     * is laid out again.
     */
    fun setEntranceTime(time: Float) {
        var index = 0
        for (i in 0 until childCount) {
            val icon = getChildAt(i)
            if (icon.visibility != VISIBLE) continue
            val progress = ((time - index * ICON_ENTRANCE_STAGGER_MS) / ICON_ENTRANCE_DURATION_MS).coerceIn(0f, 1f)
            index++
            icon.alpha = (progress / ICON_FADE_SHARE).coerceAtMost(1f)
            val scale = Utilities.mapRange(Interpolators.OVERSHOOT_1_7.getInterpolation(progress), ICON_START_SCALE, 1f)
            icon.scaleX = scale
            icon.scaleY = scale
            icon.translationY = (1f - Interpolators.EMPHASIZED_DECELERATE.getInterpolation(progress)) * -iconDrop
        }
    }

    /** Ends any entrance, showing the icons as usual. */
    fun endEntrance() {
        for (i in 0 until childCount) {
            getChildAt(i).apply {
                alpha = 1f
                scaleX = 1f
                scaleY = 1f
                translationY = 0f
            }
        }
    }

    private fun visibleIconCount(): Int = (0 until childCount).count { getChildAt(it).visibility == VISIBLE }

    private fun ensureCells(inflater: LayoutInflater, count: Int) {
        val launcher = launcher ?: return
        while (childCount > count) removeViewAt(childCount - 1)
        if (childCount == count) return
        // Same choice of icon view as BaseAllAppsAdapter.
        val layout = if (launcher.deviceProfile.inv.enableTwoLinesInAllApps) {
            R.layout.all_apps_icon_twoline
        } else {
            R.layout.all_apps_icon
        }
        while (childCount < count) {
            val icon = inflater.inflate(layout, this, false) as BubbleTextView
            icon.setOnClickListener(launcher.itemOnClickListener)
            // Long press opens the popup and starts drag and drop, as in the A-Z list.
            icon.setOnLongClickListener(launcher.allAppsItemLongClickListener)
            icon.setLongPressTimeoutFactor(1f)
            icon.onFocusChangeListener = focusHelper
            addView(icon)
        }
    }

    /**
     * Height of a row: an icon and its label with a little space above and below, which is less
     * than the drawer's own cell height. The rows are drawn over the list, so they don't need to
     * line up with the list's. Icons center themselves in it (`centerVertically`). Two-line labels
     * keep the full cell height.
     */
    fun rowHeight(): Int {
        val dp = launcher?.deviceProfile ?: return 0
        val cellHeight = dp.allAppsProfile.cellHeightPx
        if (dp.inv.enableTwoLinesInAllApps) return cellHeight
        val icon = (0 until childCount).firstNotNullOfOrNull { getChildAt(it) as? BubbleTextView } ?: return cellHeight
        val metrics = icon.paint.fontMetrics
        val content = icon.iconSize + icon.compoundDrawablePadding + ceil(metrics.bottom - metrics.top).toInt()
        return min(cellHeight, content + 2 * rowPadding)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val cellHeight = rowHeight()
        borders = calculateItemBorders(borders, columns, width)
        val isRtl = layoutDirection == LAYOUT_DIRECTION_RTL
        for (i in 0 until childCount) {
            val column = columnPosition(i % columns, isRtl)
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(borders[column + 1] - borders[column], MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(width, rows * cellHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cellHeight = rowHeight()
        val isRtl = layoutDirection == LAYOUT_DIRECTION_RTL
        for (i in 0 until childCount) {
            val column = columnPosition(i % columns, isRtl)
            val top = (i / columns) * cellHeight
            getChildAt(i).layout(borders[column], top, borders[column + 1], top + cellHeight)
        }
    }

    /** Left-to-right position of the column at [index] in reading order. */
    private fun columnPosition(index: Int, isRtl: Boolean): Int = if (isRtl) columns - 1 - index else index

    override fun dispatchDraw(canvas: Canvas) {
        focusHelper.draw(canvas)
        super.dispatchDraw(canvas)
    }

    override fun shouldDelayChildPressedState(): Boolean = false

    override fun hasOverlappingRendering(): Boolean = false

    private companion object {
        const val ICON_ENTRANCE_DURATION_MS = 280L
        const val ICON_ENTRANCE_STAGGER_MS = 22L

        /** The share of an icon's entrance it takes to fade in. */
        const val ICON_FADE_SHARE = 0.6f
        const val ICON_START_SCALE = 0.72f

        /**
         * Column borders for [spanCount] columns over [totalSpace] pixels, spreading the remainder
         * the same way as `GridLayoutManager.calculateItemBorders`, which lays out the A-Z grid.
         */
        fun calculateItemBorders(cached: IntArray, spanCount: Int, totalSpace: Int): IntArray {
            val borders = if (cached.size == spanCount + 1) cached else IntArray(spanCount + 1)
            borders[0] = 0
            val sizePerSpan = totalSpace / spanCount
            val sizePerSpanRemainder = totalSpace % spanCount
            var consumedPixels = 0
            var additionalSize = 0
            for (i in 1..spanCount) {
                var itemSize = sizePerSpan
                additionalSize += sizePerSpanRemainder
                if (additionalSize > 0 && spanCount - additionalSize < sizePerSpanRemainder) {
                    itemSize += 1
                    additionalSize -= spanCount
                }
                consumedPixels += itemSize
                borders[i] = consumedPixels
            }
            return borders
        }
    }
}
