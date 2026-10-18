package com.thor.instantreplay;

import android.content.Context;
import android.content.res.Configuration;
import java.util.Locale;

/** UI preference only; existing replay settings stay in thor_replay_prefs. */
public final class AppLanguage {
    private AppLanguage() {}
    public static String get(Context context) {
        return context.getSharedPreferences("android_replay_ui", Context.MODE_PRIVATE)
                .getString("language", "vi");
    }
    public static void set(Context context, String language) {
        if (!"en".equals(language) && !"vi".equals(language)) throw new IllegalArgumentException("language");
        context.getSharedPreferences("android_replay_ui", Context.MODE_PRIVATE).edit()
                .putString("language", language).apply();
        context.getSharedPreferences("thorqol_ui", Context.MODE_PRIVATE).edit()
                .putString("language", language).apply();
    }
    public static Context wrap(Context context) {
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(Locale.forLanguageTag(get(context)));
        return context.createConfigurationContext(config);
    }
    public static String text(Context context, String vietnamese, String english) {
        return "en".equals(get(context)) ? english : vietnamese;
    }
}
