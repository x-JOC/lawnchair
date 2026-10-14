package app.lawnchair.search.algorithms.engine.provider.apps

import android.content.Context
import android.os.Process
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.search.algorithms.engine.SuggestionTab
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.ItemInfoWithIcon

/**
 * Immutable snapshot of the three zero-state suggestion lists. A single value so it can be cached
 * and swapped atomically. [launchRevision] is the [AppLaunchStore.revision] it was computed from.
 */
data class AppSuggestions(
    val byTab: Map<SuggestionTab, List<AppInfo>>,
    val launchRevision: Int,
) {
    fun appsFor(tab: SuggestionTab): List<AppInfo> = byTab[tab].orEmpty()

    /** [preferred] if it is non-empty, otherwise the first non-empty tab, otherwise [preferred]. */
    fun firstNonEmptyTab(preferred: SuggestionTab): SuggestionTab {
        if (appsFor(preferred).isNotEmpty()) return preferred
        return SuggestionTab.entries.firstOrNull { appsFor(it).isNotEmpty() } ?: preferred
    }
}

/**
 * Gathers the data for the zero-state app suggestions and hands it to the pure
 * [AppSuggestionRanker]. None of the tabs needs a permission:
 *  - [SuggestionTab.FREQUENT]: apps launched most often from Lawnchair, recent launches weighing more.
 *  - [SuggestionTab.RECENT]: apps launched most recently from Lawnchair.
 *  - [SuggestionTab.NEW]: apps installed or updated in the last 30 days, like Nova's New/Updated tab.
 *
 * Launches come from [AppLaunchStore]. All work is blocking (file and package-manager reads) and
 * must run on a background thread. It does not touch the app model, so it should not run on the
 * model executor either: callers pass a copy of the app list.
 */
object SuggestedAppsProvider {

    private const val NEW_APP_HORIZON_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * Computes all three suggestion lists in one pass, over the apps that can actually be opened:
     * not hidden, not still installing, and not Lawnchair itself. Each list holds at most [limit]
     * apps. [installTimes] can be passed in by a caller that caches them between loads (see
     * [loadInstallTimes]), and [nowMs] is injected so the time reference is the same for every list.
     */
    fun loadSnapshot(
        context: Context,
        apps: List<AppInfo>,
        limit: Int,
        installTimes: Map<String, AppSuggestionRanker.InstallInfo> = loadInstallTimes(context),
        nowMs: Long = System.currentTimeMillis(),
    ): AppSuggestions {
        val launchStore = AppLaunchStore.getInstance(context)
        // Read the revision before the launches, so a launch recorded in between makes the
        // snapshot look stale rather than up to date.
        val revision = launchStore.revision

        // Hidden apps never appear here, whatever "show hidden apps in search" says: that setting
        // is about matching a typed query, and nothing has been typed yet.
        val hiddenApps = PreferenceManager2.getInstance(context).hiddenApps.firstCached()
        val ownPackage = context.packageName
        val currentUser = Process.myUserHandle()

        // The AppInfo objects still belong to the model. Of the fields read here, only the status
        // flags can change meanwhile (an app being installed or updated), and a stale read only
        // moves that app in or out of the tabs until the next load.
        val byKey = LinkedHashMap<String, AppInfo>()
        apps.forEach { app ->
            // Only the current user's apps: work and private-space apps stay out of the
            // suggestions, so a locked private space can't show through them.
            if (app.user != currentUser) return@forEach
            val pkg = app.componentName?.packageName ?: return@forEach
            if (pkg == ownPackage) return@forEach
            // Skip apps with an install session in progress (e.g. promise icons): they can't be opened yet.
            if ((app.runtimeStatusFlags and ItemInfoWithIcon.FLAG_INSTALL_SESSION_ACTIVE) != 0) return@forEach
            val key = app.toComponentKey().toString()
            if (key in hiddenApps) return@forEach
            byKey.putIfAbsent(key, app)
        }

        val launches = launchStore.getLaunches().filter { it.key in byKey }

        // New is per package: one entry per app even if it has several launcher activities.
        val byPackage = LinkedHashMap<String, AppInfo>()
        byKey.values.forEach { app ->
            app.componentName?.packageName?.let { byPackage.putIfAbsent(it, app) }
        }
        val installs = byPackage.keys.mapNotNull { installTimes[it] }

        val maxApps = limit.coerceAtLeast(0)
        return AppSuggestions(
            byTab = mapOf(
                SuggestionTab.FREQUENT to AppSuggestionRanker.rankFrequent(launches, nowMs, maxApps)
                    .mapNotNull { byKey[it] },
                SuggestionTab.RECENT to AppSuggestionRanker.rankRecent(launches, maxApps)
                    .mapNotNull { byKey[it] },
                SuggestionTab.NEW to AppSuggestionRanker.rankNew(installs, nowMs, NEW_APP_HORIZON_MS, maxApps)
                    .mapNotNull { byPackage[it] },
            ),
            launchRevision = revision,
        )
    }

    /**
     * Install and update times of every package installed for the current user, in one
     * package-manager query. Blocking: call it from a background thread.
     */
    fun loadInstallTimes(context: Context): Map<String, AppSuggestionRanker.InstallInfo> = try {
        context.packageManager.getInstalledPackages(0).associate {
            it.packageName to AppSuggestionRanker.InstallInfo(it.packageName, it.firstInstallTime, it.lastUpdateTime)
        }
    } catch (_: Exception) {
        emptyMap()
    }
}
