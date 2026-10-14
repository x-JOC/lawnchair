package app.lawnchair.allapps.suggestions

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import com.android.launcher3.BubbleTextView
import com.android.launcher3.R
import com.android.launcher3.keyboard.FocusIndicatorHelper.SimpleFocusIndicatorHelper
import com.android.launcher3.model.data.AppInfo

/**
 * Rows of app icons laid out exactly like the drawer's A-Z grid below them: the same icon view
 * (`all_apps_icon`), cell height, column widths and right-to-left order as the grid's
 * `GridLayoutManager`, so the columns line up. The icons are direct children, so
 * [com.android.launcher3.allapps.AllAppsStore] can update their notification dots and install
 * progress like any other drawer icon.
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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val cellHeight = cellHeight()
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
        val cellHeight = cellHeight()
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

    private fun cellHeight(): Int = launcher?.deviceProfile?.allAppsProfile?.cellHeightPx ?: 0

    private companion object {
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
