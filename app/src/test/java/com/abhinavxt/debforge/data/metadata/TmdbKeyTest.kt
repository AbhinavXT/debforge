package com.abhinavxt.debforge.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbKeyTest {

    private val v3 = "0123456789abcdef0123456789ABCDEF"
    private val v4 = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJ4In0.c2lnbmF0dXJl_-x"

    @Test fun v3Key() = assertEquals(TmdbKey.Kind.V3_KEY, TmdbKey.kindOf(v3))

    @Test fun v4Token() = assertEquals(TmdbKey.Kind.V4_TOKEN, TmdbKey.kindOf(v4))

    @Test fun garbage() {
        assertEquals(TmdbKey.Kind.UNKNOWN, TmdbKey.kindOf("hello"))
        assertEquals(TmdbKey.Kind.UNKNOWN, TmdbKey.kindOf(v3.dropLast(1)))
        assertEquals(TmdbKey.Kind.UNKNOWN, TmdbKey.kindOf("eyJonlyonepart"))
    }

    @Test fun trimsAndUnquotes() = assertEquals(v3, TmdbKey.normalize("  \"$v3\"\n"))

    @Test fun dropsBearerPrefix() = assertEquals(v4, TmdbKey.normalize("Bearer $v4"))

    @Test fun joinsWrappedToken() {
        val wrapped = v4.substring(0, 20) + "\n  " + v4.substring(20, 40) + " " + v4.substring(40)
        assertEquals(v4, TmdbKey.normalize(wrapped))
    }

    @Test fun emptyStaysEmpty() = assertEquals("", TmdbKey.normalize("   "))
}
