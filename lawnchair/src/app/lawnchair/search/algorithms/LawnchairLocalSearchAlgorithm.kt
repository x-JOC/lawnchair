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
import app.lawnchair.search.algorithms.engine.provider.apps.AppSuggestions
import app.lawnchair.search.algorithms.engine.provider.apps.SuggestedAppsProvider
import app.lawnchair.search.algorithms.engine.provider.web.CustomWebSearchProvider
import app.lawnchair.search.algorithms.engine.provider.web.WebSuggestionProvider
import com.android.launcher3.LauncherAppState
import com.android.launcher3.R
import com.android.launcher3.allapps.BaseAllAppsAdapter
import com.android.launcher3.search.SearchCallback
import com.patrykmichalik.opto.core.firstBlocking
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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

    /**
     * Zero-state suggestion session: the selected tab plus a cached snapshot so switching tabs is
     * instant and doesn't re-query usage stats. Held as one atomic value because it is read and
     * written across the main thread, the model executor and IO coroutines. The snapshot is cleared
     * when the search box loses focus or the app list changes; the tab choice persists.
     */
    private data class SuggestionState(
        val selectedTab: SuggestionTab = SuggestionTab.FREQUENT,
        val userPickedTab: Boolean = false,
        val snapshot: AppSuggestions? = null,
    )

    private val suggestionState = AtomicReference(SuggestionState())

    /**
     * Monotonic request token, bumped on every new zero-state request and on cancel. A stale model
     * task or a late coroutine compares it before posting and skips if it has been superseded — the
     * enqueued model task itself is not cancellable mid-flight, so this is what makes cancellation
     * effective and stops a stale zero-state result from painting over a live query.
     */
    private val requestSeq = AtomicInteger(0)

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
        val token = requestSeq.incrementAndGet()

        val prefs2 = PreferenceManager2.getInstance(context)
        val suggestionsEnabled = prefs2.showSuggestedAppsInSearch.firstBlocking()

        if (!suggestionsEnabled) {
            currentJob = coroutineScope.launch {
                postZeroState(token, suggestions = null, selectedTab = SuggestionTab.FREQUENT, callback = callback)
            }
            return
        }

        val state = suggestionState.get()
        val cached = state.snapshot
        if (cached != null) {
            currentJob = coroutineScope.launch {
                postZeroState(token, cached, state.selectedTab, callback)
            }
            return
        }

        appState.model.enqueueModelUpdateTask { _, _, apps ->
            if (token != requestSeq.get()) return@enqueueModelUpdateTask
            val columns = LauncherAppState.getIDP(context).numAllAppsColumns
            val limit = (columns * 2).coerceAtLeast(1)
            val snapshot = SuggestedAppsProvider.loadSnapshot(context, apps, limit)
            val updated = suggestionState.updateAndGet { current ->
                current.copy(snapshot = snapshot, selectedTab = resolveTab(current, snapshot))
            }
            if (token != requestSeq.get()) return@enqueueModelUpdateTask
            currentJob = coroutineScope.launch {
                postZeroState(token, snapshot, updated.selectedTab, callback)
            }
        }
    }

    /** Default tab for a freshly-loaded snapshot, unless the user has explicitly picked one. */
    private fun resolveTab(state: SuggestionState, snapshot: AppSuggestions): SuggestionTab {
        if (state.userPickedTab) return state.selectedTab
        val default = if (snapshot.usageGranted) SuggestionTab.FREQUENT else SuggestionTab.NEW
        return snapshot.firstNonEmptyTab(default)
    }

    override fun selectSuggestionTab(tab: SuggestionTab, callback: SearchCallback<BaseAllAppsAdapter.AdapterItem>) {
        currentJob?.cancel()
        val token = requestSeq.incrementAndGet()
        val updated = suggestionState.updateAndGet { it.copy(selectedTab = tab, userPickedTab = true) }
        val snapshot = updated.snapshot
        if (snapshot != null) {
            currentJob = coroutineScope.launch {
                postZeroState(token, snapshot, tab, callback)
            }
        } else {
            doZeroStateSearch(callback)
        }
    }

    override fun invalidateSuggestionCache() {
        suggestionState.updateAndGet { it.copy(snapshot = null) }
    }

    private suspend fun postZeroState(
        token: Int,
        suggestions: AppSuggestions?,
        selectedTab: SuggestionTab,
        callback: SearchCallback<BaseAllAppsAdapter.AdapterItem>,
    ) {
        val prefs = PreferenceManager.getInstance(context)
        val prefs2 = PreferenceManager2.getInstance(context)
        val historyEnabled = prefs.searchResulRecentSuggestion.get()

        val results = mutableListOf<SearchResult>()

        val showTabs = suggestions != null && suggestions.hasAny
        if (showTabs) {
            results.add(SearchResult.Action.SuggestionTabs(selectedTab, suggestions!!.usageGranted))
            suggestions.appsFor(selectedTab).forEach {
                results.add(SearchResult.SuggestedApp(it, selectedTab))
            }
        }

        if (historyEnabled) {
            val maxHistory = prefs2.maxRecentResultCount.firstBlocking()
            results.addAll(historySearchProvider.getRecentKeywords(context, maxHistory))
        }

        when {
            results.isNotEmpty() -> {
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
        }

        if (results.isEmpty()) {
            // Nothing to show (suggestions and history both off / empty).
            withContext(Dispatchers.Main) {
                if (token == requestSeq.get()) callback.clearSearchResult()
            }
            return
        }

        val searchTargets = translateToSearchTargets(results)
        val adapterItems = transformSearchResults(searchTargets)
        withContext(Dispatchers.Main) {
            if (token == requestSeq.get()) callback.onSearchResult("", ArrayList(adapterItems))
        }
    }

    override fun cancel(interruptActiveRequests: Boolean) {
        requestSeq.incrementAndGet()
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
