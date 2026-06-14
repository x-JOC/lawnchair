package app.lawnchair.search.algorithms

import android.content.Context
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.search.adapter.SearchLinksTarget
import app.lawnchair.search.adapter.SearchTargetCompat
import app.lawnchair.search.adapter.SearchTargetFactory
import app.lawnchair.search.algorithms.engine.ActionsSectionBuilder
import app.lawnchair.search.algorithms.engine.AppsAndShortcutsSectionBuilder
import app.lawnchair.search.algorithms.engine.CalculationSectionBuilder
import app.lawnchair.search.algorithms.engine.ContactsSectionBuilder
import app.lawnchair.search.algorithms.engine.EmptyStateSectionBuilder
import app.lawnchair.search.algorithms.engine.FilesSectionBuilder
import app.lawnchair.search.algorithms.engine.HistorySectionBuilder
import app.lawnchair.search.algorithms.engine.SearchProvider
import app.lawnchair.search.algorithms.engine.SearchResult
import app.lawnchair.search.algorithms.engine.SearchSettingsSectionBuilder
import app.lawnchair.search.algorithms.engine.SectionBuilder
import app.lawnchair.search.algorithms.engine.SettingsSectionBuilder
import app.lawnchair.search.algorithms.engine.SuggestedAppsSectionBuilder
import app.lawnchair.search.algorithms.engine.SuggestionTab
import app.lawnchair.search.algorithms.engine.WebSuggestionsSectionBuilder
import app.lawnchair.search.algorithms.engine.provider.CalculatorSearchProvider
import app.lawnchair.search.algorithms.engine.provider.ContactsSearchProvider
import app.lawnchair.search.algorithms.engine.provider.FileSearchProvider
import app.lawnchair.search.algorithms.engine.provider.HistorySearchProvider
import app.lawnchair.search.algorithms.engine.provider.SettingsSearchProvider
import app.lawnchair.search.algorithms.engine.provider.ShortcutSearchProvider
import app.lawnchair.search.algorithms.engine.provider.apps.AppSearchProvider
import app.lawnchair.search.algorithms.engine.provider.apps.SuggestedAppsProvider
import app.lawnchair.search.algorithms.engine.provider.web.CustomWebSearchProvider
import app.lawnchair.search.algorithms.engine.provider.web.WebSuggestionProvider
import com.android.launcher3.LauncherAppState
import com.android.launcher3.R
import com.android.launcher3.allapps.BaseAllAppsAdapter
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.search.SearchCallback
import com.patrykmichalik.opto.core.firstBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LawnchairLocalSearchAlgorithm(context: Context) : LawnchairSearchAlgorithm(context) {

    private val appState = LauncherAppState.getInstance(context)

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentJob: Job? = null

    private val appSearchProvider = AppSearchProvider
    private val shortcutSearchProvider = ShortcutSearchProvider
    private val historySearchProvider = HistorySearchProvider

    /** The currently selected zero-state suggestion tab. */
    var selectedSuggestionTab = SuggestionTab.FREQUENT

    /**
     * Cached suggestion lists for the current search session, so switching tabs is instant
     * and doesn't re-query usage stats. Invalidated when the search box loses focus or the
     * app list changes.
     */
    private var cachedSuggestions: Map<SuggestionTab, List<AppInfo>>? = null
    private var cachedUsageGranted = false

    private val searchProviders: List<SearchProvider> = listOf(
        SettingsSearchProvider,
        FileSearchProvider,
        ContactsSearchProvider,
        WebSuggestionProvider,
    )

    override fun doSearch(query: String, callback: SearchCallback<BaseAllAppsAdapter.AdapterItem>) {
        appState.model.enqueueModelUpdateTask { _, _, apps ->
            val appResults = appSearchProvider.search(context, query, apps)
            val shortcutResults = shortcutSearchProvider.search(context, appResults)

            currentJob?.cancel()
            currentJob = coroutineScope.launch {
                val nonAppProvidersFlow = combine(
                    searchProviders.map { it.search(context, query) },
                ) { resultsArray ->
                    resultsArray.toList().flatten()
                }

                nonAppProvidersFlow.collect { nonAppResults ->
                    val calcResult = CalculatorSearchProvider.search(context, query)
                        .firstOrNull()

                    val allResults = appResults + shortcutResults + (calcResult ?: emptyList()) + nonAppResults + generateActionResults(query)

                    val searchTargets = translateToSearchTargets(allResults)
                    setFirstItemQuickLaunch(searchTargets)
                    val adapterItems = transformSearchResults(searchTargets)
                    withContext(Dispatchers.Main) {
                        callback.onSearchResult(query, ArrayList(adapterItems))
                    }
                }
            }
        }
    }

    override fun doZeroStateSearch(callback: SearchCallback<BaseAllAppsAdapter.AdapterItem>) {
        currentJob?.cancel()

        val prefs2 = PreferenceManager2.getInstance(context)
        val suggestionsEnabled = prefs2.showSuggestedAppsInSearch.firstBlocking()

        if (!suggestionsEnabled) {
            currentJob = coroutineScope.launch {
                postZeroState(emptyMap(), usageGranted = false, showTabs = false, callback = callback)
            }
            return
        }

        val cached = cachedSuggestions
        if (cached != null) {
            currentJob = coroutineScope.launch {
                postZeroState(cached, cachedUsageGranted, showTabs = true, callback = callback)
            }
            return
        }

        appState.model.enqueueModelUpdateTask { _, _, apps ->
            val columns = LauncherAppState.getIDP(context).numAllAppsColumns
            val limit = (columns * 2).coerceAtLeast(1)
            val granted = SuggestedAppsProvider.hasUsageAccess(context)
            val suggestions = SuggestedAppsProvider.getSuggestions(context, apps, limit)
            cachedSuggestions = suggestions
            cachedUsageGranted = granted
            currentJob = coroutineScope.launch {
                postZeroState(suggestions, granted, showTabs = true, callback = callback)
            }
        }
    }

    override fun selectSuggestionTab(tab: SuggestionTab, callback: SearchCallback<BaseAllAppsAdapter.AdapterItem>) {
        selectedSuggestionTab = tab
        val cached = cachedSuggestions
        if (cached != null) {
            currentJob?.cancel()
            currentJob = coroutineScope.launch {
                postZeroState(cached, cachedUsageGranted, showTabs = true, callback = callback)
            }
        } else {
            doZeroStateSearch(callback)
        }
    }

    override fun invalidateSuggestionCache() {
        cachedSuggestions = null
    }

    private suspend fun postZeroState(
        suggestions: Map<SuggestionTab, List<AppInfo>>,
        usageGranted: Boolean,
        showTabs: Boolean,
        callback: SearchCallback<BaseAllAppsAdapter.AdapterItem>,
    ) {
        val prefs = PreferenceManager.getInstance(context)
        val prefs2 = PreferenceManager2.getInstance(context)
        val historyEnabled = prefs.searchResulRecentSuggestion.get()

        val results = mutableListOf<SearchResult>()

        if (showTabs) {
            results.add(SearchResult.Action.SuggestionTabs(selectedSuggestionTab, usageGranted))
            suggestions.forEach { (tab, apps) ->
                apps.forEach { results.add(SearchResult.SuggestedApp(it, tab)) }
            }
        }

        val historyResults = if (historyEnabled) {
            val maxHistory = prefs2.maxRecentResultCount.firstBlocking()
            historySearchProvider.getRecentKeywords(context, maxHistory)
        } else {
            emptyList()
        }
        results.addAll(historyResults)

        when {
            showTabs || historyResults.isNotEmpty() -> {
                results.add(SearchResult.Action.SearchSettings)
            }

            historyEnabled -> {
                // History enabled but empty, and no suggestions: keep the original empty-state card.
                results.add(
                    SearchResult.Action.EmptyState(
                        titleRes = R.string.search_empty_state_title,
                        subtitleRes = R.string.search_empty_state_no_history_subtitle,
                    ),
                )
                results.add(SearchResult.Action.SearchSettings)
            }

            else -> {
                // Nothing to show (suggestions and history both off).
                withContext(Dispatchers.Main) { callback.clearSearchResult() }
                return
            }
        }

        val searchTargets = translateToSearchTargets(results)
        val adapterItems = transformSearchResults(searchTargets)
        withContext(Dispatchers.Main) {
            callback.onSearchResult("", ArrayList(adapterItems))
        }
    }

    override fun cancel(interruptActiveRequests: Boolean) {
        currentJob?.cancel()
    }

    private fun generateActionResults(query: String): List<SearchResult.Action> {
        val actions = mutableListOf<SearchResult.Action>()
        val prefs = PreferenceManager.getInstance(context)
        val prefs2 = PreferenceManager2.getInstance(context)

        if (prefs.searchResultStartPageSuggestion.get()) {
            val provider = prefs2.webSuggestionProvider.firstBlocking()
            val webProvider = provider.configure(context)

            val providerName = if (webProvider is CustomWebSearchProvider) {
                webProvider.getDisplayName()
            } else {
                context.getString(webProvider.label)
            }

            actions.add(
                SearchResult.Action.WebSearch(
                    query = query,
                    providerName = providerName,
                    searchUrl = webProvider.getSearchUrl(query),
                    providerIconRes = webProvider.iconRes,
                    tintIcon = webProvider is CustomWebSearchProvider,
                ),
            )
        }

        if (SearchLinksTarget.resolveMarketSearchActivity(context) != null) {
            actions.add(SearchResult.Action.MarketSearch(query = query))
        }

        actions.add(SearchResult.Action.SearchSettings)

        return actions
    }

    private val sectionBuilders: List<SectionBuilder> = listOf(
        SuggestedAppsSectionBuilder,
        AppsAndShortcutsSectionBuilder,
        CalculationSectionBuilder,
        WebSuggestionsSectionBuilder,
        ContactsSectionBuilder,
        FilesSectionBuilder,
        SettingsSectionBuilder,
        HistorySectionBuilder,
        ActionsSectionBuilder,
        EmptyStateSectionBuilder,
        SearchSettingsSectionBuilder,
    )

    private fun translateToSearchTargets(
        results: List<SearchResult>,
    ): List<SearchTargetCompat> {
        val factory = SearchTargetFactory(context)

        // The new function is just a flatMap. It's declarative and beautiful.
        return sectionBuilders.flatMap { builder ->
            builder.build(context, factory, results)
        }
    }
}
