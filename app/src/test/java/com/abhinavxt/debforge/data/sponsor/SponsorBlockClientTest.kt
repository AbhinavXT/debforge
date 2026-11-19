package com.abhinavxt.debforge.data.sponsor

import com.abhinavxt.debforge.domain.SponsorSegment
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class SponsorBlockClientTest {
    @Test fun hashPrefixIsFirstFourHexOfSha256() {
        val full = MessageDigest.getInstance("SHA-256").digest("dQw4w9WgXcQ".toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(full.take(4), SponsorBlockClient.hashPrefix("dQw4w9WgXcQ"))
        assertEquals("e3b0", SponsorBlockClient.hashPrefix("")) // sha256("") = e3b0c442...
    }

    @Test fun categoriesParamIsJsonArray() {
        assertEquals("[\"sponsor\",\"selfpromo\"]", SponsorBlockClient.categoriesParam(listOf("sponsor", "selfpromo")))
    }

    @Test fun filtersToRequestedVideoAndSkipAction() {
        val json = """[
          {"videoID":"other","segments":[{"category":"sponsor","segment":[1.0,9.0],"UUID":"x","actionType":"skip"}]},
          {"videoID":"mine","segments":[
            {"category":"sponsor","segment":[12.5,40.25],"UUID":"a","actionType":"skip","votes":3},
            {"category":"sponsor","segment":[50,60],"UUID":"b","actionType":"mute"},
            {"category":"outro","segment":[70,80],"UUID":"c","actionType":"skip"}
          ]}]"""
        val type = Types.newParameterizedType(List::class.java, SponsorVideoDto::class.java)
        val parsed = Moshi.Builder().build().adapter<List<SponsorVideoDto>>(type).fromJson(json)!!
        val out = SponsorBlockClient.filterFor("mine", parsed, listOf("sponsor", "selfpromo"))
        assertEquals(listOf(SponsorSegment("sponsor", 12_500, 40_250, "a")), out)
    }
}
