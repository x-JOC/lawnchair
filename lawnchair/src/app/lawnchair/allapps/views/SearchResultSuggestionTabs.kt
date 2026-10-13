package app.lawnchair.allapps.views

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
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
import app.lawnchair.ui.preferences.PreferenceActivity
import app.lawnchair.ui.preferences.destinations.SearchRoute
import app.lawnchair.ui.preferences.navigation.Search
import com.android.launcher3.R
import com.android.launcher3.workprofile.PersonalWorkSlidingTabStrip

/**
 * The header for the zero-state app suggestions: three switchable tabs (frequent / recent / new)
 * drawn with the app drawer's own Personal/Work tab strip, so they share its pills, colors and font
 * and follow the user's theme and tab color. The row lines up with the first and last icon columns
 * and ends with the search settings button, so the settings gear doesn't sit alone below the
 * results. The icons for the selected tab follow in the search grid. Tapping a tab asks the search
 * input to re-render the empty state for that tab.
 */
class SearchResultSuggestionTabs(context: Context, attrs: AttributeSet?) :
    LinearLayout(context, attrs),
    SearchResultView {

    private lateinit var tabsRow: View
    private lateinit var tabStrip: PersonalWorkSlidingTabStrip
    private lateinit var frequentTab: Button
    private lateinit var recentTab: Button
    private lateinit var newTab: Button
    private lateinit var emptyHint: TextView

    override fun onFinishInflate() {
        super.onFinishInflate()
        tabsRow = ViewCompat.requireViewById(this, R.id.tabs_row)
        tabStrip = ViewCompat.requireViewById(this, R.id.tabs_container)
        frequentTab = ViewCompat.requireViewById(this, R.id.tab_frequent)
        recentTab = ViewCompat.requireViewById(this, R.id.tab_recent)
        newTab = ViewCompat.requireViewById(this, R.id.tab_new)
        emptyHint = ViewCompat.requireViewById(this, R.id.empty_hint)
        ViewCompat.requireViewById<ImageButton>(this, R.id.search_settings).setOnClickListener {
            context.startActivity(PreferenceActivity.createIntent(context, Search(SearchRoute.DRAWER_SEARCH)))
        }

        // Delegates are set once; they read the selected state from the view at query time.
        ViewCompat.setAccessibilityDelegate(
            tabStrip,
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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Same inset as the drawer's tab strip with alignOnIcon: the row spans from the first icon
        // column to the last one. Done on the row rather than the strip so the settings button
        // stays inside that span.
        val width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        if (width > 0) {
            val inset = (width - PersonalWorkSlidingTabStrip.getTabWidth(context, width)) / 2
            if (tabsRow.paddingStart != inset || tabsRow.paddingEnd != inset) {
                tabsRow.setPaddingRelative(inset, tabsRow.paddingTop, inset, tabsRow.paddingBottom)
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override val isQuickLaunch: Boolean = false
    override fun launch(): Boolean = false

    override fun bind(
        target: SearchTargetCompat,
        shortcuts: List<SearchTargetCompat>,
    ) {
        val extras = target.extras
        val selected = TAB_ORDER.getOrElse(extras.getInt("selected", 0)) { SuggestionTab.FREQUENT }
        val selectedEmpty = extras.getBoolean("selectedEmpty", false)

        // Same selection mechanism as the drawer's Personal/Work tabs: the selected state drives
        // the pill background and text color state lists.
        tabStrip.updateTabTextColor(TAB_ORDER.indexOf(selected))

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

    private fun tabView(tab: SuggestionTab): Button = when (tab) {
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
        /** Must match the order of the buttons in the tab strip. */
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
