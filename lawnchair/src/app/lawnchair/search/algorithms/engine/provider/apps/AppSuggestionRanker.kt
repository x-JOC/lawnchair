package app.lawnchair.search.algorithms.engine.provider.apps

/**
 * Ranking rules for the zero-state app suggestions (frequent / recent / new). It has no Android
 * dependencies so it can be unit-tested on the JVM. The caller gathers the usage and install data
 * and maps the returned package names back to app entries.
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
     * Most-recently installed apps. Ranks by [InstallInfo.firstInstallTime] only, so app updates
     * don't count as new. Apps installed more than [recencyHorizonMs] before [nowMs] are dropped;
     * pass [recencyHorizonMs] >= [nowMs] to disable the horizon.
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
