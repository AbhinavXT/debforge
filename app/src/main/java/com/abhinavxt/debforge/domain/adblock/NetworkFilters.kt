package com.abhinavxt.debforge.domain.adblock

/**
 * What a request is, as filter options name it. WebView doesn't say, so
 * [RequestType.infer] guesses, and a request can carry several bits when
 * the guess is unsure.
 */
object RequestType {
    const val SCRIPT = 1
    const val IMAGE = 1 shl 1
    const val STYLESHEET = 1 shl 2
    const val XHR = 1 shl 3
    const val SUBDOCUMENT = 1 shl 4
    const val MEDIA = 1 shl 5
    const val FONT = 1 shl 6
    const val OBJECT = 1 shl 7
    const val PING = 1 shl 8
    const val OTHER = 1 shl 9
    const val WEBSOCKET = 1 shl 10
    /** A page opening in the browser itself: a link, a redirect, a pop-up. */
    const val DOCUMENT = 1 shl 11
    const val POPUP = 1 shl 12

    const val NAVIGATION = DOCUMENT or POPUP
    const val SUBRESOURCE = (1 shl 11) - 1
    const val ALL = SUBRESOURCE or NAVIGATION

    private val IMAGES = setOf("png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico", "bmp")
    private val FONTS = setOf("woff", "woff2", "ttf", "otf", "eot")
    private val MEDIAS = setOf("mp4", "webm", "m3u8", "mpd", "ts", "m4s", "mp3", "m4a", "aac", "ogg", "vtt")

    /**
     * Chrome's Accept header tells images, stylesheets and frames apart;
     * anything else could be any other type unless its extension says.
     */
    fun infer(url: String, accept: String?, requestedWith: String?): Int {
        if (requestedWith == "XMLHttpRequest") return XHR
        val a = accept.orEmpty().lowercase()
        when {
            a.startsWith("text/css") -> return STYLESHEET
            a.startsWith("image/") -> return IMAGE
            a.startsWith("text/html") || a.startsWith("application/xhtml") -> return SUBDOCUMENT
            a.startsWith("text/event-stream") -> return XHR
        }
        val path = url.substringBefore('#').substringBefore('?')
        val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return when {
            ext == "js" || ext == "mjs" -> SCRIPT
            ext == "css" -> STYLESHEET
            ext in IMAGES -> IMAGE
            ext in FONTS -> FONT
            ext in MEDIAS -> MEDIA
            else -> SUBRESOURCE and (IMAGE or STYLESHEET or SUBDOCUMENT).inv()
        }
    }
}

/**
 * Adblock Plus / uBlock Origin network filters: "||ads.example.com^",
 * "/banner/ad_$script,third-party,domain=site.com", "@@" exceptions, /regex/.
 * Filters with options DebForge can't honour (removeparam, csp, scriptlet
 * redirects that need uBO's resources...) are left out rather than guessed at.
 *
 * Built once by [AdFilters.parse]; read-only afterwards, so safe from the
 * WebView's request threads.
 */
internal class NetworkFilters {

    /** "||host^" with no options: the bulk of every list. */
    private val blockedHosts = HashSet<String>()
    /** "||host^$options" filters, by host. */
    private val hostRules = HashMap<String, MutableList<Rule>>()
    /** Other filters, by the rarest-looking whole word they need in the URL. */
    private val tokenRules = HashMap<String, MutableList<Rule>>()
    /** Filters with no usable word (regexes, "*$domain=..."): checked on every request. */
    private val otherRules = ArrayList<Rule>()
    /** "@@...$document / $generichide / $elemhide": what's off on a whole page. */
    private val pageRules = ArrayList<Pair<Rule, Int>>()

    var size = 0
        private set

    class Request(val url: String, source: String?, val types: Int) {
        val lower: String = url.lowercase()
        val host: String = Hosts.of(url)
        val sourceHost: String = source?.let(Hosts::of).orEmpty()
        val thirdParty: Boolean by lazy { sourceHost.isNotEmpty() && Hosts.thirdParty(host, sourceHost) }
        val hostStart: Int = lower.indexOf("://").let { if (it < 0) 0 else it + 3 }.let { s ->
            lower.indexOf(host, s).coerceAtLeast(s)
        }
        val hostEnd: Int = hostStart + host.length
    }

    /**
     * True when [req] should not load: a filter blocks it and no exception
     * allows it, unless the blocking filter is $important.
     */
    fun blocks(req: Request): Boolean {
        if (req.host.isEmpty()) return false
        var blocked = false
        var allowed = false
        val navigation = (req.types and RequestType.SUBRESOURCE) == 0
        fun check(r: Rule): Boolean {
            if (!r.applies(req, navigation)) return false
            when {
                r.exception -> allowed = true
                r.important -> return true
                else -> blocked = true
            }
            return false
        }
        Hosts.forEachSuffix(req.host) { h ->
            if (h in blockedHosts) blocked = true
            hostRules[h]?.forEach { if (check(it)) return true }
        }
        val seen = HashSet<String>()
        forEachToken(req.lower) { t ->
            if (seen.add(t)) tokenRules[t]?.forEach { if (check(it)) return true }
        }
        otherRules.forEach { if (check(it)) return true }
        return blocked && !allowed
    }

    /** [PageFlags] exceptions that apply to a page (or frame) at [url]. */
    fun pageFlags(url: String): Int {
        val req = Request(url, url, RequestType.ALL)
        if (req.host.isEmpty()) return 0
        var flags = 0
        for ((rule, f) in pageRules) if ((flags and f) != f && rule.applies(req, navigation = false)) flags = flags or f
        return flags
    }

    fun add(line: String): Boolean {
        var s = line
        val exception = s.startsWith("@@")
        if (exception) s = s.substring(2)

        // Options follow the last '$', except inside a /regex/.
        var optionsAt = s.lastIndexOf('$')
        if (s.startsWith("/") && optionsAt >= 0 && optionsAt < s.lastIndexOf('/')) optionsAt = -1
        val pattern = if (optionsAt >= 0) s.substring(0, optionsAt) else s
        val o = Options()
        if (optionsAt >= 0 && !o.parse(s.substring(optionsAt + 1), exception)) return false

        // Hosts-file lines and bare hostnames mean the whole host, as in uBO.
        val hostsLine = HOSTS_LINE.matchEntire(pattern)?.groupValues?.get(1)
            ?: pattern.takeIf { optionsAt < 0 && BARE_HOST.matches(it) }
        val body = when {
            hostsLine != null -> "||$hostsLine^"
            o.matchCase -> pattern
            else -> pattern.lowercase()
        }

        val host = PURE_HOST.matchEntire(body)?.groupValues?.get(1)
        if (host != null && !exception && o.isPlain) {
            if (blockedHosts.add(host)) size++
            return true
        }
        val matcher = Pattern.of(body, o.matchCase) ?: return false
        // A rule filed under its host needs no pattern: the key is the match.
        fun rule(pattern: Pattern?) = Rule(
            exception = exception,
            important = o.important,
            types = o.types,
            party = o.party,
            from = o.from,
            to = o.to,
            hostOnly = host != null,
            pattern = pattern
        )
        size++
        if (o.pageFlags != 0) {
            pageRules += rule(matcher) to o.pageFlags
            if (o.types == 0) return true
        }
        when {
            host != null -> hostRules.getOrPut(host) { ArrayList(1) } += rule(null)
            else -> (matcher as? Pattern.Glob)?.token?.let { tokenRules.getOrPut(it) { ArrayList(2) } += rule(matcher) }
                ?: otherRules.add(rule(matcher))
        }
        return true
    }

    private class Rule(
        val exception: Boolean,
        val important: Boolean,
        /** 0: any subresource. */
        val types: Int,
        val party: Int,
        val from: DomainList?,
        val to: DomainList?,
        /** Matched by its host key alone ("||host^"). */
        val hostOnly: Boolean,
        val pattern: Pattern?
    ) {
        fun applies(req: Request, navigation: Boolean): Boolean {
            // A filter with no type blocks a page outright only when it names a
            // whole host (uBO's strict blocking); "/ads/" must not stop a page at /ads/.
            if (types == 0) { if (navigation && !exception && !hostOnly) return false }
            else if ((types and req.types) == 0) return false
            if (party == FIRST && req.thirdParty) return false
            if (party == THIRD && !req.thirdParty) return false
            if (from != null && (req.sourceHost.isEmpty() || !from.matches(req.sourceHost))) return false
            if (to != null && !to.matches(req.host)) return false
            return pattern?.matches(req) ?: true
        }
    }

    private class Options {
        var types = 0
        var notTypes = 0
        var party = ANY
        var from: DomainList? = null
        var to: DomainList? = null
        var important = false
        var matchCase = false
        var pageFlags = 0
        /** No option that changes what the filter matches. */
        val isPlain get() = types == 0 && party == ANY && from == null && to == null && !important

        fun parse(options: String, exception: Boolean): Boolean {
            for (raw in options.split(',')) {
                val opt = raw.trim()
                val name = opt.substringBefore('=').lowercase()
                val value = opt.substringAfter('=', "")
                when (name) {
                    "third-party", "3p", "~first-party", "~1p", "strict3p" -> party = THIRD
                    "first-party", "1p", "~third-party", "~3p", "strict1p" -> party = FIRST
                    "domain", "from" -> from = DomainList.parse(value, '|') ?: return false
                    "to" -> to = DomainList.parse(value, '|') ?: return false
                    // Block everywhere except these hosts.
                    "denyallow" -> to = DomainList.parse(value.split('|').joinToString("|") { "~$it" }, '|') ?: return false
                    "important" -> important = true
                    "match-case" -> matchCase = true
                    // Blocking does what the stand-in would: the ad never runs.
                    "redirect", "rewrite", "empty", "mp4" -> Unit
                    "reason", "_" -> Unit
                    "document", "doc" -> if (exception) pageFlags = pageFlags or PageFlags.ALL else types = types or RequestType.DOCUMENT
                    "elemhide", "ehide" -> if (exception) pageFlags = pageFlags or PageFlags.ELEMENTS else return false
                    "generichide", "ghide" -> if (exception) pageFlags = pageFlags or PageFlags.GENERIC else return false
                    "specifichide", "shide" -> if (exception) pageFlags = pageFlags or PageFlags.SPECIFIC else return false
                    else -> {
                        val negated = name.startsWith("~")
                        val type = TYPES[name.removePrefix("~")] ?: return false
                        if (negated) notTypes = notTypes or type else types = types or type
                    }
                }
            }
            if (notTypes != 0) types = (if (types == 0) RequestType.SUBRESOURCE else types) and notTypes.inv()
            // Every type negated: matches nothing.
            return notTypes == 0 || types != 0
        }
    }

    /** A filter's URL pattern. */
    private sealed class Pattern {
        abstract fun matches(req: Request): Boolean

        /** "*" or "": every URL (the filter's options do the narrowing). */
        object Everything : Pattern() {
            override fun matches(req: Request) = true
        }

        class RegexPattern(private val regex: Regex) : Pattern() {
            override fun matches(req: Request) = regex.containsMatchIn(req.url)
        }

        /** Wildcards (*), separators (^) and anchors (|, ||). */
        class Glob(
            private val glob: String,
            private val hostAnchor: Boolean,
            private val matchCase: Boolean,
            val token: String?
        ) : Pattern() {
            override fun matches(req: Request): Boolean {
                val url = if (matchCase) req.url else req.lower
                if (!hostAnchor) return globMatch(glob, url, 0)
                // "||" starts at the host or after any dot in it.
                if (globMatch(glob, url, req.hostStart)) return true
                for (i in req.hostStart until req.hostEnd) {
                    if (url[i] == '.' && globMatch(glob, url, i + 1)) return true
                }
                return false
            }
        }

        companion object {
            fun of(body: String, matchCase: Boolean): Pattern? {
                if (body.isEmpty() || body == "*") return Everything
                if (body.length > 2 && body.startsWith("/") && body.endsWith("/")) {
                    val options = if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
                    // JavaScript regexes Java can't read are dropped.
                    return runCatching { RegexPattern(Regex(body.substring(1, body.length - 1), options)) }.getOrNull()
                }
                var p = body
                val hostAnchor = p.startsWith("||")
                val start = hostAnchor || p.startsWith("|")
                p = p.removePrefix("||").removePrefix("|")
                val end = p.endsWith("|")
                p = p.removeSuffix("|")
                if (p.isEmpty()) return Everything
                val token = bestToken(p, start, end)
                val glob = (if (start) "" else "*") + p + (if (end) "" else "*")
                return Glob(glob.replace(STARS, "*"), hostAnchor, matchCase, token)
            }

            /**
             * The longest whole word the URL must contain: one bounded by
             * separators or anchors, never by a wildcard (a word next to '*'
             * may be part of a longer one in the URL).
             */
            private fun bestToken(p: String, anchoredStart: Boolean, anchoredEnd: Boolean): String? {
                var best: String? = null
                var i = 0
                while (i < p.length) {
                    if (!isTokenChar(p[i])) { i++; continue }
                    val from = i
                    while (i < p.length && isTokenChar(p[i])) i++
                    val leftOk = if (from == 0) anchoredStart else p[from - 1] != '*'
                    val rightOk = if (i == p.length) anchoredEnd else p[i] != '*'
                    if (!leftOk || !rightOk) continue
                    val t = p.substring(from, i).lowercase()
                    val better = best == null || (t !in COMMON_TOKENS && (best in COMMON_TOKENS || t.length > best.length))
                    if (better) best = t
                }
                return best
            }
        }
    }

    companion object {
        private const val ANY = 0
        private const val FIRST = 1
        private const val THIRD = 2

        private val TYPES = mapOf(
            "script" to RequestType.SCRIPT,
            "image" to RequestType.IMAGE,
            "stylesheet" to RequestType.STYLESHEET, "css" to RequestType.STYLESHEET,
            "xmlhttprequest" to RequestType.XHR, "xhr" to RequestType.XHR,
            "subdocument" to RequestType.SUBDOCUMENT, "frame" to RequestType.SUBDOCUMENT,
            "media" to RequestType.MEDIA,
            "font" to RequestType.FONT,
            "object" to RequestType.OBJECT, "object-subrequest" to RequestType.OBJECT,
            "ping" to RequestType.PING, "beacon" to RequestType.PING,
            "other" to RequestType.OTHER,
            "websocket" to RequestType.WEBSOCKET,
            "popup" to RequestType.POPUP, "popunder" to RequestType.POPUP,
            "all" to RequestType.ALL
        )

        /** Words nearly every URL has: a filter indexed by one would be checked all the time. */
        private val COMMON_TOKENS = setOf("http", "https", "www", "com", "net", "org", "js", "html", "php", "css", "jpg", "png", "gif")

        private val PURE_HOST = Regex("""\|\|([a-z0-9][a-z0-9.-]*\.[a-z0-9-]+)\^""")
        private val HOSTS_LINE = Regex("""(?:0\.0\.0\.0|127\.0\.0\.1)\s+([a-z0-9][a-z0-9.-]*\.[a-z0-9-]+)""", RegexOption.IGNORE_CASE)
        private val BARE_HOST = Regex("""[a-z0-9][a-z0-9-]*(?:\.[a-z0-9-]+)+""")
        private val STARS = Regex("""\*{2,}""")

        private fun isTokenChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '%'

        /** ABP's "^": anything but a letter, digit, or _ - . % (the end of the URL counts too). */
        private fun isSeparator(c: Char) = !(c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == '%')

        private inline fun forEachToken(url: String, action: (String) -> Unit) {
            var i = 0
            while (i < url.length) {
                if (!isTokenChar(url[i])) { i++; continue }
                val from = i
                while (i < url.length && isTokenChar(url[i])) i++
                action(url.substring(from, i))
            }
        }

        /** [glob] ('*', '^' and literals) against [s] from [from] to its end. */
        private fun globMatch(glob: String, s: String, from: Int): Boolean {
            var i = from
            var j = 0
            var star = -1
            var starAt = 0
            while (i < s.length) {
                if (j < glob.length && glob[j] == '*') {
                    star = j++
                    starAt = i
                } else if (j < glob.length && (if (glob[j] == '^') isSeparator(s[i]) else glob[j] == s[i])) {
                    i++
                    j++
                } else if (star >= 0) {
                    j = star + 1
                    i = ++starAt
                } else {
                    return false
                }
            }
            while (j < glob.length && (glob[j] == '*' || glob[j] == '^')) j++
            return j == glob.length
        }
    }
}

/** What a page-wide exception turns off. */
object PageFlags {
    /** No network blocking. */
    const val NETWORK = 0b001
    /** "@@...$document": no blocking at all. */
    const val ALL = 0b111
    /** Element hiding of either kind. */
    const val ELEMENTS = 0b110
    /** "##.ad" rules that apply to every site. */
    const val GENERIC = 0b010
    /** "site.com##.ad" rules. */
    const val SPECIFIC = 0b100
}
