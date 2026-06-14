package app.lawnchair.search.algorithms.engine.provider.apps

import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.search.algorithms.engine.SuggestionTab
import app.lawnchair.search.algorithms.filterHiddenApps
import com.android.launcher3.model.AllAppsList
import com.android.launcher3.model.data.AppInfo
import com.patrykmichalik.opto.core.firstBlocking

/**
 * Computes the three zero-state suggestion lists shown as switchable tabs in the empty
 * app-drawer search (Nova-style):
 *  - [SuggestionTab.FREQUENT]: most-used apps, by foreground time (needs usage access).
 *  - [SuggestionTab.RECENT]: most-recently-used apps (needs usage access).
 *  - [SuggestionTab.NEW]: most-recently installed or updated apps (no permission needed).
 *
 * All work is blocking (package-manager + usage-stats queries) and must therefore be run
 * off the main thread, e.g. inside `LauncherModel.enqueueModelUpdateTask { ... }`.
 */
object SuggestedAppsProvider {

    private const val USAGE_WINDOW_MS = 30L * 24 * 60 * 60 * 1000

    /** Whether the "usage access" special permission has been granted to Lawnchair. */
    fun hasUsageAccess(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Returns a map with all three tabs. Each list is ranked and capped at [limit]; an
     * empty list means that tab has nothing to show (e.g. usage access not granted).
     */
    fun getSuggestions(
        context: Context,
        allApps: AllAppsList,
        limit: Int,
    ): Map<SuggestionTab, List<AppInfo>> {
        if (limit <= 0) {
            return mapOf(
                SuggestionTab.FREQUENT to emptyList(),
                SuggestionTab.RECENT to emptyList(),
                SuggestionTab.NEW to emptyList(),
            )
        }

        val prefs2 = PreferenceManager2.getInstance(context)
        val hiddenApps = prefs2.hiddenApps.firstBlocking()
        val hiddenAppsInSearch = prefs2.hiddenAppsInSearch.firstBlocking()

        // One AppInfo per package (the first launcher activity wins), with hidden apps removed.
        val byPackage = LinkedHashMap<String, AppInfo>()
        allApps.data.asSequence()
            .filterHiddenApps(query = "", hiddenApps = hiddenApps, hiddenAppsInSearch = hiddenAppsInSearch)
            .forEach { app ->
                val pkg = app.componentName?.packageName ?: return@forEach
                if (pkg !in byPackage) byPackage[pkg] = app
            }

        return mapOf(
            SuggestionTab.FREQUENT to usageRanked(context, byPackage, limit, recent = false),
            SuggestionTab.RECENT to usageRanked(context, byPackage, limit, recent = true),
            SuggestionTab.NEW to newApps(context, byPackage, limit),
        )
    }

    private fun usageRanked(
        context: Context,
        byPackage: Map<String, AppInfo>,
        limit: Int,
        recent: Boolean,
    ): List<AppInfo> {
        if (!hasUsageAccess(context)) return emptyList()
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()

        val now = System.currentTimeMillis()
        val stats = try {
            usageStatsManager.queryAndAggregateUsageStats(now - USAGE_WINDOW_MS, now)
        } catch (_: Exception) {
            return emptyList()
        }

        val comparator = if (recent) {
            compareByDescending<UsageStats> { it.lastTimeUsed }
        } else {
            compareByDescending<UsageStats> { it.totalTimeInForeground }
        }

        return stats.values.asSequence()
            .filter { if (recent) it.lastTimeUsed > 0 else it.totalTimeInForeground > 0 }
            .sortedWith(comparator)
            .mapNotNull { byPackage[it.packageName] }
            .filter { it.componentName?.packageName != context.packageName }
            .take(limit)
            .toList()
    }

    private fun newApps(
        context: Context,
        byPackage: Map<String, AppInfo>,
        limit: Int,
    ): List<AppInfo> {
        val pm = context.packageManager
        return byPackage.entries.asSequence()
            .mapNotNull { (pkg, app) ->
                if (pkg == context.packageName) return@mapNotNull null
                val time = try {
                    val info = pm.getPackageInfo(pkg, 0)
                    maxOf(info.firstInstallTime, info.lastUpdateTime)
                } catch (_: Exception) {
                    return@mapNotNull null
                }
                app to time
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(limit)
            .toList()
    }
}
