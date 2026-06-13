package app.lawnchair.data.widget

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-widget size override, stored as a scale percentage (100 = natural cell size, >100 enlarges
 * the widget beyond its cell). Keyed by the system-allocated [appWidgetId] so the value follows the
 * specific widget instance on the workspace.
 */
@Entity(tableName = "widgetpadding")
data class WidgetPadding(
    @PrimaryKey val appWidgetId: Int,
    val scalePercent: Int,
)
