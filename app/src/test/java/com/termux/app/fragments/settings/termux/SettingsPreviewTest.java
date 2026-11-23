package com.termux.app.fragments.settings.termux;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import com.termux.app.activities.SettingsActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.GraphicsMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SettingsPreviewTest {
    private SettingsPreview preview(String key) {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        SettingsPreview view = new SettingsPreview(activity, key);
        int w = View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY);
        int h = View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY);
        view.measure(w, h);
        view.layout(0, 0, 400, 200);
        return view;
    }

    private int imageHash(SettingsPreview view) {
        Bitmap image = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(image));
        int hash = 1;
        for (int y = 0; y < 200; y++) for (int x = 0; x < 400; x++) hash = 31 * hash + image.getPixel(x, y);
        image.recycle();
        return hash;
    }

    @Test public void supportsOnlyVisualProperties() {
        assertTrue(SettingsPreview.supports("terminal-toolbar-height"));
        assertTrue(SettingsPreview.supports("app_accent"));
        assertTrue(SettingsPreview.supports("night-mode"));
        assertTrue(SettingsPreview.supports("terminal-triple-tap-action"));
        assertTrue(SettingsPreview.supports("terminal-cursor-style"));
        assertTrue(SettingsPreview.supports("terminal-toast-position"));
        assertTrue(SettingsPreview.supports("gesture-feedback-position"));
        assertTrue(SettingsPreview.supports("terminal-margin-horizontal"));
        assertTrue(SettingsPreview.supports("extra-keys-button-corner-radius"));
        assertFalse(SettingsPreview.supports("terminal-engine"));
    }

    @Test public void cursorAndToastChoicesChangeTheSample() {
        SettingsPreview cursor = preview("terminal-cursor-style");
        cursor.setValue("block");
        int block = imageHash(cursor);
        cursor.setValue("bar");
        assertNotEquals(block, imageHash(cursor));
        SettingsPreview toast = preview("terminal-toast-position");
        toast.setValue("top-left");
        int top = imageHash(toast);
        toast.setValue("bottom-right");
        assertNotEquals(top, imageHash(toast));
    }

    @Test public void gesturePillPositionChangesTheSample() {
        SettingsPreview pill = preview("gesture-feedback-position");
        pill.setValue("top-left");
        int top = imageHash(pill);
        pill.setValue("bottom-right");
        assertNotEquals(top, imageHash(pill));
    }

    @Test public void invalidEditsKeepTheLastValidPreview() {
        SettingsPreview margin = preview("terminal-margin-horizontal");
        margin.setValue("20");
        int good = imageHash(margin);
        margin.setValue("2000");
        assertEquals(good, imageHash(margin));
        margin.setValue("0");
        assertNotEquals(good, imageHash(margin));
    }

    @Test public void toolbarHeightChangesActualKeyboardHeight() {
        SettingsPreview keys = preview("terminal-toolbar-height");
        keys.setValue("1");
        int normal = keys.getChildAt(0).getLayoutParams().height;
        keys.setValue("2");
        assertEquals(normal * 2, keys.getChildAt(0).getLayoutParams().height, 2);
        keys.setValue("99");
        assertEquals(normal * 2, keys.getChildAt(0).getLayoutParams().height, 2);
    }

    @Test public void actionAndThemePreviewsReflectTheSelection() {
        SettingsPreview action = preview("terminal-triple-tap-action");
        action.setValue("none");
        int none = imageHash(action);
        action.setValue("show-keyboard");
        assertNotEquals(none, imageHash(action));
        SettingsPreview accent = preview("app_accent");
        accent.setValue("blue");
        int blue = imageHash(accent);
        accent.setValue("rose");
        assertNotEquals(blue, imageHash(accent));
        SettingsPreview night = preview("night-mode");
        night.setValue("true");
        int dark = imageHash(night);
        night.setValue("false");
        assertNotEquals(dark, imageHash(night));
    }

    @Test public void extraKeysUsesTheRealKeyboardView() {
        SettingsPreview keys = preview("extra-keys-style");
        keys.setValue("arrows-only");
        assertTrue(keys.getChildAt(0) instanceof dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysView);
        assertEquals(7, ((dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysView) keys.getChildAt(0)).getChildCount());
    }
}
