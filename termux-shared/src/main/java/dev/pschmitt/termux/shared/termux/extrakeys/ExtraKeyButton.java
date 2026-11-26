package dev.pschmitt.termux.shared.termux.extrakeys;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
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
    @Nullable final String hint;
    @Nullable final Float insetDp;
    @Nullable private final ExtraKeyButton hold;
    @Nullable private final ExtraKeyButton flingLeft;
    @Nullable private final ExtraKeyButton flingRight;
    @Nullable private final ExtraKeyButton flingDown;


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
        String[] keys;
        if (commandFromConfig != null && (keyFromConfig != null || macroFromConfig != null)) {
            throw new JSONException("Command cannot be combined with key or macro");
        } else if (commandFromConfig != null) {
            if (commandFromConfig.trim().isEmpty()) throw new JSONException("Command cannot be blank");
            keys = new String[0];
            this.macro = false;
        } else if (keyFromConfig != null && macroFromConfig != null) {
            throw new JSONException("Both key and macro can't be set for the same key. key: \"" + keyFromConfig + "\", macro: \"" + macroFromConfig + "\"");
        } else if (keyFromConfig != null) {
            keys = new String[]{keyFromConfig};
            this.macro = false;
        } else if (macroFromConfig != null) {
            keys = macroFromConfig.split(" ");
            this.macro = true;
        } else {
            throw new JSONException("All keys have to specify either key or macro");
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
        hint = getStringFromJson(config, "hint");
        insetDp = config.has("inset") ? (float) config.getDouble("inset") : null;
        if (insetDp != null && !(insetDp >= 0 && insetDp <= 8)) {
            throw new JSONException("Extra key inset must be from 0 to 8 dp");
        }
        if (customDisplay) {
            this.display = displayFromConfig;
        } else if (commandFromConfig != null) {
            this.display = "CMD";
        } else {
            this.display = Arrays.stream(keys)
                .map(key -> extraKeyDisplayMap.get(key, key))
                .collect(Collectors.joining(" "));
        }

        this.popup = popup;
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
