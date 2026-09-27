package com.abhinavxt.debforge.diagnostics

/**
 * Strips anything that could identify the user's account or library from
 * text before it is shown or shared: API tokens, auth headers, URL paths and
 * query strings, torrent hashes and file names under shared storage.
 * Pure Kotlin so it can be unit-tested.
 */
object Redactor {

    fun redact(text: String): String = RULES.fold(text) { acc, (pattern, replacement) ->
        pattern.replace(acc, replacement)
    }

    private val RULES = listOf(
        Regex("""(?i)\b(token|apikey|api_key|access_token|refresh_token|client_secret|code|password|pin)=[^&\s"']+""") to "$1=***",
        Regex("""(?i)(authorization:\s*)(bearer\s+)?\S+""") to "$1***",
        Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{8,}""") to "Bearer ***",
        Regex("""(?i)urn:btih:[a-z0-9]+""") to "urn:btih:***",
        Regex("""\b[a-fA-F0-9]{40}\b""") to "<hash>",
        Regex("""(https?://[^\s/?#"']+)[^\s"']*""") to "$1/…",
        Regex("""(/storage/[^/\s]+/[^/\s]+/)[^\s:"']+""") to "$1…"
    )
}
