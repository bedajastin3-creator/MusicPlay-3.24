package com.example.data

enum class TrackSortOrder {
    TITLE_ASC,
    TITLE_DESC,
    SIZE_DESC,
    SIZE_ASC,
    DURATION_DESC,
    DURATION_ASC,
    ARTIST_ASC,
    DATE_ADDED_DESC;

    companion object {
        fun fromName(name: String?): TrackSortOrder {
            return entries.find { it.name.equals(name, ignoreCase = true) } ?: TITLE_ASC
        }
    }
}
