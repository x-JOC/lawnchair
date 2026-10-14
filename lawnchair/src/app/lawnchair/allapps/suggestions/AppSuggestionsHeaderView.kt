package app.lawnchair.allapps.suggestions

import android.animation.ValueAnimator
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Build
import android.util.AttributeSet
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
import app.lawnchair.theme.color.tokens.AllAppsTabColors
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
 * never moves the list. A new height is only applied while the drawer is off screen, because the
 * drawer resets its list padding and scrolls back to the top whenever the header height changes.
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
    private val loader by lazy { AppSuggestionsLoader(context) }

    private lateinit var tabBar: SuggestionTabBar
    private lateinit var grid: SuggestionIconGrid
    private lateinit var emptyText: TextView

    private var header: FloatingHeaderView? = null
    private var scope: CoroutineScope? = null
    private var refreshJob: Job? = null

    /** The newest suggestions, shown as soon as no launch animation is running. */
    private var latest: AppSuggestions? = null

    /** Rows of icons the header is measured for; 0 until it is first shown. */
    private var reservedRows = 0

    /** The height last reported to the drawer through [FloatingHeaderView.onHeightUpdated]. */
    private var reportedHeight = 0

    private var uiPaused = false
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

    override fun getExpectedHeight(): Int = if (visibility == GONE) 0 else tabBarHeight + reservedRows * cellHeight() + paddingTop + paddingBottom

    override fun shouldDraw(): Boolean = visibility != GONE

    override fun hasVisibleContent(): Boolean = featureOn && reservedRows > 0

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
    }

    override fun onAppsUpdated() {
        refresh()
    }

    override fun onDeviceProfileChanged(dp: DeviceProfile) {
        // Icon views take their sizes from the device profile when they are created.
        grid.clearCells()
        showLatest(allowHeightChange = true)
        refresh()
    }

    /**
     * Pauses updates while an app opens, so the icon being launched doesn't change or move under
     * the launch animation. Called by [LawnchairLauncher.startActivitySafely].
     */
    fun setUiUpdatesPaused(paused: Boolean) {
        uiPaused = paused
        if (!paused) showLatest(allowHeightChange = !isDrawerOnScreen())
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
            latest = loader.load(apps, limit)
            showLatest(allowHeightChange = !isDrawerOnScreen())
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
        }
        if (reservedRows == 0) return
        // Until the user picks a tab, open on Frequent, or on the first tab with apps. Not while
        // the drawer is on screen, so the tab doesn't change under the user.
        if (lastPickedTab == null && allowHeightChange) {
            selectedTab = snapshot.firstNonEmptyTab(SuggestionTab.FREQUENT)
        }
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

    private fun bindSelectedTab(animate: Boolean) {
        val snapshot = latest ?: return
        val columns = launcher?.deviceProfile?.numShownAllAppsColumns ?: return
        val apps = snapshot.appsFor(selectedTab).take(columns * reservedRows)
        tabBar.select(selectedTab, animate)
        grid.bind(apps, columns, reservedRows)

        val isEmpty = apps.isEmpty()
        emptyText.isVisible = isEmpty
        if (isEmpty) {
            val textRes = emptyTextFor(selectedTab)
            // Only on a change: the text is a live region, and TalkBack reads every update.
            if (textRes != emptyTextRes) {
                emptyTextRes = textRes
                emptyText.setText(textRes)
            }
        }

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
     * Label colors follow the drawer's icon labels (including the alternate color on light or
     * see-through drawers) and the indicator uses the drawer's tab color, so the header matches
     * custom drawer and tab colors.
     */
    private fun applyColors() {
        val useAlternate = shouldUseAlternateAllAppsTextColor(context)
        val selectedColor = Themes.getAttrColor(
            context,
            if (useAlternate) R.attr.allAppsAlternateTextColor else android.R.attr.textColorPrimary,
        )
        val labelColor = Themes.getAttrColor(
            context,
            if (useAlternate) R.attr.allAppsAlternateTextColor else android.R.attr.textColorSecondary,
        )
        val unselectedColor = ColorUtils.setAlphaComponent(
            labelColor,
            (Color.alpha(labelColor) * UNSELECTED_TEXT_ALPHA).roundToInt(),
        )
        tabBar.applyColors(selectedColor, unselectedColor, AllAppsTabColors.selectedBackground(context))
        emptyText.setTextColor(unselectedColor)
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

    private val stateListener = object : StateManager.StateListener<LauncherState> {
        override fun onStateTransitionStart(toState: LauncherState) {
            if (toState == LauncherState.ALL_APPS) {
                applyColors()
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
            showLatest(allowHeightChange = true)
        }
    }

    companion object {
        private const val TAB_FADE_DURATION_MS = 150L
        private const val UNSELECTED_TEXT_ALPHA = 0.7f

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
 * Rows of icons for the fullest tab, between 1 (room for the empty-tab message) and [maxRows], so
 * the header keeps one height whichever tab is selected.
 */
internal fun AppSuggestions.rowsNeeded(columns: Int, maxRows: Int): Int {
    val mostApps = SuggestionTab.entries.maxOf { appsFor(it).size }
    return ((mostApps + columns - 1) / columns.coerceAtLeast(1)).coerceIn(1, maxRows)
}
