package app.lawnchair.allapps

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.provider.SearchRecentSuggestions
import android.text.Selection
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_POINT_MARK
import android.text.method.TextKeyListener
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.ViewTreeObserver.OnGlobalFocusChangeListener
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import app.lawnchair.allapps.suggestions.AppSuggestionsHeaderView
import app.lawnchair.launcher
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.asState
import app.lawnchair.preferences2.firstCached
import app.lawnchair.qsb.LawnQsbLayout.Companion.getLensIntent
import app.lawnchair.qsb.LawnQsbLayout.Companion.getSearchProvider
import app.lawnchair.qsb.LawnQsbLayout.Companion.getVoiceIntent
import app.lawnchair.qsb.LawnQsbUi
import app.lawnchair.qsb.QsbActions
import app.lawnchair.qsb.QsbIconId
import app.lawnchair.qsb.buildQsbStyle
import app.lawnchair.qsb.getHotseatQsbCornerRadius
import app.lawnchair.qsb.providers.Google
import app.lawnchair.qsb.providers.PixelSearch
import app.lawnchair.qsb.rememberAllAppsQsbState
import app.lawnchair.search.LawnchairRecentSuggestionProvider
import app.lawnchair.search.algorithms.LawnchairSearchAlgorithm
import app.lawnchair.theme.color.tokens.ColorTokens
import app.lawnchair.theme.color.tokens.DayNightColorToken
import app.lawnchair.theme.color.tokens.setAlpha
import app.lawnchair.theme.color.tokens.setLStar
import app.lawnchair.ui.theme.LawnchairTheme
import app.lawnchair.util.ProvideLifecycleState
import com.android.launcher3.Insettable
import com.android.launcher3.InvariantDeviceProfile.OnIDPChangeListener
import com.android.launcher3.LauncherState
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.allapps.ActivityAllAppsContainerView
import com.android.launcher3.allapps.AllAppsStore
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem
import com.android.launcher3.allapps.SearchUiManager
import com.android.launcher3.allapps.search.AllAppsSearchBarController
import com.android.launcher3.search.SearchCallback
import com.android.launcher3.util.Themes
import com.android.systemui.shared.system.BlurUtils
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.launch

class AllAppsSearchInput(context: Context, attrs: AttributeSet?) :
    FrameLayout(context, attrs),
    Insettable,
    OnIDPChangeListener,
    SearchUiManager,
    SearchCallback<AdapterItem>,
    AllAppsStore.OnUpdateListener,
    ViewTreeObserver.OnGlobalLayoutListener {

    private lateinit var hint: TextView
    private lateinit var input: FallbackSearchInputView
    private lateinit var qsbShell: ComposeView

    private val qsbMarginTopAdjusting = resources.getDimensionPixelSize(R.dimen.qsb_margin_top_adjusting)
    private val allAppsSearchVerticalOffset = resources.getDimensionPixelSize(R.dimen.all_apps_search_vertical_offset)

    private val launcher = context.launcher
    private val searchBarController = AllAppsSearchBarController()
    private val searchQueryBuilder = SpannableStringBuilder().apply {
        Selection.setSelection(this, 0)
    }

    private lateinit var apps: LawnchairAlphabeticalAppsList<*>
    private lateinit var appsView: ActivityAllAppsContainerView<*>
    private var searchAlgorithm: LawnchairSearchAlgorithm? = null

    private var isDirectFocus = false
    private var focusedResultTitle = ""
    private var canShowHint = false
    private var queryEmpty by mutableStateOf(true)

    /**
     * Whether the drawer's suggestion tabs draw this bar's background, as the top of the bar they
     * grow it into ([AppSuggestionsHeaderView]). The bar's own background stays clear meanwhile,
     * so the two never add up, and is back as it should be the moment they hand it back.
     */
    private var barTakenOver by mutableStateOf(false)

    private var bgAlphaState by mutableFloatStateOf(1f)
    private val supportBlur = BlurUtils.supportsBlursOnWindows()
    private val bgAlphaAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 300
        interpolator = DecelerateInterpolator()
    }
    private var bgVisible = true
    private var bgAlpha = 1f
    private val suggestionsRecent = SearchRecentSuggestions(launcher, LawnchairRecentSuggestionProvider.AUTHORITY, LawnchairRecentSuggestionProvider.MODE)
    private val prefs = PreferenceManager.getInstance(launcher)
    private val prefs2 = PreferenceManager2.getInstance(launcher)

    private var initialPaddingLeft: Int = 0
    private var initialPaddingRight: Int = 0
    private var hideSearchBar = false

    override fun onFinishInflate() {
        super.onFinishInflate()

        setupPadding()
        bgAlphaAnimator.addUpdateListener { updateBgAlpha() }

        hint = ViewCompat.requireViewById(this, R.id.hint)

        input = ViewCompat.requireViewById(this, R.id.input)

        qsbShell = ViewCompat.requireViewById(this, R.id.qsb_shell)

        qsbShell.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)

            setContent {
                var isFocused by remember(input) { mutableStateOf(input.hasFocus()) }

                // Yes, this is a bit hacky, but it's the only way to ensure that
                // we can check if the input has focus in Compose without wrestling
                // with multiple global variables or state changes
                DisposableEffect(input) {
                    val focusListener = OnGlobalFocusChangeListener { _, _ ->
                        isFocused = input.hasFocus()
                    }

                    val observer = input.viewTreeObserver
                    observer.addOnGlobalFocusChangeListener(focusListener)

                    onDispose {
                        if (observer.isAlive) {
                            observer.removeOnGlobalFocusChangeListener(focusListener)
                        }
                    }
                }

                val searchProviderPref by prefs2.hotseatQsbProvider.asState()
                val searchProvider = remember(searchProviderPref, context) {
                    getSearchProvider(context, searchProviderPref)
                }
                val themedQsb by prefs2.themedHotseatQsb.asState()
                val shouldShowIcons by prefs2.matchHotseatQsbStyle.asState()

                val supportsLens = searchProvider == Google || searchProvider == PixelSearch
                val voiceIntent = remember(searchProvider, context) {
                    getVoiceIntent(searchProvider, context)
                }
                val lensIntent = remember(supportsLens, context) {
                    if (supportsLens) getLensIntent(context) else null
                }

                val state = rememberAllAppsQsbState(
                    searchProvider = searchProvider,
                    themed = themedQsb,
                    shouldShowIcons = shouldShowIcons,
                    queryEmpty = queryEmpty,
                    showMic = voiceIntent != null,
                    showLens = lensIntent != null,
                )

                val backgroundColor = barFillColor()

                // While the suggestion tabs draw the background, changes jump to their end, so
                // the bar's own background is right when the tabs hand it back.
                val takenOver = barTakenOver
                val ownBackgroundAlpha by animateIntAsState(
                    if (isFocused || !queryEmpty) 0 else 100,
                    animationSpec = if (takenOver) snap() else spring(visibilityThreshold = 1),
                    label = "searchBarBackgroundAlpha",
                )
                val backgroundAlpha = if (takenOver) 0 else ownBackgroundAlpha

                // Ignore other theme attributes to preserve existing behavior
                val style = buildQsbStyle(
                    context = context,
                    themed = themedQsb,
                    backgroundColor = backgroundColor,
                    backgroundAlpha = backgroundAlpha,
                    cornerRadius = BAR_CORNER_RADIUS_FACTOR,
                    strokeColor = null,
                    strokeWidth = 0f,
                )

                val actions = QsbActions(
                    onQsbClick = {
                        if (input.text.isNullOrEmpty()) {
                            showZeroState()
                        }
                        input.requestFocus()
                        input.showKeyboard()
                        ensureKeyboardWithSuggestionTabs()
                    },
                    onStartIconClick = if (shouldShowIcons) {
                        {
                            val launcher = context.launcher
                            launcher.lifecycleScope.launch {
                                searchProvider.launch(launcher)
                            }
                        }
                    } else {
                        null
                    },
                    onEndIconClick = { id ->
                        when (id) {
                            QsbIconId.MIC -> voiceIntent?.let { context.startActivity(it) }

                            QsbIconId.LENS -> lensIntent?.let { context.startActivity(it) }

                            QsbIconId.CLEAR -> {
                                // Deleting the query has the search bar controller clear the results.
                                input.reset()
                                showZeroState(clearResults = false)
                                updateHint()
                            }

                            else -> Unit
                        }
                    },
                )

                LawnchairTheme {
                    ProvideLifecycleState {
                        LawnQsbUi(
                            state = state,
                            style = style,
                            actions = actions,
                        )
                    }
                }
            }
        }

        // Stop Compose QSB from disappearing
        // https://stackoverflow.com/questions/72781705/jetpack-compose-view-not-drawing-when-coming-back-to-fragment/77496737#77496737
        qsbShell.addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                requestLayout()
                qsbShell.disposeComposition()
            }
            override fun onViewDetachedFromWindow(v: View) {
                qsbShell.disposeComposition()
            }
        })

        val currentPaddingLeft = initialPaddingLeft
        val currentPaddingRight = initialPaddingRight

        // Activate zero search on tap
        @SuppressLint("ClickableViewAccessibility")
        input.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN && !input.hasFocus()) {
                setDirectFocus(true)
            }
            false
        }

        input.onFocusChangeListener = { _, hasFocus ->
            if (hasFocus) {
                if (prefs2.searchAlgorithm.firstCached() != LawnchairSearchAlgorithm.APP_SEARCH) {
                    input.setHint(R.string.all_apps_device_search_hint)
                } else {
                    input.setHint(R.string.all_apps_search_bar_hint)
                }

                if (input.text.toString().isEmpty() && isDirectFocus) {
                    showZeroState()
                    setDirectFocus(false)
                    ensureKeyboardWithSuggestionTabs()
                }

                suggestionsPanel()?.setSearchBoxState(focused = true, queryEmpty = input.text.isNullOrEmpty())
                setBackgroundVisibility(false, 0f)
                animateHintVisibility(true)
                animatePadding(currentPaddingLeft / 2, currentPaddingRight / 2)
            } else {
                // Also when dragging the list takes the focus (AllAppsRecyclerView): the tabs
                // would cover its top.
                suggestionsPanel()?.setSearchBoxState(focused = false, queryEmpty = input.text.isNullOrEmpty())
                setBackgroundVisibility(true, 1f)
                animateHintVisibility(false)
                // Not with the suggestion tabs on: the history is only shown on the search page
                // that the tabs replace (showZeroState), so it would only pile up unseen.
                if (prefs.searchResulRecentSuggestion.get() && !AppSuggestionsHeaderView.isEnabledFor(context)) {
                    val query = editText.text.toString()
                    suggestionsRecent.saveRecentQuery(query, null)
                }

                if (input.text.isNullOrEmpty()) {
                    animatePadding(currentPaddingLeft, currentPaddingRight)
                }
                focusedResultTitle = ""
                input.setHint("")
                hint.text = ""
            }

            if (::appsView.isInitialized) {
                appsView.mSearchRecyclerView.invalidate()
            }
        }

        input.addTextChangedListener(
            beforeTextChanged = { _, _, _, _ ->
                hint.isInvisible = true
            },
            afterTextChanged = {
                updateHint()
                if (input.text.isNullOrEmpty() && input.hasFocus() && !input.isResetting) {
                    // The search bar controller's own listener, which runs next, clears the results.
                    showZeroState(clearResults = false)
                }
                if (input.text.toString() == "/lawnchairdebug") {
                    val enableDebugMenu = prefs.enableDebugMenu
                    enableDebugMenu.set(!enableDebugMenu.get())
                    launcher.stateManager.goToState(LauncherState.NORMAL)
                }

                val isEmpty = it.isNullOrEmpty()
                if (isEmpty && !input.hasFocus()) {
                    animatePadding(currentPaddingLeft, currentPaddingRight)
                }
                queryEmpty = isEmpty
                // Typing hides the suggestion tabs for the results; clearing the query brings them
                // back. A reset leaves search, which the focus change handles.
                suggestionsPanel()?.setSearchBoxState(focused = input.hasFocus() && !input.isResetting, queryEmpty = isEmpty)
            },
        )

        hideSearchBar = prefs2.hideAppDrawerSearchBar.firstCached()
        if (hideSearchBar) {
            // GONE so top margin/height do not reserve empty space above the app list.
            isGone = true
            layoutParams.height = 0
        }
    }

    /**
     * What an empty, focused search box shows. With the drawer's suggestion tabs on, the drawer
     * itself is the empty state, as in Nova: the tabs and the app list stay visible with the
     * keyboard up, and only typing switches to results. Otherwise the search algorithm decides
     * (search history, or the drawer).
     *
     * [clearResults] is false when the query was just deleted: the search bar controller clears
     * the results then anyway, and clearing twice restarts the animation back to the drawer.
     */
    private fun showZeroState(clearResults: Boolean = true) {
        if (AppSuggestionsHeaderView.isEnabledFor(context)) {
            // Cancel first, so results of a query that was just cleared can't arrive late and
            // cover the drawer again.
            searchAlgorithm?.cancel(true)
            // Keeps focus and the keyboard while the query is empty (onClearSearchResult).
            if (clearResults) clearSearchResult()
        } else {
            searchAlgorithm?.doZeroStateSearch(this)
        }
    }

    /** The drawer's suggestion tabs, which show while the search box is in use. */
    private fun suggestionsPanel(): AppSuggestionsHeaderView? = if (::appsView.isInitialized) appsView.suggestionsPanel else null

    /** The search bar itself, inside this view's padding, which the suggestion tabs grow. */
    val barView: View
        get() = qsbShell

    /** The search bar's fill color, before its background alpha. */
    fun barFillColor(): Int = (if (supportBlur) BAR_FILL_BLUR else BAR_FILL).resolveColor(context)

    /** The radius of the search bar's corners. */
    fun barCornerRadius(): Float = getHotseatQsbCornerRadius(context, BAR_CORNER_RADIUS_FACTOR)

    /** This view's left padding while the search box has focus, when the bar is wider. */
    val focusedBarPaddingLeft: Int
        get() = initialPaddingLeft / 2

    /** This view's right padding while the search box has focus, when the bar is wider. */
    val focusedBarPaddingRight: Int
        get() = initialPaddingRight / 2

    /** See [barTakenOver]. Called by the suggestion tabs. */
    fun setBarBackgroundTakenOver(takenOver: Boolean) {
        barTakenOver = takenOver
    }

    /**
     * With the suggestion tabs on, tapping the empty box keeps the drawer on screen, so the
     * keyboard is the main sign that the box is ready. The first tap in a new launcher window has
     * been seen to focus the box without the keyboard coming up, so ask for it once more on the
     * next frame if it isn't showing by then.
     */
    private fun ensureKeyboardWithSuggestionTabs() {
        if (!AppSuggestionsHeaderView.isEnabledFor(context)) return
        input.post {
            if (!input.isFocused) return@post
            val imeVisible = ViewCompat.getRootWindowInsets(input)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            val window = launcher.window ?: return@post
            if (!imeVisible) WindowCompat.getInsetsController(window, input).show(WindowInsetsCompat.Type.ime())
        }
    }

    private fun setupPadding() {
        launcher.deviceProfile.let { dp ->
            val padding = dp.getAllAppsIconStartMargin(context)
            initialPaddingLeft = padding
            initialPaddingRight = padding
            setPadding(padding, paddingTop, padding, paddingBottom)
        }
    }

    private fun animateHintVisibility(visible: Boolean) {
        val targetAlpha = if (visible) 1f else 0f
        val duration = if (visible) 300L else 200L

        if (visible) {
            hint.alpha = 0f
            hint.isVisible = true
        }

        hint.animate()
            .alpha(targetAlpha)
            .setDuration(duration)
            .setInterpolator(FastOutSlowInInterpolator())
            .withEndAction {
                if (!visible) hint.isVisible = false
            }
            .start()
    }

    private fun animatePadding(newPaddingLeft: Int, newPaddingRight: Int) {
        val currentPaddingLeft = paddingLeft
        val currentPaddingRight = paddingRight

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedFraction
                val leftPadding = currentPaddingLeft + (newPaddingLeft - currentPaddingLeft) * fraction
                val rightPadding = currentPaddingRight + (newPaddingRight - currentPaddingRight) * fraction
                setPadding(leftPadding.toInt(), paddingTop, rightPadding.toInt(), paddingBottom)
            }
            start()
        }
    }

    override fun setFocusedResultTitle(title: CharSequence?, sub: CharSequence?, showArrow: Boolean) {
        focusedResultTitle = title?.toString().orEmpty()
        updateHint()
    }

    override fun refreshResults() {
        onAppsUpdated()
    }

    private fun updateHint() {
        val inputString = input.text.toString()
        val inputLowerCase = inputString.lowercase(Locale.getDefault())
        val focusedLowerCase = focusedResultTitle.lowercase(Locale.getDefault())
        if (canShowHint &&
            inputLowerCase.isNotEmpty() &&
            focusedLowerCase.isNotEmpty() &&
            focusedLowerCase.matches(Regex("^[\\x00-\\x7F]*$")) &&
            focusedLowerCase.startsWith(inputLowerCase)
        ) {
            val hintColor = Themes.getAttrColor(context, android.R.attr.textColorTertiary)
            val hintText = SpannableStringBuilder(inputString)
                .append(focusedLowerCase.substring(inputLowerCase.length))
            hintText.setSpan(ForegroundColorSpan(Color.TRANSPARENT), 0, inputLowerCase.length, SPAN_POINT_MARK)
            hintText.setSpan(ForegroundColorSpan(hintColor), inputLowerCase.length, hintText.length, SPAN_POINT_MARK)
            hint.text = hintText
            hint.isVisible = true
        }
    }

    override fun onGlobalLayout() {
        canShowHint = input.layout?.getEllipsisCount(0) == 0
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        launcher.deviceProfile.inv.addOnChangeListener(this)
        if (::appsView.isInitialized) {
            appsView.appsStore?.addUpdateListener(this)
        }
        input.viewTreeObserver.addOnGlobalLayoutListener(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        launcher.deviceProfile.inv.removeOnChangeListener(this)
        if (::appsView.isInitialized) {
            appsView.appsStore?.removeUpdateListener(this)
        }
        input.viewTreeObserver.removeOnGlobalLayoutListener(this)
        setDirectFocus(false)
    }

    override fun onAppsUpdated() {
        searchBarController.refreshSearchResult()
    }

    override fun initializeSearch(appsView: ActivityAllAppsContainerView<*>) {
        apps = appsView.searchResultList as LawnchairAlphabeticalAppsList<*>
        this.appsView = appsView
        val algorithm = LawnchairSearchAlgorithm.create(context)
        this.searchAlgorithm = algorithm
        searchBarController.initialize(
            algorithm,
            input,
            launcher,
            this,
        )
        input.initialize(appsView)
    }

    override fun resetSearch() {
        searchBarController.reset()
    }

    override fun setDirectFocus(directFocus: Boolean) {
        isDirectFocus = directFocus
    }

    override fun preDispatchKeyEvent(event: KeyEvent) {
        // Determine if the key event was actual text, if so, focus the search bar and then dispatch
        // the key normally so that it can process this key event
        if (!searchBarController.isSearchFieldFocused && event.action == KeyEvent.ACTION_DOWN) {
            val unicodeChar = event.unicodeChar
            val isKeyNotWhitespace = unicodeChar > 0 &&
                !Character.isWhitespace(unicodeChar) &&
                !Character.isSpaceChar(unicodeChar)
            if (isKeyNotWhitespace) {
                val gotKey = TextKeyListener.getInstance().onKeyDown(input, searchQueryBuilder, event.keyCode, event)
                if (gotKey && searchQueryBuilder.isNotEmpty()) {
                    searchBarController.focusSearchField()
                }
            }
        }
    }

    override fun onSearchResult(query: String, items: ArrayList<AdapterItem>?) {
        if (items != null) {
            apps.setSearchResults(items)
            notifyResultChanged()
            appsView.setSearchResults(items)
        }
    }

    override fun clearSearchResult() {
        if (apps.setSearchResults(null)) {
            notifyResultChanged()
        }

        // Clear the search query
        searchQueryBuilder.clear()
        searchQueryBuilder.clearSpans()
        Selection.setSelection(searchQueryBuilder, 0)
        appsView.onClearSearchResult()
        appsView.floatingHeaderView?.setFloatingRowsCollapsed(false)
    }

    private fun notifyResultChanged() {
        appsView.mSearchRecyclerView.onSearchResultsChanged()
    }

    override fun setInsets(insets: Rect) {
        (layoutParams as MarginLayoutParams).apply {
            topMargin = when {
                hideSearchBar -> 0

                // Sheet mode already pads the container with status-bar insets; only clear the
                // drag handle. Re-applying insets.top here created the large empty band under it.
                launcher.deviceProfile.shouldShowAllAppsOnSheet() ->
                    resources.getDimensionPixelSize(R.dimen.bottom_sheet_handle_area_height)

                else -> max(-allAppsSearchVerticalOffset, insets.top - qsbMarginTopAdjusting)
            }
        }
        requestLayout()
    }

    override fun getEditText() = input

    override fun setBackgroundVisibility(visible: Boolean, maxAlpha: Float) {
        if (bgVisible != visible) {
            bgVisible = visible
            bgAlpha = maxAlpha
            if (visible) {
                bgAlphaAnimator.start()
            } else {
                bgAlphaAnimator.reverse()
            }
        } else if (bgAlpha != maxAlpha && !bgAlphaAnimator.isRunning && visible) {
            bgAlpha = maxAlpha
            bgAlphaAnimator.setCurrentFraction(maxAlpha)
            updateBgAlpha()
        }
    }

    override fun getBackgroundVisibility(): Boolean {
        return bgVisible
    }

    private fun updateBgAlpha() {
        val fraction = bgAlphaAnimator.animatedFraction
        bgAlphaState = Utilities.mapRange(fraction, 0f, bgAlpha)
    }

    override fun onIdpChanged(modelPropertiesChanged: Boolean) {
        setupPadding()
        invalidate()
        requestLayout()
    }

    private companion object {
        /**
         * [ColorTokens.SearchboxHighlight], darker in dark mode, where it showed as a light grey
         * over the drawer, and less see-through there with blur.
         */
        val BAR_FILL = DayNightColorToken(ColorTokens.Neutral2_600.setLStar(98.0), ColorTokens.Neutral1_900)
        val BAR_FILL_BLUR = DayNightColorToken(
            ColorTokens.Neutral2_600.setLStar(98.0).setAlpha(.54f),
            ColorTokens.Neutral1_900.setAlpha(.8f),
        )

        /** The bar's corners are fully rounded. */
        const val BAR_CORNER_RADIUS_FACTOR = 1f
    }
}
