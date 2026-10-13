package app.lawnchair.search.algorithms

import android.content.Context
import android.content.pm.ShortcutInfo
import app.lawnchair.launcher
import app.lawnchair.ui.preferences.components.HiddenAppsInSearch
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.popup.PopupPopulator
import com.android.launcher3.search.StringMatcherUtility
import com.android.launcher3.shortcuts.ShortcutRequest
import java.util.Locale
import me.xdrop.fuzzywuzzy.FuzzySearch
import me.xdrop.fuzzywuzzy.algorithms.WeightedRatio

object SearchUtils {
    fun normalSearch(apps: List<AppInfo>, query: String, maxResultsCount: Int, hiddenApps: Set<String>, hiddenAppsInSearch: String): List<AppInfo> {
        // Rank the matches before applying the limit, so that a better match is not cut off by
        // weaker ones that come earlier in the app list. The sort is stable, so apps with the
        // same match type keep their order.
        val queryNormalized = normalizeForSearch(query)
        val matcher = StringMatcherUtility.StringMatcher.getInstance()
        return apps.asSequence()
            .filterHiddenApps(queryNormalized, hiddenApps, hiddenAppsInSearch)
            .mapNotNull { app -> matchAppTitle(queryNormalized, app.title.toString(), matcher)?.let { app to it } }
            .sortedBy { it.second }
            .take(maxResultsCount)
            .map { it.first }
            .toList()
    }

    fun fuzzySearch(apps: List<AppInfo>, query: String, maxResultsCount: Int, hiddenApps: Set<String>, hiddenAppsInSearch: String): List<AppInfo> {
        val queryNormalized = normalizeForSearch(query)
        val filteredApps = apps.asSequence()
            .filterHiddenApps(queryNormalized, hiddenApps, hiddenAppsInSearch)
            .toList()
        val matches = FuzzySearch.extractSorted(
            queryNormalized,
            filteredApps,
            { normalizeForSearch(it.sectionName + it.title) },
            WeightedRatio(),
            65,
        )

        return matches.take(maxResultsCount)
            .map { it.referent }
    }

    /**
     * How well an app title matches the query in the non-fuzzy search, from best to worst.
     */
    private enum class AppTitleMatch {
        EXACT,
        PREFIX,
        WORD_START,
        SUBSTRING,
    }

    /**
     * Returns how [title] matches [query] in the non-fuzzy search, or `null` if it doesn't.
     *
     * The query must already be normalized with [normalizeForSearch]. The title is normalized
     * here, keeping its case so that camel case word breaks still work (e.g. "tube" matches
     * "YouTube").
     *
     * The query has to match at the start of a word. Arabic labels often start with an attached
     * article or prefix (e.g. "al-"), so for Arabic queries any substring of the title also
     * matches, similar to how [StringMatcherUtility] handles Han characters.
     */
    private fun matchAppTitle(query: String, title: String, matcher: StringMatcherUtility.StringMatcher): AppTitleMatch? {
        if (query.isEmpty()) return null
        val titleNormalized = normalizeForSearch(title, lowercase = false)
        val titleLower = titleNormalized.lowercase(Locale.getDefault())
        return when {
            titleLower == query -> AppTitleMatch.EXACT

            titleNormalized.length >= query.length &&
                matcher.matches(query, titleNormalized.substring(0, query.length)) -> AppTitleMatch.PREFIX

            StringMatcherUtility.matches(query, titleNormalized, matcher) -> AppTitleMatch.WORD_START

            query.containsArabicScript() && titleLower.contains(query) -> AppTitleMatch.SUBSTRING

            else -> null
        }
    }

    fun getShortcuts(app: AppInfo, context: Context): List<ShortcutInfo> {
        val shortcuts = ShortcutRequest(context.launcher, app.user)
            .withContainer(app.targetComponent)
            .query(ShortcutRequest.PUBLISHED)
        return PopupPopulator.sortAndFilterShortcuts(shortcuts)
    }
}

/**
 * Filters out hidden apps according to [hiddenAppsInSearch].
 *
 * @param query The search query, normalized with [normalizeForSearch].
 */
fun Sequence<AppInfo>.filterHiddenApps(
    query: String,
    hiddenApps: Set<String>,
    hiddenAppsInSearch: String,
): Sequence<AppInfo> {
    return when (hiddenAppsInSearch) {
        HiddenAppsInSearch.ALWAYS -> {
            this
        }

        HiddenAppsInSearch.IF_NAME_TYPED -> {
            filter {
                it.toComponentKey().toString() !in hiddenApps ||
                    normalizeForSearch(it.title.toString()) == query
            }
        }

        else -> {
            filter { it.toComponentKey().toString() !in hiddenApps }
        }
    }
}
