package app.lawnchair.data.widget

import android.content.Context
import app.lawnchair.data.AppDatabase
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppComponent
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.util.DaggerSingletonObject
import com.android.launcher3.util.SafeCloseable
import com.android.launcher3.widget.LauncherAppWidgetHostView
import javax.inject.Inject
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * Stores and applies per-widget size overrides, expressed as a scale percentage (100 = natural cell
 * size). The in-memory [scaleMap] is kept in sync with the Room table so the value can be read
 * synchronously while widgets are being bound.
 */
@LauncherAppSingleton
class WidgetPaddingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : SafeCloseable {

    private val scope = MainScope() + CoroutineName("WidgetPaddingRepository")
    private val dao = AppDatabase.INSTANCE.get(context).widgetPaddingDao()

    private val _scaleMap = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val scaleMap: StateFlow<Map<Int, Int>> = _scaleMap.asStateFlow()

    init {
        scope.launch {
            dao.observeAll()
                .flowOn(Dispatchers.Main)
                .collect { items ->
                    _scaleMap.value = items.associateBy({ it.appWidgetId }, { it.scalePercent })
                }
        }
    }

    /** Scale percentage stored for the given widget, or [DEFAULT_SCALE] (100) if none. */
    fun getScalePercent(appWidgetId: Int): Int = _scaleMap.value[appWidgetId] ?: DEFAULT_SCALE

    suspend fun setScale(appWidgetId: Int, scalePercent: Int) {
        if (scalePercent == DEFAULT_SCALE) {
            dao.delete(appWidgetId)
        } else {
            dao.insert(WidgetPadding(appWidgetId, scalePercent))
        }
    }

    /** Persist the scale on the repository scope so the write survives the caller being disposed. */
    fun saveScale(appWidgetId: Int, scalePercent: Int) {
        // Update the in-memory map optimistically so the new value is visible to the workspace
        // immediately (before the async DB write completes), avoiding a flicker back to the old size.
        _scaleMap.value = if (scalePercent == DEFAULT_SCALE) {
            _scaleMap.value - appWidgetId
        } else {
            _scaleMap.value + (appWidgetId to scalePercent)
        }
        scope.launch { setScale(appWidgetId, scalePercent) }
    }

    suspend fun deleteScale(appWidgetId: Int) {
        dao.delete(appWidgetId)
    }

    override fun close() {}

    companion object {
        const val DEFAULT_SCALE = 100

        @JvmField
        val INSTANCE = DaggerSingletonObject(LauncherAppComponent::getWidgetPaddingRepository)

        /** Apply a uniform scale (percentage, centered) to the widget host view. */
        @JvmStatic
        fun applyScalePercent(view: LauncherAppWidgetHostView, scalePercent: Int) {
            val scale = scalePercent / 100f
            if (view.scaleX != scale || view.scaleY != scale) {
                view.scaleX = scale
                view.scaleY = scale
            }
        }

        /** Apply the stored scale for [appWidgetId] to [view]; resets to 100% when none is stored. */
        @JvmStatic
        fun applyStoredScale(view: LauncherAppWidgetHostView, appWidgetId: Int) {
            applyScalePercent(view, INSTANCE.get(view.context).getScalePercent(appWidgetId))
        }
    }
}
