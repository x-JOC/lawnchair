package app.lawnchair.allapps.suggestions

import android.content.Context
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.search.algorithms.engine.provider.apps.AppLaunchStore
import app.lawnchair.search.algorithms.engine.provider.apps.AppSuggestionRanker
import app.lawnchair.search.algorithms.engine.provider.apps.AppSuggestions
import app.lawnchair.search.algorithms.engine.provider.apps.SuggestedAppsProvider
import com.android.launcher3.model.data.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads the drawer's app suggestions off the main thread, one load at a time, and reuses the last
 * result while nothing it depends on has changed: the app list, the launch history, the hidden
 * apps, the number of apps per tab and the day (New/Updated looks back 30 days from today).
 *
 * The package manager is only queried again when the app list changes, which is also when install
 * and update times can change.
 */
internal class AppSuggestionsLoader(context: Context) {

    private val context = context.applicationContext

    /** Runs loads one after another on the IO pool, so the fields below need no locking. */
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)

    private var installTimesFor: Array<AppInfo>? = null
    private var installTimes: Map<String, AppSuggestionRanker.InstallInfo> = emptyMap()

    private var cached: AppSuggestions? = null
    private var cachedApps: Array<AppInfo>? = null
    private var cachedHiddenApps: Set<String>? = null
    private var cachedLimit = -1
    private var cachedDay = -1L

    /** Suggestions for [apps] (the drawer's app list) with at most [limit] apps per tab. */
    suspend fun load(apps: Array<AppInfo>, limit: Int): AppSuggestions = withContext(dispatcher) {
        val nowMs = System.currentTimeMillis()
        val day = nowMs / DAY_MS
        val revision = AppLaunchStore.getInstance(context).revision
        val hiddenApps = PreferenceManager2.getInstance(context).hiddenApps.firstCached()

        val previous = cached
        if (previous != null &&
            cachedApps === apps &&
            previous.launchRevision == revision &&
            cachedHiddenApps == hiddenApps &&
            cachedLimit == limit &&
            cachedDay == day
        ) {
            return@withContext previous
        }

        if (installTimesFor !== apps) {
            installTimes = SuggestedAppsProvider.loadInstallTimes(context)
            installTimesFor = apps
        }
        // The snapshot records the launch revision it was built from, so a launch recorded while
        // it loads makes the next call load again.
        val snapshot = SuggestedAppsProvider.loadSnapshot(context, apps.asList(), limit, installTimes, nowMs)
        cached = snapshot
        cachedApps = apps
        cachedHiddenApps = hiddenApps
        cachedLimit = limit
        cachedDay = day
        snapshot
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
