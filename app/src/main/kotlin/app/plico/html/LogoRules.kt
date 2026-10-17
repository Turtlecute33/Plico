package app.plico.html

import app.plico.data.Prefs

/** Pure rules for sender logos (DESIGN.md §12): what each source gets and which hosts it uses. */
object LogoRules {
    /**
     * The hosts that a logo source connects to, for About. Empty for [Prefs.LogoSource.WEBSITE]: the website of
     * each sender (and the hosts it redirects to).
     */
    fun hosts(source: Prefs.LogoSource): List<String> = when (source) {
        Prefs.LogoSource.DUCKDUCKGO -> listOf("icons.duckduckgo.com")
        Prefs.LogoSource.GOOGLE -> listOf("www.google.com", "*.gstatic.com")
        Prefs.LogoSource.WEBSITE -> emptyList()
    }
}
