package com.example.audio

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Lightweight persistent history tracking recent song-and-effect combinations.
 * Ensures variety across playlist playthroughs so replaying the same playlist
 * does not generate the same repetitive effects sequence.
 */
class DjPerformanceHistory(context: Context) {

    companion object {
        private const val PREFS_NAME = "musiplay_dj_performance_history"
        private const val KEY_RECENT_TRACKS = "recent_tracks_csv" // e.g. "101:VINYL_SCRATCH,102:CLEAN,..."
        private const val KEY_LAST_EFFECT = "last_global_effect"
        private const val MAX_HISTORY_ITEMS = 50
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // In-memory cache for fast lookups during playback loops
    private val trackHistoryMap = mutableMapOf<Int, String>()
    private var lastGlobalEffect: String? = null

    init {
        loadHistory()
    }

    private fun loadHistory() {
        try {
            val csv = prefs.getString(KEY_RECENT_TRACKS, "") ?: ""
            trackHistoryMap.clear()
            if (csv.isNotBlank()) {
                csv.split(";").forEach { item ->
                    val parts = item.split(":")
                    if (parts.size == 2) {
                        val trackId = parts[0].toIntOrNull()
                        if (trackId != null) {
                            trackHistoryMap[trackId] = parts[1]
                        }
                    }
                }
            }
            lastGlobalEffect = prefs.getString(KEY_LAST_EFFECT, null)
        } catch (e: Exception) {
            Log.e("DjPerformanceHistory", "Error loading DJ history: ${e.message}")
        }
    }

    private fun persistHistory() {
        try {
            // Keep at most MAX_HISTORY_ITEMS entries
            val trimmedEntries = trackHistoryMap.entries.toList().takeLast(MAX_HISTORY_ITEMS)
            val csv = trimmedEntries.joinToString(";") { "${it.key}:${it.value}" }
            prefs.edit()
                .putString(KEY_RECENT_TRACKS, csv)
                .putString(KEY_LAST_EFFECT, lastGlobalEffect)
                .apply()
        } catch (e: Exception) {
            Log.e("DjPerformanceHistory", "Error saving DJ history: ${e.message}")
        }
    }

    /**
     * Records that an effect was executed on the specified track.
     */
    @Synchronized
    fun recordEffect(trackId: Int, effect: DjEffectType) {
        trackHistoryMap[trackId] = effect.name
        lastGlobalEffect = effect.name
        persistHistory()
    }

    /**
     * Records that a track was played cleanly without any DJ effects.
     */
    @Synchronized
    fun recordCleanPlay(trackId: Int) {
        trackHistoryMap[trackId] = "CLEAN"
        persistHistory()
    }

    /**
     * Returns the last recorded effect for a specific track, or null if never played or played clean.
     */
    @Synchronized
    fun getLastEffectForTrack(trackId: Int): DjEffectType? {
        val name = trackHistoryMap[trackId] ?: return null
        return if (name == "CLEAN") null else DjEffectType.entries.find { it.name == name }
    }

    /**
     * Checks if this track had ANY effect on its most recent playthrough.
     */
    @Synchronized
    fun hadEffectLastPlay(trackId: Int): Boolean {
        val name = trackHistoryMap[trackId] ?: return false
        return name != "CLEAN"
    }

    /**
     * Returns the most recent effect triggered globally across any song.
     * Used by scheduler to prevent consecutive songs from using the exact same effect.
     */
    @Synchronized
    fun getLastGlobalEffect(): DjEffectType? {
        val name = lastGlobalEffect ?: return null
        return DjEffectType.entries.find { it.name == name }
    }

    /**
     * Clears history if requested.
     */
    @Synchronized
    fun clear() {
        trackHistoryMap.clear()
        lastGlobalEffect = null
        prefs.edit().clear().apply()
    }
}
