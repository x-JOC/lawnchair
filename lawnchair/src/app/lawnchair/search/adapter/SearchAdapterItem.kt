package app.lawnchair.search.adapter

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RoundRectShape
import android.view.View
import app.lawnchair.allapps.views.SearchItemBackground
import app.lawnchair.allapps.views.SearchResultView
import app.lawnchair.search.LawnchairSearchAdapterProvider
import com.android.launcher3.R
import com.android.launcher3.allapps.BaseAllAppsAdapter

data class SearchAdapterItem(
    val searchTarget: SearchTargetCompat,
    val background: SearchItemBackground?,
    val viewType: Int,
) : BaseAllAppsAdapter.AdapterItem(viewType) {

    override fun isSameAs(other: BaseAllAppsAdapter.AdapterItem): Boolean {
        return other is SearchAdapterItem && other.searchTarget.id == searchTarget.id
    }

    override fun isContentSame(other: BaseAllAppsAdapter.AdapterItem): Boolean {
        if (other !is SearchAdapterItem) return false
        // AllAppsSearchInput.onSearchResult diffs first (apps.setSearchResults), then
        // appsView.setSearchResults clears the highlighted item; rows rebind at the next layout.
        // LawnchairSearchAdapterProvider only sets the quick-launch view again when it binds it,
        // so always rebind the quick-launch row to keep Enter-to-launch and its highlight.
        if (isQuickLaunch || other.isQuickLaunch) return false
        return other.viewType == viewType &&
            // The ripple mask is built from the background when the row is bound.
            other.background == background &&
            other.searchTarget.isContentSameAs(searchTarget)
    }

    private val isQuickLaunch: Boolean
        get() = searchTarget.extras.getBoolean(SearchResultView.EXTRA_QUICK_LAUNCH, false)

    fun setRippleEffect(child: View) {
        // The ripple only depends on the background, so keep it while the same background is bound
        // again to this view (recycled view holders rebind with the same background instance often).
        val boundKey = background ?: NO_BACKGROUND
        if (child.getTag(R.id.search_ripple_background) === boundKey && child.background is RippleDrawable) {
            return
        }
        val shape = RoundRectShape(background?.cornerRadii, null, null)
        val maskDrawable = ShapeDrawable(shape).apply {
            paint.color = Color.WHITE
        }
        val rippleColor = background?.focusHighlight ?: background?.groupHighlight ?: 0
        val inset = child.resources.getDimensionPixelSize(R.dimen.search_decoration_padding)
        val insetMask = InsetDrawable(maskDrawable, 0, inset, 0, inset)
        child.background = RippleDrawable(ColorStateList.valueOf(rippleColor), null, insetMask)
        child.setTag(R.id.search_ripple_background, boundKey)
    }

    companion object {

        private val NO_BACKGROUND = Any()

        fun createAdapterItem(
            target: SearchTargetCompat,
            background: SearchItemBackground?,
        ): SearchAdapterItem? {
            val type = LawnchairSearchAdapterProvider.viewTypeMap[target.layoutType] ?: return null
            return SearchAdapterItem(target, background, type)
        }
    }
}
