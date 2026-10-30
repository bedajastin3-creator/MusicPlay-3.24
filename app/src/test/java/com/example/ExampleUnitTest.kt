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

    @Test
    fun testEightBeatLoopBpmDurationAndRendering() {
        val sampleRate = 22050
        val loopEngine = com.example.audio.DspEightBeatLoopEngine(sampleRate)

        // 120 BPM: 8 beats = 8 * (60 / 120) = 4.0 seconds
        val dur120 = loopEngine.calculateEightBeatDurationSec(120.0)
        assertEquals(4.0, dur120, 0.001)

        // 140 BPM: 8 beats = 8 * (60 / 140) ~ 3.42857 seconds
        val dur140 = loopEngine.calculateEightBeatDurationSec(140.0)
        assertEquals(3.42857, dur140, 0.001)

        // 160 BPM: 8 beats = 8 * (60 / 160) = 3.0 seconds
        val dur160 = loopEngine.calculateEightBeatDurationSec(160.0)
        assertEquals(3.0, dur160, 0.001)

        // Generate synthetic source PCM
        val sourcePcm = ShortArray((sampleRate * 4.5).toInt()) { i ->
            (kotlin.math.sin(2.0 * Math.PI * 110.0 * i / sampleRate) * 16000.0).toInt().toShort()
        }

        // Render 2 cycles of 8-beat loop at 120 BPM (expected 8.0 seconds total = 176400 samples)
        val renderedLoop = loopEngine.renderEightBeatLoop(sourcePcm, 120.0, repeatCount = 2)
        val expectedSamples = (4.0 * sampleRate * 2).toInt()
        assertEquals(expectedSamples, renderedLoop.size)
        assertTrue("Loop buffer must have audible samples", renderedLoop.any { it != 0.toShort() })
    }

    @Test
    fun testSmartAudioClassifierBeatOnly() {
        val sampleRate = 22050
        val classifier = com.example.audio.SmartAudioClassifier(sampleRate)

        // Create heavy rhythmic bass beat kick pulses (60 Hz kicks with high transient crest factor, no vocals)
        val beatPcm = ShortArray(sampleRate * 1) { i ->
            val phase = (i % (sampleRate / 2)).toDouble() / (sampleRate / 2) // 2 kicks per sec
            val env = kotlin.math.exp(-phase * 12.0)
            (kotlin.math.sin(2.0 * Math.PI * 65.0 * i / sampleRate) * env * 26000.0).toInt().toShort()
        }

        val result = classifier.classifySingleBuffer(beatPcm)
        assertTrue("Beat strength must be detected as strong", result.beatStrength >= 0.40f)
        assertEquals(com.example.audio.SmartAudioClassifier.AudioContentType.BEAT_ONLY, result.type)
    }

    @Test
    fun testSmartAudioClassifierAcapellaSingingVersusSpeech() {
        val sampleRate = 22050
        val classifier = com.example.audio.SmartAudioClassifier(sampleRate)

        // Melodic singing: sustained F0 at 260 Hz with integer harmonics (520 Hz, 780 Hz) and vibrato
        val acapellaPcm = ShortArray(sampleRate * 1) { i ->
            val t = i.toDouble() / sampleRate
            val vibrato = kotlin.math.sin(2.0 * Math.PI * 5.0 * t) * 6.0
            val f0 = 260.0 + vibrato
            val s1 = kotlin.math.sin(2.0 * Math.PI * f0 * t) * 0.6
            val s2 = kotlin.math.sin(2.0 * Math.PI * (f0 * 2.0) * t) * 0.3
            val s3 = kotlin.math.sin(2.0 * Math.PI * (f0 * 3.0) * t) * 0.1
            ((s1 + s2 + s3) * 20000.0).toInt().toShort()
        }

        val acapellaResult = classifier.classifySingleBuffer(acapellaPcm)
        assertTrue("Acapella must have high harmonicity", acapellaResult.harmonicity >= 0.45f)
        assertEquals(
            "Melodic singing must NOT be classified as speech",
            com.example.audio.SmartAudioClassifier.AudioContentType.ACAPELLA_SINGING,
            acapellaResult.type
        )
    }

    @Test
    fun testDspRhythmicEchoRenderBufferOutput() {
        val sampleRate = 22050
        val echo = com.example.audio.DspRhythmicEcho(sampleRate)

        val snippet = ShortArray(sampleRate * 2) { i ->
            (kotlin.math.sin(2.0 * Math.PI * 440.0 * i / sampleRate) * 15000.0).toInt().toShort()
        }

        val echoBuffer = echo.renderEchoBuffer(snippet, estimatedBpm = 120.0)
        // Verify buffer extends beyond snippet size for decay tail (~1.8 seconds extra)
        val expectedMinSamples = snippet.size + (sampleRate * 1.5).toInt()
        assertTrue("Echo buffer must contain decay tail samples", echoBuffer.size >= expectedMinSamples)

        // Verify audio content is audible and bounded
        assertTrue("Echo buffer must contain audible samples", echoBuffer.any { it != 0.toShort() })
        for (s in echoBuffer) {
            assertTrue(s in -32768..32767)
        }
    }
}
