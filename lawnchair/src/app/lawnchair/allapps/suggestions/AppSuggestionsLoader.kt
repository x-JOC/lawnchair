package app.lawnchair.allapps.suggestions

import android.content.Context
import android.os.SystemClock
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.ItemInfoWithIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads the drawer's app suggestions off the main thread, one load at a time, and reuses the last
 * result while nothing it depends on has changed: the app list, the launch history, the hidden
 * apps, the number of apps per tab, the day (New/Updated looks back 30 days from today) and the
 * install times.
 *
 * Install and update times come from one package manager query. The drawer's app list is replaced
 * on every change, including each progress step of an install, so the query is only made again
 * when the installed packages or the installs in progress change (an app installed, removed, or
 * done updating), and at the latest after [INSTALL_TIMES_MAX_AGE_MS], for updates the launcher
 * sees no install for.
 */
internal class AppSuggestionsLoader(context: Context) {

    private val context = context.applicationContext

    /** Runs loads one after another on the IO pool, so the fields below need no locking. */
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)

    private var installTimes: Map<String, AppSuggestionRanker.InstallInfo> = emptyMap()
    private var installTimesKey: InstallTimesKey? = null
    private var installTimesLoadedAt = 0L

    private var cached: AppSuggestions? = null
    private var cachedApps: Array<AppInfo>? = null
    private var cachedHiddenApps: Set<String>? = null
    private var cachedLimit = -1
    private var cachedDay = -1L

    /** Suggestions for [apps] (the drawer's app list) with at most [limit] apps per tab. */
    suspend fun load(apps: Array<AppInfo>, limit: Int): AppSuggestions = withContext(dispatcher) {
        val nowMs = System.currentTimeMillis()
        val day = nowMs / DAY_MS
        val elapsedMs = SystemClock.elapsedRealtime()
        val revision = AppLaunchStore.getInstance(context).revision
        val hiddenApps = PreferenceManager2.getInstance(context).hiddenApps.firstCached()
        val installTimesFresh = installTimesKey != null &&
            elapsedMs - installTimesLoadedAt < INSTALL_TIMES_MAX_AGE_MS

        val previous = cached
        if (previous != null &&
            cachedApps === apps &&
            previous.launchRevision == revision &&
            cachedHiddenApps == hiddenApps &&
            cachedLimit == limit &&
            cachedDay == day &&
            installTimesFresh
        ) {
            return@withContext previous
        }

        val key = InstallTimesKey.of(apps)
        if (key != installTimesKey || !installTimesFresh) {
            installTimes = SuggestedAppsProvider.loadInstallTimes(context)
            installTimesKey = key
            installTimesLoadedAt = elapsedMs
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

    /**
     * The packages in the app list, and those of them with an install or update in progress.
     * Install and update times can only have changed when this changes.
     */
    private data class InstallTimesKey(val packages: Set<String>, val installing: Set<String>) {
        companion object {
            fun of(apps: Array<AppInfo>): InstallTimesKey {
                val packages = HashSet<String>(apps.size)
                val installing = HashSet<String>()
                apps.forEach { app ->
                    val pkg = app.componentName?.packageName ?: return@forEach
                    packages += pkg
                    if ((app.runtimeStatusFlags and ItemInfoWithIcon.FLAG_INSTALL_SESSION_ACTIVE) != 0) {
                        installing += pkg
                    }
                }
                return InstallTimesKey(packages, installing)
            }
        }
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000

        /** See the class comment. */
        const val INSTALL_TIMES_MAX_AGE_MS = 5L * 60 * 1000
    }
}
