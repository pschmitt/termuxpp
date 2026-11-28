package com.termux.app.terminal.io;

import android.app.Activity;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.google.android.material.button.MaterialButton;
import com.termux.R;

import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeyButton;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysConstants;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysInfo;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysView;
import dev.pschmitt.termux.shared.termux.settings.properties.TerminalGestureAction;

import org.json.JSONException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w1000dp-h800dp-mdpi")
public class ExtraKeyMenuAndActionsTest {
    private final List<String> pressed = new ArrayList<>();

    private static ExtraKeyButton parse(String layout) throws JSONException {
        return new ExtraKeysInfo(layout, "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES).getMatrix()[0][0];
    }

    private ExtraKeysView keys(String layout) throws JSONException {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ExtraKeysView keys = new ExtraKeysView(
            new ContextThemeWrapper(activity, R.style.Theme_TermuxApp_DayNight_NoActionBar), null);
        FrameLayout root = new FrameLayout(activity);
        root.addView(keys, new FrameLayout.LayoutParams(600, 100));
        activity.setContentView(root, new ViewGroup.LayoutParams(1000, 800));
        keys.reload(new ExtraKeysInfo(layout, "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES), 100);
        keys.setExtraKeysViewClient(new ExtraKeysView.IExtraKeysView() {
            @Override public void onExtraKeyButtonClick(View view, ExtraKeyButton key, MaterialButton button) {
                pressed.add(key.getAction() != null ? "action:" + key.getAction().getValue() : key.getKey());
            }

            @Override public boolean performExtraKeyButtonHapticFeedback(View view, ExtraKeyButton key, MaterialButton button) {
                return true;
            }
        });
        root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1000, 800);
        return keys;
    }

    private static void touch(View view, int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        view.dispatchTouchEvent(event);
        event.recycle();
    }

    @Test public void keysParseActionsMenusAndDoubleTaps() throws Exception {
        ExtraKeyButton sidebar = parse("[[{action:'open-sidebar'}]]");
        assertEquals(TerminalGestureAction.OPEN_SIDEBAR, sidebar.getAction());
        assertEquals("☰", sidebar.getDisplay());
        assertEquals("Panes", parse("[[{action:'open-tmux-switcher',display:'Panes'}]]").getDisplay());

        ExtraKeyButton pipe = parse("[[{key:'|',menu:['~','/',{action:'open-menu'}],'double-tap':{action:'new-session'}}]]");
        assertEquals(3, pipe.getMenu().size());
        assertEquals("/", pipe.getMenu().get(1).getKey());
        assertEquals(TerminalGestureAction.OPEN_MENU, pipe.getMenu().get(2).getAction());
        assertEquals(TerminalGestureAction.NEW_SESSION, pipe.getDoubleTap().getAction());
        assertFalse(pipe.getMenu().get(0).isMenuDefault());
        ExtraKeyButton dash = parse("[[{key:'|',menu:['~',{key:'-',default:true},{key:'/',default:false}]}]]");
        assertTrue(dash.getMenu().get(1).isMenuDefault());
        assertFalse(dash.getMenu().get(2).isMenuDefault());
        assertEquals(TerminalGestureAction.PREVIOUS_SESSION,
            parse("[[{key:'a','fling-left':{action:'previous-session'}}]]").getFling(false).getAction());

        for (String invalid : new String[]{
            "[[{action:'teleport'}]]",
            "[[{action:'send-keys'}]]",
            "[[{action:'open-menu',key:'a'}]]",
            "[[{key:'a',menu:[]}]]",
            "[[{key:'a',menu:'~'}]]",
            "[[{key:'a',menu:['1','2','3','4','5','6','7','8','9','10','11','12','13']}]]",
            "[[{key:'a',menu:['~'],hold:'CTRL'}]]",
            "[[{key:'a',menu:[{key:'~',default:true},{key:'/',default:true}]}]]",
            "[[{key:'a',menu:[{key:'~',default:'yes'}]}]]",
        }) {
            assertThrows(invalid, JSONException.class, () -> parse(invalid));
        }
    }

    @Test public void longPressOpensTheMenuAndSlidingPicksAnItem() throws Exception {
        ExtraKeysView keys = keys("[[{key:'|',menu:['~','/','\\\\']},'a','b','c','d','e']]");
        View pipe = keys.getChildAt(0);
        assertEquals(100, pipe.getWidth());

        touch(pipe, MotionEvent.ACTION_DOWN, 50, 50);
        ShadowLooper.idleMainLooper(ViewConfiguration.getLongPressTimeout() + 50, TimeUnit.MILLISECONDS);
        // The menu sits at the left screen edge; its third cell starts at 6 + 2 * 100 px.
        touch(pipe, MotionEvent.ACTION_MOVE, 260, 50);
        touch(pipe, MotionEvent.ACTION_UP, 260, 50);
        assertEquals(List.of("\\"), pressed);

        // Lifting without sliding picks the item over the key; a short tap still types the key.
        touch(pipe, MotionEvent.ACTION_DOWN, 50, 50);
        ShadowLooper.idleMainLooper(ViewConfiguration.getLongPressTimeout() + 50, TimeUnit.MILLISECONDS);
        touch(pipe, MotionEvent.ACTION_UP, 50, 50);
        touch(pipe, MotionEvent.ACTION_DOWN, 50, 50);
        touch(pipe, MotionEvent.ACTION_UP, 50, 50);
        ShadowLooper.idleMainLooper();
        assertEquals(List.of("\\", "~", "|"), pressed);
    }

    @Test public void aDefaultItemIsPickedUntilTheFingerMovesAway() throws Exception {
        ExtraKeysView keys = keys("[[{key:'|',menu:['~','/',{key:'-',default:true}]},'a','b','c','d','e']]");
        View pipe = keys.getChildAt(0);

        // Lifting without moving picks the default, not the item nearest to the finger.
        touch(pipe, MotionEvent.ACTION_DOWN, 50, 50);
        ShadowLooper.idleMainLooper(ViewConfiguration.getLongPressTimeout() + 50, TimeUnit.MILLISECONDS);
        touch(pipe, MotionEvent.ACTION_MOVE, 60, 52);
        touch(pipe, MotionEvent.ACTION_UP, 60, 52);
        assertEquals(List.of("-"), pressed);

        // Sliding away hands over to the nearest item: the second cell starts at 6 + 100 px.
        touch(pipe, MotionEvent.ACTION_DOWN, 50, 50);
        ShadowLooper.idleMainLooper(ViewConfiguration.getLongPressTimeout() + 50, TimeUnit.MILLISECONDS);
        touch(pipe, MotionEvent.ACTION_MOVE, 156, 50);
        touch(pipe, MotionEvent.ACTION_UP, 156, 50);
        assertEquals(List.of("-", "/"), pressed);
    }

    @Test public void doubleTapRunsItsBindingAndASingleTapWaitsForIt() throws Exception {
        ExtraKeysView keys = keys("[[{key:'a','double-tap':{action:'open-right-sidebar'}},'b',{action:'font-size-up'}]]");
        View a = keys.getChildAt(0);
        a.performClick();
        assertTrue("the single tap waits for a possible second tap", pressed.isEmpty());
        ShadowLooper.idleMainLooper(ViewConfiguration.getDoubleTapTimeout() + 10, TimeUnit.MILLISECONDS);
        assertEquals(List.of("a"), pressed);

        a.performClick();
        a.performClick();
        ShadowLooper.idleMainLooper(ViewConfiguration.getDoubleTapTimeout() + 10, TimeUnit.MILLISECONDS);
        assertEquals(List.of("a", "action:open-right-sidebar"), pressed);

        // Another key sends the waiting tap first, so the order of input is kept.
        a.performClick();
        keys.getChildAt(1).performClick();
        keys.getChildAt(2).performClick();
        assertEquals(List.of("a", "action:open-right-sidebar", "a", "b", "action:font-size-up"), pressed);
        assertEquals(View.VISIBLE, keys.getChildAt(2).getVisibility());
    }
}
