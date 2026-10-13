package app.lawnchair.search.algorithms.engine.provider.apps

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.search.algorithms.engine.SuggestionTab
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.ItemInfoWithIcon

/**
 * Immutable snapshot of the three zero-state suggestion lists plus whether usage access was
 * granted when it was computed. A single value so the lists and the permission flag can never
 * disagree, and so it can be cached and swapped atomically.
 */
data class AppSuggestions(
    val byTab: Map<SuggestionTab, List<AppInfo>>,
    val usageGranted: Boolean,
) {
    /** True if at least one tab has something to show. */
    val hasAny: Boolean get() = byTab.values.any { it.isNotEmpty() }

    /**
     * Whether the tabs should be shown at all. They stay visible while usage access is missing,
     * even if every list is empty, so the user can still see the prompt to turn it on.
     */
    val shouldShowTabs: Boolean get() = hasAny || !usageGranted

    fun appsFor(tab: SuggestionTab): List<AppInfo> = byTab[tab].orEmpty()

    /** [preferred] if it is non-empty, otherwise the first non-empty tab, otherwise [preferred]. */
    fun firstNonEmptyTab(preferred: SuggestionTab): SuggestionTab {
        if (appsFor(preferred).isNotEmpty()) return preferred
        return SuggestionTab.entries.firstOrNull { appsFor(it).isNotEmpty() } ?: preferred
    }

    companion object {
        val EMPTY = AppSuggestions(
            byTab = SuggestionTab.entries.associateWith { emptyList() },
            usageGranted = false,
        )
    }
}

/**
 * Gathers the raw Android data for the zero-state app suggestions and hands it to the pure
 * [AppSuggestionRanker]:
 *  - [SuggestionTab.FREQUENT]: most-used apps by foreground time (needs usage access).
 *  - [SuggestionTab.RECENT]: most-recently-used apps (needs usage access).
 *  - [SuggestionTab.NEW]: most-recently installed apps (no permission needed).
 *
 * All work is blocking (package-manager + usage-stats queries) and must therefore run on a
 * background thread. It does not touch the app model, so it should not run on the model executor
 * either: callers pass a copy of the app list. Usage access is checked once, and the usage window
 * and the installed packages are each queried at most once per call.
 */
object SuggestedAppsProvider {

    private const val USAGE_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
    private const val NEW_APP_HORIZON_MS = 30L * 24 * 60 * 60 * 1000

    /** Whether the "usage access" special permission has been granted to Lawnchair. */
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Computes all three suggestion lists in one pass. Each list is ranked and capped at [limit]
     * over the apps that are actually visible (current user, not hidden, not Lawnchair itself).
     * [nowMs] is injected so the time reference is consistent across the usage and install queries.
     */
    fun loadSnapshot(
        context: Context,
        apps: List<AppInfo>,
        limit: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): AppSuggestions {
        if (limit <= 0) return AppSuggestions.EMPTY

        // Hidden apps never appear here, whatever "show hidden apps in search" says: that setting
        // is about matching a typed query, and nothing has been typed yet.
        val hiddenApps = PreferenceManager2.getInstance(context).hiddenApps.firstCached()
        val ownPackage = context.packageName
        val currentUser = Process.myUserHandle()

        // One AppInfo per package for the current user, with hidden apps and Lawnchair removed.
        // The AppInfo objects still belong to the model. Of the fields read here, only the status
        // flags can change meanwhile (an app being installed or updated), and a stale read only
        // moves that app in or out of New until the next load.
        val byPackage = LinkedHashMap<String, AppInfo>()
        apps.asSequence()
            .filter { it.user == currentUser }
            .filter { it.toComponentKey().toString() !in hiddenApps }
            .forEach { app ->
                val pkg = app.componentName?.packageName ?: return@forEach
                if (pkg == ownPackage) return@forEach
                if (pkg !in byPackage) byPackage[pkg] = app
            }

        val granted = hasUsageAccess(context)
        val usage = if (granted) {
            queryUsage(context, nowMs).filter { it.packageName in byPackage.keys }
        } else {
            emptyList()
        }
        // Skip apps with an install session in progress (e.g. promise icons): they can't be opened yet.
        val installTimes = firstInstallTimes(context)
        val installs = byPackage
            .filterValues { (it.runtimeStatusFlags and ItemInfoWithIcon.FLAG_INSTALL_SESSION_ACTIVE) == 0 }
            .keys
            .mapNotNull { pkg -> installTimes[pkg]?.let { AppSuggestionRanker.InstallInfo(pkg, it) } }

        fun keysToApps(keys: List<String>): List<AppInfo> = keys.mapNotNull { byPackage[it] }

        return AppSuggestions(
            byTab = mapOf(
                SuggestionTab.FREQUENT to keysToApps(AppSuggestionRanker.rankFrequent(usage, limit)),
                SuggestionTab.RECENT to keysToApps(AppSuggestionRanker.rankRecent(usage, limit)),
                SuggestionTab.NEW to keysToApps(
                    AppSuggestionRanker.rankNew(installs, nowMs, NEW_APP_HORIZON_MS, limit),
                ),
            ),
            usageGranted = granted,
        )
    }

    private fun queryUsage(context: Context, nowMs: Long): List<AppSuggestionRanker.UsageInfo> {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()
        val stats = try {
            usageStatsManager.queryAndAggregateUsageStats(nowMs - USAGE_WINDOW_MS, nowMs)
        } catch (_: Exception) {
            return emptyList()
        }
        return stats.values.map {
            AppSuggestionRanker.UsageInfo(it.packageName, it.totalTimeInForeground, it.lastTimeUsed)
        }
    }

    /** First install time of every package installed for the current user, in one query. */
    private fun firstInstallTimes(context: Context): Map<String, Long> = try {
        context.packageManager.getInstalledPackages(0).associate { it.packageName to it.firstInstallTime }
    } catch (_: Exception) {
        emptyMap()
    }
}
