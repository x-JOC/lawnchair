package app.lawnchair.allapps.views

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
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
 * for that tab. When usage access is missing, a tappable prompt is shown for the
 * frequent/recent tabs so the user can grant it.
 */
class SearchResultSuggestionTabs(context: Context, attrs: AttributeSet?) :
    LinearLayout(context, attrs),
    SearchResultView {

    private lateinit var tabsContainer: LinearLayout
    private lateinit var frequentTab: TextView
    private lateinit var recentTab: TextView
    private lateinit var newTab: TextView
    private lateinit var usageAccessPrompt: TextView

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
        usageAccessPrompt = ViewCompat.requireViewById(this, R.id.usage_access_prompt)

        frequentTab.setOnClickListener { onTabClicked(SuggestionTab.FREQUENT) }
        recentTab.setOnClickListener { onTabClicked(SuggestionTab.RECENT) }
        newTab.setOnClickListener { onTabClicked(SuggestionTab.NEW) }
        usageAccessPrompt.setOnClickListener {
            runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
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
        usageAccessPrompt.setTextColor(accentColor)

        val extras = target.extras
        val selected = TAB_ORDER.getOrElse(extras.getInt("selected", 0)) { SuggestionTab.FREQUENT }
        val usageGranted = extras.getBoolean("usageGranted", false)

        // Reset all segments first, then highlight the selected one. Text weight stays constant
        // (no bold) so segments never change width when switching tabs.
        for (tab in TAB_ORDER) {
            val view = tabView(tab)
            val isSelected = tab == selected
            view.background = if (isSelected) selectedPill(accentColor) else null
            view.setTextColor(if (isSelected) onAccentColor else secondaryColor)
            applyTabAccessibility(view, isSelected)
        }

        val needsUsageAccess = !usageGranted &&
            (selected == SuggestionTab.FREQUENT || selected == SuggestionTab.RECENT)
        usageAccessPrompt.isVisible = needsUsageAccess
    }

    private fun tabView(tab: SuggestionTab): TextView = when (tab) {
        SuggestionTab.FREQUENT -> frequentTab
        SuggestionTab.RECENT -> recentTab
        SuggestionTab.NEW -> newTab
    }

    /** Announce each segment as a selectable tab so TalkBack reports the selected state. */
    private fun applyTabAccessibility(view: TextView, isSelected: Boolean) {
        ViewCompat.setAccessibilityDelegate(
            view,
            object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.roleDescription = host.context.getString(R.string.search_suggested_tab_role)
                    info.isSelected = isSelected
                }
            },
        )
    }

    private fun onTabClicked(tab: SuggestionTab) {
        val searchUiManager = context.launcher.appsView.searchUiManager
        (searchUiManager as? AllAppsSearchInput)?.selectSuggestionTab(tab)
    }

    private companion object {
        /** Cached once: [SuggestionTab.values] allocates a new array on every call. */
        val TAB_ORDER: List<SuggestionTab> = SuggestionTab.values().toList()
    }
}
