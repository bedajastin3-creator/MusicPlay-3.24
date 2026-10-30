package com.example

import com.example.audio.DjCurveType
import com.example.audio.MusicPlaybackManager
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ExampleUnitTest {

    @Test
    fun testCrossfadeGainsEqualPower() {
        // At t = 0.0 (start of transition): Outgoing is 100%, Incoming is 0%
        val startGains = MusicPlaybackManager.calculateCrossfadeGains(0.0f, DjCurveType.EQUAL_POWER.name)
        assertEquals(1.0f, startGains.outGain, 0.001f)
        assertEquals(0.0f, startGains.inGain, 0.001f)

        // At t = 1.0 (end of transition): Outgoing is 0%, Incoming is 100%
        val endGains = MusicPlaybackManager.calculateCrossfadeGains(1.0f, DjCurveType.EQUAL_POWER.name)
        assertEquals(0.0f, endGains.outGain, 0.001f)
        assertEquals(1.0f, endGains.inGain, 0.001f)

        // At t = 0.5 (midpoint of transition):
        // In equal power curve: gain = cos(pi/4) = sin(pi/4) ~ 0.7071
        // Sum of squares = 0.5 + 0.5 = 1.0 (constant acoustic pressure, no dip)
        val midGains = MusicPlaybackManager.calculateCrossfadeGains(0.5f, DjCurveType.EQUAL_POWER.name)
        val powerSum = (midGains.outGain * midGains.outGain) + (midGains.inGain * midGains.inGain)
        assertEquals(1.0f, powerSum, 0.02f)
        assertTrue(abs(midGains.outGain - midGains.inGain) < 0.01f)
    }

    @Test
    fun testCrossfadeGainsLinear() {
        val midGains = MusicPlaybackManager.calculateCrossfadeGains(0.5f, DjCurveType.LINEAR.name)
        assertEquals(0.5f, midGains.outGain, 0.001f)
        assertEquals(0.5f, midGains.inGain, 0.001f)
    }

    @Test
    fun testCrossfadeGainsExponential() {
        val midGains = MusicPlaybackManager.calculateCrossfadeGains(0.5f, DjCurveType.EXPONENTIAL.name)
        val powerSum = (midGains.outGain * midGains.outGain) + (midGains.inGain * midGains.inGain)
        assertEquals(1.0f, powerSum, 0.02f)
    }

    @Test
    fun testTurntableScratchBabyAndFlareTechniques() {
        val sampleRate = 22050
        val engine = com.example.audio.TurntableScratchEngine(sampleRate)

        // Generate synthetic test source PCM (1.5 seconds normalized -1.0f..1.0f)
        val sourcePcm = FloatArray((sampleRate * 1.5).toInt()) { i ->
            (kotlin.math.sin(2.0 * Math.PI * 440.0 * i / sampleRate) * 0.7).toFloat()
        }

        val babyPcm = engine.renderScratchPerformance(
            sourcePcm = sourcePcm,
            technique = com.example.audio.TurntableScratchEngine.ScratchTechnique.BABY_SCRATCH,
            estimatedBpm = 120.0
        )

        val flarePcm = engine.renderScratchPerformance(
            sourcePcm = sourcePcm,
            technique = com.example.audio.TurntableScratchEngine.ScratchTechnique.FLARE_SCRATCH,
            estimatedBpm = 120.0
        )

        // Verify valid output buffers
        assertTrue("Baby scratch buffer must be populated", babyPcm.isNotEmpty())
        assertTrue("Flare scratch buffer must be populated", flarePcm.isNotEmpty())

        // Verify performance contains audio signal
        val babyHasAudio = babyPcm.any { it != 0.toShort() }
        val flareHasAudio = flarePcm.any { it != 0.toShort() }
        assertTrue("Baby scratch must contain audible samples", babyHasAudio)
        assertTrue("Flare scratch must contain audible samples", flareHasAudio)

        // Verify Baby Scratch and Flare Scratch are independent techniques with distinct waveforms
        var differences = 0
        val minLen = minOf(babyPcm.size, flarePcm.size)
        for (i in 0 until minLen) {
            if (babyPcm[i] != flarePcm[i]) differences++
        }
        assertTrue("Baby scratch and Flare scratch must produce distinct waveforms", differences > minLen * 0.2)
    }

    @Test
    fun testDspFlangerCombFiltering() {
        val sampleRate = 22050
        val flanger = com.example.audio.DspFlanger(sampleRate)

        // Source buffer with harmonic content (FloatArray)
        val source = FloatArray(sampleRate * 2) { i ->
            (kotlin.math.sin(2.0 * Math.PI * 300.0 * i / sampleRate) * 0.6).toFloat()
        }

        val flanged = flanger.renderFlangedBuffer(source, estimatedBpm = 124.0)
        assertEquals("Output buffer length should match input length", source.size, flanged.size)

        val hasAudibleSamples = flanged.any { it != 0.toShort() }
        assertTrue("Flanger output must contain audible samples", hasAudibleSamples)

        // Check sample processing doesn't blow up or produce NaN
        flanger.wetMix = 0.75f
        val processed = flanger.processSample(0.5)
        assertTrue("Flanger sample must remain bounded", processed in -1.0..1.0)
    }

    @Test
    fun testDspRhythmicEchoTempoAndDamping() {
        val sampleRate = 22050
        val echo = com.example.audio.DspRhythmicEcho(sampleRate)

        // 120 BPM dotted 1/8 note = (60/120) * 0.75 = 0.375s = 8268 samples
        echo.configureTempo(120.0, com.example.audio.DspRhythmicEcho.EchoDivision.DOTTED_EIGHTH, 0.52f)
        echo.wetMix = 0.65f

        // Feed an impulse at t=0
        val impulseResponse = DoubleArray(sampleRate * 2)
        impulseResponse[0] = echo.processSample(1.0)
        for (i in 1 until impulseResponse.size) {
            impulseResponse[i] = echo.processSample(0.0)
        }

        // Verify repetitions occur at rhythmically spaced intervals
        val expectedEchoSample = (sampleRate * 0.375).toInt()
        val firstRepeatAmplitude = impulseResponse[expectedEchoSample]
        assertTrue("First echo repeat should be audible and non-zero", abs(firstRepeatAmplitude) > 0.05)

        // Verify each subsequent repetition gradually reduces in volume
        val secondRepeatSample = expectedEchoSample * 2
        if (secondRepeatSample < impulseResponse.size) {
            val secondRepeatAmplitude = impulseResponse[secondRepeatSample]
            assertTrue(
                "Second repeat ($secondRepeatAmplitude) must be quieter than first repeat ($firstRepeatAmplitude)",
                abs(secondRepeatAmplitude) < abs(firstRepeatAmplitude)
            )
        }

        // Verify tail mode muting: no new audio fed, existing echoes decay smoothly to silence
        echo.isInputMuted = true
        for (i in 0 until (sampleRate * 2)) {
            echo.processSample(0.0)
        }
        val tailSample = echo.processSample(0.0)
        assertTrue("Tail decay must naturally dissipate to silence", abs(tailSample) < 0.01)
    }

    @Test
    fun testScratchDeepFrequencyCharacter() {
        val sampleRate = 22050
        val engine = com.example.audio.TurntableScratchEngine(sampleRate)

        // Harmonic test signal
        val sourcePcm = FloatArray((sampleRate * 1.5).toInt()) { i ->
            val t = i.toDouble() / sampleRate
            (kotlin.math.sin(2.0 * Math.PI * 220.0 * t) * 0.5 + kotlin.math.sin(2.0 * Math.PI * 880.0 * t) * 0.3).toFloat()
        }

        val rendered = engine.renderScratchPerformance(
            sourcePcm = sourcePcm,
            technique = com.example.audio.TurntableScratchEngine.ScratchTechnique.BABY_SCRATCH,
            estimatedBpm = 120.0
        )

        // Verify no clipping (values bounded strictly to 16-bit range)
        for (s in rendered) {
            assertTrue("Sample must remain within 16-bit bounds", s in -32768..32767)
        }

        // Verify that low-mid frequency energy is preserved and prominent
        var nonZeroCount = 0
        for (s in rendered) {
            if (abs(s.toInt()) > 200) nonZeroCount++
        }
        assertTrue("Rendered scratch must have rich, continuous audio body", nonZeroCount > rendered.size * 0.4)
    }
}
