package com.example.audio

import kotlin.math.*

/**
 * Real-Time Smart Music Awareness & Audio Classifier.
 *
 * Performs local, real-time spectral and temporal analysis on audio buffers to identify:
 * 1. BEAT_ONLY: Drums, percussion, bass rhythm without vocals or speech.
 * 2. BEAT_AND_VOCALS: Singing or vocal lines present alongside the instrumental beat.
 * 3. SPOKEN_VOICE: Talking, speeches, announcements, podcast/intro dialogue (distinguished from singing).
 * 4. ACAPELLA_SINGING: Melodic, harmonic singing without strong drums or percussion (NOT classified as speech).
 * 5. INSTRUMENTAL_MELODIC: Ambient, classical, melodic passages without prominent rhythmic percussion.
 * 6. UNCERTAIN: Transient analysis state or silence.
 *
 * Implements multi-feature extraction (sub-bass beat pulse, vocal formant concentration,
 * pitch autocorrelation / harmonicity, zero-crossing rate variance, and syllabic cadence)
 * with temporal smoothing and confidence thresholds to eliminate classification jitter.
 */
class SmartAudioClassifier(private val sampleRate: Int = 22050) {

    enum class AudioContentType(val displayName: String, val description: String) {
        BEAT_ONLY("Beat Only", "Pure drums, bass, and instrumental rhythm"),
        BEAT_AND_VOCALS("Beat + Vocals", "Singing or vocals alongside rhythm"),
        SPOKEN_VOICE("Spoken Voice", "Speech, announcements, or dialogue"),
        ACAPELLA_SINGING("Acapella Singing", "Melodic vocal harmonies without strong percussion"),
        INSTRUMENTAL_MELODIC("Instrumental", "Melodic passages without strong beat"),
        UNCERTAIN("Analyzing", "Evaluating audio profile")
    }

    data class ClassificationResult(
        val type: AudioContentType,
        val confidence: Float,
        val beatStrength: Float,
        val vocalEnergy: Float,
        val harmonicity: Float,
        val speechCadence: Float
    )

    // History buffer for temporal smoothing and hysteresis
    private val smoothingWindow = ArrayDeque<ClassificationResult>()
    private val windowCapacity = 7

    @Volatile
    var currentClassification: ClassificationResult = ClassificationResult(
        type = AudioContentType.UNCERTAIN,
        confidence = 0.5f,
        beatStrength = 0.5f,
        vocalEnergy = 0.0f,
        harmonicity = 0.0f,
        speechCadence = 0.0f
    )
        private set

    /**
     * Resets classification states when a new track starts.
     */
    fun reset() {
        smoothingWindow.clear()
        currentClassification = ClassificationResult(
            type = AudioContentType.UNCERTAIN,
            confidence = 0.5f,
            beatStrength = 0.5f,
            vocalEnergy = 0.0f,
            harmonicity = 0.0f,
            speechCadence = 0.0f
        )
    }

    fun analyzePcm(pcm: FloatArray): ClassificationResult {
        if (pcm.isEmpty()) return currentClassification
        val shorts = ShortArray(pcm.size) { i ->
            (pcm[i].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
        }
        return analyzePcm(shorts)
    }

    /**
     * Analyzes a PCM buffer (16-bit signed Mono) and updates the smoothed classification.
     */
    fun analyzePcm(pcm: ShortArray): ClassificationResult {
        if (pcm.isEmpty()) return currentClassification

        val raw = classifySingleBuffer(pcm)

        // Add to rolling window for temporal smoothing
        if (smoothingWindow.size >= windowCapacity) {
            smoothingWindow.removeFirst()
        }
        smoothingWindow.addLast(raw)

        // Aggregate counts and average scores
        val counts = mutableMapOf<AudioContentType, Int>()
        var sumConfidence = 0f
        var sumBeat = 0f
        var sumVocal = 0f
        var sumHarmonicity = 0f
        var sumSpeechCadence = 0f

        for (res in smoothingWindow) {
            counts[res.type] = (counts[res.type] ?: 0) + 1
            sumConfidence += res.confidence
            sumBeat += res.beatStrength
            sumVocal += res.vocalEnergy
            sumHarmonicity += res.harmonicity
            sumSpeechCadence += res.speechCadence
        }

        val dominantEntry = counts.maxByOrNull { it.value }
        val smoothedType = if (dominantEntry != null && (dominantEntry.value.toFloat() / smoothingWindow.size) >= 0.55f) {
            dominantEntry.key
        } else {
            raw.type
        }

        val n = smoothingWindow.size.toFloat()
        val smoothedResult = ClassificationResult(
            type = smoothedType,
            confidence = (sumConfidence / n).coerceIn(0f, 1f),
            beatStrength = (sumBeat / n).coerceIn(0f, 1f),
            vocalEnergy = (sumVocal / n).coerceIn(0f, 1f),
            harmonicity = (sumHarmonicity / n).coerceIn(0f, 1f),
            speechCadence = (sumSpeechCadence / n).coerceIn(0f, 1f)
        )

        currentClassification = smoothedResult
        return smoothedResult
    }

    /**
     * Performs single-frame feature extraction and rules-based classification.
     */
    fun classifySingleBuffer(pcm: ShortArray): ClassificationResult {
        val n = pcm.size
        if (n < 256) {
            return ClassificationResult(AudioContentType.UNCERTAIN, 0.4f, 0f, 0f, 0f, 0f)
        }

        // 1. RMS Total Energy & Silence Gate
        var sumSq = 0.0
        var zeroCrossings = 0
        for (i in 0 until n) {
            val s = pcm[i].toDouble()
            sumSq += s * s
            if (i > 0 && ((pcm[i] >= 0 && pcm[i - 1] < 0) || (pcm[i] < 0 && pcm[i - 1] >= 0))) {
                zeroCrossings++
            }
        }
        val totalRms = sqrt(sumSq / n)
        if (totalRms < 400.0) { // Near silence
            return ClassificationResult(AudioContentType.UNCERTAIN, 0.9f, 0f, 0f, 0f, 0f)
        }

        val zcr = zeroCrossings.toDouble() / n

        // 2. Sub-Bass Beat & Rhythm Filter (40 Hz - 130 Hz for kick drum rhythm)
        val bassCutoff = 130.0
        val dt = 1.0 / sampleRate
        val rc = 1.0 / (2.0 * Math.PI * bassCutoff)
        val alpha = dt / (rc + dt)

        var bassFilter = 0.0
        var bassSumSq = 0.0
        var bassPeak = 0.0

        for (i in 0 until n) {
            val sample = pcm[i].toDouble()
            bassFilter += alpha * (sample - bassFilter)
            val bassVal = bassFilter
            bassSumSq += bassVal * bassVal
            val absBass = abs(bassVal)
            if (absBass > bassPeak) bassPeak = absBass
        }
        val bassRms = sqrt(bassSumSq / n)
        val bassRatio = (bassRms / (totalRms + 1e-6)).coerceIn(0.0, 1.0)
        // Crest factor of bass: sharp rhythmic transients (kicks/drums) yield high crest factor (>= 2.5)
        val bassCrest = (bassPeak / (bassRms + 1e-6)).coerceIn(1.0, 8.0)
        val transientCrestFactor = ((bassCrest - 1.4) / 3.0).coerceIn(0.0, 1.0)
        val beatStrength = (bassRatio * (0.35 + 0.65 * transientCrestFactor)).toFloat().coerceIn(0f, 1f)

        // 3. Vocal Energy & Formant Band (180 Hz - 3400 Hz, covering vocal fundamental and formants)
        val vocalLowAlpha = dt / ((1.0 / (2.0 * Math.PI * 180.0)) + dt)
        val vocalHighAlpha = dt / ((1.0 / (2.0 * Math.PI * 3400.0)) + dt)

        var vLow = 0.0
        var vHigh = 0.0
        var vocalSumSq = 0.0

        for (i in 0 until n) {
            val sample = pcm[i].toDouble()
            vLow += vocalLowAlpha * (sample - vLow)
            vHigh += vocalHighAlpha * (sample - vHigh)
            val bandpass = vHigh - vLow
            vocalSumSq += bandpass * bandpass
        }
        val vocalRms = sqrt(vocalSumSq / n)
        val vocalEnergy = (vocalRms / (totalRms + 1e-6)).toFloat().coerceIn(0f, 1f)

        // 4. Harmonicity / Pitch Autocorrelation in Vocal Frequency Range (~125 Hz - 550 Hz)
        // Distinguishes melodic singing (strong harmonic peaks, sustained F0) from speech and low kicks
        val minLag = (sampleRate / 550).coerceAtLeast(15) // ~550 Hz (high soprano)
        val maxLag = (sampleRate / 125).coerceAtMost(n / 2) // ~125 Hz (low tenor/baritone)
        var maxCorr = 0.0
        val step = 2 // Decimate lag search for speed

        var lag = minLag
        while (lag < maxLag) {
            var corrSum = 0.0
            var norm1 = 0.0
            var norm2 = 0.0
            val len = min(n - lag, 600)
            for (k in 0 until len step 2) {
                val s1 = pcm[k].toDouble()
                val s2 = pcm[k + lag].toDouble()
                corrSum += s1 * s2
                norm1 += s1 * s1
                norm2 += s2 * s2
            }
            val norm = sqrt(norm1 * norm2) + 1e-6
            val normCorr = corrSum / norm
            if (normCorr > maxCorr) {
                maxCorr = normCorr
            }
            lag += step
        }
        val harmonicity = maxCorr.toFloat().coerceIn(0f, 1f)

        // 5. Speech Cadence & Syllabic Modulations
        // Speech exhibits rapid zero-crossing fluctuations (fricatives / vowels) and moderate vocal energy with low harmonicity
        val speechCadence = if (harmonicity < 0.42f && vocalEnergy > 0.35f && zcr in 0.08..0.48) {
            ((1.0f - harmonicity) * vocalEnergy * (zcr.toFloat() / 0.30f)).coerceIn(0f, 1f)
        } else {
            0.0f
        }

        // 6. Classification Decision Matrix
        val hasStrongBeat = beatStrength >= 0.40f
        val hasVocalEnergy = vocalEnergy >= 0.35f
        val isMelodicTonal = harmonicity >= 0.45f

        val (type, confidence) = when {
            // Case 1: Strong Beat AND Melodic Vocals present -> BEAT_AND_VOCALS
            hasStrongBeat && hasVocalEnergy && isMelodicTonal -> {
                val conf = (0.70f + ((beatStrength + vocalEnergy + harmonicity) / 3f * 0.28f)).coerceIn(0.70f, 0.98f)
                AudioContentType.BEAT_AND_VOCALS to conf
            }

            // Case 2: Strong Beat without singing vocals -> BEAT_ONLY
            hasStrongBeat -> {
                val conf = (0.75f + (beatStrength * 0.25f)).coerceIn(0.70f, 0.98f)
                AudioContentType.BEAT_ONLY to conf
            }

            // Case 3: Acapella Singing (Melodic vocal singing WITHOUT strong beat)
            // MUST NOT be classified as speech: High harmonicity, sustained tonal pitch
            !hasStrongBeat && isMelodicTonal -> {
                val conf = (0.75f + (harmonicity * 0.23f)).coerceIn(0.70f, 0.98f)
                AudioContentType.ACAPELLA_SINGING to conf
            }

            // Case 4: Spoken Voice (Talking / Outro announcements / Speech)
            // Distinguish from singing: Low harmonicity, clear speech cadence, and absence of strong beat
            !hasStrongBeat && speechCadence >= 0.25f && !isMelodicTonal -> {
                val conf = (0.72f + (speechCadence * 0.26f)).coerceIn(0.70f, 0.95f)
                AudioContentType.SPOKEN_VOICE to conf
            }

            // Case 5: Instrumental without strong beat (ambient, classical, calm melodic breakdown)
            else -> {
                val conf = (0.65f + ((1f - beatStrength) * 0.30f)).coerceIn(0.65f, 0.92f)
                AudioContentType.INSTRUMENTAL_MELODIC to conf
            }
        }

        return ClassificationResult(
            type = type,
            confidence = confidence,
            beatStrength = beatStrength,
            vocalEnergy = vocalEnergy,
            harmonicity = harmonicity,
            speechCadence = speechCadence
        )
    }
}
