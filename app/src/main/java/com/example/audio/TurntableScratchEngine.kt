package com.example.audio

import kotlin.math.*

/**
 * Authentic Turntable Scratch Performance Engine.
 * 
 * Simulates physical vinyl record manipulation by a professional turntablist:
 * 1. Baby Scratch: Pure vinyl movement by the record hand (forward & backward continuous rub,
 *    no crossfader cuts, smooth speed curves, dynamic pitch shifting).
 * 2. Flare Scratch: Advanced scratch technique where the record is pushed forward and backward
 *    WHILE the DJ rapidly clicks/cuts the crossfader, creating sharp rhythmic chops and multiple
 *    percussive bursts per stroke.
 *
 * Both techniques operate directly on the real song's PCM audio buffer, giving authentic
 * scratching that sounds integrated with the user's music.
 */
class TurntableScratchEngine(private val sampleRate: Int = 22050) {

    enum class ScratchTechnique {
        BABY_SCRATCH,
        FLARE_SCRATCH
    }

    /**
     * Generates a complete 2-phrase scratch performance:
     * - Phrase 1: 4 rhythmic scratch movements
     * - Intermission: ~1.0 second pause (allowing the music beat to breathe)
     * - Phrase 2: 4 rhythmic scratch movements
     *
     * Returns 16-bit PCM ShortArray ready for playback.
     */
    fun renderScratchPerformance(
        sourcePcm: FloatArray,
        technique: ScratchTechnique,
        estimatedBpm: Double = 120.0
    ): ShortArray {
        if (sourcePcm.isEmpty()) {
            return ShortArray(0)
        }

        // Tempo-aligned movement duration (approx 16th or 8th note duration at current BPM)
        val beatSec = (60.0 / estimatedBpm.coerceIn(75.0, 160.0))
        val strokeDurationSec = (beatSec * 0.45).coerceIn(0.20, 0.38)
        val pauseDurationSec = 0.95 // ~1 second pause between the two 4-movement groups

        val samplesPerStroke = (sampleRate * strokeDurationSec).toInt()
        val pauseSamples = (sampleRate * pauseDurationSec).toInt()

        // 4 strokes + pause + 4 strokes = 8 strokes total
        val totalSamples = (samplesPerStroke * 8) + pauseSamples
        val outPcm = ShortArray(totalSamples)

        var writeIdx = 0

        // Performance Group 1: 4 rhythmic scratch movements
        for (movement in 0 until 4) {
            val strokePcm = renderSingleMovement(
                sourcePcm = sourcePcm,
                technique = technique,
                movementIndex = movement,
                durationSamples = samplesPerStroke
            )
            for (s in strokePcm) {
                if (writeIdx < totalSamples) outPcm[writeIdx++] = s
            }
        }

        // Intermission: ~1.0 second pause
        for (p in 0 until pauseSamples) {
            if (writeIdx < totalSamples) outPcm[writeIdx++] = 0
        }

        // Performance Group 2: 4 rhythmic scratch movements (with slight rhythmic variation)
        for (movement in 0 until 4) {
            val strokePcm = renderSingleMovement(
                sourcePcm = sourcePcm,
                technique = technique,
                movementIndex = movement + 4,
                durationSamples = samplesPerStroke
            )
            for (s in strokePcm) {
                if (writeIdx < totalSamples) outPcm[writeIdx++] = s
            }
        }

        return outPcm
    }

    /**
     * Renders a single forward-backward turntable movement.
     */
    private fun renderSingleMovement(
        sourcePcm: FloatArray,
        technique: ScratchTechnique,
        movementIndex: Int,
        durationSamples: Int
    ): ShortArray {
        val out = ShortArray(durationSamples)
        val halfDuration = durationSamples / 2
        val sourceLen = sourcePcm.size

        // Anchor position inside source buffer (moves slightly between movements for realism)
        val baseAnchor = (sourceLen * 0.25 + (movementIndex % 4) * (sourceLen * 0.12)).toInt().coerceIn(0, sourceLen - 1)
        val maxTravelSamples = (sourceLen * 0.18).toInt().coerceAtLeast(300)

        // Low-pass filter state for needle friction physics
        var lpState = 0.0

        for (i in 0 until durationSamples) {
            val isForward = (i < halfDuration)
            val strokeProgress = if (isForward) {
                i.toDouble() / halfDuration.toDouble()
            } else {
                (i - halfDuration).toDouble() / (durationSamples - halfDuration).toDouble()
            }

            // 1. Turntable velocity curve v(t)
            // Forward moves from 0 to +maxTravel, Reverse moves back from +maxTravel to 0
            val travel = if (isForward) {
                // Smooth forward motion: sin(t * pi/2)^1.3
                sin(strokeProgress * (Math.PI / 2.0)).pow(1.3) * maxTravelSamples
            } else {
                // Smooth reverse motion: cos(t * pi/2)^1.3
                cos(strokeProgress * (Math.PI / 2.0)).pow(1.3) * maxTravelSamples
            }

            val readPos = (baseAnchor + travel).coerceIn(0.0, (sourceLen - 2).toDouble())

            // 2. Fractional interpolation to eliminate aliasing and clicks
            val idx0 = readPos.toInt()
            val idx1 = (idx0 + 1).coerceAtMost(sourceLen - 1)
            val frac = (readPos - idx0).toFloat()
            val rawSample = sourcePcm[idx0] * (1f - frac) + sourcePcm[idx1] * frac

            // 3. Physical Vinyl Dynamics & Velocity-dependent Filtering
            // Velocity magnitude affects high-frequency brightness & needle friction
            val velNorm = abs(sin(strokeProgress * Math.PI)).coerceIn(0.05, 1.0)
            val cutoffAlpha = (0.20 + 0.65 * velNorm).coerceIn(0.10, 0.90)
            lpState += cutoffAlpha * (rawSample - lpState)

            // Vinyl surface friction noise
            val vinylHiss = (Math.random() * 2.0 - 1.0) * 0.03 * velNorm
            var processed = (lpState * 1.15 + vinylHiss)

            // 4. CROSSFADER GATING (The defining distinction between Baby and Flare)
            val faderGate = when (technique) {
                ScratchTechnique.BABY_SCRATCH -> {
                    // Baby scratch: Crossfader remains 100% OPEN.
                    // Only natural envelope at turnaround/edges to prevent pops.
                    val edgeFade = sin(strokeProgress * Math.PI).pow(0.35)
                    edgeFade
                }

                ScratchTechnique.FLARE_SCRATCH -> {
                    // Flare scratch: Fader starts OPEN and DJ rapidly CLICKS it closed in the middle!
                    // In a 1-click / 2-click flare, the cut splits the movement into distinct bursts.
                    computeFlareFaderGate(strokeProgress, isForward, movementIndex)
                }
            }

            processed *= faderGate

            // Soft saturation limiter to keep sound punchy without clipping
            val saturated = tanh(processed * 1.25) * 0.95
            out[i] = (saturated * 31500.0).toInt().coerceIn(-32768, 32767).toShort()
        }

        return out
    }

    /**
     * Simulates professional DJ crossfader clicks characteristic of Flare Scratching.
     * Fast fader cut (< 15-20ms) with anti-click 2ms micro-ramp.
     */
    private fun computeFlareFaderGate(
        progress: Double,
        isForward: Boolean,
        movementIndex: Int
    ): Double {
        // Vary between 1-Click Flare (movement % 2 == 0) and 2-Click Flare (movement % 2 == 1)
        val isTwoClick = (movementIndex % 2 == 1)

        val cutCenter1 = if (isForward) 0.42 else 0.48
        val cutWidth = 0.12 // Cut width in normalized stroke time (~25ms)

        if (!isTwoClick) {
            // 1-Click Flare: 1 fader cut in the middle -> splits stroke into 2 sounds
            val dist = abs(progress - cutCenter1)
            return if (dist < cutWidth / 2.0) {
                // Crossfader is closed (micro ramp at edges)
                val ramp = (dist / (cutWidth / 2.0)).pow(2.0)
                ramp.coerceIn(0.0, 1.0)
            } else {
                1.0 // Fader open
            }
        } else {
            // 2-Click Flare: 2 rapid fader cuts -> splits stroke into 3 distinct chirps!
            val cutCenter2 = if (isForward) 0.68 else 0.72
            val dist1 = abs(progress - cutCenter1)
            val dist2 = abs(progress - cutCenter2)
            val halfW = cutWidth * 0.42

            return when {
                dist1 < halfW -> (dist1 / halfW).pow(2.0).coerceIn(0.0, 1.0)
                dist2 < halfW -> (dist2 / halfW).pow(2.0).coerceIn(0.0, 1.0)
                else -> 1.0
            }
        }
    }
}
