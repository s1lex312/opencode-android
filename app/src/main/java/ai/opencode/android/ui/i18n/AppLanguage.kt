package ai.opencode.android.ui.i18n

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Interface languages the app ships translations for (one `res/values-<tag>` folder each; add an
 * entry here together with the folder). [tag] is a BCP-47 tag; an empty tag means
 * "follow the system language". Names are endonyms on purpose: a user who cannot read the current
 * language must still find their own in the list.
 */
enum class AppLanguage(val tag: String, val endonym: String) {
    SYSTEM("", ""),
    ENGLISH("en", "English"),
    RUSSIAN("ru", "Русский"),
    ;

    companion object {
        /**
         * The per-app language. AppCompat persists it itself (Android 13+ through the system
         * LocaleManager, older versions through `AppLocalesMetadataHolderService`), so it is not
         * part of DataStore.
         */
        fun current(): AppLanguage {
            val locales = AppCompatDelegate.getApplicationLocales()
            if (locales.isEmpty) return SYSTEM
            val locale = locales[0] ?: return SYSTEM
            val tag = locale.toLanguageTag()
            return entries.firstOrNull { it != SYSTEM && it.tag.equals(tag, ignoreCase = true) }
                ?: entries.firstOrNull { it != SYSTEM && it.tag.substringBefore('-') == locale.language }
                ?: SYSTEM
        }

        /** Applies [language]; the activity is recreated by AppCompat with the new resources. */
        fun apply(language: AppLanguage) {
            val locales = if (language == SYSTEM) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(language.tag)
            }
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}
