package com.abhinavxt.debforge.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Test

class DescriptionTextTest {
    @Test fun plainTextIsKeptAndTrimmed() = assertEquals("a <b> c", descriptionText("  a <b> c \n", html = false))
    @Test fun nullIsEmpty() = assertEquals("", descriptionText(null, html = true))
    @Test fun htmlBecomesText() =
        assertEquals("Hi & bye\nnext", descriptionText("<p>Hi &amp; bye</p><a href=\"x\">next</a>".replace("</p>", "<br>"), html = true))
    @Test fun entitiesInPlainTextAreDecoded() =
        assertEquals("It hasn't \"ended\" <yet> & \u2014 ok", descriptionText("It hasn&apos;t &quot;ended&quot; &lt;yet&gt; &amp; &mdash; ok", html = false))
    @Test fun numericEntities() = assertEquals("it's it's \u00e9", descriptionText("it&#39;s it&#x27;s &#233;", html = true))
    @Test fun decodedOnceNotTwice() = assertEquals("&lt;b&gt;", descriptionText("&amp;lt;b&amp;gt;", html = true))
    @Test fun unknownEntitiesAreKept() = assertEquals("&bogus; &#0; a&b", descriptionText("&bogus; &#0; a&b", html = false))
    @Test fun htmlTagsDroppedEntitiesDecoded() =
        assertEquals("Line one\nDon't miss", descriptionText("<p>Line one</p><b>Don&apos;t</b>&nbsp;miss", html = true))
}

class DecodeEntitiesTest {
    @Test fun decimalAboveBmp() = assertEquals("1 A.M Study Session 📚 [lofi hip hop]", decodeEntities("1 A.M Study Session &#128218; [lofi hip hop]"))
    @Test fun hexAboveBmp() = assertEquals("📚", decodeEntities("&#x1F4DA;"))
    @Test fun named() = assertEquals("a & b \"c\" 'd'", decodeEntities("a &amp; b &quot;c&quot; &#39;d&#39;"))
    @Test fun invalidLeftAsIs() = assertEquals("&#xZZ; &#1114112;", decodeEntities("&#xZZ; &#1114112;"))
    @Test fun plainAmpersandsKept() = assertEquals("R&B and AT&T; rock", decodeEntities("R&B and AT&T; rock"))
}
