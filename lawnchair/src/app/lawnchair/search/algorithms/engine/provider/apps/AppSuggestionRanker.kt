package app.lawnchair.search.algorithms.engine.provider.apps

/**
 * Pure, Android-free ranking core for the zero-state app suggestions (frequent / recent / new).
 *
 * It operates on plain data classes so the ranking rules can be reasoned about and unit-tested
 * without a device or any Android framework. The caller gathers the raw usage/install data and
 * maps the returned package names back to real app entries. Keeping this dependency-free is the
 * whole point — it is the one piece of genuine business logic in the feature.
 */
object AppSuggestionRanker {

    /** Aggregated usage for a single package over some window. */
    data class UsageInfo(
        val packageName: String,
        val totalTimeInForeground: Long,
        val lastTimeUsed: Long,
    )

    /** Install metadata for a single package. */
    data class InstallInfo(
        val packageName: String,
        val firstInstallTime: Long,
    )

    /** Most-used apps, ranked by total foreground time. Apps never used are excluded. */
    fun rankFrequent(usage: List<UsageInfo>, limit: Int): List<String> = usage.asSequence()
        .filter { it.totalTimeInForeground > 0 }
        .sortedByDescending { it.totalTimeInForeground }
        .map { it.packageName }
        .take(limit.coerceAtLeast(0))
        .toList()

    /** Most-recently-used apps, ranked by last-used timestamp. */
    fun rankRecent(usage: List<UsageInfo>, limit: Int): List<String> = usage.asSequence()
        .filter { it.lastTimeUsed > 0 }
        .sortedByDescending { it.lastTimeUsed }
        .map { it.packageName }
        .take(limit.coerceAtLeast(0))
        .toList()

    /**
     * Most-recently *installed* apps. Ranked by [InstallInfo.firstInstallTime] only — never by
     * last-update time — so Play Store auto-updates do not churn the list (the old behaviour made
     * "New" mean "recently auto-updated"). Apps installed longer ago than [recencyHorizonMs]
     * before [nowMs] are dropped so the tab stays meaningful. Pass [recencyHorizonMs] >= [nowMs]
     * to disable the horizon entirely.
     */
    fun rankNew(
        installs: List<InstallInfo>,
        nowMs: Long,
        recencyHorizonMs: Long,
        limit: Int,
    ): List<String> {
        val cutoff = if (recencyHorizonMs >= nowMs) Long.MIN_VALUE else nowMs - recencyHorizonMs
        return installs.asSequence()
            .filter { it.firstInstallTime >= cutoff }
            .sortedByDescending { it.firstInstallTime }
            .map { it.packageName }
            .take(limit.coerceAtLeast(0))
            .toList()
    }
}
