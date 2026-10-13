package app.lawnchair.allapps.views

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.CollectionInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.CollectionItemInfoCompat
import androidx.core.view.isVisible
import app.lawnchair.allapps.AllAppsSearchInput
import app.lawnchair.launcher
import app.lawnchair.search.adapter.SearchTargetCompat
import app.lawnchair.search.algorithms.engine.SuggestionTab
import app.lawnchair.theme.color.tokens.ColorTokens
import com.android.launcher3.R
import com.android.launcher3.util.Themes

/**
 * The header control for the zero-state app suggestions: a segmented control with three
 * switchable tabs (frequent / recent / new) inside a rounded track, the selected one shown
 * as a filled accent pill. Tapping a tab asks the search input to re-render the empty state
 * for that tab.
 */
class SearchResultSuggestionTabs(context: Context, attrs: AttributeSet?) :
    LinearLayout(context, attrs),
    SearchResultView {

    private lateinit var tabsContainer: LinearLayout
    private lateinit var frequentTab: TextView
    private lateinit var recentTab: TextView
    private lateinit var newTab: TextView
    private lateinit var emptyHint: TextView

    private val density = resources.displayMetrics.density

    private fun trackBackground(secondaryColor: Int): Drawable = GradientDrawable().apply {
        cornerRadius = density * 100f
        setColor(ColorUtils.setAlphaComponent(secondaryColor, 76))
    }

    private fun selectedPill(accentColor: Int): Drawable {
        val pill = GradientDrawable().apply {
            cornerRadius = density * 100f
            setColor(accentColor)
        }
        val inset = (density * 3f).toInt()
        return InsetDrawable(pill, inset, inset, inset, inset)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        tabsContainer = ViewCompat.requireViewById(this, R.id.tabs_container)
        frequentTab = ViewCompat.requireViewById(this, R.id.tab_frequent)
        recentTab = ViewCompat.requireViewById(this, R.id.tab_recent)
        newTab = ViewCompat.requireViewById(this, R.id.tab_new)
        emptyHint = ViewCompat.requireViewById(this, R.id.empty_hint)

        // Delegates are set once; they read the selected state from the view at query time.
        ViewCompat.setAccessibilityDelegate(
            tabsContainer,
            object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.setCollectionInfo(CollectionInfoCompat.obtain(1, TAB_ORDER.size, false))
                }
            },
        )
        TAB_ORDER.forEachIndexed { index, tab ->
            val view = tabView(tab)
            view.setOnClickListener { onTabClicked(tab) }
            ViewCompat.setAccessibilityDelegate(
                view,
                object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.roleDescription = host.context.getString(R.string.search_suggested_tab_role)
                        info.isSelected = host.isSelected
                        info.setCollectionItemInfo(
                            CollectionItemInfoCompat.obtain(0, 1, index, 1, false, host.isSelected),
                        )
                    }
                },
            )
        }
    }

    override val isQuickLaunch: Boolean = false
    override fun launch(): Boolean = false

    override fun bind(
        target: SearchTargetCompat,
        shortcuts: List<SearchTargetCompat>,
    ) {
        // Resolve theme colors on every bind so dark-mode / wallpaper-accent changes are picked up
        // and a recycled view never keeps stale colors.
        val accentColor = ColorTokens.ColorAccent.resolveColor(context)
        val secondaryColor = Themes.getAttrColor(context, android.R.attr.textColorSecondary)
        val onAccentColor = if (ColorUtils.calculateLuminance(accentColor) > 0.5) {
            0xDE000000.toInt()
        } else {
            Color.WHITE
        }

        tabsContainer.background = trackBackground(secondaryColor)
        emptyHint.setTextColor(secondaryColor)

        val extras = target.extras
        val selected = TAB_ORDER.getOrElse(extras.getInt("selected", 0)) { SuggestionTab.FREQUENT }
        val selectedEmpty = extras.getBoolean("selectedEmpty", false)

        // Reset all segments first, then highlight the selected one. Text weight stays constant
        // (no bold) so segments never change width when switching tabs.
        for (tab in TAB_ORDER) {
            val view = tabView(tab)
            val isSelected = tab == selected
            view.isSelected = isSelected
            view.background = if (isSelected) selectedPill(accentColor) else null
            view.setTextColor(if (isSelected) onAccentColor else secondaryColor)
        }

        emptyHint.isVisible = selectedEmpty
        emptyHint.setText(
            if (selected == SuggestionTab.NEW) R.string.search_suggested_empty_new else R.string.search_suggested_empty_launches,
        )

        // Rebinding after a tab switch can drop accessibility focus; put it back on the tab the
        // user just activated. A request whose rebind never came (e.g. superseded by typing) expires.
        pendingAccessibilityFocus?.let { pending ->
            val expired = SystemClock.uptimeMillis() - pending.requestedAt > PENDING_FOCUS_TIMEOUT_MS
            if (expired || pending.tab == selected) pendingAccessibilityFocus = null
            if (!expired && pending.tab == selected) {
                val view = tabView(selected)
                view.post { view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) }
            }
        }
    }

    private fun tabView(tab: SuggestionTab): TextView = when (tab) {
        SuggestionTab.FREQUENT -> frequentTab
        SuggestionTab.RECENT -> recentTab
        SuggestionTab.NEW -> newTab
    }

    private fun onTabClicked(tab: SuggestionTab) {
        val accessibilityManager = context.getSystemService(AccessibilityManager::class.java)
        pendingAccessibilityFocus = if (accessibilityManager?.isTouchExplorationEnabled == true) {
            PendingFocus(tab, SystemClock.uptimeMillis())
        } else {
            null
        }
        val searchUiManager = context.launcher.appsView.searchUiManager
        (searchUiManager as? AllAppsSearchInput)?.selectSuggestionTab(tab)
    }

    private companion object {
        val TAB_ORDER: List<SuggestionTab> = SuggestionTab.entries

        const val PENDING_FOCUS_TIMEOUT_MS = 1000L

        class PendingFocus(val tab: SuggestionTab, val requestedAt: Long)

        /**
         * The tab to move accessibility focus to when it is next bound. Kept here rather than on
         * the instance because the list may rebind the header into a different view.
         */
        var pendingAccessibilityFocus: PendingFocus? = null
    }
}
