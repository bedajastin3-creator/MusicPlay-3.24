package com.example.audio

import android.content.Context
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.media.audiofx.PresetReverb
import android.util.Log
import com.example.data.MusicTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * Smart Random DJ Performance Engine.
 * 
 * An intelligent, state-aware scheduler that dynamically decides:
 * 1. Which effect is musically suitable (Intro reveal, mid-track filter/break, or outro scratch/echo-out).
 * 2. Exact musical placement and timing while strictly respecting existing crossfade boundaries.
 * 3. Playlist replay variation: Uses lightweight history to favor different outcomes when replaying
 *    the same playlist, avoiding repetitive effect sequences or consecutive song repetitions.
 * 4. Safe isolation: Never interferes with or resets the existing crossfade or track playback position.
 * 5. Direct manual pad triggering: Allows instant auditioning of any effect on demand.
 */
class SmartDjPerformanceEngine(
    private val context: Context,
    private val processor: DjAudioProcessor,
    private val history: DjPerformanceHistory
) {

    companion object {
        private const val TAG = "SmartDjEngine"
        private const val MIN_TRACK_DURATION_FOR_EFFECTS_SEC = 15
    }

    data class ScheduledDjMoment(
        val effectType: DjEffectType,
        val triggerPositionSec: Int,
        var hasTriggered: Boolean = false
    )

    // Telemetry and active status for UI indicators
    private val _isDjEffectExecuting = MutableStateFlow(false)
    val isDjEffectExecuting: StateFlow<Boolean> = _isDjEffectExecuting.asStateFlow()

    private val _lastTriggeredEffectName = MutableStateFlow<String?>(null)
    val lastTriggeredEffectName: StateFlow<String?> = _lastTriggeredEffectName.asStateFlow()

    // Current track scheduling state
    private var currentScheduledTrackId: Int? = null
    private var pendingMoment: ScheduledDjMoment? = null
    private var lastHandledPositionSec = -1
    private var trackDurationSec = 180
    private var activeCrossfadeSec = 20
    private var activeCrossfadeEnabled = true

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

        currentScheduledTrackId = track.id
        trackDurationSec = track.durationSec.coerceAtLeast(30)
        activeCrossfadeSec = crossfadeSec
        activeCrossfadeEnabled = isCrossfadeEnabled
        lastHandledPositionSec = -1

        if (!isDjEffectsEnabled) {
            pendingMoment = null
            return
        }

        // Schedule first effect for this track (e.g. Intro reveal or early drop within seconds 3-7)
        scheduleNextMoment(currentSec = 0)
    }

    private fun scheduleNextMoment(currentSec: Int) {
        val durationSec = trackDurationSec
        val effectiveCfSec = if (activeCrossfadeEnabled) activeCrossfadeSec.coerceAtLeast(0) else 0
        val crossfadeStartSec = durationSec - effectiveCfSec

        // Avoid scheduling inside or past the crossfade window
        if (currentSec >= crossfadeStartSec - 8) {
            pendingMoment = null
            return
        }

        val lastEffectForThisTrack = currentScheduledTrackId?.let { history.getLastEffectForTrack(it) }
        val lastGlobalEffect = history.getLastGlobalEffect()

        val (section, triggerSec) = when {
            currentSec < 10 -> {
                // Intro moment: between 3s and 7s
                val introSec = Random.nextInt(3, 7.coerceAtMost(durationSec / 3).coerceAtLeast(4))
                DjSection.INTRO to introSec
            }
            currentSec < crossfadeStartSec - 25 -> {
                // Mid-track moment: 20-35s ahead of current position
                val nextSec = currentSec + Random.nextInt(20, 35)
                val clamped = nextSec.coerceAtMost(crossfadeStartSec - 12)
                DjSection.MID_SONG to clamped
            }
            else -> {
                // Outro / transition moment: 6s before crossfade starts
                val outroSec = (crossfadeStartSec - 6).coerceAtLeast(currentSec + 2)
                DjSection.TRANSITION_OR_BREAK to outroSec
            }
        }

        val candidateEffects = DjEffectType.entries.filter { effect ->
            val matchesSection = when (section) {
                DjSection.INTRO -> effect.preferredSection == DjSection.INTRO || effect.preferredSection == DjSection.INTRO_OR_MID
                DjSection.MID_SONG -> effect.preferredSection == DjSection.MID_SONG || effect.preferredSection == DjSection.INTRO_OR_MID
                DjSection.TRANSITION_OR_BREAK -> effect.preferredSection == DjSection.TRANSITION_OR_BREAK
                else -> true
            }
            // Avoid repeating the exact effect used immediately prior
            val notConsecutive = (effect != lastGlobalEffect)
            matchesSection && notConsecutive
        }.ifEmpty {
            DjEffectType.entries.filter { it != lastGlobalEffect }
        }.ifEmpty {
            DjEffectType.entries.toList()
        }

        val chosenEffect = candidateEffects.random()

        pendingMoment = ScheduledDjMoment(
            effectType = chosenEffect,
            triggerPositionSec = triggerSec,
            hasTriggered = false
        )
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

        val moment = pendingMoment ?: return
        if (moment.hasTriggered || processor.isEffectActive) {
            return
        }

        // Trigger when reached or passed scheduled position
        if (isPlaying && currentPosSec >= moment.triggerPositionSec && currentPosSec <= moment.triggerPositionSec + 5) {
            moment.hasTriggered = true
            _isDjEffectExecuting.value = true
            _lastTriggeredEffectName.value = moment.effectType.displayName

            // Record in history for replay variation
            history.recordEffect(track.id, moment.effectType)

            processor.triggerEffect(
                effect = moment.effectType,
                mediaPlayer = mediaPlayer,
                equalizer = equalizer,
                reverb = reverb,
                baseEqGains = baseEqGains,
                baseReverbPreset = baseReverbPreset,
                basePitch = basePitch,
                onVolumeDuck = onVolumeDuck,
                onComplete = {
                    _isDjEffectExecuting.value = false
                    // Schedule next moment in this song if time permits
                    scheduleNextMoment(currentPosSec + 4)
                }
            )
        }

        lastHandledPositionSec = currentPosSec
    }

    /**
     * Direct manual trigger from UI pads (e.g. tapping "Scratch" or "Filter Sweep").
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
        onVolumeDuck: ((Float) -> Unit)? = null
    ) {
        _isDjEffectExecuting.value = true
        _lastTriggeredEffectName.value = effect.displayName
        currentScheduledTrackId?.let { history.recordEffect(it, effect) }

        processor.triggerEffect(
            effect = effect,
            mediaPlayer = mediaPlayer,
            equalizer = equalizer,
            reverb = reverb,
            baseEqGains = baseEqGains,
            baseReverbPreset = baseReverbPreset,
            basePitch = basePitch,
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
        processor.release()
    }
}
