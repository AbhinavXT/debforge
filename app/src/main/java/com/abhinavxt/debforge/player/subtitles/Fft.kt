package com.abhinavxt.debforge.player.subtitles

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place radix-2 complex FFT of fixed power-of-two size [n]. O(n log n); the inverse is scaled by 1/n. */
class Fft(val n: Int) {
    private val cosT = DoubleArray(n / 2)
    private val sinT = DoubleArray(n / 2)
    private val rev = IntArray(n)

    init {
        require(n >= 1 && n and (n - 1) == 0) { "size must be a power of two" }
        for (i in 0 until n / 2) {
            cosT[i] = cos(2.0 * PI * i / n)
            sinT[i] = sin(2.0 * PI * i / n)
        }
        var bits = 0
        while (1 shl bits < n) bits++
        for (i in 0 until n) rev[i] = if (bits == 0) 0 else Integer.reverse(i) ushr (32 - bits)
    }

    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        require(re.size == n && im.size == n)
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        val sign = if (inverse) 1.0 else -1.0
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cosT[k]
                    val wi = sign * sinT[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr
                    im[j + half] = im[j] - xi
                    re[j] += xr
                    im[j] += xi
                    k += step
                }
                i += len
            }
            len *= 2
        }
        if (inverse) {
            val s = 1.0 / n
            for (i in 0 until n) { re[i] *= s; im[i] *= s }
        }
    }
}
