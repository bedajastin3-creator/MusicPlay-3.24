package com.example.audio

import kotlin.math.*

/**
 * Professional Musical Rhythmic Echo Engine.
 *
 * Implements genuine tempo-synchronized delay processing:
 * 1. Musical note intervals: Dotted 1/8, 1/4, and 1/8 note subdivisions synchronized to track BPM.
 * 2. Analog Tape Damping: High frequencies are smoothly rolled off on each repetition (~2600 Hz filter),
 *    ensuring repetitions become progressively warmer and never clash with advancing vocals.
 * 3. Controlled Feedback: Bounded feedback ratio (~0.52) with soft-saturation limiting ensures each
 *    repeat decays smoothly and naturally without volume spikes or runaway distortion.
 * 4. Natural Tail Decay: When disengaged, the effect gracefully allows the existing delay tail to dissipate
 *    to silence without an abrupt cutoff.
 */
class DspRhythmicEcho(private val sampleRate: Int = 22050) {

    enum class EchoDivision(val beatMultiplier: Double, val displayName: String) {
        DOTTED_EIGHTH(0.75, "Dotted 1/8 Note"),
        QUARTER_NOTE(1.00, "1/4 Note"),
        EIGHTH_NOTE(0.50, "1/8 Note")
    }

    companion object {
        // Up to 1.5 seconds maximum delay time
        private const val MAX_DELAY_SAMPLES = 33075 // 22050 * 1.5
    }

    private val delayBuffer = FloatArray(MAX_DELAY_SAMPLES)
    private var writeIndex = 0

    // Delay parameters
    private var currentDelaySamples = (sampleRate * 0.375).toInt() // Default ~120 BPM dotted 1/8
    private var feedback = 0.52f
    private var dampingState = 0.0

    @Volatile
    var wetMix: Float = 0.0f

    @Volatile
    var isInputMuted: Boolean = false // When true, no new signal is fed into delay (tail decay mode)

    /**
     * Updates the echo timing to match the musical tempo and subdivision.
     */
    fun configureTempo(
        estimatedBpm: Double,
        division: EchoDivision = EchoDivision.DOTTED_EIGHTH,
        feedbackRatio: Float = 0.52f
    ) {
        val clampedBpm = estimatedBpm.coerceIn(70.0, 165.0)
        val beatSec = 60.0 / clampedBpm
        val delaySec = (beatSec * division.beatMultiplier).coerceIn(0.18, 0.95)
        currentDelaySamples = (delaySec * sampleRate).toInt().coerceIn(100, MAX_DELAY_SAMPLES - 1)
        feedback = feedbackRatio.coerceIn(0.20f, 0.65f)
    }

    /**
     * Clears the delay line and resets all filter states.
     */
    fun reset() {
        delayBuffer.fill(0f)
        writeIndex = 0
        dampingState = 0.0
        wetMix = 0.0f
        isInputMuted = false
    }

    /**
     * Processes a single audio sample through the rhythmic echo delay pipeline.
     */
    fun processSample(inputSample: Double): Double {
        if (wetMix <= 0.001f && isInputMuted) {
            return inputSample
        }

        // 1. Read delayed sample from the circular buffer
        var readIndex = writeIndex - currentDelaySamples
        if (readIndex < 0) {
            readIndex += MAX_DELAY_SAMPLES
        }
        val rawDelayed = delayBuffer[readIndex].toDouble()

        // 2. Analog Tape Damping: 1-pole low-pass filter (~2600 Hz rolloff)
        // High frequencies decay faster with each repetition for warmth and natural acoustic dissipation
        val dampingAlpha = 0.55 // cutoff around 2600 Hz at 22050 Hz sample rate
        dampingState += dampingAlpha * (rawDelayed - dampingState)
        val filteredDelayed = dampingState

        // 3. Controlled feedback with soft saturation to avoid runaway resonance or clipping
        val saturatedDelayed = tanh(filteredDelayed * 1.10).toFloat()
        val toWrite = if (!isInputMuted) {
            (inputSample.toFloat() + (saturatedDelayed * feedback)).coerceIn(-1.5f, 1.5f)
        } else {
            (saturatedDelayed * feedback).coerceIn(-1.5f, 1.5f)
        }

        delayBuffer[writeIndex] = toWrite
        writeIndex = (writeIndex + 1) % MAX_DELAY_SAMPLES

        // 4. Smooth Wet/Dry mix
        val wet = wetMix.coerceIn(0f, 0.75f)
        val dry = 1.0f - (wet * 0.25f) // Slight dry attenuation keeps total loudness stable
        val output = (inputSample * dry) + (filteredDelayed * wet)

        return output.coerceIn(-1.0, 1.0)
    }
}
