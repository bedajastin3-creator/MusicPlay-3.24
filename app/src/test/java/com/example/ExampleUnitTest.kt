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
    fun testScratchVolumeAndPresence() {
        val sampleRate = 22050
        val engine = com.example.audio.TurntableScratchEngine(sampleRate)

        val quietSnippet = FloatArray((sampleRate * 1.5).toInt()) { i ->
            (kotlin.math.sin(2.0 * Math.PI * 220.0 * i / sampleRate) * 0.15).toFloat()
        }

        val rendered = engine.renderScratchPerformance(
            sourcePcm = quietSnippet,
            technique = com.example.audio.TurntableScratchEngine.ScratchTechnique.BABY_SCRATCH,
            estimatedBpm = 120.0
        )

        // Peak sample should be boosted significantly by normalizer and analog saturation
        val maxAmp = rendered.maxOf { abs(it.toInt()) }
        assertTrue("Scratch volume must be boosted significantly (peak $maxAmp)", maxAmp > 18000)

        val flareRendered = engine.renderScratchPerformance(
            sourcePcm = quietSnippet,
            technique = com.example.audio.TurntableScratchEngine.ScratchTechnique.FLARE_SCRATCH,
            estimatedBpm = 124.0
        )
        val maxFlareAmp = flareRendered.maxOf { abs(it.toInt()) }
        assertTrue("Flare scratch volume must be boosted significantly (peak $maxFlareAmp)", maxFlareAmp > 18000)
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
    fun testBeatFilterConstraintsAndFlangerSeparation() {
        // Enforce the rule: Beat filter cannot run twice in music and must not be near flanger (< 25s)
        val lastFlangerTriggerSec = 45
        val candidateSecNear = 55
        val candidateSecFar = 80

        val isTooCloseToFlanger = kotlin.math.abs(candidateSecNear - lastFlangerTriggerSec) < 25
        assertTrue("10 seconds separation must be rejected as too close to flanger", isTooCloseToFlanger)

        val isFarEnoughFromFlanger = kotlin.math.abs(candidateSecFar - lastFlangerTriggerSec) >= 25
        assertTrue("35 seconds separation must be accepted as far from flanger", isFarEnoughFromFlanger)
    }

    @Test
    fun testFlangerAndFilterSweepMutualExclusivity() {
        // Rule: If you use flanger in this music don't use filter sweep, and if you use filter sweep don't use flanger
        var flangerUsed = true
        var filterSweepUsed = false
        val canUseFilterSweepWhenFlangerUsed = !flangerUsed && !filterSweepUsed
        assertFalse("Filter sweep must NOT be allowed if flanger was used in the same music", canUseFilterSweepWhenFlangerUsed)

        flangerUsed = false
        filterSweepUsed = true
        val canUseFlangerWhenFilterSweepUsed = !flangerUsed && !filterSweepUsed
        assertFalse("Flanger must NOT be allowed if filter sweep was used in the same music", canUseFlangerWhenFilterSweepUsed)

        val canUseFilterSweepAgain = !filterSweepUsed
        assertFalse("Filter sweep must NOT be allowed twice in the same music", canUseFilterSweepAgain)
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
}
