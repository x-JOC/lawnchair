package app.lawnchair.wallpaper

import android.app.WallpaperManager
import android.content.Context
import app.lawnchair.data.wallpaper.service.WallpaperService
import app.lawnchair.util.MainThreadInitializedObject
import app.lawnchair.util.requireSystemService
import app.lawnchair.wallpaper.WallpaperColorsCompat.Companion.HINT_SUPPORTS_DARK_THEME
import com.android.launcher3.Utilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class WallpaperManagerCompat(val context: Context) {

    private val listeners = mutableListOf<OnColorsChangedListener>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val colorHints: Int get() = wallpaperColors?.colorHints ?: 0
    val wallpaperManager: WallpaperManager = context.requireSystemService()
    val service = WallpaperService(context)

    abstract val wallpaperColors: WallpaperColorsCompat?

    val supportsDarkTheme: Boolean get() = (colorHints and HINT_SUPPORTS_DARK_THEME) != 0

    fun addOnChangeListener(listener: OnColorsChangedListener) {
        listeners.add(listener)
    }

    fun removeOnChangeListener(listener: OnColorsChangedListener) {
        listeners.remove(listener)
    }

    /**
     * Serialises wallpaper-history writes. Listeners that read the history after a colour change
     * take this lock too; because the save below acquires it before listeners are notified, their
     * read is queued behind the save.
     */
    val saveMutex = Mutex()

    protected fun notifyChange() {
        // Record every wallpaper change, whether or not the carousel is open. The save touches
        // Room and bitmaps, so it runs on the app-scoped IO scope (notifyChange is invoked from the
        // WallpaperManager color callback on the main looper); UNDISPATCHED so the lock is taken
        // before the listeners below are notified. The scope is never cancelled, so a save cannot
        // be interrupted halfway through its multi-statement DB sequence.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            saveMutex.withLock {
                service.saveWallpaper(wallpaperManager)
            }
        }

        listeners.toTypedArray().forEach {
            it.onColorsChanged()
        }
    }

    interface OnColorsChangedListener {
        fun onColorsChanged()
    }

    companion object {

        @JvmField
        val INSTANCE = MainThreadInitializedObject { context ->
            when {
                Utilities.ATLEAST_S -> WallpaperManagerCompatVS(context)
                Utilities.ATLEAST_O_MR1 -> WallpaperManagerCompatVOMR1(context)
                else -> WallpaperManagerCompatVO(context)
            }
        }
    }
}
