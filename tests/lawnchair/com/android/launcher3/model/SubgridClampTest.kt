package com.android.launcher3.model

import com.android.launcher3.model.data.ItemInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SubgridClampTest {
    private fun item(cellX: Int, subX: Int, spanX: Int, subSpanX: Int) = ItemInfo().apply {
        this.cellX = cellX
        this.subX = subX
        this.spanX = spanX
        this.subSpanX = subSpanX
        cellY = 0
        spanY = 1
    }

    @Test
    fun itemOnThePageIsLeftAlone() {
        val info = item(cellX = 3, subX = 1, spanX = 1, subSpanX = 0)
        assertFalse(LoaderCursor.clampSubgridToGrid(info, 5, 5))
        assertEquals(3, info.cellX)
        assertEquals(1, info.subX)
    }

    @Test
    fun halfSizedItemFlushWithTheEdgeIsLeftAlone() {
        val info = item(cellX = 4, subX = 1, spanX = 1, subSpanX = -1)
        assertFalse(LoaderCursor.clampSubgridToGrid(info, 5, 5))
    }

    @Test
    fun halfStepPastTheEdgeMovesBack() {
        val info = item(cellX = 4, subX = 1, spanX = 1, subSpanX = 0)
        assertTrue(LoaderCursor.clampSubgridToGrid(info, 5, 5))
        assertEquals(4, info.cellX)
        assertEquals(0, info.subX)
        assertEquals(1, info.spanX)
    }

    @Test
    fun halfSpanPastTheEdgeMovesBackAndKeepsItsSize() {
        // 1 + 3.5 > 4 even with subX = 0, so the cell moves back half a step.
        val info = item(cellX = 1, subX = 0, spanX = 3, subSpanX = 1)
        assertTrue(LoaderCursor.clampSubgridToGrid(info, 4, 4))
        assertEquals(0, info.cellX)
        assertEquals(1, info.subX)
        assertEquals(3, info.spanX)
        assertEquals(1, info.subSpanX)
    }

    @Test
    fun itemWiderThanThePageShrinksToThePage() {
        val info = item(cellX = 0, subX = 0, spanX = 5, subSpanX = 1)
        assertTrue(LoaderCursor.clampSubgridToGrid(info, 5, 5))
        assertEquals(0, info.cellX)
        assertEquals(0, info.subX)
        assertEquals(5, info.spanX)
        assertEquals(0, info.subSpanX)
    }
}
