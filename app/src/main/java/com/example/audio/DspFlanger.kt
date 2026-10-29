package com.example.audio

import kotlin.math.*

/**
 * Real-Time DSP Flanger Effect Engine.
 * 
 * Implements a genuine short-delay modulated comb filter:
 * 1. Modulated delay line (0.6ms to 6.5ms delay sweep)
 * 2. LFO (Low-Frequency Oscillator) synchronized with track BPM/tempo
 * 3. Controlled resonance feedback (0.52) creating iconic sweeping jet-plane comb filter peaks
 * 4. Wet/Dry automation envelope (smooth fade-in -> deep sweep -> smooth fade-out)
 * 5. Fractional delay interpolation (linear) to prevent phase quantization clicks or distortion
 */
class DspFlanger(private val sampleRate: Int = 22050) {

    companion object {
        private const val DELAY_BUFFER_SIZE = 1024
    }

    private val delayBuffer = FloatArray(DELAY_BUFFER_SIZE)
    private var writeIndex = 0

    // DSP Parameters
    private var lfoPhase = 0.0
    private val minDelayMs = 0.8
    private val maxDelayMs = 6.0
    private val feedback = 0.52f

    @Volatile
    var wetMix: Float = 0.0f

    @Volatile
    var lfoFrequencyHz: Double = 0.22

    /**
     * Resets the internal delay buffer and phase.
     */
    fun reset() {
        delayBuffer.fill(0f)
        writeIndex = 0
        lfoPhase = 0.0
        wetMix = 0f
    }

    /**
     * Processes a single audio sample through the real-time flanger DSP pipeline.
     */
    fun processSample(inputSample: Double): Double {
        if (wetMix <= 0.001f) {
            return inputSample
        }

        // 1. Advance LFO phase
        val phaseDelta = (2.0 * Math.PI * lfoFrequencyHz) / sampleRate
        lfoPhase += phaseDelta
        if (lfoPhase >= 2.0 * Math.PI) {
            lfoPhase -= 2.0 * Math.PI
        }

        // 2. Modulate delay length between minDelayMs and maxDelayMs
        val lfoNorm = (sin(lfoPhase) + 1.0) * 0.5 // 0.0 to 1.0
        val currentDelayMs = minDelayMs + (maxDelayMs - minDelayMs) * lfoNorm
        val delaySamples = (currentDelayMs * sampleRate / 1000.0).coerceIn(4.0, (DELAY_BUFFER_SIZE - 4).toDouble())

        // 3. Fractional delay reading using linear interpolation
        var readPos = writeIndex.toDouble() - delaySamples
        while (readPos < 0.0) {
            readPos += DELAY_BUFFER_SIZE
        }

        val i0 = readPos.toInt() % DELAY_BUFFER_SIZE
        val i1 = (i0 + 1) % DELAY_BUFFER_SIZE
        val frac = (readPos - readPos.toInt()).toFloat()

        val delayedSample = delayBuffer[i0] * (1.0f - frac) + delayBuffer[i1] * frac

        // 4. Feedback with soft saturation to avoid runaway resonance
        val saturatedDelayed = tanh(delayedSample * 1.15).toFloat()
        val toWrite = (inputSample.toFloat() + saturatedDelayed * feedback).coerceIn(-1.5f, 1.5f)

        delayBuffer[writeIndex] = toWrite
        writeIndex = (writeIndex + 1) % DELAY_BUFFER_SIZE

        // 5. Wet/Dry mix
        val wet = wetMix.coerceIn(0f, 1f)
        val dry = 1.0f - (wet * 0.45f) // Slight dry attenuation during deep wet mix for contrast
        val output = (inputSample * dry) + (delayedSample * wet)

        return output.coerceIn(-1.0, 1.0)
    }

    /**
     * Processes a full buffer of audio (e.g. 5-7 seconds) with automated fade-in and fade-out.
     */
    fun renderFlangedBuffer(
        sourcePcm: FloatArray,
        estimatedBpm: Double = 120.0
    ): ShortArray {
        reset()
        val totalSamples = sourcePcm.size
        val outPcm = ShortArray(totalSamples)

        // Set LFO frequency so 1 sweep cycle matches 2 to 4 musical bars
        val beatSec = (60.0 / estimatedBpm.coerceIn(75.0, 160.0))
        val barSec = beatSec * 4.0
        lfoFrequencyHz = (1.0 / (barSec * 1.5)).coerceIn(0.12, 0.32)

        val fadeInSamples = (sampleRate * 1.5).toInt().coerceAtMost(totalSamples / 4)
        val fadeOutSamples = (sampleRate * 1.5).toInt().coerceAtMost(totalSamples / 4)
        val sustainStart = fadeInSamples
        val sustainEnd = totalSamples - fadeOutSamples

        for (i in 0 until totalSamples) {
            // Smooth automation envelope
            wetMix = when {
                i < sustainStart -> (i.toFloat() / fadeInSamples) * 0.75f
                i > sustainEnd -> {
                    val progress = (totalSamples - i).toFloat() / fadeOutSamples
                    progress * 0.75f
                }
                else -> 0.75f
            }

            val raw = sourcePcm[i].toDouble()
            val flanged = processSample(raw)
            outPcm[i] = (flanged * 32000.0).toInt().coerceIn(-32768, 32767).toShort()
        }

        reset()
        return outPcm
    }
}
