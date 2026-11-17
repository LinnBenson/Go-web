package io.todu.goweb;

import android.content.Context;

/** Stable keys keep selections intact when more icons are added. Used only in :shortcut. */
final class ComplicationIcons {
    static final String[] KEYS = {"classic", "go", "globe", "window", "navigate",
        "saturn", "bot", "spark", "puppy", "ufo", "cat", "bear"};
    static final int[] DRAWABLES = {R.drawable.ic_complication_go,
        R.drawable.ic_complication_wordmark, R.drawable.ic_complication_globe,
        R.drawable.ic_complication_window, R.drawable.ic_complication_navigate,
        R.drawable.ic_complication_saturn, R.drawable.ic_complication_bot, R.drawable.ic_complication_spark, R.drawable.ic_complication_puppy, R.drawable.ic_complication_ufo, R.drawable.ic_complication_cat, R.drawable.ic_complication_bear};
    static final int[] LABELS = {R.string.complication_classic, R.string.complication_wordmark,
        R.string.complication_globe, R.string.complication_window, R.string.complication_navigate,
        R.string.complication_saturn, R.string.complication_bot, R.string.complication_spark, R.string.complication_puppy, R.string.complication_ufo, R.string.complication_cat, R.string.complication_bear};
    private static final String PREFERENCES = "complication_icons";

    static int selected(Context context, int instanceId) {
        String key = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString("icon_" + instanceId, KEYS[0]);
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return i;
        return 0;
    }

    static void save(Context context, int instanceId, int index) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString("icon_" + instanceId, KEYS[index]).apply();
    }

    private ComplicationIcons() {}
}
