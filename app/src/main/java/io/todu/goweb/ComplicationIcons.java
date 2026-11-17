package io.todu.goweb;

import android.content.Context;

/** Stable keys keep selections intact when more icons are added. Used only in :shortcut. */
final class ComplicationIcons {
    static final String[] KEYS = {"classic", "go", "globe", "window", "navigate"};
    static final int[] DRAWABLES = {R.drawable.ic_complication_go,
        R.drawable.ic_complication_wordmark, R.drawable.ic_complication_globe,
        R.drawable.ic_complication_window, R.drawable.ic_complication_navigate};
    static final int[] LABELS = {R.string.complication_classic, R.string.complication_wordmark,
        R.string.complication_globe, R.string.complication_window, R.string.complication_navigate};
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
