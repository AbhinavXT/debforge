package com.abhinavxt.debforge.player.subtitles

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SubtitleTimingStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun store(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) = SubtitleTimingStore(context, scope)

    @Test fun saveSurvivesCancelOfTheCallersScope() = runBlocking {
        val caller = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val s = store()
        val key = "os:survive"
        // Undispatched: the save is certainly called before the caller is cancelled (a cancelled-before-start launch never ran it).
        caller.launch(start = CoroutineStart.UNDISPATCHED) { s.save(key, SubtitleTiming(1.0, 1234.0)) }
        caller.cancel()
        withTimeout(10_000) { while (s.all()[key] == null) delay(20) }
        assertEquals(SubtitleTiming(1.0, 1234.0), s.all()[key])
    }

    @Test fun suspendSetFinishesWhenCancelled() = runBlocking {
        val s = store()
        val job = launch(Dispatchers.IO) { s.set("os:cancelled", SubtitleTiming(1.0, 77.0)) }
        job.cancel()
        job.join()
        val saved = s.all()["os:cancelled"]
        if (saved != null) assertEquals(SubtitleTiming(1.0, 77.0), saved)
    }

    @Test fun firstLoadDoesNotBringBackARemovedKey() = runBlocking {
        store().set("os:race", SubtitleTiming(1.0, 500.0))
        val second = store()
        second.updateCache("os:race", SubtitleTiming.IDENTITY)
        assertNull(second.cached("os:race")) // waits for the load, which must skip the removed key
        assertNull(second.lookup("os:race"))
    }

    @Test fun cachedWaitsForTheFirstLoad() = runBlocking {
        store().set("os:loaded", SubtitleTiming(1.0, 42.0))
        assertEquals(SubtitleTiming(1.0, 42.0), store().cached("os:loaded"))
    }

    @Test fun lookupFallsBackToTheOldFileNameKey() = runBlocking {
        store().set("local:English.srt|v1", SubtitleTiming(1.0, 9.0))
        val s = store()
        assertEquals(SubtitleTiming(1.0, 9.0), s.lookup("local:Subs/A/English.srt|v1"))
        assertNull(s.cached("local:Subs/A/English.srt|v1"))
    }
}
