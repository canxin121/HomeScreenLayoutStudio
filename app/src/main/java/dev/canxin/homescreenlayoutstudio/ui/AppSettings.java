package dev.canxin.homescreenlayoutstudio.ui;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

public final class AppSettings {
    private static final String PREFS = "app_settings";
    private static final String KEY_THEME = "theme";
    private static final String KEY_LANGUAGE = "language";

    public static final String THEME_SYSTEM = "system";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";

    public static final String LANGUAGE_SYSTEM = "system";
    public static final String LANGUAGE_ZH = "zh-CN";
    public static final String LANGUAGE_EN = "en";

    private AppSettings() {
    }

    public static void apply(Context context) {
        AppCompatDelegate.setDefaultNightMode(nightMode(theme(context)));
        String language = language(context);
        AppCompatDelegate.setApplicationLocales(
                LANGUAGE_SYSTEM.equals(language)
                        ? LocaleListCompat.getEmptyLocaleList()
                        : LocaleListCompat.forLanguageTags(language));
    }

    public static String theme(Context context) {
        return prefs(context).getString(KEY_THEME, THEME_SYSTEM);
    }

    public static void setTheme(Context context, String value) {
        prefs(context).edit().putString(KEY_THEME, value).apply();
        AppCompatDelegate.setDefaultNightMode(nightMode(value));
    }

    public static String language(Context context) {
        return prefs(context).getString(KEY_LANGUAGE, LANGUAGE_SYSTEM);
    }

    public static void setLanguage(Context context, String value) {
        prefs(context).edit().putString(KEY_LANGUAGE, value).apply();
        AppCompatDelegate.setApplicationLocales(
                LANGUAGE_SYSTEM.equals(value)
                        ? LocaleListCompat.getEmptyLocaleList()
                        : LocaleListCompat.forLanguageTags(value));
    }

    private static int nightMode(String value) {
        if (THEME_LIGHT.equals(value)) {
            return AppCompatDelegate.MODE_NIGHT_NO;
        }
        if (THEME_DARK.equals(value)) {
            return AppCompatDelegate.MODE_NIGHT_YES;
        }
        return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
