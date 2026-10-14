package app.lawnchair.allapps.suggestions

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.CollectionInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.CollectionItemInfoCompat
import app.lawnchair.font.FontManager
import app.lawnchair.search.algorithms.engine.SuggestionTab
import com.android.app.animation.Interpolators
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.workprofile.PersonalWorkSlidingTabStrip

/**
 * The Frequent / Recent / New tabs above the drawer's suggested apps: three equal-width text tabs
 * with a rounded indicator in the drawer's tab color under the selected label. The indicator
 * slides to a newly picked tab, stretching towards it first.
 *
 * The bar is inset to the outer edges of the first and last icon columns, the same way as the
 * drawer's Personal/Work tabs (`alignOnIcon`), and the three labels share one text size that fits
 * all of them.
 */
class SuggestionTabBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** Called when the user picks a tab, including the one already selected. */
    var onTabPicked: ((SuggestionTab) -> Unit)? = null

    /** The tab views, in [SuggestionTab] order. */
    private lateinit var tabs: List<TextView>

    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val indicatorHeight = resources.getDimension(R.dimen.drawer_suggestions_indicator_height)
    private val indicatorBottomInset = resources.getDimension(R.dimen.drawer_suggestions_indicator_bottom_inset)
    private val indicatorExtraWidth = resources.getDimension(R.dimen.drawer_suggestions_indicator_extra_width)
    private val indicatorMinWidth = resources.getDimension(R.dimen.drawer_suggestions_indicator_min_width)
    private val indicatorTabInset = resources.getDimension(R.dimen.drawer_suggestions_indicator_tab_inset)
    private val maxTextSize = resources.getDimension(R.dimen.drawer_suggestions_tab_text_size_max)
    private val minTextSize = resources.getDimension(R.dimen.drawer_suggestions_tab_text_size_min)
    private val textSizeStep = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)
    private val measurePaint = TextPaint()

    private var selectedTab = SuggestionTab.FREQUENT
    private var indicatorLeft = 0f
    private var indicatorRight = 0f
    private var indicatorAnimator: ValueAnimator? = null

    init {
        setWillNotDraw(false)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        tabs = SuggestionTab.entries.map { getChildAt(it.ordinal) as TextView }
        val fontManager = FontManager.INSTANCE.get(context)
        tabs.forEachIndexed { index, tab ->
            fontManager.setCustomFont(tab, R.id.font_body_medium)
            tab.setOnClickListener { onTabPicked?.invoke(SuggestionTab.entries[index]) }
            ViewCompat.setAccessibilityDelegate(tab, TabAccessibilityDelegate(index))
        }
        ViewCompat.setAccessibilityDelegate(
            this,
            object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.setCollectionInfo(
                        CollectionInfoCompat.obtain(1, tabs.size, false, CollectionInfoCompat.SELECTION_MODE_SINGLE),
                    )
                }
            },
        )
    }

    /** Text colors for selected and other tabs, and the indicator color. */
    fun applyColors(selectedTextColor: Int, unselectedTextColor: Int, indicatorColor: Int) {
        val textColors = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
            intArrayOf(selectedTextColor, unselectedTextColor),
        )
        tabs.forEach { it.setTextColor(textColors) }
        indicatorPaint.color = indicatorColor
        invalidate()
    }

    /** Marks [tab] as selected and moves the indicator under it, sliding if [animate]. */
    fun select(tab: SuggestionTab, animate: Boolean) {
        val changed = tab != selectedTab
        selectedTab = tab
        tabs.forEachIndexed { index, view -> view.isSelected = index == tab.ordinal }
        val running = indicatorAnimator?.isRunning == true
        if (!changed && running) return // Already sliding to this tab.
        indicatorAnimator?.cancel()
        indicatorAnimator = null
        val canSlide = changed && animate && isLaidOut && ValueAnimator.areAnimatorsEnabled()
        if (!canSlide || indicatorRight <= indicatorLeft) {
            snapIndicator()
            return
        }
        val fromLeft = indicatorLeft
        val fromRight = indicatorRight
        indicatorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = INDICATOR_SLIDE_DURATION_MS
            interpolator = Interpolators.LINEAR
            addUpdateListener { animator ->
                // Read the target every frame, in case the tabs are laid out again meanwhile.
                val (toLeft, toRight) = indicatorBounds(selectedTab)
                val fraction = animator.animatedFraction
                // The edge in the direction of travel leads and the other one follows, so the
                // indicator stretches towards the new tab, then catches up.
                val leading = Interpolators.EMPHASIZED_DECELERATE.getInterpolation(fraction)
                val trailing = Interpolators.EMPHASIZED_ACCELERATE.getInterpolation(fraction)
                val movingRight = toLeft > fromLeft
                indicatorLeft = Utilities.mapRange(if (movingRight) trailing else leading, fromLeft, toLeft)
                indicatorRight = Utilities.mapRange(if (movingRight) leading else trailing, fromRight, toRight)
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (width > 0) {
            // Line the outer tabs up with the first and last icons, like alignOnIcon does for the
            // Personal/Work tabs.
            val inset = (width - PersonalWorkSlidingTabStrip.getTabWidth(context, width)) / 2
            if (paddingStart != inset || paddingEnd != inset) {
                setPaddingRelative(inset, paddingTop, inset, paddingBottom)
            }
            fitTextSize((width - 2 * inset) / tabs.size)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /**
     * Sets the largest text size, up to the default, at which every label fits on one line in a
     * tab of [tabWidth] pixels, so the labels never get different sizes. Below the minimum size,
     * labels are ellipsized instead.
     */
    private fun fitTextSize(tabWidth: Int) {
        if (tabs.isEmpty()) return
        val available = tabWidth - tabs[0].paddingStart - tabs[0].paddingEnd
        if (available <= 0) return
        measurePaint.set(tabs[0].paint)
        var size = maxTextSize
        while (size > minTextSize && tabs.any { textWidth(it, size) > available }) {
            size = (size - textSizeStep).coerceAtLeast(minTextSize)
        }
        tabs.forEach { tab ->
            if (tab.textSize != size) tab.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        }
    }

    private fun textWidth(tab: TextView, size: Float): Float {
        measurePaint.typeface = tab.typeface
        measurePaint.textSize = size
        return measurePaint.measureText(tab.text, 0, tab.text.length)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (indicatorAnimator?.isRunning != true) snapIndicator()
    }

    private fun snapIndicator() {
        val (left, right) = indicatorBounds(selectedTab)
        indicatorLeft = left
        indicatorRight = right
        invalidate()
    }

    /**
     * Left and right edges of the indicator under [tab]'s label, in this view's coordinates, so
     * right-to-left layouts need no special case.
     */
    private fun indicatorBounds(tab: SuggestionTab): Pair<Float, Float> {
        val view = tabs[tab.ordinal]
        if (view.width == 0) return 0f to 0f
        val layout = view.layout
        val labelWidth = if (layout != null && layout.lineCount > 0) {
            layout.getLineWidth(0)
        } else {
            view.paint.measureText(view.text, 0, view.text.length)
        }
        val maxWidth = (view.width - 2 * indicatorTabInset).coerceAtLeast(indicatorMinWidth)
        val width = (labelWidth + 2 * indicatorExtraWidth).coerceIn(indicatorMinWidth, maxWidth)
        val center = view.left + view.width / 2f
        return (center - width / 2) to (center + width / 2)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (indicatorRight <= indicatorLeft) return
        val bottom = height - indicatorBottomInset
        val radius = indicatorHeight / 2
        canvas.drawRoundRect(indicatorLeft, bottom - indicatorHeight, indicatorRight, bottom, radius, radius, indicatorPaint)
    }

    /** Reads each tab as "<label>, Tab, <n> of 3", plus "selected" for the current one. */
    private class TabAccessibilityDelegate(private val index: Int) : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.roleDescription = host.context.getString(R.string.drawer_suggestions_tab_role)
            info.isSelected = host.isSelected
            info.setCollectionItemInfo(CollectionItemInfoCompat.obtain(0, 1, index, 1, false, host.isSelected))
            if (host.isSelected) {
                // Like Material tabs: the selected tab can't be activated again.
                info.isClickable = false
                info.removeAction(AccessibilityActionCompat.ACTION_CLICK)
            }
        }
    }

    private companion object {
        const val INDICATOR_SLIDE_DURATION_MS = 300L
    }
}
