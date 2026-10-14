package app.lawnchair.allapps.suggestions

import kotlin.math.pow

/**
 * Ranking rules for the app drawer's suggestion tabs (frequent / recent / new or updated). It has
 * no Android dependencies so it can be unit-tested on the JVM. The caller gathers the launch and
 * install data and maps the returned keys back to app entries.
 */
object AppSuggestionRanker {

    /**
     * How fast old launches lose weight in [rankFrequent]: a launch counts half as much after this
     * long. Long enough that an app opened every day stays on top, short enough that an app the
     * user stopped opening a few weeks ago drifts down.
     */
    const val FREQUENT_HALF_LIFE_MS = 21L * 24 * 60 * 60 * 1000

    private const val SEED_STEP_MS = 60L * 1000

    /**
     * Launch history for one app, as recorded by Lawnchair itself.
     *
     * [score] is the launch count with older launches decayed (see [FREQUENT_HALF_LIFE_MS]),
     * measured at [lastLaunched]. [launchCount] is the plain total.
     */
    data class LaunchInfo(
        val key: String,
        val launchCount: Int,
        val score: Double,
        val lastLaunched: Long,
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

    /** [score] decayed from [since] to [nowMs]. A [since] in the future means no decay. */
    fun decayedScore(score: Double, since: Long, nowMs: Long): Double {
        val elapsed = (nowMs - since).coerceAtLeast(0L)
        return score * 0.5.pow(elapsed.toDouble() / FREQUENT_HALF_LIFE_MS)
    }

    /** Returns [previous] (or a new entry for [key]) updated for one more launch at [nowMs]. */
    fun recordLaunch(previous: LaunchInfo?, key: String, nowMs: Long): LaunchInfo {
        if (previous == null) {
            return LaunchInfo(key = key, launchCount = 1, score = 1.0, lastLaunched = nowMs)
        }
        // Never move the last launch backwards, e.g. after the clock was changed.
        val time = maxOf(nowMs, previous.lastLaunched)
        return previous.copy(
            launchCount = previous.launchCount + 1,
            score = decayedScore(previous.score, previous.lastLaunched, time) + 1.0,
            lastLaunched = time,
        )
    }

    /**
     * Drops entries not launched within [maxAgeMs] before [nowMs], then keeps the [maxEntries]
     * most recently launched ones, so the history can't grow without bound.
     */
    fun prune(
        entries: Collection<LaunchInfo>,
        nowMs: Long,
        maxAgeMs: Long,
        maxEntries: Int,
    ): List<LaunchInfo> = entries.asSequence()
        .filter { nowMs - it.lastLaunched <= maxAgeMs }
        .sortedByDescending { it.lastLaunched }
        .take(maxEntries.coerceAtLeast(0))
        .toList()

    /**
     * Builds a starting launch history from ordered launch logs (most recent launch first), such
     * as the Lawnchair predictor's stores. Each occurrence of a key counts as one launch. The logs
     * have no timestamps, so each key gets a made-up launch time, one minute apart, in the order
     * of its most recent occurrence in any log, the newest one minute before [nowMs].
     */
    fun seedFromLaunchLogs(logs: List<List<String>>, nowMs: Long): List<LaunchInfo> {
        val counts = HashMap<String, Int>()
        val bestPosition = HashMap<String, Int>()
        for (log in logs) {
            log.forEachIndexed { index, key ->
                if (key.isEmpty()) return@forEachIndexed
                counts[key] = (counts[key] ?: 0) + 1
                val best = bestPosition[key]
                if (best == null || index < best) bestPosition[key] = index
            }
        }
        return bestPosition.entries
            .sortedWith(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .mapIndexed { order, entry ->
                val count = counts.getValue(entry.key)
                LaunchInfo(
                    key = entry.key,
                    launchCount = count,
                    score = count.toDouble(),
                    lastLaunched = nowMs - (order + 1) * SEED_STEP_MS,
                )
            }
    }

    /**
     * Most-launched apps: launch count with mild recency weighting (see [FREQUENT_HALF_LIFE_MS]).
     * Ties go to the app launched more recently.
     */
    fun rankFrequent(launches: List<LaunchInfo>, nowMs: Long, limit: Int): List<String> = launches.asSequence()
        .filter { it.launchCount > 0 }
        .map { it to decayedScore(it.score, it.lastLaunched, nowMs) }
        .sortedWith(
            compareByDescending<Pair<LaunchInfo, Double>> { it.second }
                .thenByDescending { it.first.lastLaunched },
        )
        .map { it.first.key }
        .take(limit.coerceAtLeast(0))
        .toList()

    /** Most-recently launched apps, by last launch time. */
    fun rankRecent(launches: List<LaunchInfo>, limit: Int): List<String> = launches.asSequence()
        .filter { it.lastLaunched > 0 }
        .sortedByDescending { it.lastLaunched }
        .map { it.key }
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
