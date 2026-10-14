package app.lawnchair.allapps.suggestions

import android.animation.Animator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.util.FloatProperty
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.Interpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.marginLeft
import androidx.core.view.marginRight
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.lawnchair.LawnchairLauncher
import app.lawnchair.allapps.AllAppsSearchInput
import app.lawnchair.font.FontManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.theme.color.tokens.ColorTokens
import app.lawnchair.theme.color.tokens.DayNightColorToken
import app.lawnchair.theme.color.tokens.Shade
import app.lawnchair.theme.color.tokens.Swatch
import app.lawnchair.theme.color.tokens.SwatchColorToken
import app.lawnchair.theme.color.tokens.setLStar
import app.lawnchair.util.shouldUseAlternateAllAppsTextColor
import com.android.app.animation.Interpolators
import com.android.launcher3.DeviceProfile
import com.android.launcher3.LauncherState
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.allapps.ActivityAllAppsContainerView
import com.android.launcher3.allapps.AllAppsStore
import com.android.launcher3.anim.AnimationSuccessListener
import com.android.launcher3.anim.SpringAnimationBuilder
import com.android.launcher3.statemanager.StateManager
import com.android.launcher3.util.Themes
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Frequent, Recent and New/Updated apps in the app drawer: a row of three tabs over up to two rows
 * of the drawer's own icons, shown inside the search bar, which grows down to hold them.
 *
 * It shows while the search box is in use with an empty query ([setSearchBoxState]): from a tap
 * on the search box until the user types, touches anything outside it, scrolls the list or leaves
 * search. The search bar and this view then make one shape, the grown bar: the search row as it
 * was, a hairline, the tabs and the icons, filled with the bar's own color, with the bar's corner
 * radius at the bottom too and a soft shadow under it. [ActivityAllAppsContainerView] adds this
 * view right under the bar, draws the grown bar behind it ([updateShape], [drawShape]) and keeps
 * the lists and the header from drawing under the bar, which is drawn over the A-Z list: the list
 * never moves for it. Meanwhile the bar's own background stays clear
 * ([AllAppsSearchInput.setBarBackgroundTakenOver]). It only shows in the launcher's own drawer: in
 * the taskbar and on other displays it stays gone.
 *
 * The panel keeps one height for all tabs (enough rows for the fullest tab), so switching tabs
 * never changes it. When every tab is empty it only has room for a one-line message. A new number
 * of rows is only applied while the panel is hidden.
 */
class AppSuggestionsHeaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs),
    DeviceProfile.OnDeviceProfileChangeListener,
    AllAppsStore.OnUpdateListener {

    private val launcher: LawnchairLauncher? = context.findLawnchairLauncher()
    private val featureOn = launcher != null && isEnabledFor(context)
    private val emptyHeight = resources.getDimensionPixelSize(R.dimen.drawer_suggestions_empty_height)
    private val contentPaddingTop = resources.getDimensionPixelSize(R.dimen.drawer_suggestions_content_padding_top)
    private val horizontalPadding = resources.getDimensionPixelSize(R.dimen.drawer_suggestions_padding_horizontal)
    private val hairlineHeight = resources.getDimensionPixelSize(R.dimen.drawer_suggestions_hairline_height).coerceAtLeast(1)
    private val hairlineGap = resources.getDimensionPixelSize(R.dimen.drawer_suggestions_hairline_gap)
    private val hairlineInset = resources.getDimension(R.dimen.drawer_suggestions_hairline_inset)
    private val shadowBlur = resources.getDimension(R.dimen.drawer_suggestions_shadow_blur)
    private val shadowOffset = resources.getDimension(R.dimen.drawer_suggestions_shadow_offset)
    private val contentSlide = resources.getDimension(R.dimen.drawer_suggestions_content_slide)
    private val loader by lazy { AppSuggestionsLoader(context) }

    private lateinit var tabBar: SuggestionTabBar
    private lateinit var content: View
    private lateinit var grid: SuggestionIconGrid
    private lateinit var emptyText: TextView

    private var scope: CoroutineScope? = null
    private var refreshJob: Job? = null

    /** The newest suggestions, shown as soon as no launch animation is running. */
    private var latest: AppSuggestions? = null

    /** Whether the panel has been sized for some suggestions; it stays gone until then. */
    private var sized = false

    /**
     * Rows of icons the panel is measured for. 0 when every tab is empty: the panel then only has
     * room for the empty-tab message.
     */
    private var reservedRows = 0

    /**
     * Set by a device profile change: the next suggestions, loaded for the new grid, may change the
     * number of rows even while the panel shows, because the drawer is laid out again anyway.
     */
    private var resizeOnNextLoad = false

    /** The search box state, see [setSearchBoxState]. */
    private var searchBoxFocused = false
    private var queryEmpty = true

    /**
     * Whether the search bar showed its own background right before the panel was asked to show,
     * so the grown bar starts out filled rather than fading in.
     */
    private var barFilledBeforeShow = false

    /** Whether the panel is shown, or showing; false while it hides. */
    private var showing = false

    /**
     * How far the bar has grown: 0 when it is only the search row, 1 when it holds all of the
     * panel. It may overshoot 1 a little on a spring.
     */
    private var reveal = 0f

    /**
     * How strongly the grown bar's fill, and with it its shadow, is drawn. It fades when the bar
     * has no background of its own before the panel shows or after it hides, for example when it
     * hides because the user types.
     */
    private var fillAlpha = 0f

    /** How strongly the hairline under the search row is drawn, for the entrance. */
    private var hairlineAlpha = 1f
    private var revealAnimator: Animator? = null
    private var entranceAnimator: Animator? = null

    /** Hides the grown bar once the search bar draws its own background again, see [animateOut]. */
    private val finishHide = Runnable { hideNow() }

    // The grown bar in the parent's coordinates, see updateShape.
    private val shapeRect = RectF()
    private var shapeRadius = 0f
    private var barBottom = 0f

    /** The outline of the grown bar, in the parent's coordinates, as of the last [updateShape]. */
    val shapePath = Path()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val protectionPaint = Paint()
    private val hairlinePaint = Paint()
    private var fillColor = 0
    private var shadowColor = 0
    private var hairlineColor = 0

    /** Where the bar's sides are while the search box has focus, as insets from this view's sides. */
    private var barInsetLeft = 0
    private var barInsetRight = 0

    /** The search box that [barLayoutListener] listens to. */
    private var observedInput: AllAppsSearchInput? = null

    /** Redraws the grown bar when the search bar moves or changes size, for example as it widens. */
    private val barLayoutListener = OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        if (visibility == VISIBLE) invalidateParent()
    }

    private var uiPaused = false
    private val resumeUiUpdates = Runnable { setUiUpdatesPaused(false) }
    private var selectedTab = lastPickedTab ?: SuggestionTab.FREQUENT

    @StringRes
    private var emptyTextRes = 0

    init {
        // Draws the hairline under the search row, see onDraw.
        setWillNotDraw(false)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        tabBar = ViewCompat.requireViewById(this, R.id.suggestion_tab_bar)
        content = ViewCompat.requireViewById(this, R.id.suggestion_content)
        grid = ViewCompat.requireViewById(this, R.id.suggestion_grid)
        emptyText = ViewCompat.requireViewById(this, R.id.suggestion_empty)
        FontManager.INSTANCE.get(context).setCustomFont(emptyText, R.id.font_body)
        tabBar.onTabPicked = ::onTabPicked
        if (featureOn) tabBar.indicatorStyle = PreferenceManager2.getInstance(context).drawerSuggestionTabIndicator.firstCached()
    }

    // The grown search bar

    /**
     * The drawer's search box, while it sits in the same parent as this view, as it must for the
     * bar to grow into the panel (not with a floating search bar).
     */
    private fun searchInput(): AllAppsSearchInput? {
        val input = launcher?.appsView?.searchUiManager as? AllAppsSearchInput ?: return null
        return input.takeIf { it.parent === parent && it.isVisible }
    }

    private fun invalidateParent() {
        (parent as? View)?.invalidate()
    }

    /** Whether the grown bar shows over the lists, so they must leave its area to it. */
    fun isCoveringContent(): Boolean = featureOn && visibility == VISIBLE && searchInput() != null

    /**
     * Works out where the grown bar is right now, in the parent's coordinates: from the search
     * bar's top to as far as it has grown into this view, as wide as the search bar. Returns
     * whether it shows. The parent calls this once a frame, before drawing its children.
     */
    fun updateShape(): Boolean {
        if (!featureOn || visibility != VISIBLE) return false
        val input = searchInput() ?: return false
        val bar = input.barView
        if (bar.width == 0 || bar.height == 0) return false
        val left = input.left + input.translationX + bar.left + bar.translationX
        val top = input.top + input.translationY + bar.top + bar.translationY
        barBottom = top + bar.height
        val fullBottom = this.top + translationY + height
        val bottom = barBottom + max(0f, fullBottom - barBottom) * reveal.coerceIn(0f, MAX_REVEAL)
        shapeRect.set(left, top, left + bar.width, bottom)
        shapeRadius = min(input.barCornerRadius(), bar.height / 2f)
        shapePath.rewind()
        shapePath.addRoundRect(shapeRect, shapeRadius, shapeRadius, Path.Direction.CW)
        return true
    }

    /**
     * Whether ([x], [y]), in the parent's coordinates, is on the grown bar. While it grows, that
     * is where it is growing to, so a quick tap on a tab or an icon that is still on its way in
     * isn't taken for one outside it.
     */
    fun coversPoint(x: Float, y: Float): Boolean = updateShape() && shapeContains(x, y, grown = showing)

    /**
     * Whether a touch that lands at ([x], [y]), in the parent's coordinates, closes the panel: it
     * shows, or is showing, and the touch is outside the grown bar. See
     * [ActivityAllAppsContainerView.dispatchTouchEvent].
     */
    fun shouldCloseOnTouchDown(x: Float, y: Float): Boolean = showing && updateShape() && !shapeContains(x, y, grown = true)

    /** Whether the bar, as it is now or once fully [grown], covers ([x], [y]). */
    private fun shapeContains(x: Float, y: Float, grown: Boolean): Boolean {
        val bottom = if (grown) max(shapeRect.bottom, top + translationY + height) else shapeRect.bottom
        if (x < shapeRect.left || x >= shapeRect.right || y < shapeRect.top || y >= bottom) return false
        val r = shapeRadius
        val dx = x - x.coerceIn(shapeRect.left + r, shapeRect.right - r)
        val dy = y - y.coerceIn(shapeRect.top + r, bottom - r)
        return dx * dx + dy * dy <= r * r
    }

    /**
     * Draws the grown bar on [canvas], the parent's, behind this view and the search bar: its soft
     * shadow over the lists below it, the header protection that shows behind the search row when
     * the list is scrolled, continued down, and its fill. Call [updateShape] first.
     */
    fun drawShape(canvas: Canvas) {
        val container = parent as? ActivityAllAppsContainerView<*> ?: return
        val alpha = fillAlpha * (searchInput()?.alpha ?: 1f)
        if (alpha <= 0f) return
        val r = shapeRadius

        // Fades in as the bar grows: the search bar alone has none.
        val growth = ((shapeRect.bottom - barBottom) / max(1f, height.toFloat())).coerceIn(0f, 1f)
        val shadowStrength = alpha * min(1f, growth * SHADOW_FADE_IN_SPEED)
        if (shadowStrength > 0f && Color.alpha(shadowColor) != 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val saveCount = canvas.save()
            // Only outside the bar, so a see-through fill doesn't show it.
            canvas.clipOutPath(shapePath)
            shadowPaint.setShadowLayer(
                shadowBlur,
                0f,
                shadowOffset,
                ColorUtils.setAlphaComponent(shadowColor, (Color.alpha(shadowColor) * shadowStrength).roundToInt()),
            )
            // A pixel inside the bar, so that the shape casting the shadow is clipped away whole.
            canvas.drawRoundRect(shapeRect.left + 1, shapeRect.top + 1, shapeRect.right - 1, shapeRect.bottom - 1, r, r, shadowPaint)
            canvas.restoreToCount(saveCount)
        }

        // The lists don't draw under the bar, so the protection that shows through the search
        // row's fill would stop where the header protection does.
        val protection = container.headerProtectionColor
        val protectionTop = max(barBottom, container.headerProtectionBottom)
        if (Color.alpha(protection) != 0 && protectionTop < shapeRect.bottom) {
            val saveCount = canvas.save()
            canvas.clipPath(shapePath)
            canvas.clipRect(shapeRect.left, protectionTop, shapeRect.right, shapeRect.bottom)
            protectionPaint.color = protection
            protectionPaint.alpha = (Color.alpha(protection) * alpha).roundToInt()
            canvas.drawRect(shapeRect, protectionPaint)
            canvas.restoreToCount(saveCount)
        }

        fillPaint.color = fillColor
        fillPaint.alpha = (Color.alpha(fillColor) * alpha).roundToInt()
        canvas.drawRoundRect(shapeRect, r, r, fillPaint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // The hairline under the search row, at the top of this view, which starts where the
        // search bar ends.
        val alpha = (Color.alpha(hairlineColor) * hairlineAlpha).roundToInt()
        if (alpha > 0) {
            hairlinePaint.color = hairlineColor
            hairlinePaint.alpha = alpha
            canvas.drawRect(barInsetLeft + hairlineInset, 0f, width - barInsetRight - hairlineInset, hairlineHeight.toFloat(), hairlinePaint)
        }
    }

    /**
     * Grows the bar to [value] (see [reveal]), with the content sliding down into place as it
     * opens. Only render properties change, so nothing is laid out again.
     */
    private fun setReveal(value: Float) {
        if (reveal == value) return
        reveal = value
        val slide = (1f - value).coerceAtLeast(0f) * -contentSlide
        tabBar.translationY = slide
        content.translationY = slide
        invalidateParent()
    }

    private fun setFillAlpha(value: Float) {
        if (fillAlpha == value) return
        fillAlpha = value
        invalidateParent()
    }

    private fun setHairlineAlpha(value: Float) {
        if (hairlineAlpha == value) return
        hairlineAlpha = value
        invalidate()
    }

    /** Hands the search bar's background to the grown bar, or back. */
    private fun setBarTakenOver(takenOver: Boolean) {
        launcher?.appsView?.searchUiManager?.let { (it as? AllAppsSearchInput)?.setBarBackgroundTakenOver(takenOver) }
    }

    /** Takes the touches on the grown bar, so none reaches the list under it. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN &&
            !(showing && coversPoint(ev.x + left + translationX, ev.y + top + translationY))
        ) {
            return false
        }
        return super.dispatchTouchEvent(ev)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        super.onTouchEvent(event)
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The tabs and icons sit inside the bar, which is wider while the search box has focus.
        val input = searchInput()
        if (input != null) {
            barInsetLeft = input.marginLeft + input.focusedBarPaddingLeft
            barInsetRight = input.marginRight + input.focusedBarPaddingRight
        } else {
            val padding = launcher?.deviceProfile?.allAppsPadding
            barInsetLeft = padding?.left ?: 0
            barInsetRight = padding?.right ?: 0
        }
        val left = barInsetLeft + horizontalPadding
        val right = barInsetRight + horizontalPadding
        val top = hairlineHeight + hairlineGap
        if (paddingLeft != left || paddingRight != right || paddingTop != top) {
            setPadding(left, top, right, paddingBottom)
        }
        // The tab bar is as tall as its labels need, which depends on the width it gets.
        tabBar.measure(
            getChildMeasureSpec(widthMeasureSpec, paddingLeft + paddingRight, LayoutParams.MATCH_PARENT),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val height = paddingTop + tabBar.measuredHeight + contentHeight() + paddingBottom
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    override fun hasOverlappingRendering(): Boolean = false

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.containerTitle = context.getString(R.string.title_app_suggestions)
        }
    }

    // Lifecycle

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val launcher = launcher?.takeIf { featureOn } ?: return
        val appsStore = launcher.appsView?.appsStore ?: return
        val newScope = MainScope()
        scope = newScope
        launcher.addOnDeviceProfileChangeListener(this)
        appsStore.addUpdateListener(this)
        // Lets the store update notification dots and install progress on these icons.
        appsStore.registerIconContainer(grid)
        launcher.stateManager.addStateListener(stateListener)
        launcher.lifecycle.addObserver(lifecycleObserver)
        AppLaunchStore.getInstance(context).revisionFlow.drop(1).onEach { refresh() }.launchIn(newScope)
        val prefs = PreferenceManager2.getInstance(context)
        prefs.hiddenApps.get().drop(1).onEach { refresh() }.launchIn(newScope)
        prefs.drawerSuggestionTabIndicator.get().onEach { tabBar.indicatorStyle = it }.launchIn(newScope)
        searchInput()?.let { input ->
            observedInput = input
            input.addOnLayoutChangeListener(barLayoutListener)
            input.barView.addOnLayoutChangeListener(barLayoutListener)
        }
        applyColors()
        refresh()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        val launcher = launcher?.takeIf { featureOn } ?: return
        removeCallbacks(resumeUiUpdates)
        scope?.cancel()
        scope = null
        refreshJob = null
        launcher.removeOnDeviceProfileChangeListener(this)
        launcher.appsView?.appsStore?.let { appsStore ->
            appsStore.removeUpdateListener(this)
            appsStore.unregisterIconContainer(grid)
        }
        launcher.stateManager.removeStateListener(stateListener)
        launcher.lifecycle.removeObserver(lifecycleObserver)
        observedInput?.let { input ->
            input.removeOnLayoutChangeListener(barLayoutListener)
            input.barView.removeOnLayoutChangeListener(barLayoutListener)
        }
        observedInput = null
        listOf(grid, emptyText).forEach {
            it.animate().cancel()
            it.alpha = 1f
        }
        cancelAnimations()
        if (showing) {
            setReveal(1f)
            setFillAlpha(1f)
            resetContent()
        } else {
            hideNow()
        }
    }

    override fun onAppsUpdated() {
        // The app list changes in bursts, for example at every progress step of an install.
        refresh(debounce = true)
    }

    override fun onDeviceProfileChanged(dp: DeviceProfile) {
        // Icon views take their sizes from the device profile when they are created.
        grid.clearCells()
        showLatest(allowHeightChange = true)
        // The current suggestions were loaded for the old grid (for example one row in landscape),
        // so let the ones for the new grid resize the panel too, as soon as they are ready.
        resizeOnNextLoad = true
        refresh()
    }

    /**
     * Pauses updates while an app opens, so the icon being launched doesn't change or move under
     * the launch animation. Called by [LawnchairLauncher.startActivitySafely], which resumes them
     * when the launch animation ends.
     *
     * That end callback doesn't come for every launch: without a launch animation (animations off,
     * or no remote transition, which is the case when Lawnchair isn't the recents provider) it is
     * never run. So a pause also ends after [MAX_UI_PAUSE_MS], when the launcher stops, and when the
     * drawer starts to open, whichever comes first.
     */
    fun setUiUpdatesPaused(paused: Boolean) {
        removeCallbacks(resumeUiUpdates)
        if (paused) postDelayed(resumeUiUpdates, MAX_UI_PAUSE_MS)
        if (uiPaused == paused) return
        uiPaused = paused
        if (!paused) showLatest(allowHeightChange = !showing)
    }

    /** Ends a pause from [setUiUpdatesPaused] without showing anything; the caller does. */
    private fun clearUiPause() {
        removeCallbacks(resumeUiUpdates)
        uiPaused = false
    }

    /**
     * Shows the panel while the search box is [focused] and the query is empty: on a tap on the
     * search box (the drawer stays, with the keyboard up), and when the query is cleared. Typing,
     * a touch outside the grown bar, dragging the list (which takes the focus) and leaving search
     * hide it. Called by the search box; closing the drawer hides it here too.
     */
    fun setSearchBoxState(focused: Boolean, queryEmpty: Boolean) {
        if (!featureOn) return
        val sessionStarts = focused && !searchBoxFocused
        // An unfocused empty search bar shows its own background, see AllAppsSearchInput.
        barFilledBeforeShow = !searchBoxFocused && this.queryEmpty
        searchBoxFocused = focused
        this.queryEmpty = queryEmpty
        if (sessionStarts) {
            showLatest(allowHeightChange = !showing)
            refresh()
        } else {
            updateShown()
        }
    }

    // Data

    /**
     * Loads the suggestions again and shows them. With [debounce], waits for [REFRESH_DEBOUNCE_MS]
     * first, so a burst of calls loads once; any newer call replaces a pending or running load.
     */
    private fun refresh(debounce: Boolean = false) {
        val launcher = launcher ?: return
        val currentScope = scope ?: return
        refreshJob?.cancel()
        refreshJob = currentScope.launch {
            if (debounce) delay(REFRESH_DEBOUNCE_MS)
            val apps = launcher.appsView?.appsStore?.apps ?: return@launch
            // Empty until the app model has loaded; onAppsUpdated() comes back here then.
            if (apps.isEmpty()) return@launch
            val dp = launcher.deviceProfile
            val limit = dp.numShownAllAppsColumns * maxRows(dp)
            val snapshot = try {
                loader.load(apps, limit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the suggestions already shown rather than taking the launcher down.
                Log.w(TAG, "Unable to load app suggestions", e)
                return@launch
            }
            latest = snapshot
            val resize = resizeOnNextLoad
            resizeOnNextLoad = false
            showLatest(allowHeightChange = resize || !showing)
        }
    }

    /**
     * Shows [latest]. Its apps are bound right away; a change in the number of rows (and so in the
     * panel height) waits for [allowHeightChange], which callers pass while the panel is hidden.
     */
    private fun showLatest(allowHeightChange: Boolean) {
        val snapshot = latest
        val dp = launcher?.deviceProfile
        if (snapshot != null && dp != null && !uiPaused) {
            if (allowHeightChange) {
                val rows = snapshot.rowsNeeded(dp.numShownAllAppsColumns, maxRows(dp))
                if (rows != reservedRows || !sized) {
                    reservedRows = rows
                    requestLayout()
                }
                sized = true
                // Until the user picks a tab, open on Frequent, or on the first tab with apps. Not
                // while the panel shows, so the tab doesn't change under the user.
                if (lastPickedTab == null) selectedTab = snapshot.firstNonEmptyTab(SuggestionTab.FREQUENT)
            }
            if (sized) bindSelectedTab(animate = false)
        }
        updateShown()
    }

    /** Shows the panel while the search box is in use with an empty query, once it has suggestions. */
    private fun updateShown(animate: Boolean = true) {
        val show = featureOn && sized && searchBoxFocused && queryEmpty && searchInput() != null
        if (show == showing) return
        showing = show
        val animated = animate && mayAnimate()
        removeCallbacks(finishHide)
        if (show) {
            visibility = VISIBLE
            setBarTakenOver(true)
            if (animated) {
                animateIn()
            } else {
                cancelAnimations()
                resetContent()
                setFillAlpha(1f)
                setReveal(1f)
            }
        } else if (animated && visibility == VISIBLE) {
            animateOut()
        } else {
            cancelAnimations()
            hideNow()
        }
        barFilledBeforeShow = false
    }

    /**
     * Opens the panel: the search bar grows down to hold it on a gentle spring while its content
     * slides down into place, the hairline and the tabs fade in, the selected tab's indicator
     * draws in and the icons pop in one after another in reading order. Coming back from a close
     * that has only just started, the content simply fades back in.
     */
    private fun animateIn() {
        cancelAnimations()
        val fresh = reveal < 0.5f
        if (fresh) {
            tabBar.alpha = 0f
            tabBar.indicatorReveal = 0f
            setHairlineAlpha(0f)
            // The empty-tab message fades in as a whole; icons each on their own.
            content.alpha = if (emptyText.isVisible) 0f else 1f
            grid.setEntranceTime(0f)
        } else {
            tabBar.indicatorReveal = 1f
            grid.endEntrance()
        }
        // The bar grows out of the search bar's own background, or fades in where there was none,
        // for example when the query is cleared.
        if (barFilledBeforeShow) setFillAlpha(1f)
        val startFillAlpha = fillAlpha
        val startTabAlpha = tabBar.alpha
        val startContentAlpha = content.alpha
        val startHairlineAlpha = hairlineAlpha

        revealAnimator = SpringAnimationBuilder(context)
            .setStartValue(reveal)
            .setEndValue(1f)
            .setDampingRatio(REVEAL_DAMPING_RATIO)
            .setStiffness(REVEAL_STIFFNESS)
            // Under a pixel of the panel, which may not have been laid out yet.
            .setMinimumVisibleChange(REVEAL_MIN_VISIBLE_CHANGE)
            .build(this, REVEAL)
            .also { it.start() }

        val duration = if (fresh) max(ENTRANCE_INDICATOR_DELAY_MS + ENTRANCE_INDICATOR_DURATION_MS, ENTRANCE_ICONS_DELAY_MS + grid.entranceDuration()) else RETURN_DURATION_MS
        entranceAnimator = ValueAnimator.ofFloat(0f, duration.toFloat()).apply {
            this.duration = duration
            interpolator = Interpolators.LINEAR
            addUpdateListener { animator ->
                val time = animator.animatedValue as Float
                setFillAlpha(Utilities.mapRange(progressAt(time, 0L, FILL_FADE_DURATION_MS, Interpolators.LINEAR), startFillAlpha, 1f))
                if (fresh) {
                    val tabs = progressAt(time, ENTRANCE_TABS_DELAY_MS, ENTRANCE_TABS_DURATION_MS, Interpolators.EMPHASIZED_DECELERATE)
                    tabBar.alpha = tabs
                    setHairlineAlpha(tabs)
                    tabBar.indicatorReveal = progressAt(time, ENTRANCE_INDICATOR_DELAY_MS, ENTRANCE_INDICATOR_DURATION_MS, Interpolators.EMPHASIZED_DECELERATE)
                    content.alpha = Utilities.mapRange(progressAt(time, ENTRANCE_ICONS_DELAY_MS, ENTRANCE_TABS_DURATION_MS, Interpolators.EMPHASIZED_DECELERATE), startContentAlpha, 1f)
                    grid.setEntranceTime(time - ENTRANCE_ICONS_DELAY_MS)
                } else {
                    val progress = Interpolators.EMPHASIZED_DECELERATE.getInterpolation(animator.animatedFraction)
                    tabBar.alpha = Utilities.mapRange(progress, startTabAlpha, 1f)
                    content.alpha = Utilities.mapRange(progress, startContentAlpha, 1f)
                    setHairlineAlpha(Utilities.mapRange(progress, startHairlineAlpha, 1f))
                }
            }
            addListener(
                object : AnimationSuccessListener() {
                    override fun onAnimationSuccess(animation: Animator) {
                        entranceAnimator = null
                        resetContent()
                        setFillAlpha(1f)
                    }
                },
            )
            start()
        }
    }

    /**
     * Closes the panel quickly: the bar shrinks back into the search bar while the content fades
     * and slides up. When the search bar then shows its own background (the search box no longer
     * has focus and the query is empty), the bar keeps its fill and hands it over to the search
     * bar at the end; otherwise, for example when the user types, the fill fades out with it.
     */
    private fun animateOut() {
        cancelAnimations()
        val handOver = !searchBoxFocused && queryEmpty
        val startReveal = reveal
        val startFillAlpha = fillAlpha
        val startTabAlpha = tabBar.alpha
        val startContentAlpha = content.alpha
        val startHairlineAlpha = hairlineAlpha
        revealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = HIDE_DURATION_MS
            interpolator = Interpolators.LINEAR
            addUpdateListener { animator ->
                val fraction = animator.animatedFraction
                setReveal(Utilities.mapRange(Interpolators.EMPHASIZED_ACCELERATE.getInterpolation(fraction), startReveal, 0f))
                setFillAlpha(Utilities.mapRange(fraction, startFillAlpha, if (handOver) 1f else 0f))
                val fade = 1f - (fraction / HIDE_FADE_SHARE).coerceAtMost(1f)
                tabBar.alpha = startTabAlpha * fade
                content.alpha = startContentAlpha * fade
                setHairlineAlpha(startHairlineAlpha * fade)
            }
            addListener(
                object : AnimationSuccessListener() {
                    override fun onAnimationSuccess(animation: Animator) {
                        revealAnimator = null
                        if (handOver) {
                            // The search bar draws its background again from its next frame;
                            // until then, the shrunk bar stands in for it.
                            setBarTakenOver(false)
                            postDelayed(finishHide, HAND_OVER_DELAY_MS)
                        } else {
                            hideNow()
                        }
                    }
                },
            )
            start()
        }
    }

    /** Hides the panel and the grown bar right away, and gives the search bar its background back. */
    private fun hideNow() {
        removeCallbacks(finishHide)
        setReveal(0f)
        setFillAlpha(0f)
        visibility = GONE
        resetContent()
        setBarTakenOver(false)
        invalidateParent()
    }

    /** Stops the panel's animations where they are. */
    private fun cancelAnimations() {
        revealAnimator?.cancel()
        revealAnimator = null
        entranceAnimator?.cancel()
        entranceAnimator = null
    }

    /** Shows the panel's content as usual, after or without an animation. */
    private fun resetContent() {
        tabBar.alpha = 1f
        tabBar.indicatorReveal = 1f
        content.alpha = 1f
        setHairlineAlpha(1f)
        grid.endEntrance()
    }

    /**
     * Whether the panel may animate: animations are on and the drawer may be on screen, that is
     * the launcher is started and the drawer is open or a state transition is running.
     */
    private fun mayAnimate(): Boolean {
        val launcher = launcher ?: return false
        if (!ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow || !launcher.isStarted) return false
        val stateManager = launcher.stateManager
        return stateManager.state == LauncherState.ALL_APPS || stateManager.isInTransition
    }

    private fun onTabPicked(tab: SuggestionTab) {
        if (tab == selectedTab || latest == null) return
        selectedTab = tab
        lastPickedTab = tab
        if (PreferenceManager2.getInstance(context).appDrawerHapticFeedback.firstCached()) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        bindSelectedTab(animate = true)
    }

    /** Shows the selected tab's apps, or its empty-tab message. [animate] for a tab the user picked. */
    private fun bindSelectedTab(animate: Boolean) {
        val snapshot = latest ?: return
        val columns = launcher?.deviceProfile?.numShownAllAppsColumns ?: return
        val tabApps = snapshot.appsFor(selectedTab)
        tabBar.select(selectedTab, animate)
        grid.bind(tabApps.take(columns * reservedRows), columns, reservedRows)

        // The message only for a tab that really has no apps. A tab whose first apps arrive while
        // the panel shows has no rows for them yet, and stays blank until the panel grows.
        val isEmpty = tabApps.isEmpty()
        // TalkBack reads the message when the user switches to an empty tab, but not when the
        // panel is updated in the background, for example while the drawer is closed.
        emptyText.accessibilityLiveRegion = if (animate) ACCESSIBILITY_LIVE_REGION_POLITE else ACCESSIBILITY_LIVE_REGION_NONE
        if (isEmpty) {
            val textRes = emptyTextFor(selectedTab)
            if (textRes != emptyTextRes) {
                emptyTextRes = textRes
                emptyText.setText(textRes)
            }
        }
        emptyText.isVisible = isEmpty

        val shown: View = if (isEmpty) emptyText else grid
        shown.animate().cancel()
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            shown.alpha = 0f
            shown.animate()
                .alpha(1f)
                .setDuration(TAB_FADE_DURATION_MS)
                .setInterpolator(Interpolators.EMPHASIZED_DECELERATE)
                .start()
        } else {
            shown.alpha = 1f
        }
    }

    // Looks

    /**
     * The selected tab uses the colors of Lawnchair's selected chips ([PILL_COLOR] and
     * [SELECTED_LABEL_COLOR], also used for the line indicator), which follow the wallpaper or the
     * custom accent color. The other
     * labels and the empty-tab message use the color of the drawer's icon labels, including the
     * alternate color on light or see-through drawers, so they match custom drawer colors.
     */
    private fun applyColors() {
        val labelColor = Themes.getAttrColor(
            context,
            if (shouldUseAlternateAllAppsTextColor(context)) R.attr.allAppsAlternateTextColor else android.R.attr.textColorSecondary,
        )
        val selectedColor = SELECTED_LABEL_COLOR.resolveColor(context)
        tabBar.applyColors(
            selectedTextColor = selectedColor,
            unselectedTextColor = labelColor,
            pillColor = PILL_COLOR.resolveColor(context),
            lineColor = selectedColor,
            rippleColor = Themes.getAttrColor(context, android.R.attr.colorControlHighlight),
        )
        emptyText.setTextColor(
            ColorUtils.setAlphaComponent(labelColor, (Color.alpha(labelColor) * EMPTY_TEXT_ALPHA).roundToInt()),
        )
        // The grown bar takes the search bar's own fill, with a hairline in Material's outline
        // variant color under the search row.
        fillColor = searchInput()?.barFillColor() ?: 0
        hairlineColor = HAIRLINE_COLOR.resolveColor(context)
        // Light labels mean a dark drawer, where the shadow needs to be stronger to show.
        val darkDrawer = ColorUtils.calculateLuminance(labelColor) > 0.5
        shadowColor = ColorUtils.setAlphaComponent(Color.BLACK, if (darkDrawer) SHADOW_ALPHA_DARK else SHADOW_ALPHA_LIGHT)
        invalidate()
        invalidateParent()
    }

    /** Height below the tabs: the rows of icons, or one line for the message if every tab is empty. */
    private fun contentHeight(): Int = contentPaddingTop + if (reservedRows > 0) reservedRows * grid.rowHeight() else emptyHeight

    private val stateListener = object : StateManager.StateListener<LauncherState> {
        override fun onStateTransitionStart(toState: LauncherState) {
            if (toState == LauncherState.ALL_APPS) {
                applyColors()
                // Any launch that paused the updates is over by the time the drawer opens again.
                clearUiPause()
                showLatest(allowHeightChange = !showing)
                refresh()
            }
        }

        override fun onStateTransitionComplete(finalState: LauncherState) {
            if (finalState != LauncherState.ALL_APPS) {
                // In case the search box kept its focus. The drawer is gone, so no animation.
                searchBoxFocused = false
                updateShown(animate = false)
                showLatest(allowHeightChange = true)
            }
        }
    }

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            // The opened app now covers the launcher, so its launch no longer needs the pause,
            // and the panel may change height while nobody sees it.
            clearUiPause()
            showLatest(allowHeightChange = true)
        }
    }

    companion object {
        private const val TAG = "AppSuggestionsHeader"
        private const val TAB_FADE_DURATION_MS = 150L
        private const val EMPTY_TEXT_ALPHA = 0.8f

        /** The spring the bar grows on: a little softer than the drawer's own, barely bouncing. */
        private const val REVEAL_STIFFNESS = 600f
        private const val REVEAL_DAMPING_RATIO = 0.82f

        /** How far past its full height the bar may grow while it bounces into place. */
        private const val MAX_REVEAL = 1.1f
        private const val REVEAL_MIN_VISIBLE_CHANGE = 0.001f

        // When each part of the panel's content comes in, from the start of its opening.
        private const val ENTRANCE_TABS_DELAY_MS = 60L
        private const val ENTRANCE_TABS_DURATION_MS = 220L
        private const val ENTRANCE_INDICATOR_DELAY_MS = 140L
        private const val ENTRANCE_INDICATOR_DURATION_MS = 300L
        private const val ENTRANCE_ICONS_DELAY_MS = 120L

        /** The fill fading in, when the search bar had no background of its own. */
        private const val FILL_FADE_DURATION_MS = 100L

        /** Fading back in after a close that was cut short. */
        private const val RETURN_DURATION_MS = 150L

        /** Closing, and the share of it that the content takes to fade out. */
        private const val HIDE_DURATION_MS = 150L
        private const val HIDE_FADE_SHARE = 0.66f

        /**
         * How long the shrunk bar stays after handing the background back to the search bar, which
         * draws it again from its next frame or so; a little overlap rather than a frame without.
         */
        private const val HAND_OVER_DELAY_MS = 50L

        private val REVEAL = object : FloatProperty<AppSuggestionsHeaderView>("reveal") {
            override fun setValue(view: AppSuggestionsHeaderView, value: Float) = view.setReveal(value)

            override fun get(view: AppSuggestionsHeaderView): Float = view.reveal
        }

        /**
         * Progress, eased by [interpolator], of a part of an animation that starts [delay]
         * milliseconds in and lasts [duration], at [time] milliseconds into the animation.
         */
        private fun progressAt(time: Float, delay: Long, duration: Long, interpolator: Interpolator): Float = interpolator.getInterpolation(((time - delay) / duration).coerceIn(0f, 1f))

        /** Strength of the shadow under the grown bar. */
        private const val SHADOW_ALPHA_LIGHT = 0x29
        private const val SHADOW_ALPHA_DARK = 0x59

        /** The shadow is at full strength once the bar has grown a third of the way. */
        private const val SHADOW_FADE_IN_SPEED = 3f

        /** Material's outline variant color, for the hairline under the search row. */
        private val HAIRLINE_COLOR = DayNightColorToken(ColorTokens.Neutral2_500.setLStar(80.0), ColorTokens.Neutral2_500.setLStar(30.0))

        /**
         * The selected tab's pill and label: the colors of a selected chip in Lawnchair's settings
         * (`ui/preferences/components/layout/Chip.kt`), that is Material's secondary container
         * and primary colors.
         */
        private val PILL_COLOR = DayNightColorToken(ColorTokens.Accent2_100, SwatchColorToken(Swatch.Accent2, Shade.S700))
        private val SELECTED_LABEL_COLOR = DayNightColorToken(ColorTokens.Accent1_600, ColorTokens.Accent1_200)

        /** Longer than any launch animation; see [setUiUpdatesPaused]. */
        private const val MAX_UI_PAUSE_MS = 2000L

        /** See [refresh]. */
        private const val REFRESH_DEBOUNCE_MS = 300L

        /** The tab the user picked last. Kept for the whole process, so it survives the launcher being recreated. */
        private var lastPickedTab: SuggestionTab? = null

        /**
         * Whether the suggestion tabs are shown in the drawer of [context]: only in Lawnchair's own
         * launcher, and only while the setting is on and the drawer has its search bar, which they
         * show under; the settings say so.
         */
        @JvmStatic
        fun isEnabledFor(context: Context): Boolean {
            if (context.findLawnchairLauncher() == null) return false
            val prefs = PreferenceManager2.getInstance(context)
            return prefs.drawerSuggestionTabs.firstCached() && !prefs.hideAppDrawerSearchBar.firstCached()
        }

        /** Two rows of icons, or one on a phone in landscape, where the screen is short. */
        private fun maxRows(dp: DeviceProfile): Int = if (dp.deviceProperties.isPhone && dp.deviceProperties.isLandscape) 1 else 2

        @StringRes
        private fun emptyTextFor(tab: SuggestionTab): Int = when (tab) {
            SuggestionTab.FREQUENT -> R.string.drawer_suggestions_empty_frequent
            SuggestionTab.RECENT -> R.string.drawer_suggestions_empty_recent
            SuggestionTab.NEW -> R.string.drawer_suggestions_empty_new
        }
    }
}

/**
 * The [LawnchairLauncher] that [this] belongs to, or null in the other drawers that inflate the
 * same panel (taskbar all apps, secondary displays). Unlike [app.lawnchair.launcher], it never
 * throws there.
 */
internal fun Context.findLawnchairLauncher(): LawnchairLauncher? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is LawnchairLauncher) return current
        current = current.baseContext
    }
    return null
}

/**
 * Rows of icons for the fullest tab, at most [maxRows], so the panel keeps one height whichever
 * tab is selected. 0 when every tab is empty.
 */
internal fun AppSuggestions.rowsNeeded(columns: Int, maxRows: Int): Int {
    val mostApps = SuggestionTab.entries.maxOf { appsFor(it).size }
    val perRow = columns.coerceAtLeast(1)
    return ((mostApps + perRow - 1) / perRow).coerceIn(0, maxRows)
}
