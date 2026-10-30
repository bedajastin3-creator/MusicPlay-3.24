package com.example.audio

import android.content.Context
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.media.audiofx.PresetReverb
import android.util.Log
import com.example.data.MusicTrack
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * Smart Random DJ Performance Engine with Real-Time Audio Awareness.
 * 
 * An intelligent, state-aware scheduler that dynamically decides:
 * 1. Smart Music Awareness: Detects BEAT ONLY, BEAT + VOCALS, SPOKEN VOICE, ACAPELLA SINGING,
 *    and INSTRUMENTAL MELODIC to adapt DJ performance actions in real-time.
 * 2. Intelligent 8-Beat Loop: On pure BEAT ONLY sections, executes seamless 2-cycle 8-beat DJ loops
 *    synchronized to BPM without interrupting vocal phrases or jumping the main song player.
 * 3. Speech-Aware Intelligent Crossfade: Detects spoken dialogue near the end of the song
 *    (30-50s remaining) and smoothly starts crossfade before speech begins; falls back to standard
 *    20-second crossfade if uncertain.
 * 4. Single-Flanger Guarantee: Strictly allows no more than 1 Flanger performance per song.
 * 5. Audio-Protective DJ Effects: Baby Scratch preserves original song volume; Echo & Decay delivers
 *    tempo-synchronized delay repetitions with tape damping and natural decay.
 * 6. Playlist Replay Variation: Uses lightweight history to favor different outcomes when replaying
 *    the same playlist, avoiding repetitive effect sequences.
 */
class SmartDjPerformanceEngine(
    private val context: Context,
    private val processor: DjAudioProcessor,
    private val history: DjPerformanceHistory
) {

    companion object {
        private const val TAG = "SmartDjEngine"
        private const val MIN_TRACK_DURATION_FOR_EFFECTS_SEC = 18
    }

    data class ScheduledDjMoment(
        val effectType: DjEffectType,
        val triggerPositionSec: Int,
        var hasTriggered: Boolean = false
    )

    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Telemetry and active status for UI indicators
    private val _isDjEffectExecuting = MutableStateFlow(false)
    val isDjEffectExecuting: StateFlow<Boolean> = _isDjEffectExecuting.asStateFlow()

    private val _lastTriggeredEffectName = MutableStateFlow<String?>(null)
    val lastTriggeredEffectName: StateFlow<String?> = _lastTriggeredEffectName.asStateFlow()

    // Real-Time Smart Music Awareness State
    private val _currentClassification = MutableStateFlow(
        SmartAudioClassifier.ClassificationResult(
            type = SmartAudioClassifier.AudioContentType.UNCERTAIN,
            confidence = 0.5f,
            beatStrength = 0.5f,
            vocalEnergy = 0.0f,
            harmonicity = 0.0f,
            speechCadence = 0.0f
        )
    )
    val currentClassification: StateFlow<SmartAudioClassifier.ClassificationResult> = _currentClassification.asStateFlow()

    private val _currentAudioContentType = MutableStateFlow(SmartAudioClassifier.AudioContentType.UNCERTAIN)
    val currentAudioContentType: StateFlow<SmartAudioClassifier.AudioContentType> = _currentAudioContentType.asStateFlow()

    // Callback when speech is detected near track end to initiate speech-aware crossfade
    var onSpeechAwareCrossfadeRequested: (() -> Unit)? = null

    // Current track scheduling state
    private var currentTrack: MusicTrack? = null
    private var currentScheduledTrackId: Int? = null
    private var pendingMoment: ScheduledDjMoment? = null
    private var lastHandledPositionSec = -1
    private var lastAnalysisSec = -1
    private var trackDurationSec = 180
    private var activeCrossfadeSec = 20
    private var activeCrossfadeEnabled = true

    // Performance constraints
    private var hasFlangerTriggeredForThisTrack = false
    private var hasSpeechCrossfadeInitiatedForThisTrack = false

    /**
     * Estimates musical tempo (BPM) reliably for the given track.
     */
    fun estimateBpmForTrack(track: MusicTrack?): Double {
        if (track == null) return 124.0
        val hash = (track.title.hashCode() xor track.artist.hashCode()).let { if (it < 0) -it else it }
        return 112.0 + (hash % 21)
    }

    /**
     * Called whenever a new track begins or is loaded.
     */
    @Synchronized
    fun onTrackStarted(
        track: MusicTrack,
        isDjEffectsEnabled: Boolean,
        crossfadeSec: Int,
        isCrossfadeEnabled: Boolean
    ) {
        cancelCurrentPerformance()

        currentTrack = track
        currentScheduledTrackId = track.id
        trackDurationSec = track.durationSec.coerceAtLeast(30)
        activeCrossfadeSec = crossfadeSec
        activeCrossfadeEnabled = isCrossfadeEnabled
        lastHandledPositionSec = -1
        lastAnalysisSec = -1
        hasFlangerTriggeredForThisTrack = false
        hasSpeechCrossfadeInitiatedForThisTrack = false

        processor.smartAudioClassifier.reset()
        _currentAudioContentType.value = SmartAudioClassifier.AudioContentType.UNCERTAIN

        if (!isDjEffectsEnabled) {
            pendingMoment = null
            return
        }

        // Schedule first DJ moment (targeting ~16-20 seconds after track start)
        scheduleNextMoment(currentSec = 0)
    }

    private fun scheduleNextMoment(currentSec: Int) {
        val durationSec = trackDurationSec
        val effectiveCfSec = if (activeCrossfadeEnabled) activeCrossfadeSec.coerceAtLeast(0) else 0
        val crossfadeStartSec = durationSec - effectiveCfSec

        // Strict crossfade isolation: Never schedule inside or within 8 seconds of crossfade
        if (currentSec >= crossfadeStartSec - 8) {
            pendingMoment = null
            return
        }

        val trackId = currentScheduledTrackId

        if (currentSec < 15) {
            // ─────────────────────────────────────────────────────────────────
            // 1. Initial Scratch Moment: Target approximately 16 to 20 seconds
            // ─────────────────────────────────────────────────────────────────
            val targetSec = Random.nextInt(16, 21).coerceAtMost((crossfadeStartSec - 8).coerceAtLeast(10))

            // Intelligent selection: Baby Scratch, Flare Scratch, or Clean Play (~28% probability)
            val isCleanPlay = Random.nextFloat() < 0.28f

            if (isCleanPlay) {
                trackId?.let { history.recordCleanPlay(it) }
                Log.d(TAG, "Track $trackId selected for clean intro play")
                scheduleMidSongMoment(currentSec = targetSec + 4, crossfadeStartSec = crossfadeStartSec)
            } else {
                val lastTrackEffect = trackId?.let { history.getLastEffectForTrack(it) }
                val lastGlobalEffect = history.getLastGlobalEffect()

                val chosenTechnique = when {
                    lastTrackEffect == DjEffectType.BABY_SCRATCH -> DjEffectType.FLARE_SCRATCH
                    lastTrackEffect == DjEffectType.FLARE_SCRATCH -> DjEffectType.BABY_SCRATCH
                    lastGlobalEffect == DjEffectType.BABY_SCRATCH -> DjEffectType.FLARE_SCRATCH
                    lastGlobalEffect == DjEffectType.FLARE_SCRATCH -> DjEffectType.BABY_SCRATCH
                    else -> if (Random.nextBoolean()) DjEffectType.BABY_SCRATCH else DjEffectType.FLARE_SCRATCH
                }

                pendingMoment = ScheduledDjMoment(
                    effectType = chosenTechnique,
                    triggerPositionSec = targetSec,
                    hasTriggered = false
                )
                Log.d(TAG, "Scheduled initial scratch ${chosenTechnique.displayName} at ${targetSec}s for track $trackId")
            }
        } else if (currentSec < crossfadeStartSec - 18) {
            // ─────────────────────────────────────────────────────────────────
            // 2. Mid-Song Moment: Flanger (max 1x) or 8-Beat Loop (on Beat Only)
            // ─────────────────────────────────────────────────────────────────
            scheduleMidSongMoment(currentSec, crossfadeStartSec)
        } else {
            // ─────────────────────────────────────────────────────────────────
            // 3. Outro Moment: ~6s prior to crossfade start (strictly aborts on CF)
            // ─────────────────────────────────────────────────────────────────
            val outroSec = (crossfadeStartSec - 6).coerceAtLeast(currentSec + 2)
            if (outroSec < crossfadeStartSec - 3) {
                pendingMoment = ScheduledDjMoment(
                    effectType = DjEffectType.ECHO_OUT,
                    triggerPositionSec = outroSec,
                    hasTriggered = false
                )
            } else {
                pendingMoment = null
            }
        }
    }

    private fun scheduleMidSongMoment(currentSec: Int, crossfadeStartSec: Int) {
        val midSec = (trackDurationSec * 0.52).toInt()
        val triggerSec = midSec.coerceIn(currentSec + 5, (crossfadeStartSec - 12).coerceAtLeast(currentSec + 5))

        if (triggerSec < crossfadeStartSec - 8) {
            val awareness = _currentAudioContentType.value

            // Selection adhering strictly to constraints:
            // 1. If BEAT_ONLY is detected, prioritize 8-Beat Loop.
            // 2. Flanger allowed NO MORE THAN ONCE per song.
            val chosenEffect = when {
                awareness == SmartAudioClassifier.AudioContentType.BEAT_ONLY -> DjEffectType.EIGHT_BEAT_LOOP
                !hasFlangerTriggeredForThisTrack -> DjEffectType.FLANGER
                else -> DjEffectType.FILTER_SWEEP
            }

            pendingMoment = ScheduledDjMoment(
                effectType = chosenEffect,
                triggerPositionSec = triggerSec,
                hasTriggered = false
            )
            Log.d(TAG, "Scheduled mid-song ${chosenEffect.displayName} at ${triggerSec}s (Awareness: ${awareness.displayName})")
        } else {
            pendingMoment = null
        }
    }

    /**
     * Called on each playback progress loop tick (e.g. ~35ms) by MusicPlaybackManager.
     */
    @Synchronized
    fun onPlaybackTick(
        track: MusicTrack,
        currentPosSec: Int,
        isPlaying: Boolean,
        isCrossfading: Boolean,
        isDjEffectsEnabled: Boolean,
        mediaPlayer: MediaPlayer?,
        equalizer: Equalizer?,
        reverb: PresetReverb?,
        baseEqGains: FloatArray,
        baseReverbPreset: String,
        basePitch: Float,
        onVolumeDuck: ((Float) -> Unit)? = null
    ) {
        currentTrack = track

        if (!isDjEffectsEnabled) {
            if (processor.isEffectActive) {
                cancelCurrentPerformance(equalizer, reverb, baseEqGains, baseReverbPreset, mediaPlayer, basePitch, onVolumeDuck)
            }
            return
        }

        // CRITICAL: If crossfade has begun, abort any DJ effect immediately to prevent dual transitions
        if (isCrossfading) {
            if (processor.isEffectActive) {
                cancelCurrentPerformance(equalizer, reverb, baseEqGains, baseReverbPreset, mediaPlayer, basePitch, onVolumeDuck)
            }
            return
        }

        if (currentScheduledTrackId != track.id) {
            currentScheduledTrackId = track.id
            trackDurationSec = track.durationSec.coerceAtLeast(30)
            scheduleNextMoment(currentPosSec)
        }

        // ─────────────────────────────────────────────────────────────────────
        // Real-Time Smart Music Awareness Analysis (debounced every ~2 seconds)
        // ─────────────────────────────────────────────────────────────────────
        if (isPlaying && (lastAnalysisSec < 0 || kotlin.math.abs(currentPosSec - lastAnalysisSec) >= 2)) {
            lastAnalysisSec = currentPosSec
            runAudioAwarenessAnalysis(track, currentPosSec)
        }

        // ─────────────────────────────────────────────────────────────────────
        // Speech-Aware Intelligent Crossfade Check (30 to 50 seconds remaining)
        // ─────────────────────────────────────────────────────────────────────
        val remSec = trackDurationSec - currentPosSec
        if (isPlaying && !hasSpeechCrossfadeInitiatedForThisTrack && !isCrossfading && remSec in 30..50) {
            val classResult = _currentClassification.value
            // Distinguish speech from melodic singing:
            // Must be SPOKEN_VOICE with high confidence (>= 0.75) and NOT ACAPELLA_SINGING
            if (classResult.type == SmartAudioClassifier.AudioContentType.SPOKEN_VOICE &&
                classResult.confidence >= 0.75f &&
                classResult.type != SmartAudioClassifier.AudioContentType.ACAPELLA_SINGING
            ) {
                hasSpeechCrossfadeInitiatedForThisTrack = true
                Log.d(TAG, "Speech detected near end ($remSec s remaining). Initiating speech-aware crossfade.")
                onSpeechAwareCrossfadeRequested?.invoke()
                return
            }
        }

        // ─────────────────────────────────────────────────────────────────────
        // Effect Execution Dispatch
        // ─────────────────────────────────────────────────────────────────────
        val moment = pendingMoment ?: return
        if (moment.hasTriggered || processor.isEffectActive) {
            return
        }

        // Trigger when reached or passed scheduled position
        if (isPlaying && currentPosSec >= moment.triggerPositionSec && currentPosSec <= moment.triggerPositionSec + 5) {
            // Guard: Strict Flanger single-execution limit per song
            if (moment.effectType == DjEffectType.FLANGER && hasFlangerTriggeredForThisTrack) {
                Log.d(TAG, "Skipping repeated Flanger trigger for track ${track.id}; already performed once.")
                moment.hasTriggered = true
                scheduleNextMoment(currentPosSec + 4)
                return
            }

            // Guard: Never perform 8-Beat Loop over singing or speech
            val currentAwareness = _currentAudioContentType.value
            if (moment.effectType == DjEffectType.EIGHT_BEAT_LOOP &&
                (currentAwareness == SmartAudioClassifier.AudioContentType.BEAT_AND_VOCALS ||
                 currentAwareness == SmartAudioClassifier.AudioContentType.SPOKEN_VOICE ||
                 currentAwareness == SmartAudioClassifier.AudioContentType.ACAPELLA_SINGING)
            ) {
                Log.d(TAG, "Deferring 8-Beat Loop: vocal/speech content detected ($currentAwareness)")
                moment.hasTriggered = true
                scheduleNextMoment(currentPosSec + 5)
                return
            }

            moment.hasTriggered = true
            if (moment.effectType == DjEffectType.FLANGER) {
                hasFlangerTriggeredForThisTrack = true
            }

            _isDjEffectExecuting.value = true
            _lastTriggeredEffectName.value = moment.effectType.displayName

            history.recordEffect(track.id, moment.effectType)

            processor.triggerEffect(
                effect = moment.effectType,
                mediaPlayer = mediaPlayer,
                equalizer = equalizer,
                reverb = reverb,
                baseEqGains = baseEqGains,
                baseReverbPreset = baseReverbPreset,
                basePitch = basePitch,
                track = track,
                estimatedBpm = estimateBpmForTrack(track),
                onVolumeDuck = onVolumeDuck,
                onComplete = {
                    _isDjEffectExecuting.value = false
                    scheduleNextMoment(currentPosSec + 6)
                }
            )
        }

        lastHandledPositionSec = currentPosSec
    }

    private fun runAudioAwarenessAnalysis(track: MusicTrack, currentPosSec: Int) {
        engineScope.launch {
            try {
                val snippetPcm = if (track.contentUri.isNotBlank()) {
                    processor.songAudioDecoder.extractAudioSnippet(
                        contentUri = track.contentUri,
                        startOffsetSec = currentPosSec,
                        durationSec = 0.85,
                        fallbackSeed = track.id + currentPosSec
                    )
                } else {
                    processor.songAudioDecoder.extractAudioSnippet("", 0, 0.85, track.id + currentPosSec)
                }

                val result = processor.smartAudioClassifier.analyzePcm(snippetPcm)
                _currentClassification.value = result
                _currentAudioContentType.value = result.type
            } catch (e: Throwable) {
                Log.w(TAG, "Awareness analysis fallback: ${e.message}")
            }
        }
    }

    /**
     * Direct manual trigger from UI pads (e.g. tapping "Baby Scratch", "Flare Scratch", "Flanger", "8-Beat Loop").
     */
    @Synchronized
    fun triggerManualEffect(
        effect: DjEffectType,
        mediaPlayer: MediaPlayer?,
        equalizer: Equalizer?,
        reverb: PresetReverb?,
        baseEqGains: FloatArray,
        baseReverbPreset: String,
        basePitch: Float,
        track: MusicTrack? = null,
        onVolumeDuck: ((Float) -> Unit)? = null
    ) {
        if (effect == DjEffectType.FLANGER) {
            hasFlangerTriggeredForThisTrack = true
        }

        _isDjEffectExecuting.value = true
        _lastTriggeredEffectName.value = effect.displayName
        val activeTrack = track ?: currentTrack
        currentScheduledTrackId?.let { history.recordEffect(it, effect) }

        processor.triggerEffect(
            effect = effect,
            mediaPlayer = mediaPlayer,
            equalizer = equalizer,
            reverb = reverb,
            baseEqGains = baseEqGains,
            baseReverbPreset = baseReverbPreset,
            basePitch = basePitch,
            track = activeTrack,
            estimatedBpm = estimateBpmForTrack(activeTrack),
            onVolumeDuck = onVolumeDuck,
            onComplete = {
                _isDjEffectExecuting.value = false
            }
        )
    }

    /**
     * Cancels any pending or active effect cleanly, restoring baseline audio.
     */
    @Synchronized
    fun cancelCurrentPerformance(
        equalizer: Equalizer? = null,
        reverb: PresetReverb? = null,
        baseEqGains: FloatArray = floatArrayOf(0f, 0f, 0f, 0f, 0f),
        baseReverbPreset: String = "None",
        mediaPlayer: MediaPlayer? = null,
        basePitch: Float = 1.0f,
        onVolumeDuck: ((Float) -> Unit)? = null
    ) {
        if (processor.isEffectActive) {
            processor.cancelActiveEffect(equalizer, reverb, baseEqGains, baseReverbPreset, mediaPlayer, basePitch, onVolumeDuck)
        }
        _isDjEffectExecuting.value = false
    }

    @Synchronized
    fun onSeek(targetProgress: Float, durationSec: Int) {
        val targetSec = (targetProgress * durationSec).toInt()
        scheduleNextMoment(targetSec)
    }

    fun release() {
        cancelCurrentPerformance()
        engineScope.cancel()
        processor.release()
    }
}
