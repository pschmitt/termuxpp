package dev.pschmitt.termux.shared.termux.extrakeys;

import android.view.View;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysConstants.EXTRA_KEY_DISPLAY_MAPS;
import dev.pschmitt.termux.shared.termux.terminal.io.TerminalExtraKeys;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Class} that defines the info needed by {@link ExtraKeysView} to display the extra key
 * views.
 *
 * The {@code propertiesInfo} passed to the constructors of this class must be json array of arrays.
 * Each array element of the json array will be considered a separate row of keys.
 * Each key can either be simple string that defines the name of the key or a json dict that defines
 * advance info for the key. The syntax can be `'KEY'` or `{key: 'KEY'}`.
 * For example `HOME` or `{key: 'HOME', ...}.
 *
 * In advance json dict mode, the key can also be a sequence of space separated keys instead of one
 * key. This can be done by replacing `key` key/value pair of the dict with a `macro` key/value pair.
 * The syntax is `{macro: 'KEY COMBINATION'}`. For example {macro: 'HOME RIGHT', ...}.
 *
 * In advance json dict mode, you can define a nested json dict with the `popup` key which will be
 * used as the popup key and will be triggered on swipe up. The syntax can be
 * `{key: 'KEY', popup: 'POPUP_KEY'}` or `{key: 'KEY', popup: {macro: 'KEY COMBINATION', display: 'Key combo'}}`.
 * For example `{key: 'HOME', popup: {KEY: 'END', ...}, ...}`.
 *
 * In advance json dict mode, the key can also have a custom display name that can be used as the
 * text to display on the button by defining the `display` key. The syntax is `{display: 'DISPLAY'}`.
 * For example {display: 'Custom name', ...}.
 * A button object may instead use `command` to start a command in a new terminal session, for
 * example `{command: 'nix develop', display: 'DEV'}`. A command cannot be combined with `key` or
 * `macro`. Commands are trusted shell text and are not included in command logs.
 *
 * Examples:
 * {@code
 * # Empty:
 * []
 *
 * # Single row:
 * [[ESC, TAB, CTRL, ALT, {key: '-', popup: '|'}, DOWN, UP]]
 *
 * # 2 row:
 * [['ESC','/',{key: '-', popup: '|'},'HOME','UP','END','PGUP'],
 * ['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]
 *
 * # Advance:
 * [[
 *   {key: ESC, popup: {macro: "CTRL f d", display: "tmux exit"}},
 *   {key: CTRL, popup: {macro: "CTRL f BKSP", display: "tmux ←"}},
 *   {key: ALT, popup: {macro: "CTRL f TAB", display: "tmux →"}},
 *   {key: TAB, popup: {macro: "ALT a", display: A-a}},
 *   {key: LEFT, popup: HOME},
 *   {key: DOWN, popup: PGDN},
 *   {key: UP, popup: PGUP},
 *   {key: RIGHT, popup: END},
 *   {macro: "ALT j", display: A-j, popup: {macro: "ALT g", display: A-g}},
 *   {key: KEYBOARD, popup: {macro: "CTRL d", display: exit}}
 * ]]
 *
 * }
 *
 * Aliases are also allowed for the keys that you can pass as {@code extraKeyAliasMap}. Check
 * {@link ExtraKeysConstants#CONTROL_CHARS_ALIASES}.
 *
 * Its up to the {@link ExtraKeysView.IExtraKeysView} client on how to handle individual key values
 * of an {@link ExtraKeyButton}. They are sent as is via
 * {@link ExtraKeysView.IExtraKeysView#onExtraKeyButtonClick(View, ExtraKeyButton, MaterialButton)}. The
 * {@link TerminalExtraKeys} which is an implementation of the interface,
 * checks if the key is one of {@link ExtraKeysConstants#PRIMARY_KEY_CODES_FOR_STRINGS} and generates
 * a {@link android.view.KeyEvent} for it, and if its not, then converts the key to code points by
 * calling {@link CharSequence#codePoints()} and passes them to the terminal as literal strings.
 *
 * Examples:
 * {@code
 * "ENTER" will trigger the ENTER keycode
 * "LEFT" will trigger the LEFT keycode and be displayed as "←"
 * "→" will input a "→" character
 * "−" will input a "−" character
 * "-_-" will input the string "-_-"
 * }
 *
 * For more info, check https://wiki.termux.com/wiki/Touch_Keyboard.
 */
public class ExtraKeysInfo {

    /**
     * Matrix of buttons to be displayed in {@link ExtraKeysView}.
     */
    private final ExtraKeyButton[][] mButtons;

    /**
     * Initialize {@link ExtraKeysInfo}.
     *
     * @param propertiesInfo The {@link String} containing the info to create the {@link ExtraKeysInfo}.
     *                       Check the class javadoc for details.
     * @param style The style to pass to {@link #getCharDisplayMapForStyle(String)} to get the
     *              {@link ExtraKeysConstants.ExtraKeyDisplayMap} that defines the display text
     *              mapping for the keys if a custom value is not defined by
     *              {@link ExtraKeyButton#KEY_DISPLAY_NAME} for a key.
     * @param extraKeyAliasMap The {@link ExtraKeysConstants.ExtraKeyDisplayMap} that defines the
     *                           aliases for the actual key names. You can create your own or
     *                           optionally pass {@link ExtraKeysConstants#CONTROL_CHARS_ALIASES}.
     */
    public ExtraKeysInfo(@NonNull String propertiesInfo, String style,
                         @NonNull ExtraKeysConstants.ExtraKeyDisplayMap extraKeyAliasMap) throws JSONException {
        mButtons = initExtraKeysInfo(propertiesInfo, getCharDisplayMapForStyle(style), extraKeyAliasMap);
    }

    /**
     * Initialize {@link ExtraKeysInfo}.
     *
     * @param propertiesInfo The {@link String} containing the info to create the {@link ExtraKeysInfo}.
     *                       Check the class javadoc for details.
     * @param extraKeyDisplayMap The {@link ExtraKeysConstants.ExtraKeyDisplayMap} that defines the
     *                           display text mapping for the keys if a custom value is not defined
     *                           by {@link ExtraKeyButton#KEY_DISPLAY_NAME} for a key. You can create
     *                           your own or optionally pass one of the values defined in
     *                           {@link #getCharDisplayMapForStyle(String)}.
     * @param extraKeyAliasMap The {@link ExtraKeysConstants.ExtraKeyDisplayMap} that defines the
     *                           aliases for the actual key names. You can create your own or
     *                           optionally pass {@link ExtraKeysConstants#CONTROL_CHARS_ALIASES}.
     */
    public ExtraKeysInfo(@NonNull String propertiesInfo,
                         @NonNull ExtraKeysConstants.ExtraKeyDisplayMap extraKeyDisplayMap,
                         @NonNull ExtraKeysConstants.ExtraKeyDisplayMap extraKeyAliasMap) throws JSONException {
        mButtons = initExtraKeysInfo(propertiesInfo, extraKeyDisplayMap, extraKeyAliasMap);
    }

    private ExtraKeyButton[][] initExtraKeysInfo(@NonNull String propertiesInfo,
                                                 @NonNull ExtraKeysConstants.ExtraKeyDisplayMap extraKeyDisplayMap,
                                                 @NonNull ExtraKeysConstants.ExtraKeyDisplayMap extraKeyAliasMap) throws JSONException {
        // Convert String propertiesInfo to Array of Arrays
        JSONArray arr = new JSONArray(propertiesInfo);
        Object[][] matrix = new Object[arr.length()][];
        for (int i = 0; i < arr.length(); i++) {
            JSONArray line = arr.getJSONArray(i);
            matrix[i] = new Object[line.length()];
            for (int j = 0; j < line.length(); j++) {
                matrix[i][j] = line.get(j);
            }
        }

        // convert matrix to buttons
        ExtraKeyButton[][] buttons = new ExtraKeyButton[matrix.length][];
        for (int i = 0; i < matrix.length; i++) {
            buttons[i] = new ExtraKeyButton[matrix[i].length];
            for (int j = 0; j < matrix[i].length; j++) {
                Object key = matrix[i][j];

                JSONObject jobject = normalizeKeyConfig(key);

                buttons[i][j] = new ExtraKeyButton(jobject,
                    readAlternate(jobject, ExtraKeyButton.KEY_POPUP, extraKeyDisplayMap, extraKeyAliasMap),
                    readAlternate(jobject, "fling-left", extraKeyDisplayMap, extraKeyAliasMap),
                    readAlternate(jobject, "fling-right", extraKeyDisplayMap, extraKeyAliasMap),
                    readAlternate(jobject, "fling-down", extraKeyDisplayMap, extraKeyAliasMap),
                    extraKeyDisplayMap, extraKeyAliasMap);
                buttons[i][j].setDoubleTap(readAlternate(jobject, ExtraKeyButton.KEY_DOUBLE_TAP, extraKeyDisplayMap, extraKeyAliasMap));
                buttons[i][j].setMenu(readMenu(jobject, extraKeyDisplayMap, extraKeyAliasMap));
            }
        }

        return buttons;
    }

    @Nullable
    private static ExtraKeyButton readAlternate(JSONObject config, String name,
                                                ExtraKeysConstants.ExtraKeyDisplayMap displayMap,
                                                ExtraKeysConstants.ExtraKeyDisplayMap aliasMap) throws JSONException {
        return config.has(name)
            ? new ExtraKeyButton(normalizeKeyConfig(config.get(name)), displayMap, aliasMap) : null;
    }

    @Nullable
    private static List<ExtraKeyButton> readMenu(JSONObject config,
                                                 ExtraKeysConstants.ExtraKeyDisplayMap displayMap,
                                                 ExtraKeysConstants.ExtraKeyDisplayMap aliasMap) throws JSONException {
        if (!config.has(ExtraKeyButton.KEY_MENU)) return null;
        Object value = config.get(ExtraKeyButton.KEY_MENU);
        if (!(value instanceof JSONArray)) throw new JSONException("A key menu must be a list of keys");
        JSONArray items = (JSONArray) value;
        if (items.length() == 0) throw new JSONException("A key menu needs at least one item");
        if (items.length() > ExtraKeyButton.MAX_MENU_ITEMS)
            throw new JSONException("A key menu can have at most " + ExtraKeyButton.MAX_MENU_ITEMS + " items");
        List<ExtraKeyButton> menu = new ArrayList<>();
        for (int i = 0; i < items.length(); i++)
            menu.add(new ExtraKeyButton(normalizeKeyConfig(items.get(i)), displayMap, aliasMap));
        return menu;
    }

    /**
     * Convert "value" -> {"key": "value"}. Required by
     * {@link ExtraKeyButton#ExtraKeyButton(JSONObject, ExtraKeyButton, ExtraKeysConstants.ExtraKeyDisplayMap, ExtraKeysConstants.ExtraKeyDisplayMap)}.
     */
    private static JSONObject normalizeKeyConfig(Object key) throws JSONException {
        JSONObject jobject;
        if (key instanceof String) {
            jobject = new JSONObject();
            jobject.put(ExtraKeyButton.KEY_KEY_NAME, key);
        } else if (key instanceof JSONObject) {
            jobject = (JSONObject) key;
        } else {
            throw new JSONException("An key in the extra-key matrix must be a string or an object");
        }
        return jobject;
    }

    public ExtraKeyButton[][] getMatrix() {
        return mButtons;
    }

    @NonNull
    public static ExtraKeysConstants.ExtraKeyDisplayMap getCharDisplayMapForStyle(String style) {
        switch (style) {
            case "arrows-only":
                return EXTRA_KEY_DISPLAY_MAPS.ARROWS_ONLY_CHAR_DISPLAY;
            case "arrows-all":
                return EXTRA_KEY_DISPLAY_MAPS.LOTS_OF_ARROWS_CHAR_DISPLAY;
            case "all":
                return EXTRA_KEY_DISPLAY_MAPS.FULL_ISO_CHAR_DISPLAY;
            case "none":
                return new ExtraKeysConstants.ExtraKeyDisplayMap();
            default:
                return EXTRA_KEY_DISPLAY_MAPS.DEFAULT_CHAR_DISPLAY;
        }
    }

}
