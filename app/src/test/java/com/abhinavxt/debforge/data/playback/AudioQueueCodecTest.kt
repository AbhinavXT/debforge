package com.abhinavxt.debforge.data.playback

import com.abhinavxt.debforge.player.AudioRepeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// org.json is a stub in plain JVM unit tests, so this needs Robolectric.
@RunWith(RobolectricTestRunner::class)
class AudioQueueCodecTest {
    private val local = SavedTrack("a", "Song", "Band")
    private val yt = SavedTrack("ytaudio:x", "Clip", "Chan", local = false, videoId = "x", itag = 140,
        streamUrl = "https://v/x", artworkUrl = "https://i/x.jpg", artistUrl = "https://c/chan")

    @Test fun roundTrip() {
        val q = QueueSnapshot(listOf(local, yt), shuffle = true, repeat = AudioRepeat.ONE, speed = 1.5f)
        assertEquals(q, AudioQueueCodec.decode(AudioQueueCodec.encode(q)))
    }

    @Test fun streamedPodcastEpisodeRoundTrips() {
        val pod = SavedTrack("pod:abc", "Episode", "Show", local = false, streamUrl = "https://cdn/ep.mp3", artworkUrl = "https://cdn/a.jpg")
        val q = QueueSnapshot(listOf(local, pod))
        assertEquals(q, AudioQueueCodec.decode(AudioQueueCodec.encode(q)))
    }

    @Test fun shuffleOrderRoundTripsAndBadOnesAreDropped() {
        val q = QueueSnapshot(listOf(local, yt, local.copy(id = "b")), shuffle = true, shuffleOrder = listOf(2, 0, 1))
        assertEquals(listOf(2, 0, 1), AudioQueueCodec.decode(AudioQueueCodec.encode(q))!!.shuffleOrder)
        assertNull(AudioQueueCodec.decode(AudioQueueCodec.encode(q.copy(shuffleOrder = listOf(0, 0, 1))))!!.shuffleOrder)
        assertNull(AudioQueueCodec.decode("""{"v":1,"t":[{"i":"a","n":"S","k":"l"}],"s":true}""")!!.shuffleOrder)
    }

    @Test fun unknownFieldsIgnored() {
        val raw = """{"v":1,"zz":1,"t":[{"i":"a","n":"S","k":"l","extra":[1]}],"s":false,"r":"ALL","p":1.0}"""
        val q = AudioQueueCodec.decode(raw)!!
        assertEquals("a", q.tracks.single().id)
        assertEquals(AudioRepeat.ALL, q.repeat)
    }

    @Test fun garbageIsNull() {
        assertNull(AudioQueueCodec.decode(null))
        assertNull(AudioQueueCodec.decode("not json"))
        assertNull(AudioQueueCodec.decode("""{"v":1}"""))
    }

    @Test fun newerVersionIsNull() {
        assertNull(AudioQueueCodec.decode("""{"v":2,"t":[]}"""))
        assertNotNull(AudioQueueCodec.decode("""{"v":1,"t":[]}"""))
    }

    @Test fun capKeepsCurrentTrack() {
        val many = (0 until 3000).map { SavedTrack("t$it", "T$it") }
        val q = AudioQueueCodec.decode(AudioQueueCodec.encode(QueueSnapshot(many), "t2900"))!!
        assertEquals(MAX_SAVED_TRACKS, q.tracks.size)
        assertTrue(q.tracks.any { it.id == "t2900" })
        assertEquals("t2999", q.tracks.last().id)
        val head = AudioQueueCodec.decode(AudioQueueCodec.encode(QueueSnapshot(many), "t5"))!!
        assertEquals("t0", head.tracks.first().id)
    }

    // The v1.12.0 format: no podcast tracks, a YouTube track keeps videoId, itag, stream url and artwork.
    @Test fun oldFormatQueueStillDecodes() {
        val raw = """{"v":1,"t":[{"i":"direct:1","n":"Song","a":"Band","k":"l"},""" +
            """{"i":"ytaudio:x","n":"Clip","a":"Chan","k":"y","y":"x","q":140,"u":"https://v/x","w":"https://i/x.jpg","c":"https://c/chan"},""" +
            """{"i":"ytaudio:bad","n":"No itag","k":"y","y":"bad"}],"s":true,"r":"ONE","p":1.5}"""
        val q = AudioQueueCodec.decode(raw)!!
        assertEquals(listOf("direct:1", "ytaudio:x"), q.tracks.map { it.id })
        assertEquals(yt, q.tracks[1])
        assertTrue(q.shuffle)
        assertEquals(AudioRepeat.ONE, q.repeat)
        assertEquals(1.5f, q.speed)
    }

    @Test fun podcastTrackWithoutStreamUrlIsDropped() {
        assertTrue(AudioQueueCodec.decode("""{"v":1,"t":[{"i":"pod:abc","n":"E","k":"y"}]}""")!!.tracks.isEmpty())
    }
}
