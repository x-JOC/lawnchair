package app.lawnchair.theme.color.tokens

import android.content.Context
import androidx.core.graphics.luminance
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.theme.ThemeProvider
import app.lawnchair.theme.UiColorMode
import com.android.launcher3.R
import com.android.launcher3.util.Themes
import dev.kdrag0n.monet.theme.ColorScheme

/** Shared colors for Personal/Work tabs in the app drawer. */
object AllAppsTabColors {

    fun selectedBackground(
        context: Context,
        scheme: ColorScheme,
        uiColorMode: UiColorMode,
    ): Int {
        val prefs2 = PreferenceManager2.getInstance(context)
        val customColor = prefs2.workProfileTabBackgroundColor.firstCached()
            .colorPreferenceEntry.lightColor.invoke(context)
        return if (customColor != 0) {
            customColor
        } else {
            ColorTokens.AllAppsTabBackgroundSelected.resolveColor(context, scheme, uiColorMode)
        }
    }

    /** [selectedBackground] for the current color scheme and the theme of [context]. */
    fun selectedBackground(context: Context): Int = selectedBackground(
        context,
        ThemeProvider.INSTANCE.get(context).colorScheme,
        UiColorMode(Themes.getAttrInteger(context, R.attr.uiColorMode)),
    )

    /**
     * Text color for the selected tab, chosen for contrast against [selectedBackground].
     */
    fun selectedText(
        context: Context,
        scheme: ColorScheme,
        uiColorMode: UiColorMode,
    ): Int {
        val background = selectedBackground(context, scheme, uiColorMode)
        return if (background.luminance > 0.5f) {
            ColorTokens.Neutral1_900.resolveColor(context, scheme, uiColorMode)
        } else {
            ColorTokens.Neutral1_50.resolveColor(context, scheme, uiColorMode)
        }
    }
}
