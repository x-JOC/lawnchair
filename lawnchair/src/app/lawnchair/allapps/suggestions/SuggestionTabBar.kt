package app.lawnchair.allapps.suggestions

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.CollectionInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.CollectionItemInfoCompat
import app.lawnchair.font.FontManager
import com.android.app.animation.Interpolators
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.workprofile.PersonalWorkSlidingTabStrip
import kotlin.math.max
import kotlin.math.min

/**
 * The Frequent / Recent / New tabs at the top of the app drawer, drawn like the tab chips in
 * Lawnchair's settings (`ui/preferences/components/layout/Chip.kt`): the selected label sits on a
 * rounded pill and takes the accent color, the other labels are plain text in the drawer's label
 * color. The pill slides to a newly picked tab, stretching towards it first, and each label takes
 * the selected color while the pill is under it. Press feedback is a ripple of the pill's shape.
 *
 * Each tab is as wide as its label plus the pill padding, and the space left over is shared out
 * equally, so the pills sit evenly spaced and long labels (Arabic "الأكثر استخدامًا") keep their
 * size. The row is inset to the outer edges of the first and last icon columns, the same way as
 * the drawer's Personal/Work tabs (`alignOnIcon`), and the three labels share one text size.
 */
class SuggestionTabBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** Called when the user picks a tab, including the one already selected. */
    var onTabPicked: ((SuggestionTab) -> Unit)? = null

    /** The tab views, in [SuggestionTab] order. */
    private lateinit var tabs: List<TextView>

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillMinWidth = resources.getDimension(R.dimen.drawer_suggestions_pill_min_width)
    private val minPillGap = resources.getDimension(R.dimen.drawer_suggestions_pill_min_gap)
    private val maxTextSize = resources.getDimension(R.dimen.drawer_suggestions_tab_text_size_max)
    private val minTextSize = resources.getDimension(R.dimen.drawer_suggestions_tab_text_size_min)
    private val textSizeStep = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)
    private val measurePaint = TextPaint()

    private var selectedTextColor = Color.BLACK
    private var unselectedTextColor = Color.GRAY

    private var selectedTab = SuggestionTab.FREQUENT
    private var pillLeft = 0f
    private var pillRight = 0f
    private var pillAnimator: ValueAnimator? = null

    init {
        setWillNotDraw(false)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        tabs = SuggestionTab.entries.map { getChildAt(it.ordinal) as TextView }
        val fontManager = FontManager.INSTANCE.get(context)
        tabs.forEachIndexed { index, tab ->
            fontManager.setCustomFont(tab, R.id.font_body_medium)
            tab.background = RippleDrawable(ColorStateList.valueOf(Color.TRANSPARENT), null, PillMask())
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

    /**
     * Label colors for the selected tab (on the pill) and the others, the pill color, and the
     * color of the press ripple.
     */
    fun applyColors(selectedTextColor: Int, unselectedTextColor: Int, pillColor: Int, rippleColor: Int) {
        this.selectedTextColor = selectedTextColor
        this.unselectedTextColor = unselectedTextColor
        pillPaint.color = pillColor
        val rippleColors = ColorStateList.valueOf(rippleColor)
        tabs.forEach { (it.background as? RippleDrawable)?.setColor(rippleColors) }
        updateLabelColors()
        invalidate()
    }

    /** Marks [tab] as selected and moves the pill under it, sliding if [animate]. */
    fun select(tab: SuggestionTab, animate: Boolean) {
        val changed = tab != selectedTab
        selectedTab = tab
        tabs.forEachIndexed { index, view -> view.isSelected = index == tab.ordinal }
        val running = pillAnimator?.isRunning == true
        if (!changed && running) return // Already sliding to this tab.
        pillAnimator?.cancel()
        pillAnimator = null
        val canSlide = changed && animate && isLaidOut && ValueAnimator.areAnimatorsEnabled()
        if (!canSlide || pillRight <= pillLeft) {
            snapPill()
            return
        }
        val fromLeft = pillLeft
        val fromRight = pillRight
        pillAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PILL_SLIDE_DURATION_MS
            interpolator = Interpolators.LINEAR
            addUpdateListener { animator ->
                // Read the target every frame, in case the tabs are laid out again meanwhile.
                val (toLeft, toRight) = pillBounds(selectedTab.ordinal)
                val fraction = animator.animatedFraction
                // The edge in the direction of travel leads and the other one follows, so the
                // pill stretches towards the new tab, then catches up.
                val leading = Interpolators.EMPHASIZED_DECELERATE.getInterpolation(fraction)
                val trailing = Interpolators.EMPHASIZED_ACCELERATE.getInterpolation(fraction)
                val movingRight = toLeft > fromLeft
                pillLeft = Utilities.mapRange(if (movingRight) trailing else leading, fromLeft, toLeft)
                pillRight = Utilities.mapRange(if (movingRight) leading else trailing, fromRight, toRight)
                updateLabelColors()
                invalidate()
            }
            start()
        }
    }

    /**
     * Sets the inset, the label size and the tab widths before the tabs are measured. They all
     * depend on this bar's width, so they can only be worked out here, and each is only set when
     * it actually changes.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (width > 0) {
            // Line the outer tabs up with the first and last icons, like alignOnIcon does for the
            // Personal/Work tabs.
            val inset = (width - PersonalWorkSlidingTabStrip.getTabWidth(context, width)) / 2
            if (paddingStart != inset || paddingEnd != inset) {
                setPaddingRelative(inset, paddingTop, inset, paddingBottom)
            }
            fitTabs(width - 2 * inset)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /**
     * Sets the largest text size, up to the default, at which all three pills fit side by side in
     * [available] pixels with a small gap between them, so the labels never get different sizes.
     * Each tab is then as wide as its pill, plus an equal share of the space left (the tabs have
     * equal weights). If even the smallest size doesn't fit, the tabs get equal widths and the
     * longer labels are ellipsized, rather than every label losing the same width.
     */
    private fun fitTabs(available: Int) {
        if (tabs.isEmpty() || available <= 0) return
        val fixed = tabs.sumOf { it.paddingStart + it.paddingEnd } + minPillGap * (tabs.size - 1)
        fun fits(size: Float) = tabs.sumOf { textWidth(it, size).toDouble() } + fixed <= available
        measurePaint.set(tabs[0].paint)
        var size = maxTextSize
        while (size > minTextSize && !fits(size)) {
            size = (size - textSizeStep).coerceAtLeast(minTextSize)
        }
        val tabWidth = if (fits(size)) ViewGroup.LayoutParams.WRAP_CONTENT else 0
        tabs.forEach { tab ->
            if (tab.textSize != size) tab.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
            val params = tab.layoutParams
            if (params.width != tabWidth) {
                params.width = tabWidth
                tab.layoutParams = params
            }
        }
    }

    private fun textWidth(tab: TextView, size: Float): Float {
        measurePaint.typeface = tab.typeface
        measurePaint.textSize = size
        return measurePaint.measureText(tab.text, 0, tab.text.length)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        updateRippleMasks()
        if (pillAnimator?.isRunning != true) snapPill()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (pillAnimator?.isRunning == true) {
            pillAnimator?.cancel()
            pillAnimator = null
            snapPill()
        }
    }

    private fun snapPill() {
        val (left, right) = pillBounds(selectedTab.ordinal)
        pillLeft = left
        pillRight = right
        updateLabelColors()
        invalidate()
    }

    /**
     * Left and right edges of the pill for the tab at [index], in this view's coordinates, so
     * right-to-left layouts need no special case: the label's width plus the tab's padding,
     * centered on the tab and kept inside it.
     */
    private fun pillBounds(index: Int): Pair<Float, Float> {
        val view = tabs[index]
        if (view.width == 0) return 0f to 0f
        val layout = view.layout
        val labelWidth = if (layout != null && layout.lineCount > 0) {
            layout.getLineWidth(0)
        } else {
            view.paint.measureText(view.text, 0, view.text.length)
        }
        val maxWidth = view.width.toFloat()
        val width = (labelWidth + view.paddingStart + view.paddingEnd).coerceIn(min(pillMinWidth, maxWidth), maxWidth)
        val center = view.left + view.width / 2f
        return (center - width / 2) to (center + width / 2)
    }

    /** Keeps each tab's ripple to the area its pill takes when that tab is selected. */
    private fun updateRippleMasks() {
        tabs.forEachIndexed { index, view ->
            val mask = (view.background as? RippleDrawable)?.findDrawableByLayerId(android.R.id.mask) as? PillMask
                ?: return@forEachIndexed
            val (left, right) = pillBounds(index)
            if (right <= left) return@forEachIndexed
            mask.setInsets(
                (left - view.left).toInt(),
                view.paddingTop,
                (view.right - right).toInt(),
                view.paddingBottom,
            )
        }
    }

    /**
     * Label colors follow the pill: a label is in the selected color as far as the pill covers
     * its own pill area, so it changes color as the pill slides over it.
     */
    private fun updateLabelColors() {
        tabs.forEachIndexed { index, view ->
            val (left, right) = pillBounds(index)
            val coverage = if (right > left && pillRight > pillLeft) {
                ((min(right, pillRight) - max(left, pillLeft)) / (right - left)).coerceIn(0f, 1f)
            } else if (index == selectedTab.ordinal) {
                1f
            } else {
                0f
            }
            view.setTextColor(ColorUtils.blendARGB(unselectedTextColor, selectedTextColor, coverage))
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        // Behind the labels and their ripples.
        if (pillRight > pillLeft && tabs.isNotEmpty()) {
            val tab = tabs[0]
            val top = (tab.top + tab.paddingTop).toFloat()
            val bottom = (tab.bottom - tab.paddingBottom).toFloat()
            val radius = (bottom - top) / 2
            canvas.drawRoundRect(pillLeft, top, pillRight, bottom, radius, radius, pillPaint)
        }
        super.dispatchDraw(canvas)
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

    /**
     * Ripple mask in the shape of a tab's pill: a fully rounded rectangle, inset from the tab's
     * edges. Unlike layer insets, changed insets apply right away, without a bounds change.
     */
    private class PillMask : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val insets = Rect()
        private val pill = RectF()

        fun setInsets(left: Int, top: Int, right: Int, bottom: Int) {
            if (insets.left == left && insets.top == top && insets.right == right && insets.bottom == bottom) return
            insets.set(left, top, right, bottom)
            // Also tells the ripple to rebuild its mask.
            invalidateSelf()
        }

        private fun updatePill(): Float {
            val b = bounds
            pill.set(
                (b.left + insets.left).toFloat(),
                (b.top + insets.top).toFloat(),
                (b.right - insets.right).toFloat(),
                (b.bottom - insets.bottom).toFloat(),
            )
            return pill.height() / 2
        }

        override fun draw(canvas: Canvas) {
            val radius = updatePill()
            if (pill.width() > 0 && pill.height() > 0) canvas.drawRoundRect(pill, radius, radius, paint)
        }

        override fun getOutline(outline: Outline) {
            val radius = updatePill()
            outline.setRoundRect(
                pill.left.toInt(),
                pill.top.toInt(),
                pill.right.toInt(),
                pill.bottom.toInt(),
                radius,
            )
        }

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private companion object {
        const val PILL_SLIDE_DURATION_MS = 300L
    }
}
