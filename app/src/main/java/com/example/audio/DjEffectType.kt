package com.example.audio

/**
 * Supported DJ Audio Effects for the Smart Random DJ Performance Engine.
 * Each effect represents a distinct, professional DJ performance technique
 * supported natively by the Android audio processing pipeline.
 */
enum class DjEffectType(
    val displayName: String,
    val description: String,
    val preferredSection: DjSection
) {
    BABY_SCRATCH(
        displayName = "Baby Scratch",
        description = "Authentic turntable forward-backward vinyl manipulation of the actual song",
        preferredSection = DjSection.INTRO_OR_MID
    ),
    FLARE_SCRATCH(
        displayName = "Flare Scratch",
        description = "Advanced turntable flare scratch with rhythmic crossfader cuts into the song",
        preferredSection = DjSection.INTRO_OR_MID
    ),
    FLANGER(
        displayName = "Flanger",
        description = "Genuine DSP flanger with sweeping LFO comb filter and wet/dry automation",
        preferredSection = DjSection.MID_SONG
    ),
    VINYL_SCRATCH(
        displayName = "Vinyl Scratch",
        description = "Crisp, controlled DJ vinyl scratch burst during musical breaks or transitions",
        preferredSection = DjSection.TRANSITION_OR_BREAK
    ),
    FILTER_SWEEP(
        displayName = "Filter Sweep",
        description = "Smooth frequency filter sweep opening or closing across audio spectrum",
        preferredSection = DjSection.INTRO_OR_MID
    ),
    BEAT_FILTER(
        displayName = "Beat-Filtering",
        description = "Rhythmic tonal filter pulses aligned with musical phrasing",
        preferredSection = DjSection.MID_SONG
    ),
    RADIO_FILTER(
        displayName = "Radio / Vocal Filter",
        description = "Vintage bandpass filter emphasizing mid-range frequencies",
        preferredSection = DjSection.MID_SONG
    ),
    ECHO_OUT(
        displayName = "Echo & Decay",
        description = "Atmospheric spatial echo with natural acoustic tail decay",
        preferredSection = DjSection.TRANSITION_OR_BREAK
    ),
    VINYL_SPIN_UP(
        displayName = "Vinyl Spin-Up",
        description = "Subtle turntable startup speed ramp at the beginning of a track",
        preferredSection = DjSection.INTRO
    )
}

enum class DjSection {
    INTRO,
    INTRO_OR_MID,
    MID_SONG,
    TRANSITION_OR_BREAK
}
