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
 * 1. Whether an effect should happen (~25–35% initial target frequency for eligible songs).
 * 2. Which effect is musically suitable (Intro reveal, mid-track filter/break, or outro scratch/echo-out).
 * 3. Exact musical placement and timing while strictly respecting existing crossfade boundaries.
 * 4. Playlist replay variation: Uses lightweight history to favor different outcomes when replaying
 *    the same playlist, avoiding repetitive effect sequences or consecutive song repetitions.
 * 5. Safe isolation: Never interferes with or resets the existing crossfade or track playback position.
 */
class SmartDjPerformanceEngine(
    private val context: Context,
    private val processor: DjAudioProcessor,
    private val history: DjPerformanceHistory
) {

    companion object {
        private const val TAG = "SmartDjEngine"
        private const val MIN_TRACK_DURATION_FOR_EFFECTS_SEC = 25
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

    /**
     * Called whenever a new track begins or is loaded.
     * Evaluates whether this track is eligible for an effect, and if so, schedules
     * a tailored, randomized effect moment considering past replay history.
     */
    @Synchronized
    fun onTrackStarted(
        track: MusicTrack,
        isDjEffectsEnabled: Boolean,
        crossfadeSec: Int,
        isCrossfadeEnabled: Boolean
    ) {
        // Cancel any active effect cleanly when track changes
        cancelCurrentPerformance()

        currentScheduledTrackId = track.id
        lastHandledPositionSec = -1

        if (!isDjEffectsEnabled) {
            pendingMoment = null
            return
        }

        val durationSec = track.durationSec
        if (durationSec < MIN_TRACK_DURATION_FOR_EFFECTS_SEC) {
            // Very short track: deliberately leave clean
            history.recordCleanPlay(track.id)
            pendingMoment = null
            return
        }

        // 1. Evaluate effect eligibility:
        // Target: ~25-35% of eligible songs receive an effect.
        // Check replay history to create variation between playlist playthroughs:
        val hadEffectLastTime = history.hadEffectLastPlay(track.id)
        val selectionProbability = if (hadEffectLastTime) {
            // Favor clean play on this playthrough if it had an effect last time (~12%)
            0.12f
        } else {
            // Favor an effect if it was clean last playthrough (~36%)
            0.36f
        }

        val shouldPerform = Random.nextFloat() < selectionProbability
        if (!shouldPerform) {
            // Deliberately untouched for natural listening experience
            history.recordCleanPlay(track.id)
            pendingMoment = null
            return
        }

        // 2. Select appropriate effect based on history and section
        val lastEffectForThisTrack = history.getLastEffectForTrack(track.id)
        val lastGlobalEffect = history.getLastGlobalEffect()

        // Choose candidate section:
        // - INTRO (0-4s): ~30% chance
        // - MID_SONG (35-55% duration): ~40% chance
        // - OUTRO (before crossfade): ~30% chance
        val sectionRoll = Random.nextFloat()
        val section = when {
            sectionRoll < 0.30f -> DjSection.INTRO
            sectionRoll < 0.70f -> DjSection.MID_SONG
            else -> DjSection.TRANSITION_OR_BREAK
        }

        val candidateEffects = DjEffectType.entries.filter { effect ->
            val matchesSection = when (section) {
                DjSection.INTRO -> effect.preferredSection == DjSection.INTRO || effect.preferredSection == DjSection.INTRO_OR_MID
                DjSection.MID_SONG -> effect.preferredSection == DjSection.MID_SONG || effect.preferredSection == DjSection.INTRO_OR_MID
                DjSection.TRANSITION_OR_BREAK -> effect.preferredSection == DjSection.TRANSITION_OR_BREAK
                else -> true
            }
            // Avoid repeating the exact effect used on the immediately preceding song
            val notConsecutive = (effect != lastGlobalEffect)
            // Avoid repeating the effect used on this specific track last time
            val notSameAsLastPlay = (effect != lastEffectForThisTrack)
            matchesSection && notConsecutive && notSameAsLastPlay
        }.ifEmpty {
            // Fallback: any effect matching section that is not consecutive
            DjEffectType.entries.filter { it != lastGlobalEffect }
        }.ifEmpty {
            DjEffectType.entries.toList()
        }

        val chosenEffect = candidateEffects.random()

        // 3. Compute safe trigger position based on section and existing crossfade boundaries
        val effectiveCrossfadeSec = if (isCrossfadeEnabled) crossfadeSec.coerceAtLeast(0) else 0

        val triggerSec = when (section) {
            DjSection.INTRO -> {
                // Intro: between second 1 and 3
                val maxIntro = 3.coerceAtMost(durationSec / 4).coerceAtLeast(2)
                Random.nextInt(1, maxIntro)
            }
            DjSection.MID_SONG, DjSection.INTRO_OR_MID -> {
                // Mid: stable middle portion (35% - 55% of track duration)
                val minMid = (durationSec * 0.35f).toInt()
                val maxMid = (durationSec * 0.55f).toInt().coerceAtLeast(minMid + 1)
                Random.nextInt(minMid, maxMid)
            }
            DjSection.TRANSITION_OR_BREAK -> {
                // Outro / Transition:
                // MUST trigger and complete BEFORE the crossfade starts so they never compete!
                // If crossfade is 20s, crossfade begins at (durationSec - 20).
                // We schedule outro effect at (durationSec - 20 - 5) seconds.
                val bufferBeforeCrossfade = 5
                val outroStart = (durationSec - effectiveCrossfadeSec - bufferBeforeCrossfade)
                    .coerceIn(durationSec / 2, (durationSec - effectiveCrossfadeSec - 2).coerceAtLeast(durationSec / 2))
                outroStart
            }
        }

        pendingMoment = ScheduledDjMoment(
            effectType = chosenEffect,
            triggerPositionSec = triggerSec,
            hasTriggered = false
        )
    }

    /**
     * Called on each playback progress loop tick (e.g. ~35ms) by MusicPlaybackManager.
     * Evaluates if conditions are safe and timing is reached to trigger the scheduled moment.
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

        // If track changed, reset
        if (currentScheduledTrackId != track.id) {
            return
        }

        // Seeking detection: If position jumped backward or jumped ahead past the window
        val moment = pendingMoment ?: return
        if (moment.hasTriggered || processor.isEffectActive) {
            return
        }

        // Trigger when reached scheduled position
        if (isPlaying && currentPosSec >= moment.triggerPositionSec && currentPosSec <= moment.triggerPositionSec + 3) {
            moment.hasTriggered = true
            _isDjEffectExecuting.value = true
            _lastTriggeredEffectName.value = moment.effectType.displayName

            // Record in history for future replay variety
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
                }
            )
        }

        lastHandledPositionSec = currentPosSec
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

    /**
     * Called when the user seeks: if seeking past the scheduled moment, mark as skipped.
     */
    @Synchronized
    fun onSeek(targetProgress: Float, durationSec: Int) {
        val targetSec = (targetProgress * durationSec).toInt()
        pendingMoment?.let { moment ->
            if (targetSec > moment.triggerPositionSec + 3) {
                // Seeked past the effect window: skip it rather than glitching
                moment.hasTriggered = true
            }
        }
    }

    fun release() {
        cancelCurrentPerformance()
        processor.release()
    }
}
