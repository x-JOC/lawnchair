package app.lawnchair.allapps.suggestions

import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.os.UserHandle
import android.util.AtomicFile
import android.util.Log
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import app.lawnchair.allapps.suggestions.AppSuggestionRanker.LaunchInfo
import app.lawnchair.predictions.LawnchairPredictionManager
import app.lawnchair.predictions.PredictionAppKey
import app.lawnchair.util.SingletonHolder
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_APPLICATION
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.pm.UserCache
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.util.Executors.ORDERED_BG_EXECUTOR
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lawnchair's own record of app launches, which feeds the Frequent and Recent suggestion tabs at
 * the top of the app drawer. Counting launches inside the launcher (like Nova does) needs no
 * permission, so the tabs work even where Android blocks usage access for sideloaded apps.
 *
 * Every app launched from the launcher (home screen, dock, folders, drawer, search, taskbar) is
 * recorded once per launch, keyed by its [ComponentKey]. Only apps of the current user are kept,
 * because the tabs only show those: work profile and private space launches are never written.
 * The history is a small JSON file in the app's files folder, capped at [MAX_ENTRIES] apps and
 * [MAX_AGE_MS] since the last launch. Until that file exists, it is seeded from the Lawnchair
 * predictor's launch logs, if there are any.
 *
 * Launches are recorded on [ORDERED_BG_EXECUTOR] and written to disk shortly after, never on the
 * main thread. [getLaunches] reads the file on first use, so call it from a background thread.
 */
class AppLaunchStore private constructor(private val context: Context) {

    /** Created on first load or save, both off the main thread: [Context.getFilesDir] may hit the disk. */
    private val file by lazy { AtomicFile(File(context.filesDir, FILE_NAME)) }
    private val lock = Any()

    /** The launch history by [ComponentKey] string, or null until first loaded. Guarded by [lock]. */
    private var entries: MutableMap<String, LaunchInfo>? = null

    private val savePending = AtomicBoolean(false)
    private val saveRunnable = Runnable {
        savePending.set(false)
        save()
    }

    private val revisionState = MutableStateFlow(0)

    /**
     * Changes whenever a launch is recorded, so a caller holding suggestions computed from an
     * older history can tell they are out of date.
     */
    val revision: Int get() = revisionState.value

    /** [revision] as a flow, for UI that refreshes its suggestions after each recorded launch. */
    val revisionFlow: StateFlow<Int> = revisionState.asStateFlow()

    /**
     * Records one launch of [info] if it is an app of the current user. Shortcuts, widgets, search
     * actions, work profile and private space apps, and Lawnchair itself are ignored. Safe to call
     * on the main thread: only builds the key there.
     */
    @AnyThread
    fun recordLaunch(info: ItemInfo?) {
        if (info == null || info.itemType != ITEM_TYPE_APPLICATION) return
        val componentKey = info.componentKey ?: return
        if (componentKey.user != Process.myUserHandle()) return
        if (componentKey.componentName.packageName == context.packageName) return
        val key = componentKey.toString()
        val nowMs = System.currentTimeMillis()
        ORDERED_BG_EXECUTOR.execute {
            synchronized(lock) {
                val map = loadLocked()
                map[key] = AppSuggestionRanker.recordLaunch(map[key], key, nowMs)
                if (map.size > MAX_ENTRIES + PRUNE_SLACK) pruneLocked(map, nowMs)
            }
            revisionState.update { it + 1 }
            scheduleSave()
        }
    }

    /** A copy of the launch history. Reads the file the first time, so don't call on the main thread. */
    @WorkerThread
    fun getLaunches(): List<LaunchInfo> = synchronized(lock) { loadLocked().values.toList() }

    private fun loadLocked(): MutableMap<String, LaunchInfo> {
        entries?.let { return it }
        val nowMs = System.currentTimeMillis()
        val loaded = readFile()
        val map = HashMap<String, LaunchInfo>()
        if (loaded != null) {
            loaded.forEach { map[it.key] = it }
        } else {
            // First run: start from what the Lawnchair predictor has already logged. An empty seed
            // isn't saved, so the next time Lawnchair starts it tries again (the predictor may have
            // logged launches by then, or a backup may have been restored), until a launch is
            // recorded here.
            val seed = seedFromPredictions(nowMs)
            seed.forEach { map[it.key] = it }
            if (seed.isNotEmpty()) scheduleSave()
        }
        pruneLocked(map, nowMs)
        entries = map
        return map
    }

    private fun pruneLocked(map: MutableMap<String, LaunchInfo>, nowMs: Long) {
        val kept = AppSuggestionRanker.prune(map.values, nowMs, MAX_AGE_MS, MAX_ENTRIES)
        if (kept.size == map.size) return
        map.clear()
        kept.forEach { map[it.key] = it }
    }

    private fun scheduleSave() {
        if (savePending.compareAndSet(false, true)) {
            ORDERED_BG_EXECUTOR.handler.postDelayed(saveRunnable, SAVE_DELAY_MS)
        }
    }

    @WorkerThread
    private fun save() {
        val snapshot = synchronized(lock) { entries?.values?.toList() } ?: return
        val apps = JSONArray()
        snapshot.forEach {
            apps.put(
                JSONObject()
                    .put(KEY_COMPONENT, it.key)
                    .put(KEY_COUNT, it.launchCount)
                    .put(KEY_SCORE, it.score)
                    .put(KEY_LAST_LAUNCHED, it.lastLaunched),
            )
        }
        val json = JSONObject()
            .put(KEY_VERSION, FILE_VERSION)
            .put(KEY_APPS, apps)
            .toString()
        val stream = try {
            file.startWrite()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to save app launch history", e)
            return
        }
        try {
            stream.write(json.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            Log.w(TAG, "Unable to save app launch history", e)
        }
    }

    /** The saved history, an empty list if the file can't be read, or null if there is no file yet. */
    @WorkerThread
    private fun readFile(): List<LaunchInfo>? {
        val bytes = try {
            file.readFully()
        } catch (_: FileNotFoundException) {
            return null
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read app launch history, starting over", e)
            return emptyList()
        }
        return try {
            val apps = JSONObject(String(bytes, Charsets.UTF_8)).optJSONArray(KEY_APPS)
                ?: return emptyList()
            (0 until apps.length()).mapNotNull { index ->
                val app = apps.optJSONObject(index) ?: return@mapNotNull null
                val key = app.optString(KEY_COMPONENT)
                // Skip keys that don't parse, and other users' apps the tabs never show.
                if (ComponentKey.fromString(key)?.user != Process.myUserHandle()) return@mapNotNull null
                LaunchInfo(
                    key = key,
                    launchCount = app.optInt(KEY_COUNT, 0).coerceAtLeast(0),
                    score = app.optDouble(KEY_SCORE, 0.0).takeIf { it.isFinite() && it >= 0 } ?: 0.0,
                    lastLaunched = app.optLong(KEY_LAST_LAUNCHED, 0L),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read app launch history, starting over", e)
            emptyList()
        }
    }

    /** Launch history rebuilt from the Lawnchair predictor's launch logs, or empty if there are none. */
    @WorkerThread
    private fun seedFromPredictions(nowMs: Long): List<LaunchInfo> = try {
        val predictions = LawnchairPredictionManager.getInstance(context)
        val userCache = UserCache.INSTANCE.get(context)
        val logs = listOf(predictions.allAppsStore, predictions.hotseatStore).map { store ->
            store.getEntries().mapNotNull { predictionKeyToComponentKey(it, userCache) }
        }
        AppSuggestionRanker.seedFromLaunchLogs(logs, nowMs)
    } catch (e: Exception) {
        Log.w(TAG, "Unable to seed app launch history from predictions", e)
        emptyList()
    }

    /**
     * Turns a [PredictionAppKey] ("package/class/userSerial") into a [ComponentKey] string, or null
     * if it can't be parsed or belongs to another user (work profile, private space).
     */
    private fun predictionKeyToComponentKey(key: String, userCache: UserCache): String? {
        val target = PredictionAppKey.parse(key) ?: return null
        val user = resolveUser(target.userToken, userCache) ?: return null
        if (user != Process.myUserHandle()) return null
        return ComponentKey(ComponentName(target.packageName, target.className), user).toString()
    }

    private fun resolveUser(token: String, userCache: UserCache): UserHandle? {
        // Same rules as LawnchairPredictionEngine: a user serial number, or a profile's hash code.
        token.toLongOrNull()?.let { serialNumber ->
            val user = userCache.getUserForSerialNumber(serialNumber)
            if (user != null && userCache.getSerialNumberForUser(user).toString() == token) return user
        }
        return userCache.userProfiles.firstOrNull { it.hashCode().toString() == token }
    }

    companion object : SingletonHolder<AppLaunchStore, Context>({ AppLaunchStore(it.applicationContext) }) {
        private const val TAG = "AppLaunchStore"
        private const val FILE_NAME = "app_launch_history.json"
        private const val FILE_VERSION = 1

        /** At most this many apps are remembered; the ones launched longest ago go first. */
        private const val MAX_ENTRIES = 300

        /** Extra entries tolerated before pruning on record, so pruning doesn't run on every launch. */
        private const val PRUNE_SLACK = 20

        /** Apps not launched for this long are forgotten. */
        private const val MAX_AGE_MS = 180L * 24 * 60 * 60 * 1000

        private const val SAVE_DELAY_MS = 1000L

        private const val KEY_VERSION = "version"
        private const val KEY_APPS = "apps"
        private const val KEY_COMPONENT = "component"
        private const val KEY_COUNT = "count"
        private const val KEY_SCORE = "score"
        private const val KEY_LAST_LAUNCHED = "lastLaunched"
    }
}
