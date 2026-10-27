package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.media.audiofx.Equalizer
import android.media.audiofx.PresetReverb
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * Executes real-time DJ audio effects on the active playback session.
 * 
 * Supports:
 * 1. Authentic Vinyl Scratch bursts (via low-latency SoundPool + transient ducking)
 * 2. Equalizer Filter Sweeps (smoothly swept frequency attenuation and opening)
 * 3. Rhythmic Beat-Filtering (syncopated tonal frequency modulation)
 * 4. Radio / Vocal Filter (vintage bandpass filtering)
 * 5. Spatial Echo and Echo-Out (spatial acoustic reverb tail with smooth decay)
 * 6. Turntable Spin-Up (subtle pitch acceleration on track start)
 *
 * Guarantees:
 * - Never modifies music files on disk
 * - Never interferes with or modifies the existing crossfade implementation
 * - Preserves and restores user's base Equalizer, Reverb, Pitch, and Volume settings
 * - Safe fallback if hardware audio effects are unavailable
 */
class DjAudioProcessor(private val context: Context) {

    companion object {
        private const val TAG = "DjAudioProcessor"
        private const val SAMPLE_RATE = 22050
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var activeJob: Job? = null

    // Low-latency SoundPool for authentic vinyl scratch bursts
    private var soundPool: SoundPool? = null
    private var scratchSoundIds = IntArray(3) { -1 }
    private var isSoundPoolReady = false

    // State tracking for baseline restoration
    @Volatile
    var isEffectActive: Boolean = false
        private set

    @Volatile
    var activeEffectType: DjEffectType? = null
        private set

    init {
        initSoundPool()
    }

    private fun initSoundPool() {
        scope.launch {
            try {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()

                soundPool = SoundPool.Builder()
                    .setMaxStreams(3)
                    .setAudioAttributes(attrs)
                    .build()

                // Generate or load 3 distinct authentic DJ scratch waveforms into cache
                val scratchFiles = listOf(
                    generateScratchWav("dj_scratch_chirp.wav", ScratchStyle.CHIRP),
                    generateScratchWav("dj_scratch_baby.wav", ScratchStyle.BABY),
                    generateScratchWav("dj_scratch_spin.wav", ScratchStyle.BACKSPIN)
                )

                soundPool?.let { pool ->
                    for (i in scratchFiles.indices) {
                        val file = scratchFiles[i]
                        if (file != null && file.exists()) {
                            scratchSoundIds[i] = pool.load(file.absolutePath, 1)
                        }
                    }
                    pool.setOnLoadCompleteListener { _, _, status ->
                        if (status == 0) {
                            isSoundPoolReady = true
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "SoundPool initialization fallback: ${e.message}")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Effect Execution Dispatcher
    // ─────────────────────────────────────────────────────────────────────────────

    fun triggerEffect(
        effect: DjEffectType,
        mediaPlayer: MediaPlayer?,
        equalizer: Equalizer?,
        reverb: PresetReverb?,
        baseEqGains: FloatArray,
        baseReverbPreset: String,
        basePitch: Float,
        onVolumeDuck: ((Float) -> Unit)? = null,
        onComplete: () -> Unit
    ) {
        // Cancel any previous effect before starting a new one
        cancelActiveEffect(equalizer, reverb, baseEqGains, baseReverbPreset, mediaPlayer, basePitch, onVolumeDuck)

        isEffectActive = true
        activeEffectType = effect

        activeJob = scope.launch {
            try {
                when (effect) {
                    DjEffectType.VINYL_SCRATCH -> {
                        executeVinylScratch(onVolumeDuck)
                    }
                    DjEffectType.FILTER_SWEEP -> {
                        executeFilterSweep(equalizer, baseEqGains)
                    }
                    DjEffectType.BEAT_FILTER -> {
                        executeBeatFilter(equalizer, baseEqGains)
                    }
                    DjEffectType.RADIO_FILTER -> {
                        executeRadioFilter(equalizer, baseEqGains)
                    }
                    DjEffectType.ECHO_OUT -> {
                        executeEchoOut(reverb, baseReverbPreset, onVolumeDuck)
                    }
                    DjEffectType.VINYL_SPIN_UP -> {
                        executeVinylSpinUp(mediaPlayer, basePitch, onVolumeDuck)
                    }
                }
            } catch (e: CancellationException) {
                // Cancelled cleanly (e.g. track change, seek, switch OFF)
            } catch (e: Throwable) {
                Log.e(TAG, "Error executing DJ effect $effect: ${e.message}")
            } finally {
                // Guaranteed restoration of baseline audio states
                restoreBaseAudioStates(equalizer, reverb, baseEqGains, baseReverbPreset, mediaPlayer, basePitch, onVolumeDuck)
                isEffectActive = false
                activeEffectType = null
                withContext(Dispatchers.Main) {
                    onComplete()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 1. Vinyl Scratch Implementation
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun executeVinylScratch(onVolumeDuck: ((Float) -> Unit)?) {
        val soundIndex = (0..2).random()
        val soundId = scratchSoundIds.getOrNull(soundIndex) ?: -1

        // Brief, subtle ducking of underlying track by ~2.5dB so scratch cuts through cleanly
        onVolumeDuck?.invoke(0.72f)

        if (isSoundPoolReady && soundId > 0 && soundPool != null) {
            try {
                soundPool?.play(soundId, 0.70f, 0.70f, 1, 0, 1.0f)
            } catch (_: Throwable) {}
        }

        // Scratch duration is controlled and brief (400ms - 550ms)
        delay(480)

        // Smooth volume restore
        onVolumeDuck?.invoke(0.88f)
        delay(60)
        onVolumeDuck?.invoke(1.0f)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 2. Filter Sweep Implementation
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun executeFilterSweep(equalizer: Equalizer?, baseGains: FloatArray) {
        if (equalizer == null) {
            delay(1500)
            return
        }

        val numBands = min(equalizer.numberOfBands.toInt(), baseGains.size).coerceAtLeast(1)
        val range = equalizer.bandLevelRange
        val minMb = range[0].toInt()
        val maxMb = range[1].toInt()

        // Filter sweep target: Attenuate high frequencies (bands 3 & 4), slight warmth on bass
        // Sweep in: 1200ms, Hold: 400ms, Sweep out: 1400ms
        val totalStepsIn = 35
        for (step in 1..totalStepsIn) {
            val progress = step.toFloat() / totalStepsIn
            // Ease-in curve
            val eased = progress * progress
            for (band in 0 until numBands) {
                val baseDb = baseGains[band]
                val targetDelta = when (band) {
                    numBands - 1 -> -11f // Highest treble
                    numBands - 2 -> -7f  // High-mid
                    0 -> 2f              // Low bass warmth
                    else -> -2f
                }
                val currentDb = baseDb + (targetDelta * eased)
                val mb = (currentDb * 100).toInt().coerceIn(minMb, maxMb).toShort()
                try { equalizer.setBandLevel(band.toShort(), mb) } catch (_: Throwable) {}
            }
            delay(32)
        }

        delay(400) // Brief plateau

        // Smooth filter sweep back to normal base gains
        val totalStepsOut = 40
        for (step in 1..totalStepsOut) {
            val progress = step.toFloat() / totalStepsOut
            val eased = 1.0f - cos(progress * Math.PI.toFloat() * 0.5f) // Smooth cosine release
            for (band in 0 until numBands) {
                val baseDb = baseGains[band]
                val targetDelta = when (band) {
                    numBands - 1 -> -11f
                    numBands - 2 -> -7f
                    0 -> 2f
                    else -> -2f
                }
                val currentDb = baseDb + (targetDelta * (1f - eased))
                val mb = (currentDb * 100).toInt().coerceIn(minMb, maxMb).toShort()
                try { equalizer.setBandLevel(band.toShort(), mb) } catch (_: Throwable) {}
            }
            delay(35)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 3. Beat-Filtering Implementation
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun executeBeatFilter(equalizer: Equalizer?, baseGains: FloatArray) {
        if (equalizer == null) {
            delay(1600)
            return
        }

        val numBands = min(equalizer.numberOfBands.toInt(), baseGains.size).coerceAtLeast(1)
        val range = equalizer.bandLevelRange
        val minMb = range[0].toInt()
        val maxMb = range[1].toInt()

        // 3 syncopated rhythmic filter dips (e.g. 500ms per pulse)
        repeat(3) { pulse ->
            val dipSteps = 8
            for (s in 1..dipSteps) {
                val t = s.toFloat() / dipSteps
                val dipAmount = sin(t * Math.PI.toFloat()) * -8.0f
                for (b in (numBands / 2) until numBands) {
                    val currentDb = baseGains[b] + dipAmount
                    val mb = (currentDb * 100).toInt().coerceIn(minMb, maxMb).toShort()
                    try { equalizer.setBandLevel(b.toShort(), mb) } catch (_: Throwable) {}
                }
                delay(30)
            }
            delay(120) // Gap between rhythmic pulses
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 4. Radio / Vocal Filter Implementation
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun executeRadioFilter(equalizer: Equalizer?, baseGains: FloatArray) {
        if (equalizer == null) {
            delay(2500)
            return
        }

        val numBands = min(equalizer.numberOfBands.toInt(), baseGains.size).coerceAtLeast(1)
        val range = equalizer.bandLevelRange
        val minMb = range[0].toInt()
        val maxMb = range[1].toInt()

        // Smooth transition into radio filter (telephone bandpass)
        val inSteps = 15
        for (step in 1..inSteps) {
            val progress = step.toFloat() / inSteps
            for (band in 0 until numBands) {
                val baseDb = baseGains[band]
                val targetDelta = when (band) {
                    0 -> -11f // Cut sub-bass
                    1 -> -8f  // Cut low-mid
                    2 -> 6f   // Boost telephone mid
                    3 -> 2f
                    else -> -9f // Cut extreme treble
                }
                val currentDb = baseDb + (targetDelta * progress)
                val mb = (currentDb * 100).toInt().coerceIn(minMb, maxMb).toShort()
                try { equalizer.setBandLevel(band.toShort(), mb) } catch (_: Throwable) {}
            }
            delay(30)
        }

        // Hold vintage radio tone for 2.2 seconds
        delay(2200)

        // Smooth glide back to clean sound
        val outSteps = 25
        for (step in 1..outSteps) {
            val progress = step.toFloat() / outSteps
            val rem = 1f - progress
            for (band in 0 until numBands) {
                val baseDb = baseGains[band]
                val targetDelta = when (band) {
                    0 -> -11f
                    1 -> -8f
                    2 -> 6f
                    3 -> 2f
                    else -> -9f
                }
                val currentDb = baseDb + (targetDelta * rem)
                val mb = (currentDb * 100).toInt().coerceIn(minMb, maxMb).toShort()
                try { equalizer.setBandLevel(band.toShort(), mb) } catch (_: Throwable) {}
            }
            delay(32)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 5. Echo-Out Implementation
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun executeEchoOut(
        reverb: PresetReverb?,
        basePreset: String,
        onVolumeDuck: ((Float) -> Unit)?
    ) {
        try {
            // Engage expansive spatial reverb tail
            reverb?.preset = PresetReverb.PRESET_LARGEHALL
        } catch (_: Throwable) {}

        // Gentle spatial decay over 2.4 seconds
        delay(1200)
        onVolumeDuck?.invoke(0.90f)
        delay(600)
        onVolumeDuck?.invoke(0.95f)
        delay(600)
        onVolumeDuck?.invoke(1.0f)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 6. Turntable Spin-Up Implementation
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun executeVinylSpinUp(
        mediaPlayer: MediaPlayer?,
        basePitch: Float,
        onVolumeDuck: ((Float) -> Unit)?
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && mediaPlayer != null) {
            try {
                // Short needle touch
                onVolumeDuck?.invoke(0.85f)
                val params = mediaPlayer.playbackParams
                val startPitch = (basePitch * 0.86f).coerceIn(0.5f, 2.0f)
                params.pitch = startPitch
                mediaPlayer.playbackParams = params

                val steps = 14
                for (s in 1..steps) {
                    val p = s.toFloat() / steps
                    val currentPitch = startPitch + ((basePitch - startPitch) * p)
                    params.pitch = currentPitch
                    try { mediaPlayer.playbackParams = params } catch (_: Throwable) {}
                    delay(35)
                }
            } catch (_: Throwable) {}
        } else {
            delay(500)
        }
        onVolumeDuck?.invoke(1.0f)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Baseline Restoration & Clean State Management
    // ─────────────────────────────────────────────────────────────────────────────

    fun cancelActiveEffect(
        equalizer: Equalizer?,
        reverb: PresetReverb?,
        baseEqGains: FloatArray,
        baseReverbPreset: String,
        mediaPlayer: MediaPlayer?,
        basePitch: Float,
        onVolumeDuck: ((Float) -> Unit)? = null
    ) {
        activeJob?.cancel()
        activeJob = null
        restoreBaseAudioStates(equalizer, reverb, baseEqGains, baseReverbPreset, mediaPlayer, basePitch, onVolumeDuck)
        isEffectActive = false
        activeEffectType = null
    }

    private fun restoreBaseAudioStates(
        equalizer: Equalizer?,
        reverb: PresetReverb?,
        baseEqGains: FloatArray,
        baseReverbPreset: String,
        mediaPlayer: MediaPlayer?,
        basePitch: Float,
        onVolumeDuck: ((Float) -> Unit)?
    ) {
        try {
            // Restore Equalizer to user's saved base band levels
            equalizer?.let { eq ->
                val numBands = min(eq.numberOfBands.toInt(), baseEqGains.size)
                val range = eq.bandLevelRange
                val minMb = range[0].toInt()
                val maxMb = range[1].toInt()
                for (i in 0 until numBands) {
                    val db = baseEqGains[i]
                    val mb = (db * 100).toInt().coerceIn(minMb, maxMb).toShort()
                    eq.setBandLevel(i.toShort(), mb)
                }
            }
        } catch (_: Throwable) {}

        try {
            // Restore Reverb preset
            reverb?.let { rev ->
                rev.preset = when (baseReverbPreset.lowercase()) {
                    "small room" -> PresetReverb.PRESET_SMALLROOM
                    "medium room" -> PresetReverb.PRESET_MEDIUMROOM
                    "large hall" -> PresetReverb.PRESET_LARGEHALL
                    "plate" -> PresetReverb.PRESET_PLATE
                    else -> PresetReverb.PRESET_NONE
                }
            }
        } catch (_: Throwable) {}

        try {
            // Restore pitch on MediaPlayer
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && mediaPlayer != null) {
                val params = mediaPlayer.playbackParams
                if (params.pitch != basePitch) {
                    params.pitch = basePitch
                    mediaPlayer.playbackParams = params
                }
            }
        } catch (_: Throwable) {}

        // Restore volume ducking
        try {
            onVolumeDuck?.invoke(1.0f)
        } catch (_: Throwable) {}
    }

    fun release() {
        activeJob?.cancel()
        activeJob = null
        try {
            soundPool?.release()
        } catch (_: Throwable) {}
        soundPool = null
        isSoundPoolReady = false
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Procedural Scratch Sound Synthesizer (Authentic Vinyl Audio Assets)
    // ─────────────────────────────────────────────────────────────────────────────

    private enum class ScratchStyle {
        CHIRP, BABY, BACKSPIN
    }

    private fun generateScratchWav(fileName: String, style: ScratchStyle): File? {
        return try {
            val cacheFile = File(context.cacheDir, fileName)
            if (cacheFile.exists() && cacheFile.length() > 500) {
                return cacheFile
            }

            val durationSec = when (style) {
                ScratchStyle.CHIRP -> 0.35
                ScratchStyle.BABY -> 0.48
                ScratchStyle.BACKSPIN -> 0.58
            }
            val totalSamples = (SAMPLE_RATE * durationSec).toInt()
            val pcmData = ShortArray(totalSamples)

            for (i in 0 until totalSamples) {
                val t = i.toDouble() / totalSamples // 0.0 to 1.0

                // Frequency trajectory modeling vinyl needle rub & backspin
                val freq = when (style) {
                    ScratchStyle.CHIRP -> {
                        // Rapid forward chirp
                        val fNorm = sin(t * Math.PI)
                        450.0 + (1600.0 * fNorm)
                    }
                    ScratchStyle.BABY -> {
                        // 2-stroke "wicka-wicka" forward & backward rub
                        val cycle = sin(t * Math.PI * 2.0)
                        500.0 + (1200.0 * abs(cycle))
                    }
                    ScratchStyle.BACKSPIN -> {
                        // Turntable slip: high to low frequency deceleration
                        val decel = (1.0 - t).pow(1.8)
                        220.0 + (2400.0 * decel)
                    }
                }

                // Phase integration for frequency-modulated oscillator
                val carrier = sin(2.0 * Math.PI * freq * (i.toDouble() / SAMPLE_RATE))

                // High-frequency vinyl needle friction noise
                val noise = (Math.random() * 2.0 - 1.0) * 0.22

                // Smooth envelope avoiding pops at edges
                val envelope = sin(t * Math.PI).pow(0.8)

                val sample = ((carrier * 0.78 + noise) * envelope).coerceIn(-1.0, 1.0)
                pcmData[i] = (sample * 30000.0).toInt().toShort()
            }

            writeWavFile(cacheFile, pcmData, SAMPLE_RATE)
            cacheFile
        } catch (e: Exception) {
            Log.e(TAG, "Error synthesizing scratch WAV $fileName: ${e.message}")
            null
        }
    }

    private fun writeWavFile(file: File, pcmData: ShortArray, sampleRate: Int) {
        val totalAudioLen = pcmData.size * 2
        val totalDataLen = totalAudioLen + 36
        val channels = 1
        val byteRate = sampleRate * channels * 2

        FileOutputStream(file).use { out ->
            val header = ByteBuffer.allocate(44).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                put("RIFF".toByteArray())
                putInt(totalDataLen)
                put("WAVE".toByteArray())
                put("fmt ".toByteArray())
                putInt(16) // Subchunk1Size
                putShort(1.toShort()) // PCM format
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(byteRate)
                putShort((channels * 2).toShort())
                putShort(16.toShort()) // Bits per sample
                put("data".toByteArray())
                putInt(totalAudioLen)
            }.array()

            out.write(header)

            val pcmBytes = ByteBuffer.allocate(totalAudioLen).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                for (s in pcmData) {
                    putShort(s)
                }
            }.array()

            out.write(pcmBytes)
            out.flush()
        }
    }
}
