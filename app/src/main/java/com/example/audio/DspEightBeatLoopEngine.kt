package com.example.audio

import kotlin.math.*

/**
 * Intelligent DJ 8-Beat Loop Engine.
 *
 * Specializes in executing seamless DJ-style 8-beat phrase loops when pure instrumental rhythm
 * (BEAT_ONLY) is identified in the song.
 *
 * Characteristics:
 * 1. Tempo Synchronization: Exact 8-beat length computed from BPM:
 *    - 120 BPM -> 4.000s
 *    - 140 BPM -> 3.429s
 *    - 160 BPM -> 3.000s
 * 2. Seamless Boundary Crossfades: Applies 10ms micro-crossfades across loop wraps to eliminate
 *    any audible clicks, pops, or DC offsets.
 * 3. 2-Cycle DJ Performance: Plays the 8-beat loop twice consecutively before smoothly releasing
 *    back to the advancing track.
 * 4. Zero Disruption: Never pauses or seeks the main player back to 0:00; acts as a live DJ layer.
 */
class DspEightBeatLoopEngine(private val sampleRate: Int = 22050) {

    /**
     * Calculates the exact duration in seconds for an 8-beat musical segment.
     */
    fun calculateEightBeatDurationSec(bpm: Double): Double {
        val clampedBpm = bpm.coerceIn(70.0, 180.0)
        val singleBeatSec = 60.0 / clampedBpm
        return singleBeatSec * 8.0
    }

    fun renderEightBeatLoop(
        sourcePcm: FloatArray,
        estimatedBpm: Double = 120.0,
        repeatCount: Int = 2
    ): ShortArray {
        val shorts = ShortArray(sourcePcm.size) { i ->
            (sourcePcm[i].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
        }
        return renderEightBeatLoop(shorts, estimatedBpm, repeatCount)
    }

    /**
     * Renders an 8-beat loop performance buffer from a source audio snippet.
     * Repeats the 8-beat segment [repeatCount] times (default 2) with smooth micro-crossfades.
     */
    fun renderEightBeatLoop(
        sourcePcm: ShortArray,
        estimatedBpm: Double = 120.0,
        repeatCount: Int = 2
    ): ShortArray {
        val loopSec = calculateEightBeatDurationSec(estimatedBpm)
        val loopSamples = (loopSec * sampleRate).toInt().coerceAtLeast(sampleRate)

        // If source snippet is shorter than needed, extrapolate or pad cleanly
        val baseSegment = ShortArray(loopSamples)
        if (sourcePcm.size >= loopSamples) {
            System.arraycopy(sourcePcm, 0, baseSegment, 0, loopSamples)
        } else {
            for (i in 0 until loopSamples) {
                baseSegment[i] = if (sourcePcm.isNotEmpty()) sourcePcm[i % sourcePcm.size] else 0
            }
        }

        // Apply 10ms boundary smoothing at head and tail of the single loop
        val crossfadeSamples = (sampleRate * 0.010).toInt().coerceAtLeast(32)
        for (i in 0 until crossfadeSamples) {
            val t = i.toDouble() / crossfadeSamples
            val startWeight = sin(t * Math.PI * 0.5)
            val endWeight = cos(t * Math.PI * 0.5)

            // Blend head with tail for circular seamlessness
            val headVal = baseSegment[i].toDouble()
            val tailVal = baseSegment[loopSamples - crossfadeSamples + i].toDouble()
            val blended = (headVal * startWeight) + (tailVal * endWeight)
            baseSegment[i] = blended.toInt().coerceIn(-32768, 32767).toShort()
            baseSegment[loopSamples - crossfadeSamples + i] = blended.toInt().coerceIn(-32768, 32767).toShort()
        }

        // Generate consecutive repetitions (repeatCount times, default 2)
        val totalOutputSamples = loopSamples * repeatCount
        val output = ShortArray(totalOutputSamples)

        for (r in 0 until repeatCount) {
            val offset = r * loopSamples
            System.arraycopy(baseSegment, 0, output, offset, loopSamples)

            // Internal seam micro-crossfade between repeats
            if (r > 0) {
                val seamStart = offset - crossfadeSamples / 2
                for (k in 0 until crossfadeSamples) {
                    val idx = seamStart + k
                    if (idx in 0 until totalOutputSamples) {
                        val fadeT = k.toDouble() / crossfadeSamples
                        val inGain = sin(fadeT * Math.PI * 0.5)
                        val outGain = cos(fadeT * Math.PI * 0.5)
                        val sPrev = output[idx].toDouble()
                        val sNext = baseSegment[k % loopSamples].toDouble()
                        output[idx] = ((sPrev * outGain) + (sNext * inGain)).toInt().coerceIn(-32768, 32767).toShort()
                    }
                }
            }
        }

        // Smooth entry (15ms fade-in) and exit (25ms fade-out) envelopes
        val fadeIn = (sampleRate * 0.015).toInt()
        for (i in 0 until fadeIn) {
            val gain = i.toDouble() / fadeIn
            output[i] = (output[i] * gain).toInt().toShort()
        }

        val fadeOut = (sampleRate * 0.025).toInt()
        val fadeOutStart = totalOutputSamples - fadeOut
        for (i in 0 until fadeOut) {
            val gain = 1.0 - (i.toDouble() / fadeOut)
            output[fadeOutStart + i] = (output[fadeOutStart + i] * gain).toInt().toShort()
        }

        return output
    }
}
