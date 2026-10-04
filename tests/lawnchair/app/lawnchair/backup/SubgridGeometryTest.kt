package app.lawnchair.backup

import android.content.ContentValues
import android.graphics.Point
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.celllayout.CellLayoutLayoutParams
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
        val original = ItemInfo().apply {
            cellX = 4
            cellY = 1
            subY = 1
            spanY = 1
            subSpanY = -1
        }
        val values = ContentValues()
        original.writeToValues(ContentWriter(values, RuntimeEnvironment.getApplication()))
        assertEquals(0.5f, values.getAsFloat(Favorites.SPANY))
        val restored = ItemInfo()
        restored.readFromValues(values)
        assertEquals(1, restored.spanY)
        assertEquals(-1, restored.subSpanY)
        assertEquals(1, restored.cellY)
        assertEquals(1, restored.subY)
    }
}
