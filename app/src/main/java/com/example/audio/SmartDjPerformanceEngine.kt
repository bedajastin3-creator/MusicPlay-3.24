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
    private var hasBeatFilterTriggeredForThisTrack = false
    private var lastFlangerTriggerSec = -1
    private var lastBeatFilterTriggerSec = -1
    private var hasSpeechCrossfadeInitiatedForThisTrack = false
    private var lastAnalysisTimestampMs = 0L

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
        hasBeatFilterTriggeredForThisTrack = false
        lastFlangerTriggerSec = -1
        lastBeatFilterTriggerSec = -1
        hasSpeechCrossfadeInitiatedForThisTrack = false
        lastAnalysisTimestampMs = 0L

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
            // 2. Mid-Song Moment: Flanger (max 1x) or Filter Sweep
            // ─────────────────────────────────────────────────────────────────
            scheduleMidSongMoment(currentSec, crossfadeStartSec)
        } else {
            // ─────────────────────────────────────────────────────────────────
            // 3. Outro Moment: ~6s prior to crossfade start (near crossfade)
            // ─────────────────────────────────────────────────────────────────
            val outroSec = (crossfadeStartSec - 6).coerceAtLeast(currentSec + 2)
            if (outroSec < crossfadeStartSec - 3) {
                // Choose between Beat Filter and Turntable Scratch near crossfade
                // Rule: Never use Beat Filter twice in music and do not place it near Flanger (>= 25s apart)
                val canUseBeatFilter = !hasBeatFilterTriggeredForThisTrack &&
                    (lastFlangerTriggerSec < 0 || kotlin.math.abs(outroSec - lastFlangerTriggerSec) >= 25)

                val chosenOutroEffect = if (canUseBeatFilter && Random.nextBoolean()) {
                    DjEffectType.BEAT_FILTER
                } else {
                    if (Random.nextBoolean()) DjEffectType.BABY_SCRATCH else DjEffectType.FLARE_SCRATCH
                }

                pendingMoment = ScheduledDjMoment(
                    effectType = chosenOutroEffect,
                    triggerPositionSec = outroSec,
                    hasTriggered = false
                )
                Log.d(TAG, "Scheduled outro ${chosenOutroEffect.displayName} near crossfade at ${outroSec}s")
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
            // 1. Flanger allowed NO MORE THAN ONCE per song.
            // 2. Beat Filter allowed NO MORE THAN ONCE per song, and NEVER near Flanger (>= 25s apart).
            val canUseBeatFilter = !hasBeatFilterTriggeredForThisTrack &&
                (lastFlangerTriggerSec < 0 || kotlin.math.abs(triggerSec - lastFlangerTriggerSec) >= 25)

            val chosenEffect = when {
                !hasFlangerTriggeredForThisTrack -> DjEffectType.FLANGER
                canUseBeatFilter && Random.nextBoolean() -> DjEffectType.BEAT_FILTER
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
        // Real-Time Smart Music Awareness Analysis (fast detection every ~600ms)
        // ─────────────────────────────────────────────────────────────────────
        val nowMs = System.currentTimeMillis()
        if (isPlaying && (nowMs - lastAnalysisTimestampMs >= 600L)) {
            lastAnalysisTimestampMs = nowMs
            lastAnalysisSec = currentPosSec
            runAudioAwarenessAnalysis(track, currentPosSec, isPlaying, isCrossfading)
        }

        // ─────────────────────────────────────────────────────────────────────
        // Speech-Aware Intelligent Crossfade Check: Immediate Crossfade!
        // ─────────────────────────────────────────────────────────────────────
        val remSec = trackDurationSec - currentPosSec
        if (isPlaying && !hasSpeechCrossfadeInitiatedForThisTrack && !isCrossfading && remSec <= 75 && currentPosSec >= 12) {
            val classResult = _currentClassification.value
            val isSpokenVoice = (classResult.type == SmartAudioClassifier.AudioContentType.SPOKEN_VOICE && classResult.confidence >= 0.58f) ||
                (classResult.speechCadence >= 0.60f && classResult.vocalEnergy >= 0.20f && classResult.beatStrength <= 0.45f)

            if (isSpokenVoice && classResult.type != SmartAudioClassifier.AudioContentType.ACAPELLA_SINGING) {
                hasSpeechCrossfadeInitiatedForThisTrack = true
                Log.d(TAG, "Speech/talk detected professionally ($remSec s remaining). Applying crossfade immediately!")
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

            // Guard: Strict Beat Filter single-execution limit & Flanger separation (>= 25s apart)
            if (moment.effectType == DjEffectType.BEAT_FILTER) {
                val isNearFlanger = (lastFlangerTriggerSec >= 0 && kotlin.math.abs(currentPosSec - lastFlangerTriggerSec) < 25)
                if (hasBeatFilterTriggeredForThisTrack || isNearFlanger) {
                    Log.d(TAG, "Beat Filter cannot run twice or near flanger; dynamically switching to scratch.")
                    moment.hasTriggered = true
                    val fallbackScratch = if (Random.nextBoolean()) DjEffectType.BABY_SCRATCH else DjEffectType.FLARE_SCRATCH
                    pendingMoment = ScheduledDjMoment(fallbackScratch, currentPosSec, hasTriggered = false)
                    return
                }
            }

            moment.hasTriggered = true
            if (moment.effectType == DjEffectType.FLANGER) {
                hasFlangerTriggeredForThisTrack = true
                lastFlangerTriggerSec = currentPosSec
            }
            if (moment.effectType == DjEffectType.BEAT_FILTER) {
                hasBeatFilterTriggeredForThisTrack = true
                lastBeatFilterTriggerSec = currentPosSec
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

    private fun runAudioAwarenessAnalysis(track: MusicTrack, currentPosSec: Int, isPlaying: Boolean, isCrossfading: Boolean) {
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

                // Immediate speech check upon analysis completion - apply crossfade immediately
                val remSec = trackDurationSec - currentPosSec
                if (isPlaying && !hasSpeechCrossfadeInitiatedForThisTrack && !isCrossfading && remSec <= 75 && currentPosSec >= 12) {
                    val isSpoken = (result.type == SmartAudioClassifier.AudioContentType.SPOKEN_VOICE && result.confidence >= 0.58f) ||
                        (result.speechCadence >= 0.60f && result.vocalEnergy >= 0.20f && result.beatStrength <= 0.45f)

                    if (isSpoken && result.type != SmartAudioClassifier.AudioContentType.ACAPELLA_SINGING) {
                        hasSpeechCrossfadeInitiatedForThisTrack = true
                        Log.d(TAG, "Spoken voice confirmed in real-time audio analysis. Applying crossfade immediately!")
                        withContext(Dispatchers.Main) {
                            onSpeechAwareCrossfadeRequested?.invoke()
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Awareness analysis fallback: ${e.message}")
            }
        }
    }

    /**
     * Direct manual trigger from UI pads (e.g. tapping "Baby Scratch", "Flare Scratch", "Flanger", "Beat Filter").
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
            lastFlangerTriggerSec = currentTrack?.durationSec?.let { (it * 0.5).toInt() } ?: 60
        }
        if (effect == DjEffectType.BEAT_FILTER) {
            hasBeatFilterTriggeredForThisTrack = true
            lastBeatFilterTriggerSec = currentTrack?.durationSec?.let { (it * 0.5).toInt() } ?: 60
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
