package app.lawnchair.allapps.suggestions

import android.animation.ValueAnimator
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
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
import com.android.launcher3.allapps.AllAppsStore
import com.android.launcher3.allapps.FloatingHeaderRow
import com.android.launcher3.allapps.FloatingHeaderView
import com.android.launcher3.anim.AlphaUpdateListener
import com.android.launcher3.statemanager.StateManager
import com.android.launcher3.util.Themes
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Frequent, Recent and New/Updated apps at the top of the app drawer, right under the search box:
 * a row of three tabs over up to two rows of the drawer's own icons. The A-Z list continues below,
 * after the usual divider.
 *
 * This is a [FloatingHeaderRow], like the suggested apps row it replaces while it is on, so it
 * scrolls away with the list, hides while search results are shown and sits above the
 * Personal/Work tabs. It only shows in the launcher's own drawer: in the taskbar and on other
 * displays it stays gone.
 *
 * The header keeps one height for all tabs (enough rows for the fullest tab), so switching tabs
 * never moves the list. When every tab is empty it only has room for a one-line message. A new
 * height is only applied while the drawer is off screen, because the drawer resets its list
 * padding and scrolls back to the top whenever the header height changes. That also drops a
 * position kept by "remember scroll position", which is accepted: it only happens when the number
 * of rows changes, not on every opening.
 */
class AppSuggestionsHeaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs),
    FloatingHeaderRow,
    DeviceProfile.OnDeviceProfileChangeListener,
    AllAppsStore.OnUpdateListener {

    private val launcher: LawnchairLauncher? = context.findLawnchairLauncher()
    private val featureOn = launcher != null && isEnabledFor(context)
    private val tabBarHeight = resources.getDimensionPixelSize(R.dimen.drawer_suggestions_tab_bar_height)
    private val emptyHeight = resources.getDimensionPixelSize(R.dimen.drawer_suggestions_empty_height)
    private val loader by lazy { AppSuggestionsLoader(context) }

    private lateinit var tabBar: SuggestionTabBar
    private lateinit var grid: SuggestionIconGrid
    private lateinit var emptyText: TextView

    private var header: FloatingHeaderView? = null
    private var scope: CoroutineScope? = null
    private var refreshJob: Job? = null

    /** The newest suggestions, shown as soon as no launch animation is running. */
    private var latest: AppSuggestions? = null

    /** Whether the header has been sized for some suggestions; it stays gone until then. */
    private var sized = false

    /**
     * Rows of icons the header is measured for. 0 when every tab is empty: the header then only
     * has room for the empty-tab message.
     */
    private var reservedRows = 0

    /** The height last reported to the drawer through [FloatingHeaderView.onHeightUpdated]. */
    private var reportedHeight = 0

    /**
     * Set by a device profile change: the next suggestions, loaded for the new grid, may change the
     * header height even while the drawer is open, because the drawer is laid out again anyway.
     */
    private var resizeOnNextLoad = false

    private var uiPaused = false
    private val resumeUiUpdates = Runnable { setUiUpdatesPaused(false) }
    private var selectedTab = lastPickedTab ?: SuggestionTab.FREQUENT

    @StringRes
    private var emptyTextRes = 0

    override fun onFinishInflate() {
        super.onFinishInflate()
        tabBar = ViewCompat.requireViewById(this, R.id.suggestion_tab_bar)
        grid = ViewCompat.requireViewById(this, R.id.suggestion_grid)
        emptyText = ViewCompat.requireViewById(this, R.id.suggestion_empty)
        FontManager.INSTANCE.get(context).setCustomFont(emptyText, R.id.font_body)
        tabBar.onTabPicked = ::onTabPicked
    }

    // FloatingHeaderRow

    override fun setup(headerView: FloatingHeaderView, allRows: Array<FloatingHeaderRow>, tabsHidden: Boolean) {
        // This runs inside ActivityAllAppsContainerView.setupHeader(), so it must not call
        // onHeightUpdated(), which would set the header up again.
        header = headerView
    }

    override fun getExpectedHeight(): Int = if (visibility == GONE) 0 else tabBarHeight + contentHeight() + paddingTop + paddingBottom

    override fun shouldDraw(): Boolean = visibility != GONE

    override fun hasVisibleContent(): Boolean = featureOn && sized

    override fun isVisible(): Boolean = visibility == VISIBLE

    override fun getTypeClass(): Class<out FloatingHeaderRow> = AppSuggestionsHeaderView::class.java

    override fun getFocusedChild(): View? = super<LinearLayout>.getFocusedChild()

    override fun setVerticalScroll(scroll: Int, isScrolledOut: Boolean) {
        // Same as the suggested apps row: follow the list, and hide once scrolled past.
        if (!isScrolledOut) translationY = scroll.toFloat()
        alpha = if (isScrolledOut) 0f else 1f
        if (visibility != GONE) AlphaUpdateListener.updateVisibility(this)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(getExpectedHeight(), MeasureSpec.EXACTLY))
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
        PreferenceManager2.getInstance(context).hiddenApps.get().drop(1).onEach { refresh() }.launchIn(newScope)
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
    }

    override fun onAppsUpdated() {
        refresh()
    }

    override fun onDeviceProfileChanged(dp: DeviceProfile) {
        // Icon views take their sizes from the device profile when they are created.
        grid.clearCells()
        showLatest(allowHeightChange = true)
        // The current suggestions were loaded for the old grid (for example one row in landscape),
        // so let the ones for the new grid resize the header too, as soon as they are ready.
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
        if (!paused) showLatest(allowHeightChange = !isDrawerOnScreen())
    }

    /** Ends a pause from [setUiUpdatesPaused] without showing anything; the caller does. */
    private fun clearUiPause() {
        removeCallbacks(resumeUiUpdates)
        uiPaused = false
    }

    // Data

    private fun refresh() {
        val launcher = launcher ?: return
        val currentScope = scope ?: return
        val apps = launcher.appsView?.appsStore?.apps ?: return
        // Empty until the app model has loaded; onAppsUpdated() comes back here then.
        if (apps.isEmpty()) return
        val dp = launcher.deviceProfile
        val limit = dp.numShownAllAppsColumns * maxRows(dp)
        refreshJob?.cancel()
        refreshJob = currentScope.launch {
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
            showLatest(allowHeightChange = resize || !isDrawerOnScreen())
        }
    }

    /**
     * Shows [latest]. Its apps are bound right away; a change in the number of rows (and so in the
     * header height) waits for [allowHeightChange], which callers pass only while the drawer is off
     * screen or just starting to open.
     */
    private fun showLatest(allowHeightChange: Boolean) {
        val snapshot = latest ?: return
        val dp = launcher?.deviceProfile ?: return
        if (uiPaused) return
        if (allowHeightChange) {
            reservedRows = snapshot.rowsNeeded(dp.numShownAllAppsColumns, maxRows(dp))
            sized = true
            // Until the user picks a tab, open on Frequent, or on the first tab with apps. Not
            // while the drawer is on screen, so the tab doesn't change under the user.
            if (lastPickedTab == null) selectedTab = snapshot.firstNonEmptyTab(SuggestionTab.FREQUENT)
        }
        if (!sized) return
        if (visibility == GONE) visibility = VISIBLE
        bindSelectedTab(animate = false)
        val height = getExpectedHeight()
        if (height != reportedHeight) {
            reportedHeight = height
            requestLayout()
            // Updates the list padding and the divider below, and scrolls the lists to the top.
            (header ?: parent as? FloatingHeaderView)?.onHeightUpdated()
        }
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
        // the drawer is open has no rows for them yet, and stays blank until the header grows.
        val isEmpty = tabApps.isEmpty()
        // TalkBack reads the message when the user switches to an empty tab, but not when the
        // header is updated in the background, for example while the drawer is closed.
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
     * [SELECTED_LABEL_COLOR]), which follow the wallpaper or the custom accent color. The other
     * labels and the empty-tab message use the color of the drawer's icon labels, including the
     * alternate color on light or see-through drawers, so they match custom drawer colors.
     */
    private fun applyColors() {
        val labelColor = Themes.getAttrColor(
            context,
            if (shouldUseAlternateAllAppsTextColor(context)) R.attr.allAppsAlternateTextColor else android.R.attr.textColorSecondary,
        )
        tabBar.applyColors(
            selectedTextColor = SELECTED_LABEL_COLOR.resolveColor(context),
            unselectedTextColor = labelColor,
            pillColor = PILL_COLOR.resolveColor(context),
            rippleColor = Themes.getAttrColor(context, android.R.attr.colorControlHighlight),
        )
        emptyText.setTextColor(
            ColorUtils.setAlphaComponent(labelColor, (Color.alpha(labelColor) * EMPTY_TEXT_ALPHA).roundToInt()),
        )
    }

    /**
     * Whether the drawer may be visible: the launcher is started and the drawer is open or a state
     * transition (which may be opening or closing it) is running. A deferred height change is
     * applied when that transition completes.
     */
    private fun isDrawerOnScreen(): Boolean {
        val launcher = launcher ?: return false
        val stateManager = launcher.stateManager
        return launcher.isStarted && (stateManager.state == LauncherState.ALL_APPS || stateManager.isInTransition)
    }

    private fun cellHeight(): Int = launcher?.deviceProfile?.allAppsProfile?.cellHeightPx ?: 0

    /** Height below the tabs: the rows of icons, or one line for the message if every tab is empty. */
    private fun contentHeight(): Int = if (reservedRows > 0) reservedRows * cellHeight() else emptyHeight

    private val stateListener = object : StateManager.StateListener<LauncherState> {
        override fun onStateTransitionStart(toState: LauncherState) {
            if (toState == LauncherState.ALL_APPS) {
                applyColors()
                // Any launch that paused the updates is over by the time the drawer opens again.
                clearUiPause()
                // The drawer is only starting to open and its list is at the top, so a new height
                // doesn't move anything the user is looking at.
                showLatest(allowHeightChange = true)
                refresh()
            }
        }

        override fun onStateTransitionComplete(finalState: LauncherState) {
            if (finalState != LauncherState.ALL_APPS) showLatest(allowHeightChange = true)
        }
    }

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            // The opened app now covers the launcher, so its launch no longer needs the pause,
            // and the header may change height while nobody sees it.
            clearUiPause()
            showLatest(allowHeightChange = true)
        }
    }

    companion object {
        private const val TAG = "AppSuggestionsHeader"
        private const val TAB_FADE_DURATION_MS = 150L
        private const val EMPTY_TEXT_ALPHA = 0.8f

        /**
         * The selected tab's pill and label: the colors of a selected chip in Lawnchair's settings
         * (`ui/preferences/components/layout/Chip.kt`), that is Material's secondary container
         * and primary colors.
         */
        private val PILL_COLOR = DayNightColorToken(ColorTokens.Accent2_100, SwatchColorToken(Swatch.Accent2, Shade.S700))
        private val SELECTED_LABEL_COLOR = DayNightColorToken(ColorTokens.Accent1_600, ColorTokens.Accent1_200)

        /** Longer than any launch animation; see [setUiUpdatesPaused]. */
        private const val MAX_UI_PAUSE_MS = 2000L

        /** The tab the user picked last. Kept for the whole process, so it survives the launcher being recreated. */
        private var lastPickedTab: SuggestionTab? = null

        /**
         * Whether the suggestion tabs are shown in the drawer of [context]: only in Lawnchair's own
         * launcher, and only while the setting is on. The suggested apps row hides itself then.
         */
        @JvmStatic
        fun isEnabledFor(context: Context): Boolean = context.findLawnchairLauncher() != null &&
            PreferenceManager2.getInstance(context).drawerSuggestionTabs.firstCached()

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
 * same header (taskbar all apps, secondary displays). Unlike [app.lawnchair.launcher], it never
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
 * Rows of icons for the fullest tab, at most [maxRows], so the header keeps one height whichever
 * tab is selected. 0 when every tab is empty.
 */
internal fun AppSuggestions.rowsNeeded(columns: Int, maxRows: Int): Int {
    val mostApps = SuggestionTab.entries.maxOf { appsFor(it).size }
    val perRow = columns.coerceAtLeast(1)
    return ((mostApps + perRow - 1) / perRow).coerceIn(0, maxRows)
}
