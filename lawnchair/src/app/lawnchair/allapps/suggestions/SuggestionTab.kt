package app.lawnchair.allapps.suggestions

/**
 * The app suggestion tabs at the top of the app drawer: most used, recently opened, and newly
 * installed or updated apps. The order is the order of the tabs on screen.
 */
enum class SuggestionTab { FREQUENT, RECENT, NEW }

/** How [SuggestionTabBar] marks the selected tab, set in the app drawer settings. */
enum class SuggestionTabIndicator {
    /** An accent line under the label. */
    LINE,

    /** A filled pill behind the label, like Lawnchair's selected chips. */
    PILL,
    ;

    companion object {
        fun fromString(value: String): SuggestionTabIndicator = entries.firstOrNull { it.name == value } ?: LINE
    }
}
