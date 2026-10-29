package com.abhinavxt.debforge.domain.adblock

/**
 * Element hiding: "##.ad-banner" (every site), "site.com,~m.site.com##.promo"
 * (some sites), "site.com#@#.promo" (exceptions), and uBO's ":style(...)" and
 * ":remove()". Turned into a stylesheet per page by [css].
 *
 * Rules that need uBO's own code (scriptlets "##+js(...)", procedural
 * ":has-text()" & co., HTML filtering) can't run in a WebView and are skipped.
 */
internal class ElementFilters {

    private val generic = LinkedHashSet<String>()
    /** By domain or "name.*" entity: CSS rules. */
    private val specific = HashMap<String, MutableList<String>>()
    /** By domain or entity: CSS rules that must not apply there. */
    private val exceptions = HashMap<String, MutableSet<String>>()
    private val genericExceptions = HashSet<String>()

    /** The generic stylesheet, built once: most pages have no exception touching it. */
    private val genericCss: String by lazy { (generic - genericExceptions).joinToString("") }

    val size get() = generic.size + specific.values.sumOf { it.size }

    /**
     * [domains] is the part before "##" (may be empty), [selector] the part
     * after. False when the rule is one DebForge can't apply.
     */
    fun add(domains: String, exception: Boolean, selector: String): Boolean {
        val rule = cssRule(selector.trim()) ?: return false
        val include = ArrayList<String>()
        val exclude = ArrayList<String>()
        for (raw in domains.split(',')) {
            val d = raw.trim().lowercase()
            when {
                d.startsWith("~") -> exclude += d.drop(1)
                d.isNotEmpty() -> include += d
            }
        }
        when {
            exception && include.isEmpty() -> genericExceptions += rule
            exception -> include.forEach { exceptions.getOrPut(it) { HashSet() } += rule }
            else -> {
                if (include.isEmpty()) generic += rule
                else include.forEach { specific.getOrPut(it) { ArrayList(2) } += rule }
                // "~site.com##.ad" is ".ad" everywhere but there.
                exclude.forEach { exceptions.getOrPut(it) { HashSet() } += rule }
            }
        }
        return true
    }

    /** The stylesheet for a page or frame on [host]; [flags] are its [PageFlags] exceptions. */
    fun css(host: String, flags: Int): String {
        if (host.isEmpty()) return ""
        val names = ArrayList<String>()
        Hosts.forEachSuffix(host) { names += it }
        names += Hosts.entities(host)
        val except = HashSet<String>()
        names.forEach { n -> exceptions[n]?.let(except::addAll) }

        val out = StringBuilder()
        if ((flags and PageFlags.SPECIFIC) == 0) {
            names.forEach { n -> specific[n]?.forEach { if (it !in except && it !in genericExceptions) out.append(it) } }
        }
        if ((flags and PageFlags.GENERIC) == 0) {
            if (except.none { it in generic }) out.append(genericCss)
            else generic.forEach { if (it !in except && it !in genericExceptions) out.append(it) }
        }
        return out.toString()
    }

    private companion object {
        /** uBO-only syntax a browser's CSS engine doesn't know. */
        val PROCEDURAL = listOf(
            ":has-text(", ":upward(", ":xpath(", ":matches-css", ":matches-attr(", ":matches-path(",
            ":matches-prop(", ":matches-media(", ":min-text-length(", ":watch-attr(", ":others(",
            ":-abp-", ":if(", ":if-not(", ":nth-ancestor(", ":shadow(", ":remove-attr(", ":remove-class("
        )

        /**
         * One CSS rule per selector: a rule the browser can't parse is dropped
         * on its own instead of taking the whole stylesheet with it.
         */
        fun cssRule(selector: String): String? {
            if (selector.isEmpty() || selector.startsWith("+js(") || selector.startsWith("^")) return null
            if (PROCEDURAL.any { selector.contains(it) }) return null
            var sel = selector
            var declarations = "display:none!important"
            when {
                sel.endsWith(":remove()") -> sel = sel.removeSuffix(":remove()")
                sel.endsWith(")") && sel.contains(":style(") -> {
                    val at = sel.lastIndexOf(":style(")
                    declarations = sel.substring(at + 7, sel.length - 1).trim()
                    sel = sel.substring(0, at)
                    // A style may hide or move things, never load anything.
                    if (declarations.isEmpty() || declarations.contains("url(", ignoreCase = true) ||
                        declarations.contains('{') || declarations.contains('}')
                    ) return null
                }
            }
            sel = sel.trim()
            if (sel.isEmpty() || sel.contains('{') || sel.contains('}') || sel.contains("</")) return null
            return "$sel{$declarations}\n"
        }
    }
}
