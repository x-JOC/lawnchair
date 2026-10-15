package app.lawnchair.data.wallpaper.model

import android.app.Application
import android.app.WallpaperManager
import app.lawnchair.data.wallpaper.Wallpaper
import app.lawnchair.wallpaper.WallpaperManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Holds the top wallpapers for [app.lawnchair.ui.popup.WallpaperCarouselView].
 *
 * This is constructed directly by the view (the Launcher is not a ViewModelStoreOwner), so it has
 * no `onCleared`. The owner must call [start] when it is attached and [stop] when it is detached,
 * otherwise the [WallpaperManagerCompat] listener and the coroutine scope would leak.
 */
class WallpaperViewModel(application: Application) {
    private val wallpaperManagerCompat = WallpaperManagerCompat.INSTANCE.get(application)

    val wallpapers: StateFlow<List<Wallpaper>>
        field = MutableStateFlow<List<Wallpaper>>(emptyList())

    private val mutex = Mutex()

    private var scope: CoroutineScope? = null

    private val listener = object : WallpaperManagerCompat.OnColorsChangedListener {
        override fun onColorsChanged() {
            scope?.launch {
                mutex.withLock {
                    saveWallpaper(wallpaperManagerCompat.wallpaperManager)
                }
            }
        }
    }

    fun start() {
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        wallpaperManagerCompat.addOnChangeListener(listener)
        loadTopWallpapers()
    }

    fun stop() {
        wallpaperManagerCompat.removeOnChangeListener(listener)
        scope?.cancel()
        scope = null
    }

    private suspend fun saveWallpaper(wallpaperManager: WallpaperManager) {
        wallpaperManagerCompat.service.saveWallpaper(wallpaperManager)
        refreshWallpapers()
    }

    private suspend fun refreshWallpapers() {
        val topWallpapers = wallpaperManagerCompat.service.dao.getTopWallpapers()
        wallpapers.value = topWallpapers
    }

    private fun loadTopWallpapers() {
        scope?.launch {
            mutex.withLock {
                refreshWallpapers()
            }
        }
    }

    suspend fun updateWallpaperRank(wallpaper: Wallpaper) {
        wallpaperManagerCompat.service.updateWallpaperRank(wallpaper)
        loadTopWallpapers()
    }
}
