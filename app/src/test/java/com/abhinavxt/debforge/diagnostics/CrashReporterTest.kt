package com.abhinavxt.debforge.diagnostics

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CrashReporterTest {
    private val context = RuntimeEnvironment.getApplication()
    private fun crash(message: String) = CrashReporter.write(context, Thread.currentThread(), IllegalStateException(message))

    @Test fun eachCrashIsAskedAboutOnceAndStaysAvailable() {
        assertNull(CrashReporter.takeUnseen(context))

        crash("first")
        assertNotNull(CrashReporter.takeUnseen(context))
        assertNull(CrashReporter.takeUnseen(context))
        assertNotNull(CrashReporter.pending(context))

        crash("second")
        assertTrue(CrashReporter.takeUnseen(context)!!.contains("second"))

        CrashReporter.clear(context)
        assertNull(CrashReporter.pending(context))

        crash("third")
        assertNotNull(CrashReporter.takeUnseen(context))
    }
}
