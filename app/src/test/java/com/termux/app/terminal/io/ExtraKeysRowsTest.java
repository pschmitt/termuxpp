package com.termux.app.terminal.io;

import android.app.Activity;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;

import com.termux.R;

import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysConstants;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysInfo;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysView;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxYamlConfig;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w400dp-h800dp-xxhdpi")
public class ExtraKeysRowsTest {

    /** The two-row layout a phone's Home Manager config writes must show both rows inside the toolbar. */
    @Test public void twoRowYamlLayoutBuildsBothRows() throws Exception {
        String yaml;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("extra-keys-two-rows.yaml")) {
            assertNotNull(in);
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Properties properties = TermuxYamlConfig.read(yaml, null);
        String layout = properties.getProperty("extra-keys");
        assertNotNull(layout);
        ExtraKeysInfo info = new ExtraKeysInfo(layout, "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES);
        assertEquals(2, info.getMatrix().length);

        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ExtraKeysView keys = new ExtraKeysView(
            new ContextThemeWrapper(activity, R.style.Theme_TermuxActivity_DayNight_NoActionBar), null);
        FrameLayout root = new FrameLayout(activity);
        // Two rows of the toolbar's default 37.5dp at xxhdpi, as on a phone.
        int height = Math.round(2 * 37.5f * activity.getResources().getDisplayMetrics().density);
        root.addView(keys, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
        activity.setContentView(root);
        keys.setAppearance(properties.getProperty("extra-keys-theme"), null, null, null, 7f);
        keys.reload(info, height / 2f);
        // At startup the toolbar can be measured taller before it settles at its final height.
        keys.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height * 2 + 20, View.MeasureSpec.EXACTLY));
        keys.layout(0, 0, 1200, height * 2 + 20);
        keys.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        keys.layout(0, 0, 1200, height);

        assertEquals(2, keys.getRowCount());
        assertEquals(12, keys.getChildCount());
        View secondRow = keys.getChildAt(6);
        assertTrue("second row starts inside the toolbar: " + secondRow.getTop(), secondRow.getTop() < height / 2 + 10);
        assertTrue("second row ends inside the toolbar: " + secondRow.getBottom(), secondRow.getBottom() <= height);

        // Rows that overflow the view, whatever the cause, are rebuilt to fit once.
        GridLayout.LayoutParams tall = (GridLayout.LayoutParams) keys.getChildAt(0).getLayoutParams();
        tall.height = height * 3;
        keys.getChildAt(0).setLayoutParams(tall);
        layout(keys, height);
        assertTrue("the forced key overflows", keys.getChildAt(6).getBottom() > height);
        ShadowLooper.idleMainLooper();
        layout(keys, height);
        assertEquals(12, keys.getChildCount());
        assertTrue("rebuilt second row fits: " + keys.getChildAt(6).getBottom(), keys.getChildAt(6).getBottom() <= height);
    }

    private static void layout(View view, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 1200, height);
    }
}
