package com.abhinavxt.debforge.domain

/**
 * Minimal semantic-version comparison for release tags: "v1.4.2",
 * "1.5", "2.0.0-beta.1". Numeric parts compare numerically (1.10 > 1.9);
 * missing parts count as 0 (1.4 == 1.4.0); a pre-release suffix sorts before
 * the plain release (2.0.0-beta < 2.0.0).
 */
object AppVersion {

    fun compare(a: String, b: String): Int {
        val (aNums, aPre) = split(a)
        val (bNums, bPre) = split(b)
        for (i in 0 until maxOf(aNums.size, bNums.size)) {
            val c = aNums.getOrElse(i) { 0 }.compareTo(bNums.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return when {
            aPre == null && bPre == null -> 0
            aPre == null -> 1
            bPre == null -> -1
            else -> comparePre(aPre, bPre)
        }
    }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    private fun split(v: String): Pair<List<Int>, String?> {
        val clean = v.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        val core = clean.substringBefore('-')
        val pre = clean.substringAfter('-', missingDelimiterValue = "").ifEmpty { null }
        val nums = core.split('.').map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        return nums to pre
    }

    /** beta.2 < beta.10 < rc.1 — numeric identifiers numerically, others lexically. */
    private fun comparePre(a: String, b: String): Int {
        val ap = a.split('.')
        val bp = b.split('.')
        for (i in 0 until maxOf(ap.size, bp.size)) {
            val x = ap.getOrNull(i) ?: return -1
            val y = bp.getOrNull(i) ?: return 1
            val xn = x.toIntOrNull()
            val yn = y.toIntOrNull()
            val c = when {
                xn != null && yn != null -> xn.compareTo(yn)
                xn != null -> -1
                yn != null -> 1
                else -> x.compareTo(y)
            }
            if (c != 0) return c
        }
        return 0
    }
}
