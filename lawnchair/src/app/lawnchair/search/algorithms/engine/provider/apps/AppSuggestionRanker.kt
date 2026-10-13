package app.lawnchair.search.algorithms.engine.provider.apps

/**
 * Ranking rules for the zero-state app suggestions (frequent / recent / new or updated). It has no Android
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
        val lastUpdateTime: Long,
    ) {
        /** When the package was last installed or updated, whichever is later. */
        val changedTime: Long get() = maxOf(firstInstallTime, lastUpdateTime)
    }

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
     * Most-recently installed or updated apps, ranked by [InstallInfo.changedTime]. Apps not
     * installed or updated within [recencyHorizonMs] before [nowMs] are dropped; pass
     * [recencyHorizonMs] >= [nowMs] to disable the horizon.
     */
    fun rankNew(
        installs: List<InstallInfo>,
        nowMs: Long,
        recencyHorizonMs: Long,
        limit: Int,
    ): List<String> {
        val cutoff = if (recencyHorizonMs >= nowMs) Long.MIN_VALUE else nowMs - recencyHorizonMs
        return installs.asSequence()
            .filter { it.changedTime >= cutoff }
            .sortedByDescending { it.changedTime }
            .map { it.packageName }
            .take(limit.coerceAtLeast(0))
            .toList()
    }
}
