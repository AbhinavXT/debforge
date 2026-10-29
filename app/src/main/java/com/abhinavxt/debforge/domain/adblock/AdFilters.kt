package com.abhinavxt.debforge.domain.adblock

/**
 * uBlock Origin-style filtering for the Browser tab: the lists uBO ships
 * with (EasyList, uBlock filters...), parsed into network filters (requests
 * that never load) and element filters (ad boxes hidden on the page).
 *
 * Read-only once built, so the WebView's threads can share one.
 */
class AdFilters private constructor(
    private val network: NetworkFilters,
    private val elements: ElementFilters
) {
    /** Filters in use (ones DebForge can't apply are not counted). */
    val size: Int get() = network.size + elements.size

    /**
     * True when a request made by the page at [sourceUrl] for [url] should
     * not load. [types] is from [RequestType.infer], or [RequestType.NAVIGATION]
     * for the browser itself going to [url] (a link, a redirect, a pop-up).
     */
    fun blocks(url: String, sourceUrl: String?, types: Int): Boolean =
        network.blocks(NetworkFilters.Request(url, sourceUrl, types))

    /** The list exceptions ([PageFlags]) for a page or frame at [url]. */
    fun pageFlags(url: String): Int = network.pageFlags(url)

    /** The stylesheet that hides ads on a page or frame at [url]. */
    fun css(url: String): String {
        val flags = pageFlags(url)
        if ((flags and PageFlags.ELEMENTS) == PageFlags.ELEMENTS) return ""
        return elements.css(Hosts.of(url), flags)
    }

    companion object {
        /**
         * uBO's "!#if" conditions, answered as uBO on Chrome for Android would.
         * Anything unknown is false.
         */
        private val ENV = setOf("env_chromium", "env_mobile", "cap_user_stylesheet", "ext_ublock")

        /** Builds filters from the lines of every list. */
        fun parse(lists: Sequence<Sequence<String>>): AdFilters {
            val network = NetworkFilters()
            val elements = ElementFilters()
            val lines = ArrayList<String>()
            for (list in lists) lines += activeLines(list)

            // "$badfilter" cancels the same filter written without it, in any list.
            val cancelled = HashSet<String>()
            for (line in lines) if (line.contains("badfilter")) withoutBadfilter(line)?.let(cancelled::add)

            for (line in lines) {
                if (line in cancelled) continue
                val cosmetic = cosmeticAt(line)
                if (cosmetic != null) {
                    val (at, marker) = cosmetic
                    if (marker == "##" || marker == "#@#") {
                        elements.add(line.substring(0, at), marker == "#@#", line.substring(at + marker.length))
                    }
                    // #?#, #$#, #%#: procedural, CSS-injection and script rules uBO runs itself.
                    continue
                }
                if (line.contains("badfilter")) continue
                network.add(line)
            }
            return AdFilters(network, elements)
        }

        /** Filter lines, without comments and without "!#if" blocks that don't apply. */
        private fun activeLines(list: Sequence<String>): List<String> {
            val out = ArrayList<String>()
            val conditions = ArrayDeque<Boolean>()
            for (raw in list) {
                val line = raw.trim()
                when {
                    line.startsWith("!#if ") -> conditions.addLast(evaluate(line.substring(5).trim()))
                    line.startsWith("!#else") -> conditions.removeLastOrNull()?.let { conditions.addLast(!it) }
                    line.startsWith("!#endif") -> conditions.removeLastOrNull()
                    line.isEmpty() || line.startsWith("!") || line.startsWith("[") -> Unit
                    conditions.all { it } -> out += line
                }
            }
            return out
        }

        private fun evaluate(condition: String): Boolean =
            if (condition.startsWith("!")) condition.substring(1).trim() !in ENV else condition in ENV

        /** Where "##" (or "#@#", "#?#"...) starts, when the part before it is a domain list. */
        private fun cosmeticAt(line: String): Pair<Int, String>? {
            val at = line.indexOf('#')
            if (at < 0) return null
            val marker = MARKERS.firstOrNull { line.startsWith(it, at) } ?: return null
            val domains = line.substring(0, at)
            if (domains.any { !(it.isLetterOrDigit() || it in ".,~*-_") }) return null
            return at to marker
        }

        private val MARKERS = listOf("##", "#@#", "#?#", "#@?#", "#$#", "#@$#", "#%#", "#@%#")

        private fun withoutBadfilter(line: String): String? {
            val at = line.lastIndexOf('$')
            if (at < 0) return null
            val options = line.substring(at + 1).split(',').filter { it.trim() != "badfilter" }
            if (options.size == line.substring(at + 1).split(',').size) return null
            return if (options.isEmpty()) line.substring(0, at) else line.substring(0, at + 1) + options.joinToString(",")
        }
    }
}
