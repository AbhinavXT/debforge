package com.abhinavxt.debforge.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Test

class DescriptionTextTest {
    @Test fun plainTextIsKeptAndTrimmed() = assertEquals("a <b> c", descriptionText("  a <b> c \n", html = false))
    @Test fun nullIsEmpty() = assertEquals("", descriptionText(null, html = true))
    @Test fun htmlBecomesText() =
        assertEquals("Hi & bye\nnext", descriptionText("<p>Hi &amp; bye</p><a href=\"x\">next</a>".replace("</p>", "<br>"), html = true))
}
