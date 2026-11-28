package dev.pschmitt.termux.shared.termux.extrakeys;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import dev.pschmitt.termux.shared.termux.settings.properties.TerminalGestureAction;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class ExtraKeyButton {

    /** The key name for the name of the extra key if using a dict to define the extra key. {key: name, ...} */
    public static final String KEY_KEY_NAME = "key";

    /** The key name for the macro value of the extra key if using a dict to define the extra key. {macro: value, ...} */
    public static final String KEY_MACRO = "macro";

    /** The key name for the alternate display name of the extra key if using a dict to define the extra key. {display: name, ...} */
    public static final String KEY_DISPLAY_NAME = "display";

    /** The key name for the nested dict to define popup extra key info if using a dict to define the extra key. {popup: {key: name, ...}, ...} */
    public static final String KEY_POPUP = "popup";

    public static final String KEY_HOLD = "hold";
    public static final String KEY_HOLD_DISPLAY = "hold-display";

    /** The shell command run by the button instead of sending a key or macro. */
    public static final String KEY_COMMAND = "command";

    /** An app action, such as {@code open-left-sidebar}, run instead of a key, macro or command. */
    public static final String KEY_ACTION = "action";

    /** Alternates shown in a menu on a long press: {@code menu: ['~', '/', '\\']}. */
    public static final String KEY_MENU = "menu";

    /** The key name of the flag that makes a menu item the one picked when the finger does not move. */
    public static final String KEY_MENU_DEFAULT = "default";

    /** The alternate sent by a double tap. */
    public static final String KEY_DOUBLE_TAP = "double-tap";

    /** The most items a long-press menu can have. */
    public static final int MAX_MENU_ITEMS = 12;


    /**
     * The key that will be sent to the terminal, either a control character, like defined in
     * {@link ExtraKeysConstants#PRIMARY_KEY_CODES_FOR_STRINGS} (LEFT, RIGHT, PGUP...) or some text.
     */
    private final String key;

    /**
     * If the key is a macro, i.e. a sequence of keys separated by space.
     */
    private final boolean macro;

    /**
     * The text that will be displayed on the button.
     */
    private final String display;

    /** Optional command to run in a new terminal session instead of sending terminal input. */
    @Nullable
    private final String command;

    /**
     * The {@link ExtraKeyButton} containing the information of the popup button (triggered by swipe up).
     */
    @Nullable
    private final ExtraKeyButton popup;
    private final boolean customDisplay;
    @Nullable final Integer textColor;
    @Nullable final Integer backgroundColor;
    @Nullable private final String hint;
    @Nullable final Float insetDp;
    @Nullable private final ExtraKeyButton hold;
    @Nullable private final ExtraKeyButton flingLeft;
    @Nullable private final ExtraKeyButton flingRight;
    @Nullable private final ExtraKeyButton flingDown;
    @Nullable private final TerminalGestureAction action;
    /** Set by {@link ExtraKeysInfo} after construction. */
    @Nullable private List<ExtraKeyButton> menu;
    @Nullable private ExtraKeyButton doubleTap;


    /**
     * Initialize a {@link ExtraKeyButton}.
     *
     * @param config The {@link JSONObject} containing the info to create the {@link ExtraKeyButton}.
     * @param extraKeyDisplayMap The {@link ExtraKeysConstants.ExtraKeyDisplayMap} that defines the
     *                           display text mapping for the keys if a custom value is not defined
     *                           by {@link #KEY_DISPLAY_NAME}.
     * @param extraKeyAliasMap The {@link ExtraKeysConstants.ExtraKeyDisplayMap} that defines the
     *                           aliases for the actual key names.
     */
    public ExtraKeyButton(@NonNull JSONObject config,
                          @NonNull ExtraKeysConstants.ExtraKeyDisplayMap extraKeyDisplayMap,
                          @NonNull ExtraKeysConstants.ExtraKeyDisplayMap extraKeyAliasMap) throws JSONException {
        this(config, null, extraKeyDisplayMap, extraKeyAliasMap);
    }

    /**
     * Initialize a {@link ExtraKeyButton}.
     *
     * @param config The {@link JSONObject} containing the info to create the {@link ExtraKeyButton}.
     * @param popup The {@link ExtraKeyButton} optional {@link #popup} button.
     * @param extraKeyDisplayMap The {@link ExtraKeysConstants.ExtraKeyDisplayMap} that defines the
     *                           display text mapping for the keys if a custom value is not defined
     *                           by {@link #KEY_DISPLAY_NAME}.
     * @param extraKeyAliasMap The {@link ExtraKeysConstants.ExtraKeyDisplayMap} that defines the
     *                           aliases for the actual key names.
     */
    public ExtraKeyButton(@NonNull JSONObject config, @Nullable ExtraKeyButton popup,
                          @NonNull ExtraKeysConstants.ExtraKeyDisplayMap extraKeyDisplayMap,
                          @NonNull ExtraKeysConstants.ExtraKeyDisplayMap extraKeyAliasMap) throws JSONException {
        this(config, popup, null, null, null, extraKeyDisplayMap, extraKeyAliasMap);
    }

    ExtraKeyButton(JSONObject config, @Nullable ExtraKeyButton popup,
                   @Nullable ExtraKeyButton flingLeft, @Nullable ExtraKeyButton flingRight,
                   @Nullable ExtraKeyButton flingDown,
                   ExtraKeysConstants.ExtraKeyDisplayMap extraKeyDisplayMap,
                   ExtraKeysConstants.ExtraKeyDisplayMap extraKeyAliasMap) throws JSONException {
        this.flingLeft = flingLeft;
        this.flingRight = flingRight;
        this.flingDown = flingDown;
        String keyFromConfig = getStringFromJson(config, KEY_KEY_NAME);
        String macroFromConfig = getStringFromJson(config, KEY_MACRO);
        String commandFromConfig = getStringFromJson(config, KEY_COMMAND);
        String actionFromConfig = getStringFromJson(config, KEY_ACTION);
        String[] keys;
        if (actionFromConfig != null) {
            if (keyFromConfig != null || macroFromConfig != null || commandFromConfig != null)
                throw new JSONException("An action cannot be combined with key, macro or command");
            this.action = parseKeyAction(actionFromConfig);
            keys = new String[0];
            this.macro = false;
        } else if (commandFromConfig != null && (keyFromConfig != null || macroFromConfig != null)) {
            throw new JSONException("Command cannot be combined with key or macro");
        } else if (commandFromConfig != null) {
            if (commandFromConfig.trim().isEmpty()) throw new JSONException("Command cannot be blank");
            this.action = null;
            keys = new String[0];
            this.macro = false;
        } else if (keyFromConfig != null && macroFromConfig != null) {
            throw new JSONException("Both key and macro can't be set for the same key. key: \"" + keyFromConfig + "\", macro: \"" + macroFromConfig + "\"");
        } else if (keyFromConfig != null) {
            keys = new String[]{keyFromConfig};
            this.macro = false;
            this.action = null;
        } else if (macroFromConfig != null) {
            keys = macroFromConfig.split(" ");
            this.macro = true;
            this.action = null;
        } else {
            throw new JSONException("All keys have to specify a key, macro, command or action");
        }

        for (int i = 0; i < keys.length; i++) {
            keys[i] = replaceAlias(extraKeyAliasMap, keys[i]);
        }

        this.key = TextUtils.join(" ", keys);
        this.command = commandFromConfig;

        String displayFromConfig = getStringFromJson(config, KEY_DISPLAY_NAME);
        customDisplay = displayFromConfig != null;
        textColor = readColor(config, "text-color");
        backgroundColor = readColor(config, "background-color");
        insetDp = config.has("inset") ? (float) config.getDouble("inset") : null;
        if (insetDp != null && !(insetDp >= 0 && insetDp <= 8)) {
            throw new JSONException("Extra key inset must be from 0 to 8 dp");
        }
        if (customDisplay) {
            this.display = displayFromConfig;
        } else if (commandFromConfig != null) {
            this.display = "CMD";
        } else if (action != null) {
            this.display = actionSymbol(action);
        } else {
            this.display = Arrays.stream(keys)
                .map(key -> extraKeyDisplayMap.get(key, key))
                .collect(Collectors.joining(" "));
        }

        this.popup = popup;
        if (config.has(KEY_HOLD) && config.has(KEY_MENU))
            throw new JSONException("A key can have a long-press key or a long-press menu, not both");
        if (config.has(KEY_HOLD)) {
            Object value = config.get(KEY_HOLD);
            if (!(value instanceof String) || ((String) value).trim().isEmpty())
                throw new JSONException("Hold must be a key name");
            JSONObject heldKey = new JSONObject().put(KEY_KEY_NAME, value);
            if (config.has(KEY_HOLD_DISPLAY)) heldKey.put(KEY_DISPLAY_NAME, config.get(KEY_HOLD_DISPLAY));
            hold = new ExtraKeyButton(heldKey, extraKeyDisplayMap, extraKeyAliasMap);
        } else {
            hold = null;
        }
        switch (config.optString("hint-mode")) {
            case "off":
                hint = null;
                break;
            case "auto":
                hint = pickAutomaticHint(popup, flingDown, flingLeft, flingRight, hold);
                break;
            default:
                hint = getStringFromJson(config, "hint");
                break;
        }
    }

    /**
     * Parses an app action for a key. {@code send-keys} and {@code run-command} are not actions
     * here: a key uses {@code macro} and {@code command} for them.
     */
    @NonNull
    static TerminalGestureAction parseKeyAction(@NonNull String value) throws JSONException {
        TerminalGestureAction action = TerminalGestureAction.parse(value);
        if (action == null || action == TerminalGestureAction.NONE
            || action == TerminalGestureAction.SEND_KEYS || action == TerminalGestureAction.RUN_COMMAND)
            throw new JSONException("Unknown extra key action \"" + value + "\"; use macro for keys and command for commands");
        return action;
    }

    /** The default label of a key that runs an action. */
    @NonNull
    public static String actionSymbol(@NonNull TerminalGestureAction action) {
        switch (action) {
            case SHOW_KEYBOARD:
            case TOGGLE_KEYBOARD:
            case HIDE_KEYBOARD: return "⌨";
            case TOGGLE_TEXT_INPUT: return "✎";
            case SHOW_EXTRA_KEYS:
            case HIDE_EXTRA_KEYS:
            case TOGGLE_EXTRA_KEYS: return "⌸";
            case NEXT_SESSION: return "»";
            case PREVIOUS_SESSION: return "«";
            case NEW_SESSION: return "+";
            case OPEN_SIDEBAR: return "☰";
            case OPEN_RIGHT_SIDEBAR: return "☰›";
            case OPEN_SESSION_SWITCHER: return "⇆";
            case OPEN_TMUX_SWITCHER: return "▦";
            case OPEN_MENU: return "⋮";
            case OPEN_PALETTE: return "⌕";
            case FONT_SIZE_UP: return "A+";
            case FONT_SIZE_DOWN: return "A−";
            default: return action.getValue();
        }
    }

    /** The app action this button runs, or {@code null} if it sends input or runs a command. */
    @Nullable
    public TerminalGestureAction getAction() {
        return action;
    }

    /** The long-press menu items, or {@code null}. */
    @Nullable
    public List<ExtraKeyButton> getMenu() {
        return menu;
    }

    private boolean menuDefault;

    /** Whether this menu item is its menu's default: highlighted when the menu opens, above the key. */
    public boolean isMenuDefault() {
        return menuDefault;
    }

    void setMenuDefault(boolean menuDefault) {
        this.menuDefault = menuDefault;
    }

    void setMenu(@Nullable List<ExtraKeyButton> menu) {
        this.menu = menu == null || menu.isEmpty() ? null : Collections.unmodifiableList(menu);
    }

    /** The alternate for a double tap, or {@code null}. */
    @Nullable
    public ExtraKeyButton getDoubleTap() {
        return doubleTap;
    }

    void setDoubleTap(@Nullable ExtraKeyButton doubleTap) {
        this.doubleTap = doubleTap;
    }

    @Nullable
    private static String pickAutomaticHint(ExtraKeyButton... alternates) {
        for (ExtraKeyButton alternate : alternates) {
            if (alternate != null && !alternate.getDisplay().isEmpty()) return alternate.getDisplay();
        }
        return null;
    }

    @Nullable
    public String getHint() {
        return hint;
    }

    @Nullable
    private static Integer readColor(JSONObject config, String key) throws JSONException {
        if (!config.has(key)) return null;
        Integer color = ExtraKeysColorParser.parseColor(config.getString(key));
        if (color == null) throw new JSONException("Invalid extra key " + key);
        return color;
    }

    public String getStringFromJson(@NonNull JSONObject config, @NonNull String key) {
        try {
            return config.getString(key);
        } catch (JSONException e) {
            return null;
        }
    }

    /** Get {@link #key}. */
    public String getKey() {
        return key;
    }

    /** Check whether a {@link #macro} is defined or not. */
    public boolean isMacro() {
        return macro;
    }

    /** Get {@link #display}. */
    public boolean hasCustomDisplay() {
        return customDisplay;
    }

    public String getDisplay() {
        return display;
    }

    /** Get the command, or {@code null} if this button sends terminal input. */
    @Nullable
    public String getCommand() {
        return command;
    }

    /** Get {@link #popup}. */
    @Nullable
    public ExtraKeyButton getPopup() {
        return popup;
    }

    @Nullable
    public ExtraKeyButton getFling(boolean right) {
        return right ? flingRight : flingLeft;
    }

    @Nullable
    public ExtraKeyButton getDownFling() {
        return flingDown;
    }

    /** The optional key to activate on a long press. */
    @Nullable
    public ExtraKeyButton getHold() {
        return hold;
    }

    /**
     * Replace the alias with its actual key name if found in extraKeyAliasMap.
     */
    public static String replaceAlias(@NonNull ExtraKeysConstants.ExtraKeyDisplayMap extraKeyAliasMap, String key) {
        return extraKeyAliasMap.get(key, key);
    }

}
