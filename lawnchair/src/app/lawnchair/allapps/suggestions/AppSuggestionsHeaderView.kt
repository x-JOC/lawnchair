package app.lawnchair.allapps.suggestions

import android.animation.Animator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.util.FloatProperty
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.Interpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.lawnchair.LawnchairLauncher
import app.lawnchair.font.FontManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.theme.color.tokens.ColorTokens
import app.lawnchair.theme.color.tokens.DayNightColorToken
import app.lawnchair.theme.color.tokens.Shade
import app.lawnchair.theme.color.tokens.Swatch
import app.lawnchair.theme.color.tokens.SwatchColorToken
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
import kotlin.math.ceil
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
 * Frequent, Recent and New/Updated apps in the app drawer, right under the search box: a row of
 * three tabs over up to two rows of the drawer's own icons.
 *
 * It shows while the search box is in use with an empty query ([setSearchBoxState]): from a tap
 * on the search box until the user types, scrolls the list or leaves search. Like Nova's, it is
 * drawn over the A-Z list, which never moves for it: [ActivityAllAppsContainerView] adds it above
 * the lists and below the search bar, and keeps the lists and the header from drawing under it
 * ([clipOutCoveredArea]), so it sits on the drawer background itself, whatever its color, opacity
 * or blur, with a soft shadow over the list below ([drawShadow]). It takes all touches on it; the
 * list below takes the rest. It only shows in the launcher's own drawer: in the taskbar and on
 * other displays it stays gone.
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
    private val cornerRadius = resources.getDimension(R.dimen.drawer_suggestions_panel_corner_radius)
    private val shadowHeight = resources.getDimension(R.dimen.drawer_suggestions_panel_shadow_height)
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

    /** Whether the panel is shown, or showing; false while it hides. */
    private var showing = false

    /**
     * How much of the panel is shown, from its top: 0 when hidden, 1 when all of it shows. It
     * reveals the panel downwards from the search bar, and may overshoot 1 a little on a spring.
     */
    private var reveal = 0f
    private var revealAnimator: Animator? = null
    private var entranceAnimator: Animator? = null
    private val revealClip = Rect()

    private val coverRect = RectF()
    private val coverPath = Path()
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shadowColor = 0

    private var uiPaused = false
    private val resumeUiUpdates = Runnable { setUiUpdatesPaused(false) }
    private var selectedTab = lastPickedTab ?: SuggestionTab.FREQUENT

    @StringRes
    private var emptyTextRes = 0

    init {
        // Draws the header protection when the list is scrolled, see onDraw.
        setWillNotDraw(false)
        // Only the revealed part of the panel shows, with rounded bottom corners. The outline runs
        // a corner radius above the top, so that only the bottom corners are rounded.
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, -cornerRadius.roundToInt(), view.width, coveredHeight().roundToInt(), coverRadius())
            }
        }
        clipToOutline = true
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

    // Overlay

    /** The part of the panel's height that shows, from its top. */
    private fun coveredHeight(): Float = reveal.coerceIn(0f, 1f) * height

    /** Radius of the bottom corners, smaller while the revealed part is still short. */
    private fun coverRadius(): Float = min(cornerRadius, (coveredHeight() + cornerRadius) / 2)

    /** Whether the panel shows over the lists, so they must leave its area to it. */
    fun isCoveringContent(): Boolean = featureOn && visibility == VISIBLE && coveredHeight() > 0f

    /** Whether the shown part of the panel covers ([x], [y]), in the parent's coordinates. */
    fun coversPoint(x: Float, y: Float): Boolean {
        if (!isCoveringContent()) return false
        val top = this.top + translationY
        return x >= left + translationX && x < right + translationX && y >= top && y < top + coveredHeight()
    }

    /**
     * Keeps [canvas], the parent's, from drawing under the shown part of the panel. The parent
     * calls this for the lists and the header below the search bar.
     */
    fun clipOutCoveredArea(canvas: Canvas) {
        updateCoverPath()
        canvas.clipOutPath(coverPath)
    }

    /** Draws the panel's shadow over the lists below it, on [canvas], the parent's. */
    fun drawShadow(canvas: Canvas) {
        if (Color.alpha(shadowColor) == 0 || height == 0) return
        updateCoverPath()
        val saveCount = canvas.save()
        canvas.clipOutPath(coverPath)
        canvas.translate(coverRect.left, coverRect.bottom)
        // Fades in with the panel, rather than starting at full strength under a sliver.
        shadowPaint.alpha = (255 * (coveredHeight() / height)).roundToInt().coerceIn(0, 255)
        // From a corner radius above the bottom edge, so it follows the rounded corners.
        canvas.drawRect(0f, -cornerRadius, coverRect.width(), shadowHeight, shadowPaint)
        canvas.restoreToCount(saveCount)
    }

    /** The shown part of the panel in the parent's coordinates, with its rounded bottom corners. */
    private fun updateCoverPath() {
        val left = left + translationX
        val top = top + translationY
        coverRect.set(left, top - cornerRadius, left + width, top + coveredHeight())
        val radius = coverRadius()
        coverPath.rewind()
        coverPath.addRoundRect(coverRect, radius, radius, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // With the list scrolled, the drawer draws a header protection behind the search bar; the
        // panel continues it. Otherwise the drawer background shows through.
        val protection = (parent as? ActivityAllAppsContainerView<*>)?.headerProtectionColor ?: 0
        if (Color.alpha(protection) != 0) canvas.drawColor(protection)
    }

    /**
     * Shows [value] of the panel (see [reveal]), with the content sliding down into place as it
     * opens. Only render properties change, so nothing is laid out again.
     */
    private fun setReveal(value: Float) {
        if (reveal == value) return
        reveal = value
        val slide = (1f - value) * -contentSlide
        tabBar.translationY = slide
        content.translationY = slide
        updateRevealClip()
    }

    /**
     * Clips the panel to its shown part: the outline rounds its bottom corners, the clip bounds
     * keep content that slides from above inside its top edge.
     */
    private fun updateRevealClip() {
        revealClip.set(0, 0, width, ceil(coveredHeight()).toInt())
        clipBounds = revealClip
        invalidateOutline()
        // The parent draws the clip over the lists and the shadow.
        (parent as? View)?.invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateRevealClip()
    }

    /** Takes every touch on its shown part, so none reaches the list under it. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && !(showing && ev.y < coveredHeight())) return false
        return super.dispatchTouchEvent(ev)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        super.onTouchEvent(event)
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Pad each side like the app lists (AdapterHolder.mPadding), so the icons line up with the
        // columns below.
        launcher?.deviceProfile?.allAppsPadding?.let { padding ->
            if (paddingLeft != padding.left || paddingRight != padding.right) {
                setPadding(padding.left, paddingTop, padding.right, paddingBottom)
            }
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
        listOf(grid, emptyText).forEach {
            it.animate().cancel()
            it.alpha = 1f
        }
        cancelAnimations()
        setReveal(if (showing) 1f else 0f)
        if (!showing) visibility = GONE
        resetContent()
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
     * dragging the list (which takes the focus) and leaving search hide it. Called by the search
     * box; closing the drawer hides it here too.
     */
    fun setSearchBoxState(focused: Boolean, queryEmpty: Boolean) {
        if (!featureOn) return
        val sessionStarts = focused && !searchBoxFocused
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
        val show = featureOn && sized && searchBoxFocused && queryEmpty
        if (show == showing) return
        showing = show
        val animated = animate && mayAnimate()
        if (show) {
            visibility = VISIBLE
            if (animated) {
                animateIn()
            } else {
                cancelAnimations()
                resetContent()
                setReveal(1f)
            }
        } else if (animated && reveal > 0f) {
            animateOut()
        } else {
            cancelAnimations()
            setReveal(0f)
            visibility = GONE
            resetContent()
        }
    }

    /**
     * Opens the panel: it unfolds downwards from the search bar on a gentle spring while its
     * content slides down into place, the tabs fade in, the selected tab's indicator draws in and
     * the icons pop in one after another in reading order. Coming back from a close that has only
     * just started, the content simply fades back in.
     */
    private fun animateIn() {
        cancelAnimations()
        val fresh = reveal < 0.5f
        if (fresh) {
            tabBar.alpha = 0f
            tabBar.indicatorReveal = 0f
            // The empty-tab message fades in as a whole; icons each on their own.
            content.alpha = if (emptyText.isVisible) 0f else 1f
            grid.setEntranceTime(0f)
        } else {
            tabBar.indicatorReveal = 1f
            grid.endEntrance()
        }
        val startTabAlpha = tabBar.alpha
        val startContentAlpha = content.alpha

        revealAnimator = SpringAnimationBuilder(context)
            .setStartValue(reveal)
            .setEndValue(1f)
            .setDampingRatio(REVEAL_DAMPING_RATIO)
            .setStiffness(REVEAL_STIFFNESS)
            // One pixel of the panel.
            .setMinimumVisibleChange(1f / height.coerceAtLeast(1))
            .build(this, REVEAL)
            .also { it.start() }

        val duration = if (fresh) max(ENTRANCE_INDICATOR_DELAY_MS + ENTRANCE_INDICATOR_DURATION_MS, ENTRANCE_ICONS_DELAY_MS + grid.entranceDuration()) else RETURN_DURATION_MS
        entranceAnimator = ValueAnimator.ofFloat(0f, duration.toFloat()).apply {
            this.duration = duration
            interpolator = Interpolators.LINEAR
            addUpdateListener { animator ->
                val time = animator.animatedValue as Float
                if (fresh) {
                    tabBar.alpha = progressAt(time, ENTRANCE_TABS_DELAY_MS, ENTRANCE_TABS_DURATION_MS, Interpolators.EMPHASIZED_DECELERATE)
                    tabBar.indicatorReveal = progressAt(time, ENTRANCE_INDICATOR_DELAY_MS, ENTRANCE_INDICATOR_DURATION_MS, Interpolators.EMPHASIZED_DECELERATE)
                    content.alpha = Utilities.mapRange(progressAt(time, ENTRANCE_ICONS_DELAY_MS, ENTRANCE_TABS_DURATION_MS, Interpolators.EMPHASIZED_DECELERATE), startContentAlpha, 1f)
                    grid.setEntranceTime(time - ENTRANCE_ICONS_DELAY_MS)
                } else {
                    val progress = Interpolators.EMPHASIZED_DECELERATE.getInterpolation(animator.animatedFraction)
                    tabBar.alpha = Utilities.mapRange(progress, startTabAlpha, 1f)
                    content.alpha = Utilities.mapRange(progress, startContentAlpha, 1f)
                }
            }
            addListener(
                object : AnimationSuccessListener() {
                    override fun onAnimationSuccess(animation: Animator) {
                        entranceAnimator = null
                        resetContent()
                    }
                },
            )
            start()
        }
    }

    /**
     * Closes the panel quickly: it folds back up into the search bar while its content fades and
     * slides up, then goes.
     */
    private fun animateOut() {
        cancelAnimations()
        val startReveal = reveal
        val startTabAlpha = tabBar.alpha
        val startContentAlpha = content.alpha
        revealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = HIDE_DURATION_MS
            interpolator = Interpolators.LINEAR
            addUpdateListener { animator ->
                val fraction = animator.animatedFraction
                setReveal(Utilities.mapRange(Interpolators.EMPHASIZED_ACCELERATE.getInterpolation(fraction), startReveal, 0f))
                val fade = 1f - (fraction / HIDE_FADE_SHARE).coerceAtMost(1f)
                tabBar.alpha = startTabAlpha * fade
                content.alpha = startContentAlpha * fade
            }
            addListener(
                object : AnimationSuccessListener() {
                    override fun onAnimationSuccess(animation: Animator) {
                        revealAnimator = null
                        visibility = GONE
                        resetContent()
                    }
                },
            )
            start()
        }
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
        // Light labels mean a dark drawer, where the shadow needs to be stronger to show.
        val darkDrawer = ColorUtils.calculateLuminance(labelColor) > 0.5
        val color = ColorUtils.setAlphaComponent(Color.BLACK, if (darkDrawer) SHADOW_ALPHA_DARK else SHADOW_ALPHA_LIGHT)
        if (color != shadowColor) {
            shadowColor = color
            shadowPaint.shader = LinearGradient(
                0f,
                0f,
                0f,
                shadowHeight,
                intArrayOf(color, ColorUtils.setAlphaComponent(color, Color.alpha(color) * 2 / 5), Color.TRANSPARENT),
                floatArrayOf(0f, 0.35f, 1f),
                Shader.TileMode.CLAMP,
            )
            (parent as? View)?.invalidate()
        }
    }

    /** Height below the tabs: the rows of icons, or one line for the message if every tab is empty. */
    private fun contentHeight(): Int = if (reservedRows > 0) reservedRows * grid.rowHeight() else emptyHeight

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

        /** The spring the panel opens on: a little softer than the drawer's own, barely bouncing. */
        private const val REVEAL_STIFFNESS = 600f
        private const val REVEAL_DAMPING_RATIO = 0.82f

        // When each part of the panel's content comes in, from the start of its opening.
        private const val ENTRANCE_TABS_DELAY_MS = 30L
        private const val ENTRANCE_TABS_DURATION_MS = 200L
        private const val ENTRANCE_INDICATOR_DELAY_MS = 120L
        private const val ENTRANCE_INDICATOR_DURATION_MS = 300L
        private const val ENTRANCE_ICONS_DELAY_MS = 60L

        /** Fading back in after a close that was cut short. */
        private const val RETURN_DURATION_MS = 150L

        /** Closing, and the share of it that the content takes to fade out. */
        private const val HIDE_DURATION_MS = 150L
        private const val HIDE_FADE_SHARE = 0.66f

        private val REVEAL = object : FloatProperty<AppSuggestionsHeaderView>("reveal") {
            override fun setValue(view: AppSuggestionsHeaderView, value: Float) = view.setReveal(value)

            override fun get(view: AppSuggestionsHeaderView): Float = view.reveal
        }

        /**
         * Progress, eased by [interpolator], of a part of an animation that starts [delay]
         * milliseconds in and lasts [duration], at [time] milliseconds into the animation.
         */
        private fun progressAt(time: Float, delay: Long, duration: Long, interpolator: Interpolator): Float = interpolator.getInterpolation(((time - delay) / duration).coerceIn(0f, 1f))

        /** Strength of the shadow under the panel, at its edge. */
        private const val SHADOW_ALPHA_LIGHT = 0x24
        private const val SHADOW_ALPHA_DARK = 0x59

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
