package com.abhinavxt.debforge.domain.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdFiltersTest {

    private fun filters(vararg lines: String) = AdFilters.parse(sequenceOf(lines.asSequence()))

    private val script = RequestType.SCRIPT
    private val image = RequestType.IMAGE
    private val page = "https://site.com/watch"

    @Test fun `host filters cover subdomains only`() {
        val f = filters("||ads.example.com^")
        assertTrue(f.blocks("https://ads.example.com/a.js", page, script))
        assertTrue(f.blocks("https://x.ads.example.com/a.js", page, script))
        assertFalse(f.blocks("https://badads.example.com/a.js", page, script))
        assertFalse(f.blocks("https://ads.example.com.evil.net/a.js", page, script))
        assertFalse(f.blocks("https://example.com/ads.example.com", page, script))
    }

    @Test fun `hosts-file lines are host filters`() {
        val f = filters("0.0.0.0 tracker.net", "127.0.0.1 ads.org")
        assertTrue(f.blocks("https://cdn.tracker.net/x", page, script))
        assertTrue(f.blocks("https://ads.org/", page, image))
    }

    @Test fun `patterns, wildcards, separators and anchors`() {
        val f = filters("/banner/*/ad_", "-adbox.", "|https://start.com/", "end.gif|", "||cdn.net/promo^")
        assertTrue(f.blocks("https://a.com/banner/300x250/ad_1.png", page, image))
        assertFalse(f.blocks("https://a.com/banner/ad_1.png", page, image))
        assertTrue(f.blocks("https://a.com/x-adbox.js", page, script))
        assertTrue(f.blocks("https://start.com/x", page, script))
        assertFalse(f.blocks("https://a.com/?u=https://start.com/", page, script))
        assertTrue(f.blocks("https://a.com/end.gif", page, image))
        assertFalse(f.blocks("https://a.com/end.gif?x", page, image))
        assertTrue(f.blocks("https://cdn.net/promo?id=1", page, script))
        assertTrue(f.blocks("https://cdn.net/promo", page, script))
        assertFalse(f.blocks("https://cdn.net/promotions", page, script))
    }

    @Test fun `regex filters`() {
        val f = filters("""/\/ad[0-9]+\.js/""")
        assertTrue(f.blocks("https://a.com/AD12.js", page, script))
        assertFalse(f.blocks("https://a.com/add.js", page, script))
    }

    @Test fun `exceptions win unless the block is important`() {
        val f = filters("||ads.com^", "@@||ads.com/ok.js", "||track.com^\$important", "@@||track.com^")
        assertTrue(f.blocks("https://ads.com/bad.js", page, script))
        assertFalse(f.blocks("https://ads.com/ok.js", page, script))
        assertTrue(f.blocks("https://track.com/x", page, script))
    }

    @Test fun `type and party options`() {
        val f = filters("||cdn.com/ads\$script,third-party", "||img.com^\$~image")
        assertTrue(f.blocks("https://cdn.com/ads.js", page, script))
        assertFalse(f.blocks("https://cdn.com/ads.png", page, image))
        assertFalse(f.blocks("https://cdn.com/ads.js", "https://www.cdn.com/", script))
        assertFalse(f.blocks("https://img.com/a.png", page, image))
        assertTrue(f.blocks("https://img.com/a.js", page, script))
    }

    @Test fun `domain and to options`() {
        val f = filters("/popad.\$domain=site.com|~safe.site.com", "*\$script,domain=other.org,to=evil.com|evil.*")
        assertTrue(f.blocks("https://x.com/popad.js", page, script))
        assertFalse(f.blocks("https://x.com/popad.js", "https://safe.site.com/", script))
        assertFalse(f.blocks("https://x.com/popad.js", "https://elsewhere.com/", script))
        assertTrue(f.blocks("https://evil.co.uk/x.js", "https://other.org/", script))
        assertFalse(f.blocks("https://good.com/x.js", "https://other.org/", script))
    }

    @Test fun `navigations are blocked by host filters and doc or popup filters only`() {
        val f = filters("||popads.net^", "/ads/*", "||scam.com^\$doc", "||pop.io^\$popup", "||img.com^\$image")
        val nav = RequestType.NAVIGATION
        assertTrue(f.blocks("https://popads.net/x", page, nav))
        assertFalse(f.blocks("https://news.com/ads/today", page, nav))
        assertTrue(f.blocks("https://scam.com/", page, nav))
        assertTrue(f.blocks("https://pop.io/", page, nav))
        assertFalse(f.blocks("https://pop.io/a.js", page, script))
        assertFalse(f.blocks("https://img.com/", page, nav))
    }

    @Test fun `badfilter cancels a filter`() {
        val f = filters("||ok.com^", "||ok.com^\$badfilter", "||x.com^\$script", "||x.com^\$script,badfilter")
        assertFalse(f.blocks("https://ok.com/a.js", page, script))
        assertFalse(f.blocks("https://x.com/a.js", page, script))
    }

    @Test fun `unsupported options drop the filter`() {
        val f = filters("||a.com^\$removeparam=utm", "||b.com^\$csp=script-src 'none'", "||c.com^\$redirect=noop.js")
        assertFalse(f.blocks("https://a.com/", page, script))
        assertFalse(f.blocks("https://b.com/", page, script))
        assertTrue(f.blocks("https://c.com/x.js", page, script))
    }

    @Test fun `if blocks follow uBO on Chrome for Android`() {
        val f = filters("!#if env_firefox", "||ff.com^", "!#else", "||notff.com^", "!#endif", "!#if env_mobile", "||mobile.com^", "!#endif")
        assertFalse(f.blocks("https://ff.com/", page, script))
        assertTrue(f.blocks("https://notff.com/", page, script))
        assertTrue(f.blocks("https://mobile.com/", page, script))
    }

    @Test fun `element hiding`() {
        val f = filters(
            "##.ad-box", "###banner", "site.com##.promo", "site.*##.entity", "~site.com##.notsite",
            "site.com#@#.ad-box", "##+js(nobab)", "site.com##.x:has-text(Ad)", "site.com##.big:style(height: 0 !important)",
            "site.com##.gone:remove()"
        )
        val css = f.css("https://www.site.com/")
        assertTrue(css.contains(".promo{display:none!important}"))
        assertTrue(css.contains(".entity{"))
        assertTrue(css.contains("#banner{"))
        assertTrue(css.contains(".big{height: 0 !important}"))
        assertTrue(css.contains(".gone{display:none!important}"))
        assertFalse(css.contains(".ad-box"))
        assertFalse(css.contains(".notsite"))
        assertFalse(css.contains("has-text"))
        assertFalse(css.contains("nobab"))

        val other = f.css("https://other.com/")
        assertTrue(other.contains(".ad-box{"))
        assertTrue(other.contains(".notsite{"))
        assertFalse(other.contains(".promo"))
    }

    @Test fun `page exceptions turn off hiding or everything`() {
        val f = filters("##.ad", "news.com##.promo", "@@||news.com^\$ghide", "@@||shop.com^\$document", "@@||blog.com^\$elemhide")
        val news = f.css("https://news.com/")
        assertFalse(news.contains(".ad{"))
        assertTrue(news.contains(".promo{"))
        assertEquals("", f.css("https://blog.com/"))
        assertEquals(PageFlags.ALL, f.pageFlags("https://shop.com/cart"))
        assertEquals(0, f.pageFlags("https://example.com/"))
    }

    @Test fun `request types from headers and extensions`() {
        assertEquals(RequestType.IMAGE, RequestType.infer("https://a.com/x", "image/avif,image/webp,*/*", null))
        assertEquals(RequestType.STYLESHEET, RequestType.infer("https://a.com/x", "text/css,*/*;q=0.1", null))
        assertEquals(RequestType.SUBDOCUMENT, RequestType.infer("https://a.com/x", "text/html,application/xhtml+xml", null))
        assertEquals(RequestType.SCRIPT, RequestType.infer("https://a.com/app.js?v=2", "*/*", null))
        assertEquals(RequestType.XHR, RequestType.infer("https://a.com/x", "*/*", "XMLHttpRequest"))
        val unsure = RequestType.infer("https://a.com/pagead", "*/*", null)
        assertTrue((unsure and RequestType.SCRIPT) != 0 && (unsure and RequestType.IMAGE) == 0)
    }
}
