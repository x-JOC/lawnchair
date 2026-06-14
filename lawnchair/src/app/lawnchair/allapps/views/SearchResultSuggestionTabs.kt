package app.lawnchair.allapps.views

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.provider.Settings
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
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
    private val accentColor = ColorTokens.ColorAccent.resolveColor(context)
    private val secondaryColor = Themes.getAttrColor(context, android.R.attr.textColorSecondary)
    private val onAccentColor = if (ColorUtils.calculateLuminance(accentColor) > 0.5) {
        0xDE000000.toInt()
    } else {
        Color.WHITE
    }

    private val trackBackground: Drawable
        get() = GradientDrawable().apply {
            cornerRadius = density * 100f
            setColor(ColorUtils.setAlphaComponent(secondaryColor, 76))
        }

    private fun selectedPill(): Drawable {
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

        tabsContainer.background = trackBackground

        frequentTab.setOnClickListener { onTabClicked(SuggestionTab.FREQUENT) }
        recentTab.setOnClickListener { onTabClicked(SuggestionTab.RECENT) }
        newTab.setOnClickListener { onTabClicked(SuggestionTab.NEW) }
        usageAccessPrompt.setOnClickListener {
            runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }
        usageAccessPrompt.setTextColor(accentColor)
    }

    override val isQuickLaunch: Boolean = false
    override fun launch(): Boolean = false

    override fun bind(
        target: SearchTargetCompat,
        shortcuts: List<SearchTargetCompat>,
    ) {
        val extras = target.extras
        val selected = SuggestionTab.values().getOrElse(extras.getInt("selected", 0)) { SuggestionTab.FREQUENT }
        val usageGranted = extras.getBoolean("usageGranted", false)

        // Reset all segments first, then highlight the selected one. Text weight stays
        // constant (no bold) so segments never change width when switching tabs.
        for (tab in listOf(frequentTab, recentTab, newTab)) {
            tab.background = null
            tab.setTextColor(secondaryColor)
        }
        val selectedView = when (selected) {
            SuggestionTab.FREQUENT -> frequentTab
            SuggestionTab.RECENT -> recentTab
            SuggestionTab.NEW -> newTab
        }
        selectedView.background = selectedPill()
        selectedView.setTextColor(onAccentColor)

        val needsUsageAccess = !usageGranted &&
            (selected == SuggestionTab.FREQUENT || selected == SuggestionTab.RECENT)
        usageAccessPrompt.isVisible = needsUsageAccess
    }

    private fun onTabClicked(tab: SuggestionTab) {
        val searchUiManager = context.launcher.appsView.searchUiManager
        (searchUiManager as? AllAppsSearchInput)?.selectSuggestionTab(tab)
    }
}
