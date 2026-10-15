package app.lawnchair.search

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import com.android.launcher3.R
import com.android.launcher3.allapps.ActivityAllAppsContainerView
import com.android.launcher3.allapps.SearchRecyclerView

class LawnchairSearchRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
) : SearchRecyclerView(context, attrs, defStyleAttr, defStyleRes) {

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        val extraPadding = resources.getDimensionPixelSize(R.dimen.search_result_recycler_view_padding)
        super.setPadding(left + extraPadding, top, right + extraPadding, bottom)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Inside the search bar grown to hold the results, they only take touches on the bar: the
        // rest of the screen shows the drawer, which gets them.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val panel = (parent as? ActivityAllAppsContainerView<*>)?.suggestionsPanel
            if (panel != null && panel.holdsSearchResults() &&
                !panel.resultsCoverPoint(ev.x + left + translationX, ev.y + top + translationY)
            ) {
                return false
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}
