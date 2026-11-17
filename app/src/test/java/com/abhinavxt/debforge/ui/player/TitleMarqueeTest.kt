package com.abhinavxt.debforge.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleMarqueeTest {
    @Test fun scrollsWithNormalAnimations() = assertTrue(titleMarqueeEnabled(1f))
    @Test fun scrollsWithSlowedAnimations() = assertTrue(titleMarqueeEnabled(0.5f))
    @Test fun staysStillWhenAnimationsAreOff() = assertFalse(titleMarqueeEnabled(0f))
}
