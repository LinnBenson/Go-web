package io.todu.goweb;

import android.app.Activity;
import android.content.ComponentName;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.content.IntentCompat;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester;

/** Configuration stays in the same lightweight process as the complication service. */
public final class ComplicationConfigActivity extends Activity {
    private int instanceId;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setResult(RESULT_CANCELED);
        instanceId = getIntent().getIntExtra(
            ComplicationDataSourceService.EXTRA_CONFIG_COMPLICATION_ID, -1);
        ComponentName provider = IntentCompat.getParcelableExtra(getIntent(),
            ComplicationDataSourceService.EXTRA_CONFIG_DATA_SOURCE_COMPONENT, ComponentName.class);
        ComponentName expected = new ComponentName(this, OpenAppComplicationService.class);
        if (instanceId < 0 || (provider != null
            && (!getPackageName().equals(provider.getPackageName())
                || !OpenAppComplicationService.class.getName().equals(provider.getClassName())))) {
            finish();
            return;
        }
        int selected = ComplicationIcons.selected(this, instanceId);
        boolean round = getResources().getConfiguration().isScreenRound();
        int width = getResources().getDisplayMetrics().widthPixels;
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.setClipToPadding(false);
        scroll.setPadding(round ? Math.round(width * .14f) : dp(16),
            round ? Math.round(width * .18f) : dp(20),
            round ? Math.round(width * .14f) : dp(16),
            round ? Math.round(width * .24f) : dp(24));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        TextView title = new TextView(this);
        title.setText(R.string.complication_choose_icon);
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(12));
        list.addView(title, new LinearLayout.LayoutParams(-1, -2));
        for (int i = 0; i < ComplicationIcons.KEYS.length; i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(52));
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            GradientDrawable background = new GradientDrawable();
            background.setColor(i == selected ? 0xff193d35 : 0xff1b2326);
            background.setCornerRadius(dp(18));
            if (i == selected) background.setStroke(dp(1), 0xff7de8ca);
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x447de8ca), background, null));
            row.setFocusable(true);
            row.setSelected(i == selected);
            row.setContentDescription(getString(ComplicationIcons.LABELS[i])
                + (i == selected ? getString(R.string.complication_selected) : ""));
            ImageView icon = new ImageView(this);
            icon.setImageResource(ComplicationIcons.DRAWABLES[i]);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
            TextView name = new TextView(this);
            name.setText(ComplicationIcons.LABELS[i]);
            name.setTextSize(15);
            name.setTextColor(Color.WHITE);
            name.setPadding(dp(8), 0, 0, 0);
            name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            if (i == selected) {
                TextView check = new TextView(this);
                check.setText("✓");
                check.setTextSize(18);
                check.setTextColor(0xff7de8ca);
                check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                row.addView(check);
            }
            row.setOnClickListener(view -> {
                ComplicationIcons.save(this, instanceId, index);
                ComplicationDataSourceUpdateRequester.create(this, expected).requestUpdate(instanceId);
                setResult(RESULT_OK);
                finish();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = dp(6);
            list.addView(row, params);
        }
        setContentView(scroll);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
