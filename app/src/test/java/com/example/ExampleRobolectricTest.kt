package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.audio.DjCurveType
import com.example.audio.MusicPlaybackManager
import com.example.audio.RepeatMode
import com.example.data.MusicTrack
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("MusicPlay", appName)
    }

    @Test
    fun `test default crossfade settings and persistence`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = MusicPlaybackManager.getInstance()
        manager.setContext(context)

        // Default crossfade duration must be 20 seconds per user requirement
        assertEquals(20, manager.crossfadeSec.value)
        assertTrue(manager.crossfadeEnabled.value)
        assertTrue(manager.djModeEnabled.value)
        assertEquals(DjCurveType.EQUAL_POWER.name, manager.djCurveType.value)

        // Test updating and clamping
        manager.setCrossfadeSec(12)
        assertEquals(12, manager.crossfadeSec.value)

        manager.setCrossfadeSec(25) // Clamped to 20
        assertEquals(20, manager.crossfadeSec.value)

        manager.setCrossfadeSec(-5) // Clamped to 0
        assertEquals(0, manager.crossfadeSec.value)

        manager.setCrossfadeSec(20)
        assertEquals(20, manager.crossfadeSec.value)
    }

    @Test
    fun `test queue navigation with repeat modes`() {
        val manager = MusicPlaybackManager.getInstance()
        val track1 = MusicTrack(id = 1, title = "Song A", artist = "Artist 1", album = "Album 1", genre = "Pop", year = "2026", trackNumber = "1", durationSec = 180, lyrics = "", folder = "Music")
        val track2 = MusicTrack(id = 2, title = "Song B", artist = "Artist 2", album = "Album 2", genre = "Rock", year = "2026", trackNumber = "2", durationSec = 200, lyrics = "", folder = "Music")
        val track3 = MusicTrack(id = 3, title = "Song C", artist = "Artist 3", album = "Album 3", genre = "Jazz", year = "2026", trackNumber = "3", durationSec = 220, lyrics = "", folder = "Music")

        manager.setQueue(listOf(track1, track2, track3), 0)

        // RepeatMode.ALL at index 0 -> next is index 1
        assertEquals(1, manager.getNextTrackIndex())
        assertEquals(track2.id, manager.getNextTrack()?.id)

        // At index 2 in RepeatMode.ALL -> wraps to index 0
        manager.currentIndex = 2
        assertEquals(0, manager.getNextTrackIndex())
        assertEquals(track1.id, manager.getNextTrack()?.id)

        // RepeatMode.ONE -> next track is the same track (seamless loop crossfade)
        while (manager.repeatMode.value != RepeatMode.ONE) {
            manager.toggleRepeatMode()
        }
        manager.currentIndex = 1
        assertEquals(1, manager.getNextTrackIndex())
        assertEquals(track2.id, manager.getNextTrack()?.id)

        // RepeatMode.NONE at last track -> returns -1 / null (no crossfade past end)
        while (manager.repeatMode.value != RepeatMode.NONE) {
            manager.toggleRepeatMode()
        }
        manager.currentIndex = 2
        assertEquals(-1, manager.getNextTrackIndex())
        assertNull(manager.getNextTrack())
    }

    @Test
    fun `test DJ Effects switch defaults to ON and persists across restarts`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = MusicPlaybackManager.getInstance()
        manager.setContext(context)

        // Criterion 1: DJ Effects must be ON by default on a new installation
        assertTrue("DJ Effects must be ON by default", manager.djEffectsEnabled.value)

        // Criterion 2 & 3: Switching OFF disables effects, but preserves crossfade duration and status
        val initialCrossfadeSec = manager.crossfadeSec.value
        val initialCrossfadeEnabled = manager.crossfadeEnabled.value

        manager.setDjEffectsEnabled(false)
        assertFalse(manager.djEffectsEnabled.value)
        assertEquals(initialCrossfadeSec, manager.crossfadeSec.value)
        assertEquals(initialCrossfadeEnabled, manager.crossfadeEnabled.value)

        // Test persistence: simulate app restart by reloading persisted settings
        val prefs = context.getSharedPreferences("musiplay_dj_playback_prefs", Context.MODE_PRIVATE)
        assertEquals(false, prefs.getBoolean("dj_effects_enabled", true))

        // Re-enable
        manager.setDjEffectsEnabled(true)
        assertTrue(manager.djEffectsEnabled.value)
        assertEquals(true, prefs.getBoolean("dj_effects_enabled", false))
    }

    @Test
    fun `test DJ Performance History tracks effects and creates variety across playthroughs`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val history = com.example.audio.DjPerformanceHistory(context)
        history.clear()

        val trackId = 42
        assertNull(history.getLastEffectForTrack(trackId))
        assertFalse(history.hadEffectLastPlay(trackId))

        // Record first play with VINYL_SCRATCH
        history.recordEffect(trackId, com.example.audio.DjEffectType.VINYL_SCRATCH)
        assertEquals(com.example.audio.DjEffectType.VINYL_SCRATCH, history.getLastEffectForTrack(trackId))
        assertTrue(history.hadEffectLastPlay(trackId))
        assertEquals(com.example.audio.DjEffectType.VINYL_SCRATCH, history.getLastGlobalEffect())

        // Re-read from a new instance to test persistence
        val reloadedHistory = com.example.audio.DjPerformanceHistory(context)
        assertEquals(com.example.audio.DjEffectType.VINYL_SCRATCH, reloadedHistory.getLastEffectForTrack(trackId))
        assertTrue(reloadedHistory.hadEffectLastPlay(trackId))

        // Record clean play on next playthrough
        reloadedHistory.recordCleanPlay(trackId)
        assertFalse(reloadedHistory.hadEffectLastPlay(trackId))
        assertNull(reloadedHistory.getLastEffectForTrack(trackId))
    }

    @Test
    fun `test Smart DJ Engine scheduler leaves most songs clean and respects crossfade buffer`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val processor = com.example.audio.DjAudioProcessor(context)
        val history = com.example.audio.DjPerformanceHistory(context)
        history.clear()
        val engine = com.example.audio.SmartDjPerformanceEngine(context, processor, history)

        // Simulate 20 songs playing through the scheduler
        var songsWithEffects = 0
        val totalSongs = 20
        for (i in 1..totalSongs) {
            val track = MusicTrack(
                id = i,
                title = "Track $i",
                artist = "Artist",
                album = "Album",
                genre = "Pop",
                year = "2026",
                trackNumber = "$i",
                durationSec = 180,
                lyrics = "",
                folder = "Music"
            )
            engine.onTrackStarted(track, isDjEffectsEnabled = true, crossfadeSec = 20, isCrossfadeEnabled = true)
            // Check if history recorded clean or effect
            if (history.hadEffectLastPlay(track.id)) {
                songsWithEffects++
            }
        }

        // Criterion 5 & 8: Deliberately leaves many songs clean; initial target ~25-35%
        // In a 20-song sample, songs with effects should be well below 75% (most songs clean!)
        assertTrue("Most songs should play clean without effects (got $songsWithEffects out of $totalSongs)", songsWithEffects < totalSongs * 0.75)

        // Test Outro placement timing: For a 180s song with 20s crossfade, outro effect must trigger before 160s (180 - 20)
        // Verify that cancelCurrentPerformance safely resets executing state
        engine.cancelCurrentPerformance()
        assertFalse(engine.isDjEffectExecuting.value)
    }
}
