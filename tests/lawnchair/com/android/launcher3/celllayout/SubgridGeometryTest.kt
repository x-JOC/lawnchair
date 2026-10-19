package com.android.launcher3.celllayout

import android.content.ContentValues
import android.graphics.Point
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.util.ContentWriter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SubgridGeometryTest {
    @Test
    fun halfHeightWidgetKeepsItsHalfRowPositionAndFitsAtRightEdge() {
        val params = CellLayoutLayoutParams(4, 1, 1, 1)
        params.subY = 1
        params.subSpanY = -1
        params.setup(100, 100, false, 5, 7, Point(0, 0))
        assertEquals(400, params.x)
        assertEquals(150, params.y)
        assertEquals(100, params.width)
        assertEquals(50, params.height)
    }

    @Test
    fun rtlMirrorsUsingFractionalPositionAndWidth() {
        val params = CellLayoutLayoutParams(0, 1, 1, 1)
        params.subX = 1
        params.subSpanX = 1
        params.setup(100, 100, true, 5, 7, Point(0, 0))
        assertEquals(300, params.x)
        assertEquals(150, params.width)
    }

    @Test
    fun halfHeightSpanRoundTripsWithoutAZeroBaseSpan() {
        // Write the geometry columns the way ItemInfo.writeToValues does with subgrid enabled.
        val values = ContentValues()
        ContentWriter(values, RuntimeEnvironment.getApplication())
            .put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPWIDGET)
            .put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP)
            .put(Favorites.SCREEN, 0)
            .put(Favorites.RANK, 0)
            .put(Favorites.CELLX, 4)
            .put(Favorites.CELLY, 1.5f)
            .put(Favorites.SPANX, 1)
            .put(Favorites.SPANY, 0.5f)
        assertEquals(0.5f, values.getAsFloat(Favorites.SPANY))
        val restored = ItemInfo()
        restored.readFromValues(values)
        assertEquals(1, restored.spanY)
        assertEquals(-1, restored.subSpanY)
        assertEquals(1, restored.cellY)
        assertEquals(1, restored.subY)
    }

    @Test
    fun wholeValuesDecodeWithoutAHalfStep() {
        assertEquals(0, ItemInfo.decodeSubgridStep(2f))
        assertEquals(1, ItemInfo.decodeSubgridStep(2.5f))
        assertEquals(0, ItemInfo.decodeSubgridSpanStep(1f))
        assertEquals(-1, ItemInfo.decodeSubgridSpanStep(0.5f))
        assertEquals(1, ItemInfo.decodeSubgridSpanBase(0.5f))
        assertEquals(3, ItemInfo.decodeSubgridSpanBase(3.5f))
    }
}
