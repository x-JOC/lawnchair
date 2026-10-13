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
        // ItemInfo.writeToValues reads the subgrid preference, which needs the launcher's
        // component, so write the geometry columns the same way it does with subgrid enabled.
        val values = ContentValues()
        ContentWriter(values, RuntimeEnvironment.getApplication())
            .put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPWIDGET)
            .put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP)
            .put(Favorites.SCREEN, 0)
            .put(Favorites.RANK, 0)
            .putSubgrid(Favorites.CELLX, 4, 0, true)
            .putSubgrid(Favorites.CELLY, 1, 1, true)
            .putSubgrid(Favorites.SPANX, 1, 0, true)
            .putSubgrid(Favorites.SPANY, 1, -1, true)
        assertEquals(0.5f, values.getAsFloat(Favorites.SPANY))
        val restored = ItemInfo()
        restored.readFromValues(values)
        assertEquals(1, restored.spanY)
        assertEquals(-1, restored.subSpanY)
        assertEquals(1, restored.cellY)
        assertEquals(1, restored.subY)
    }

    @Test
    fun halfStepIsNotPersistedWhenSubgridIsDisabled() {
        val values = ContentValues()
        ContentWriter(values, RuntimeEnvironment.getApplication())
            .putSubgrid(Favorites.CELLX, 2, 1, false)
            .putSubgrid(Favorites.SPANX, 1, -1, false)
        assertEquals(2, values.getAsInteger(Favorites.CELLX))
        assertEquals(0, ItemInfo.decodeSubgridStep(values.getAsFloat(Favorites.CELLX)))
        assertEquals(1, values.getAsInteger(Favorites.SPANX))
    }
}
