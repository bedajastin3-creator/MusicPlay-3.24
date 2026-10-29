package com.example.ui.localization

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.LayoutDirection

enum class AppLanguage(
    val code: String,
    val displayName: String,
    val nativeName: String,
    val flag: String,
    val isRtl: Boolean = false
) {
    ENGLISH("en", "English", "English", "🇺🇸", false),
    FRENCH("fr", "French", "Français", "🇫🇷", false),
    PORTUGUESE("pt", "Portuguese", "Português", "🇵🇹", false),
    ARABIC("ar", "Arabic", "العربية", "🇸🇦", true),
    CHINESE("zh", "Chinese", "中文", "🇨🇳", false),
    HINDI("hi", "Hindi", "हिन्दी", "🇮🇳", false),
    JAPANESE("ja", "Japanese", "日本語", "🇯🇵", false),
    RUSSIAN("ru", "Russian", "Русский", "🇷🇺", false),
    VIETNAMESE("vi", "Vietnamese", "Tiếng Việt", "🇻🇳", false),
    SPANISH("es", "Spanish (Chile)", "Español", "🇨🇱", false),
    PORTUGUESE_BRAZIL("pt-br", "Portuguese (Brazil)", "Português", "🇧🇷", false),
    INDONESIAN("id", "Indonesian", "Bahasa Indonesia", "🇮🇩", false),
    THAI("th", "Thai", "ไทย", "🇹🇭", false),
    TURKISH("tr", "Turkish", "Türkçe", "🇹🇷", false),
    GERMAN("de", "German", "Deutsch", "🇩🇪", false),
    ITALIAN("it", "Italian", "Italiano", "🇮🇹", false),
    KOREAN("ko", "Korean", "한국어", "🇰🇷", false);

    val layoutDirection: LayoutDirection
        get() = if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr

    companion object {
        fun fromCode(code: String?): AppLanguage {
            return entries.find { it.code.equals(code, ignoreCase = true) } ?: ENGLISH
        }
    }
}

val LocalAppStrings = compositionLocalOf<AppStrings> {
    EnglishStrings
}

val LocalAppLanguage = compositionLocalOf<AppLanguage> {
    AppLanguage.ENGLISH
}
