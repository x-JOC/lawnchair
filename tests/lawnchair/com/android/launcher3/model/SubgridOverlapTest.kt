package com.android.launcher3.model

import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.util.GridOccupancy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Items saved in subgrid mode can share a base cell. The loader must keep them in either load
 * order when subgrid is off, and still drop plain whole-cell overlaps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SubgridOverlapTest {
    private fun item(
        cellX: Int,
        subX: Int = 0,
        spanX: Int = 1,
        subSpanX: Int = 0,
    ) = ItemInfo().apply {
        this.cellX = cellX
        this.subX = subX
        this.spanX = spanX
        this.subSpanX = subSpanX
        cellY = 0
        spanY = 1
    }

    /** Mirrors the overlap check in [LoaderCursor.checkItemPlacement] with subgrid off. */
    private fun load(vararg items: ItemInfo): List<Boolean> {
        val occupancy = GridOccupancy(5, 5)
        val halfStepped = GridOccupancy(5, 5)
        return items.map { info ->
            val subgridOverlap = LoaderCursor.trackSubgridOverlap(info, halfStepped)
            if (occupancy.isRegionVacant(info.cellX, info.cellY, info.spanX, info.spanY)) {
                occupancy.markCells(info, true)
                true
            } else {
                subgridOverlap
            }
        }
    }

    @Test
    fun halfStepFirstKeepsTheWholeCellItem() {
        assertEquals(listOf(true, true), load(item(cellX = 0, subX = 1), item(cellX = 0)))
    }

    @Test
    fun wholeCellFirstKeepsTheHalfStepItem() {
        assertEquals(listOf(true, true), load(item(cellX = 0), item(cellX = 0, subX = 1)))
    }

    @Test
    fun halfWidthWidgetsSideBySideAreKept() {
        val left = item(cellX = 0, spanX = 1, subSpanX = -1)
        val right = item(cellX = 0, subX = 1, spanX = 1, subSpanX = -1)
        assertEquals(listOf(true, true), load(left, right))
    }

    @Test
    fun wholeCellOverlapIsStillDropped() {
        assertEquals(listOf(true, false), load(item(cellX = 1), item(cellX = 1)))
    }

    @Test
    fun halfStepElsewhereDoesNotKeepAWholeCellOverlap() {
        assertEquals(
            listOf(true, true, false),
            load(item(cellX = 3, subX = 1), item(cellX = 1), item(cellX = 1)),
        )
    }
}
