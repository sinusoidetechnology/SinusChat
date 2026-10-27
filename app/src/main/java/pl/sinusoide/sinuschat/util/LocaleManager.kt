package pl.sinusoide.sinuschat.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleManager {

    const val LANG_POLISH  = "pl"
    const val LANG_ENGLISH = "en"
    const val LANG_CZECH = "cs"
    const val LANG_UKRAINIAN = "uk"
    const val LANG_GERMAN = "de"
    const val LANG_CHINESE_TRAD = "zh-TW"
    const val LANG_SYSTEM  = "system"

    private const val PREFS = "sinuschat_locale_prefs"
    private const val KEY_LANG = "app_lang"

    val BUILT_IN = listOf(LANG_POLISH, LANG_ENGLISH, LANG_CZECH, LANG_UKRAINIAN, LANG_GERMAN, LANG_CHINESE_TRAD)

    fun getCurrent(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANG, LANG_POLISH) ?: LANG_POLISH

    fun setCurrent(ctx: Context, lang: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG, lang).commit()
    }

    fun systemLanguage(): String {
        val sys = android.content.res.Resources.getSystem().configuration.locales[0]?.language
        if (sys == "zh") return LANG_CHINESE_TRAD
        return if (sys != null && sys in BUILT_IN) sys else LANG_ENGLISH
    }

    fun resolve(lang: String): String = if (lang == LANG_SYSTEM) systemLanguage() else lang

    fun getEffective(ctx: Context): String = resolve(getCurrent(ctx))

    fun wrap(ctx: Context): Context {
        val locale = Locale.forLanguageTag(getEffective(ctx))
        Locale.setDefault(locale)
        return localized(ctx, locale.toLanguageTag())
    }

    fun localized(ctx: Context, lang: String): Context {
        val locale = Locale.forLanguageTag(lang)
        val config = Configuration(ctx.resources.configuration)
        config.setLocale(locale)
        return ctx.createConfigurationContext(config)
    }

    fun displayName(lang: String): String = when (lang) {
        LANG_POLISH  -> "Polski"
        LANG_ENGLISH -> "English"
        LANG_CZECH -> "Čeština"
        LANG_UKRAINIAN -> "Українська"
        LANG_GERMAN -> "Deutsch"
        LANG_CHINESE_TRAD -> "繁體中文"
        "es" -> "Español"
        "fr" -> "Français"
        "it" -> "Italiano"
        else -> lang
    }

    fun displayName(ctx: Context, lang: String): String =
        if (lang == LANG_SYSTEM)
            ctx.getString(pl.sinusoide.sinuschat.R.string.lang_system_value, displayName(systemLanguage()))
        else displayName(lang)
}
