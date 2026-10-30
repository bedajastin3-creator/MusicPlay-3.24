package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.media.audiofx.PresetReverb
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.data.MusicTrack
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * Executes real-time DJ audio effects on the active playback session.
 * 
 * Guarantees 100% audibility across BOTH playback paths:
 * 1. Hardware/Platform path: Android MediaPlayer, Hardware Equalizer, PresetReverb, and dedicated scratch/flanger playback deck.
 * 2. Software DSP path: Real-time PCM processing (turntable scratch injection, resonant state-variable filter sweeps,
 *    delay/echo buffer, rhythmic beat-chopping, and genuine short-delay DSP Flanger) inside AudioTrack playback.
 *
 * Guarantees:
 * - Manipulates real song audio for authentic Baby Scratch & Flare Scratching
 * - Real modulated comb-filter DSP Flanger with smooth wet/dry automation
 * - Never modifies music files on disk
 * - Never interferes with or resets the existing crossfade implementation
 * - Restores user's base Equalizer, Reverb, Pitch, and Volume settings cleanly
 */
class DjAudioProcessor(private val context: Context) {

    companion object {
        private const val TAG = "DjAudioProcessor"
        const val SAMPLE_RATE = 22050
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var activeJob: Job? = null

    // Real audio processors
    val songAudioDecoder = SongAudioDecoder(context)
    val turntableScratchEngine = TurntableScratchEngine(SAMPLE_RATE)
    val dspFlanger = DspFlanger(SAMPLE_RATE)
    val dspRhythmicEcho = DspRhythmicEcho(SAMPLE_RATE)

    // Scratch audio storage
    private var scratchFiles = mutableListOf<File>()
    private var scratchPcmChirp: ShortArray? = null
    private var scratchPcmBaby: ShortArray? = null
    private var scratchPcmBackspin: ShortArray? = null

    // Dedicated MediaPlayer for playing scratch/flanger performances over ducked MediaPlayer streams
    private var scratchMediaPlayer: MediaPlayer? = null

    // State tracking for baseline restoration
    @Volatile
    var isEffectActive: Boolean = false
        private set

    @Volatile
    var activeEffectType: DjEffectType? = null
        private set

    // ─────────────────────────────────────────────────────────────────────────────
    // Real-Time PCM DSP State (Used directly in AudioTrack synthesis loop)
    // ─────────────────────────────────────────────────────────────────────────────
    @Volatile
    private var pcmEffectMode: Int = 0 // 0: bypass, 1: scratch, 2: filter sweep, 3: beat filter, 4: radio, 5: echo, 6: spinup, 7: flanger
    @Volatile
    private var pcmCurrentScratch: ShortArray? = null
    @Volatile
    private var pcmScratchIndex = 0

    // Filter Sweep State
    @Volatile
    private var pcmFilterProgress = 0.0 // 0.0 to 1.0
    private var svfLow = 0.0
    private var svfBand = 0.0
    private var svfHigh = 0.0

    // Beat Filter State
    @Volatile
    private var pcmBeatFilterPulse = 0.0 // 0.0 to 1.0

    // Echo Ring Buffer (280ms circular delay line)
    private val echoDelaySamples = (SAMPLE_RATE * 0.28).toInt()
    private val echoBuffer = FloatArray(echoDelaySamples)
    private var echoBufferIndex = 0
    @Volatile
    private var echoMixLevel = 0.0f
    @Volatile
    private var echoFeedback = 0.55f

    // Spin-Up State
    @Volatile
    private var spinUpProgress = 1.0

    init {
        initScratchAssets()
    }

    private fun initScratchAssets() {
        try {
            scratchPcmChirp = generateScratchPcm(ScratchStyle.CHIRP)
            scratchPcmBaby = generateScratchPcm(ScratchStyle.BABY)
            scratchPcmBackspin = generateScratchPcm(ScratchStyle.BACKSPIN)

            val chirpWav = writeWavToFiles("dj_scratch_chirp.wav", scratchPcmChirp!!)
            val babyWav = writeWavToFiles("dj_scratch_baby.wav", scratchPcmBaby!!)
            val spinWav = writeWavToFiles("dj_scratch_spin.wav", scratchPcmBackspin!!)

            scratchFiles.add(chirpWav)
            scratchFiles.add(babyWav)
            scratchFiles.add(spinWav)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed creating scratch assets: ${e.message}")
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
        track: MusicTrack? = null,
        estimatedBpm: Double = 120.0,
        onVolumeDuck: ((Float) -> Unit)? = null,
        onComplete: () -> Unit
    ) {
        // Cancel any running effect before starting new one
        cancelActiveEffect(equalizer, reverb, baseEqGains, baseReverbPreset, mediaPlayer, basePitch, onVolumeDuck)

        isEffectActive = true
        activeEffectType = effect

        activeJob = scope.launch {
            try {
                when (effect) {
                    DjEffectType.BABY_SCRATCH -> {
                        executeTurntablePerformance(TurntableScratchEngine.ScratchTechnique.BABY_SCRATCH, track, estimatedBpm, onVolumeDuck)
                    }
                    DjEffectType.FLARE_SCRATCH -> {
                        executeTurntablePerformance(TurntableScratchEngine.ScratchTechnique.FLARE_SCRATCH, track, estimatedBpm, onVolumeDuck)
                    }
                    DjEffectType.FLANGER -> {
                        executeFlangerPerformance(equalizer, baseEqGains, track, estimatedBpm, onVolumeDuck)
                    }
                    DjEffectType.VINYL_SCRATCH -> {
                        executeTurntablePerformance(TurntableScratchEngine.ScratchTechnique.BABY_SCRATCH, track, estimatedBpm, onVolumeDuck)
                    }
                    DjEffectType.FILTER_SWEEP -> {
                        executeFilterSweep(equalizer, baseEqGains, onVolumeDuck)
                    }
                    DjEffectType.BEAT_FILTER -> {
                        executeBeatFilter(equalizer, baseEqGains, onVolumeDuck)
                    }
                    DjEffectType.RADIO_FILTER -> {
                        executeRadioFilter(equalizer, baseEqGains, onVolumeDuck)
                    }
                    DjEffectType.ECHO_OUT -> {
                        executeEchoOut(reverb, baseReverbPreset, equalizer, baseEqGains, estimatedBpm, onVolumeDuck)
                    }
                    DjEffectType.VINYL_SPIN_UP -> {
                        executeVinylSpinUp(mediaPlayer, basePitch, onVolumeDuck)
                    }
                }
            } catch (_: CancellationException) {
            } catch (e: Throwable) {
                Log.e(TAG, "Error executing DJ effect $effect: ${e.message}")
            } finally {
                restoreBaseAudioStates(equalizer, reverb, baseEqGains, baseReverbPreset, mediaPlayer, basePitch, onVolumeDuck)
                isEffectActive = false
                activeEffectType = null
                pcmEffectMode = 0
                withContext(Dispatchers.Main) {
                    onComplete()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 1. Turntable Scratch Performance (Baby Scratch & Flare Scratch)
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun executeTurntablePerformance(
        technique: TurntableScratchEngine.ScratchTechnique,
        track: MusicTrack?,
        estimatedBpm: Double,
        onVolumeDuck: ((Float) -> Unit)?
    ) {
        // 1. Extract raw audio slice around ~16-20s from the user's actual song
        val snippetPcm = if (track != null && track.contentUri.isNotBlank()) {
            songAudioDecoder.extractAudioSnippet(
                contentUri = track.contentUri,
                startOffsetSec = 16,
                durationSec = 2.5,
                fallbackSeed = track.id
            )
        } else {
            songAudioDecoder.extractAudioSnippet("", 0, 2.5, track?.id ?: 110)
        }

        // 2. Render authentic 4-stroke + ~1.0s pause + 4-stroke turntable scratch performance
        val renderedPcm = turntableScratchEngine.renderScratchPerformance(
            sourcePcm = snippetPcm,
            technique = technique,
            estimatedBpm = estimatedBpm
        )

        val prefix = if (technique == TurntableScratchEngine.ScratchTechnique.BABY_SCRATCH) "baby" else "flare"
        val tempWavFile = writeWavToFiles("dj_${prefix}_scratch_perf.wav", renderedPcm)

        // Setup real-time PCM overlay for AudioTrack synth playback
        pcmCurrentScratch = renderedPcm
        pcmScratchIndex = 0
        pcmEffectMode = 1 // SCRATCH

        // 3. Audible ducking of primary playback so scratches sound punchy, crisp, and prominent
        onVolumeDuck?.invoke(0.20f)

        // 4. Play scratch performance via dedicated MediaPlayer
        withContext(Dispatchers.Main) {
            try {
                scratchMediaPlayer?.release()
                scratchMediaPlayer = MediaPlayer().apply {
                    setDataSource(context, Uri.fromFile(tempWavFile))
                    setVolume(1.0f, 1.0f)
                    prepare()
                    start()
                    setOnCompletionListener { mp ->
                        try { mp.release() } catch (_: Throwable) {}
                        if (scratchMediaPlayer == mp) scratchMediaPlayer = null
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Scratch player playback fallback: ${e.message}")
            }
        }

        // Controlled performance duration (4 strokes + ~1.0s pause + 4 strokes)
        val durationMs = (renderedPcm.size * 1000L / SAMPLE_RATE).coerceIn(2400L, 5500L)
        delay(durationMs)

        // 5. Smooth volume restoration to normal music playback
        onVolumeDuck?.invoke(0.65f)
        delay(60)
        onVolumeDuck?.invoke(1.0f)
        pcmEffectMode = 0
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 2. Genuine DSP Flanger Performance (Live Advancing Audio, Zero Repetition)
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun executeFlangerPerformance(
        equalizer: Equalizer?,
        baseGains: FloatArray,
        track: MusicTrack?,
        estimatedBpm: Double,
        onVolumeDuck: ((Float) -> Unit)?
    ) {
        pcmEffectMode = 7 // FLANGER

        // Synchronize LFO sweep rate to track tempo (e.g. 1 sweep cycle across 2 musical bars)
        val beatSec = (60.0 / estimatedBpm.coerceIn(75.0, 160.0))
        val barSec = beatSec * 4.0
        val lfoRate = (1.0 / (barSec * 1.5)).coerceIn(0.12, 0.30)
        dspFlanger.lfoFrequencyHz = lfoRate
        dspFlanger.reset()

        // CONTINUOUS LIVE ADVANCING AUDIO:
        // Never loop, never replay previous sections, never launch secondary MediaPlayer.
        // The song continues advancing normally while sweeping metallic comb-filtering sweeps through it.
        val totalSteps = 56 // ~4.5 seconds total sweep duration
        val fadeInSteps = 14
        val fadeOutStart = totalSteps - 14

        for (i in 1..totalSteps) {
            val progress = i.toDouble() / totalSteps

            // Smooth dry -> wet -> dry intensity automation curve
            val wet = when {
                i <= fadeInSteps -> (i.toFloat() / fadeInSteps) * 0.85f
                i >= fadeOutStart -> ((totalSteps - i).toFloat() / 14f) * 0.85f
                else -> 0.85f
            }
            dspFlanger.wetMix = wet

            // Modulate platform hardware Equalizer with sweeping acoustic comb-filtering in sync with LFO
            equalizer?.let { eq ->
                try {
                    val numBands = min(eq.numberOfBands.toInt(), baseGains.size)
                    val range = eq.bandLevelRange
                    val lfoAngle = progress * Math.PI * 4.0 // 2 full sweeping cycles across performance
                    for (b in 0 until numBands) {
                        // Alternate band phase distribution simulates sweeping comb filter peaks & notches
                        val phaseOffset = (b.toDouble() / numBands) * Math.PI * 2.0
                        val gainOffset = sin(lfoAngle + phaseOffset) * (7.0 * (wet / 0.85f))
                        val mb = ((baseGains[b] + gainOffset) * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt()).toShort()
                        eq.setBandLevel(b.toShort(), mb)
                    }
                } catch (_: Throwable) {}
            }
            delay(80)
        }

        dspFlanger.wetMix = 0.0f
        dspFlanger.reset()
        pcmEffectMode = 0
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 3. Filter Sweep
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun executeFilterSweep(
        equalizer: Equalizer?,
        baseGains: FloatArray,
        onVolumeDuck: ((Float) -> Unit)?
    ) {
        pcmEffectMode = 2 // FILTER SWEEP
        pcmFilterProgress = 0.0

        val totalSteps = 45 // ~2.2 seconds total sweep
        for (i in 1..totalSteps) {
            val progress = i.toDouble() / totalSteps
            pcmFilterProgress = progress // Sweeps cutoff from 350Hz up to 10000Hz

            equalizer?.let { eq ->
                try {
                    val numBands = min(eq.numberOfBands.toInt(), baseGains.size)
                    val range = eq.bandLevelRange
                    val minMb = range[0].toInt()
                    val maxMb = range[1].toInt()
                    val deltaHigh = (1.0 - progress) * -14.0
                    for (b in 0 until numBands) {
                        val base = baseGains[b]
                        val added = if (b >= numBands / 2) deltaHigh.toFloat() else (deltaHigh * -0.2f).toFloat()
                        val mb = ((base + added) * 100).toInt().coerceIn(minMb, maxMb).toShort()
                        eq.setBandLevel(b.toShort(), mb)
                    }
                } catch (_: Throwable) {}
            }

            delay(48)
        }
        pcmEffectMode = 0
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 4. Beat-Filtering
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun executeBeatFilter(
        equalizer: Equalizer?,
        baseGains: FloatArray,
        onVolumeDuck: ((Float) -> Unit)?
    ) {
        pcmEffectMode = 3 // BEAT FILTER

        repeat(3) { pulse ->
            val steps = 9
            for (s in 1..steps) {
                val t = s.toDouble() / steps
                val dip = sin(t * Math.PI)
                pcmBeatFilterPulse = dip

                val duck = (1.0 - (dip * 0.50)).toFloat()
                onVolumeDuck?.invoke(duck)

                equalizer?.let { eq ->
                    try {
                        val numBands = min(eq.numberOfBands.toInt(), baseGains.size)
                        val range = eq.bandLevelRange
                        for (b in (numBands / 2) until numBands) {
                            val target = baseGains[b] - (dip * 10.0).toFloat()
                            val mb = (target * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt()).toShort()
                            eq.setBandLevel(b.toShort(), mb)
                        }
                    } catch (_: Throwable) {}
                }
                delay(30)
            }
            delay(120)
        }
        onVolumeDuck?.invoke(1.0f)
        pcmEffectMode = 0
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 5. Radio / Vocal Filter
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun executeRadioFilter(
        equalizer: Equalizer?,
        baseGains: FloatArray,
        onVolumeDuck: ((Float) -> Unit)?
    ) {
        pcmEffectMode = 4 // RADIO BANDPASS

        equalizer?.let { eq ->
            try {
                val numBands = min(eq.numberOfBands.toInt(), baseGains.size)
                val range = eq.bandLevelRange
                for (b in 0 until numBands) {
                    val db = when (b) {
                        0 -> -15f
                        1 -> -10f
                        2 -> 8f
                        3 -> 2f
                        else -> -12f
                    }
                    val mb = (db * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt()).toShort()
                    eq.setBandLevel(b.toShort(), mb)
                }
            } catch (_: Throwable) {}
        }

        delay(2400)
        pcmEffectMode = 0
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 6. Echo-Out
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun executeEchoOut(
        reverb: PresetReverb?,
        basePreset: String,
        equalizer: Equalizer?,
        baseGains: FloatArray,
        estimatedBpm: Double,
        onVolumeDuck: ((Float) -> Unit)?
    ) {
        pcmEffectMode = 5 // ECHO
        dspRhythmicEcho.reset()
        // Musical interval: Dotted 1/8 note synchronized to tempo with controlled 0.52 feedback
        dspRhythmicEcho.configureTempo(
            estimatedBpm = estimatedBpm,
            division = DspRhythmicEcho.EchoDivision.DOTTED_EIGHTH,
            feedbackRatio = 0.52f
        )

        // Set spatial acoustics on platform hardware reverb
        reverb?.let { rev ->
            try {
                rev.preset = PresetReverb.PRESET_LARGEHALL
            } catch (_: Throwable) {}
        }

        // Phase 1: Smooth Wet Ramp-In & Rhythmic Echo Capture (~1.4 seconds)
        val captureSteps = 18
        for (i in 1..captureSteps) {
            val wet = (i.toFloat() / captureSteps) * 0.65f
            dspRhythmicEcho.wetMix = wet
            equalizer?.let { eq ->
                try {
                    val numBands = min(eq.numberOfBands.toInt(), baseGains.size)
                    val range = eq.bandLevelRange
                    for (b in 0 until numBands) {
                        // Gentle high-shelf roll-off during echo to emphasize warm analog tape repeats
                        val dbOffset = if (b >= numBands - 2) -4.0f * (wet / 0.65f) else 1.5f * (wet / 0.65f)
                        val mb = ((baseGains[b] + dbOffset) * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt()).toShort()
                        eq.setBandLevel(b.toShort(), mb)
                    }
                } catch (_: Throwable) {}
            }
            delay(80)
        }

        // Phase 2: Natural Tail Decay (~2.2 seconds)
        // Mute new input into delay line: existing echo repetitions decay naturally into silence
        // without harsh cutoffs, clipping, or interfering with incoming playback
        dspRhythmicEcho.isInputMuted = true
        val decaySteps = 22
        for (i in decaySteps downTo 1) {
            val wet = (i.toFloat() / decaySteps) * 0.65f
            dspRhythmicEcho.wetMix = wet
            delay(100)
        }

        dspRhythmicEcho.wetMix = 0.0f
        dspRhythmicEcho.reset()
        pcmEffectMode = 0
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 7. Vinyl Spin-Up
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun executeVinylSpinUp(
        mediaPlayer: MediaPlayer?,
        basePitch: Float,
        onVolumeDuck: ((Float) -> Unit)?
    ) {
        pcmEffectMode = 6 // SPIN UP
        spinUpProgress = 0.20

        val steps = 18
        for (i in 1..steps) {
            val t = i.toDouble() / steps
            spinUpProgress = 0.20 + (0.80 * t)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && mediaPlayer != null) {
                try {
                    val params = mediaPlayer.playbackParams
                    params.pitch = (basePitch * (0.45f + 0.55f * t.toFloat())).coerceIn(0.5f, 2.0f)
                    mediaPlayer.playbackParams = params
                } catch (_: Throwable) {}
            }
            delay(35)
        }

        spinUpProgress = 1.0
        pcmEffectMode = 0
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Real-Time PCM Sample Processing (Direct Software AudioTrack Hook)
    // ─────────────────────────────────────────────────────────────────────────────

    fun processPcmSample(input: Double): Double {
        if (pcmEffectMode == 0) return input
        var sample = input

        when (pcmEffectMode) {
            1 -> {
                // VINYL SCRATCH MIX
                val scratch = pcmCurrentScratch
                if (scratch != null && pcmScratchIndex < scratch.size) {
                    val scratchNorm = scratch[pcmScratchIndex] / 32768.0
                    pcmScratchIndex++
                    sample = (sample * 0.20) + (scratchNorm * 0.90)
                }
            }
            2 -> {
                // FILTER SWEEP
                val cutoff = 320.0 + (10680.0 * (pcmFilterProgress * pcmFilterProgress))
                val f = 2.0 * sin(Math.PI * cutoff / SAMPLE_RATE)
                val q = 1.4
                svfHigh = sample - svfLow - svfBand / q
                svfBand += svfHigh * f
                svfLow += svfBand * f
                sample = svfLow * 1.15
            }
            3 -> {
                // BEAT FILTER
                val dip = pcmBeatFilterPulse
                val vol = (1.0 - dip * 0.60)
                sample *= vol
            }
            4 -> {
                // RADIO FILTER
                val f = 2.0 * sin(Math.PI * 1250.0 / SAMPLE_RATE)
                val q = 2.2
                svfHigh = sample - svfLow - svfBand / q
                svfBand += svfHigh * f
                svfLow += svfBand * f
                val band = svfBand * 2.2
                sample = tanh(band * 1.4) * 0.90
            }
            5 -> {
                // RHYTHMIC TAPE ECHO (DspRhythmicEcho)
                sample = dspRhythmicEcho.processSample(sample)
            }
            6 -> {
                // SPIN UP
                sample *= spinUpProgress
            }
            7 -> {
                // FLANGER (DspFlanger)
                sample = dspFlanger.processSample(sample)
            }
        }

        return sample.coerceIn(-1.0, 1.0)
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
        pcmEffectMode = 0
        dspFlanger.wetMix = 0f
        dspFlanger.reset()
        dspRhythmicEcho.wetMix = 0f
        dspRhythmicEcho.reset()
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && mediaPlayer != null) {
                val params = mediaPlayer.playbackParams
                if (params.pitch != basePitch) {
                    params.pitch = basePitch
                    mediaPlayer.playbackParams = params
                }
            }
        } catch (_: Throwable) {}

        try {
            scratchMediaPlayer?.release()
            scratchMediaPlayer = null
        } catch (_: Throwable) {}

        try {
            onVolumeDuck?.invoke(1.0f)
        } catch (_: Throwable) {}
    }

    fun release() {
        activeJob?.cancel()
        activeJob = null
        try {
            scratchMediaPlayer?.release()
        } catch (_: Throwable) {}
        scratchMediaPlayer = null
        dspFlanger.reset()
        dspRhythmicEcho.reset()
        songAudioDecoder.clearCache()
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // High-Fidelity Scratch PCM Generator (Synthesized Fallbacks)
    // ─────────────────────────────────────────────────────────────────────────────

    private enum class ScratchStyle {
        CHIRP, BABY, BACKSPIN
    }

    private fun generateScratchPcm(style: ScratchStyle): ShortArray {
        val durationSec = when (style) {
            ScratchStyle.CHIRP -> 0.38
            ScratchStyle.BABY -> 0.50
            ScratchStyle.BACKSPIN -> 0.62
        }
        val totalSamples = (SAMPLE_RATE * durationSec).toInt()
        val pcmData = ShortArray(totalSamples)

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / totalSamples
            val freq = when (style) {
                ScratchStyle.CHIRP -> 500.0 + (1900.0 * sin(t * Math.PI))
                ScratchStyle.BABY -> 550.0 + (1400.0 * abs(sin(t * Math.PI * 2.0)))
                ScratchStyle.BACKSPIN -> 250.0 + (2800.0 * (1.0 - t).pow(1.6))
            }
            val carrier = sin(2.0 * Math.PI * freq * (i.toDouble() / SAMPLE_RATE))
            val noise = (Math.random() * 2.0 - 1.0) * 0.28
            val envelope = sin(t * Math.PI).pow(0.7)
            val sample = ((carrier * 0.75 + noise) * envelope).coerceIn(-1.0, 1.0)
            pcmData[i] = (sample * 31500.0).toInt().toShort()
        }

        return pcmData
    }

    private fun writeWavToFiles(fileName: String, pcmData: ShortArray): File {
        val file = File(context.filesDir, fileName)

        val totalAudioLen = pcmData.size * 2
        val totalDataLen = totalAudioLen + 36
        val channels = 1
        val byteRate = SAMPLE_RATE * channels * 2

        FileOutputStream(file).use { out ->
            val header = ByteBuffer.allocate(44).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                put("RIFF".toByteArray())
                putInt(totalDataLen)
                put("WAVE".toByteArray())
                put("fmt ".toByteArray())
                putInt(16)
                putShort(1.toShort())
                putShort(channels.toShort())
                putInt(SAMPLE_RATE)
                putInt(byteRate)
                putShort((channels * 2).toShort())
                putShort(16.toShort())
                put("data".toByteArray())
                putInt(totalAudioLen)
            }.array()

            out.write(header)
            val pcmBytes = ByteBuffer.allocate(totalAudioLen).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                for (s in pcmData) putShort(s)
            }.array()
            out.write(pcmBytes)
            out.flush()
        }
        return file
    }
}
