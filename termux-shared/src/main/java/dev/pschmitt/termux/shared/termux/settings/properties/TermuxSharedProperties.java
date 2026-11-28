package dev.pschmitt.termux.shared.termux.settings.properties;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dev.pschmitt.termux.shared.logger.Logger;
import dev.pschmitt.termux.shared.data.DataUtils;
import dev.pschmitt.termux.shared.settings.properties.SharedProperties;
import dev.pschmitt.termux.shared.settings.properties.SharedPropertiesParser;
import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeyAnimation;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysColorParser;
import dev.pschmitt.termux.shared.termux.extrakeys.TerminalKeySequence;

import dev.pschmitt.termux.shared.termux.nix.TermuxNix;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public abstract class TermuxSharedProperties {

    protected final Context mContext;
    protected final String mLabel;
    protected final List<String> mPropertiesFilePaths;
    protected final Set<String> mPropertiesList;
    protected final SharedPropertiesParser mSharedPropertiesParser;
    protected File mPropertiesFile;
    protected SharedProperties mSharedProperties;

    public static final String LOG_TAG = "TermuxSharedProperties";

    public TermuxSharedProperties(@NonNull Context context, @NonNull String label, List<String> propertiesFilePaths,
                                  @NonNull Set<String> propertiesList, @NonNull SharedPropertiesParser sharedPropertiesParser) {
        mContext = context.getApplicationContext();
        mLabel = label;
        mPropertiesFilePaths = propertiesFilePaths;
        mPropertiesList = propertiesList;
        mSharedPropertiesParser = sharedPropertiesParser;
        loadTermuxPropertiesFromDisk();
    }

    protected List<String> getPropertiesFilePaths() {
        return mPropertiesFilePaths;
    }

    /**
     * Reload the termux properties from disk into an in-memory cache.
     */
    public synchronized void loadTermuxPropertiesFromDisk() {
        // Properties files must be searched everytime since no file may exist when constructor is
        // called or a higher priority file may have been created afterward. Otherwise, if no file
        // was found, then default props would keep loading, since mSharedProperties would be null. #2836
        mPropertiesFile = SharedProperties.getPropertiesFileFromList(getPropertiesFilePaths(), LOG_TAG);
        mSharedProperties = null;
        mSharedProperties = new SharedProperties(mContext, mPropertiesFile, mPropertiesList, mSharedPropertiesParser);

        mSharedProperties.loadPropertiesFromDisk();
        dumpPropertiesToLog();
        dumpInternalPropertiesToLog();
        logGesturesWithoutKeys();
    }





    /**
     * Get the {@link Properties} from the {@link #mPropertiesFile} file.
     *
     * @param cached If {@code true}, then the {@link Properties} in-memory cache is returned.
     *               Otherwise the {@link Properties} object is read directly from the
     *               {@link #mPropertiesFile} file.
     * @return Returns the {@link Properties} object. It will be {@code null} if an exception is
     * raised while reading the file.
     */
    public Properties getProperties(boolean cached) {
        return mSharedProperties.getProperties(cached);
    }

    /**
     * Get the {@link String} value for the key passed from the {@link #mPropertiesFile} file.
     *
     * @param key The key to read.
     * @param def The default value.
     * @param cached If {@code true}, then the value is returned from the the {@link Properties} in-memory cache.
     *               Otherwise the {@link Properties} object is read directly from the file
     *               and value is returned from it against the key.
     * @return Returns the {@link String} object. This will be {@code null} if key is not found.
     */
    public String getPropertyValue(String key, String def, boolean cached) {
        return SharedProperties.getDefaultIfNull(mSharedProperties.getProperty(key, cached), def);
    }

    /**
     * A function to check if the value is {@code true} for {@link Properties} key read from
     * the {@link #mPropertiesFile} file.
     *
     * @param key The key to read.
     * @param cached If {@code true}, then the value is checked from the the {@link Properties} in-memory cache.
     *               Otherwise the {@link Properties} object is read directly from the file
     *               and value is checked from it.
     * @param logErrorOnInvalidValue If {@code true}, then an error will be logged if key value
     *                               was found in {@link Properties} but was invalid.
     * @return Returns the {@code true} if the {@link Properties} key {@link String} value equals "true",
     * regardless of case. If the key does not exist in the file or does not equal "true", then
     * {@code false} will be returned.
     */
    public boolean isPropertyValueTrue(String key, boolean cached, boolean logErrorOnInvalidValue) {
        return (boolean) SharedProperties.getBooleanValueForStringValue(key, (String) getPropertyValue(key, null, cached), false, logErrorOnInvalidValue, LOG_TAG);
    }

    /**
     * A function to check if the value is {@code false} for {@link Properties} key read from
     * the {@link #mPropertiesFile} file.
     *
     * @param key The key to read.
     * @param cached If {@code true}, then the value is checked from the the {@link Properties} in-memory cache.
     *               Otherwise the {@link Properties} object is read directly from the file
     *               and value is checked from it.
     * @param logErrorOnInvalidValue If {@code true}, then an error will be logged if key value
     *                               was found in {@link Properties} but was invalid.
     * @return Returns {@code true} if the {@link Properties} key {@link String} value equals "false",
     * regardless of case. If the key does not exist in the file or does not equal "false", then
     * {@code true} will be returned.
     */
    public boolean isPropertyValueFalse(String key, boolean cached, boolean logErrorOnInvalidValue) {
        return (boolean) SharedProperties.getInvertedBooleanValueForStringValue(key, (String) getPropertyValue(key, null, cached), true, logErrorOnInvalidValue, LOG_TAG);
    }





    /**
     * Get the internal value {@link Object} {@link HashMap <>} in-memory cache for the
     * {@link #mPropertiesFile} file. A call to {@link #loadTermuxPropertiesFromDisk()} must be made
     * before this.
     *
     * @return Returns a copy of {@link Map} object.
     */
    public Map<String, Object> getInternalProperties() {
        return mSharedProperties.getInternalProperties();
    }

    /**
     * Get the internal {@link Object} value for the key passed from the {@link #mPropertiesFile} file.
     * If cache is {@code true}, then value is returned from the {@link HashMap <>} in-memory cache,
     * so a call to {@link #loadTermuxPropertiesFromDisk()} must be made before this.
     *
     * @param key The key to read from the {@link HashMap<>} in-memory cache.
     * @param cached If {@code true}, then the value is returned from the the {@link HashMap <>} in-memory cache,
     *               but if the value is null, then an attempt is made to return the default value.
     *               If {@code false}, then the {@link Properties} object is read directly from the file
     *               and internal value is returned for the property value against the key.
     * @return Returns the {@link Object} object. This will be {@code null} if key is not found or
     * the object stored against the key is {@code null}.
     */
    public Object getInternalPropertyValue(String key, boolean cached) {
        Object value;
        if (cached) {
            value = mSharedProperties.getInternalProperty(key);
            // If the value is not null since key was found or if the value was null since the
            // object stored for the key was itself null, we detect the later by checking if the key
            // exists in the map.
            if (value != null || mSharedProperties.getInternalProperties().containsKey(key)) {
                return value;
            } else {
                // This should not happen normally unless mMap was modified after the
                // {@link #loadTermuxPropertiesFromDisk()} call
                // A null value can still be returned by
                // {@link #getInternalPropertyValueFromValue(Context,String,String)} for some keys
                value = getInternalTermuxPropertyValueFromValue(mContext, key, null);
                Logger.logWarn(LOG_TAG, "The value for \"" + key + "\" not found in SharedProperties cache, force returning default value: `" + value +  "`");
                return value;
            }
        } else {
            // We get the property value directly from file and return its internal value
            return getInternalTermuxPropertyValueFromValue(mContext, key, mSharedProperties.getProperty(key, false));
        }
    }





    /**
     * Get the internal {@link Object} value for the key passed from the first file found in
     * {@link TermuxConstants#TERMUX_PROPERTIES_FILE_PATHS_LIST}. The {@link Properties} object is
     * read directly from the file and internal value is returned for the property value against the key.
     *
     * @param context The context for operations.
     * @param key The key for which the internal object is required.
     * @return Returns the {@link Object} object. This will be {@code null} if key is not found or
     * the object stored against the key is {@code null}.
     */
    public static Object getTermuxInternalPropertyValue(Context context, String key) {
        return SharedProperties.getInternalProperty(context,
            SharedProperties.getPropertiesFileFromList(TermuxNix.configPaths(TermuxConstants.TERMUX_PROPERTIES_FILE_PATHS_LIST), LOG_TAG),
            key, new SharedPropertiesParserClient());
    }

    /**
     * The class that implements the {@link SharedPropertiesParser} interface.
     */
    public static class SharedPropertiesParserClient implements SharedPropertiesParser {
        @NonNull
        @Override
        public Properties preProcessPropertiesOnReadFromDisk(@NonNull Context context, @NonNull Properties properties) {
            return replaceUseBlackUIProperty(properties);
        }

        /**
         * Override the
         * {@link SharedPropertiesParser#getInternalPropertyValueFromValue(Context,String,String)}
         * interface function.
         */
        @Override
        public Object getInternalPropertyValueFromValue(@NonNull Context context, String key, String value) {
            return getInternalTermuxPropertyValueFromValue(context, key, value);
        }
    }

    @NonNull
    public static Properties replaceUseBlackUIProperty(@NonNull Properties properties) {
        String useBlackUIStringValue = properties.getProperty(TermuxPropertyConstants.KEY_USE_BLACK_UI);
        if (useBlackUIStringValue == null) return properties;

        Logger.logWarn(LOG_TAG, "Removing deprecated property " + TermuxPropertyConstants.KEY_USE_BLACK_UI + "=" + useBlackUIStringValue);
        properties.remove(TermuxPropertyConstants.KEY_USE_BLACK_UI);

        // If KEY_NIGHT_MODE is not set
        if (properties.getProperty(TermuxPropertyConstants.KEY_NIGHT_MODE) == null) {
            Boolean useBlackUI = SharedProperties.getBooleanValueForStringValue(useBlackUIStringValue);
            if (useBlackUI != null) {
                String termuxAppTheme = useBlackUI ? TermuxPropertyConstants.IVALUE_NIGHT_MODE_TRUE :
                    TermuxPropertyConstants.IVALUE_NIGHT_MODE_FALSE;
                Logger.logWarn(LOG_TAG, "Replacing deprecated property " + TermuxPropertyConstants.KEY_USE_BLACK_UI + "=" + useBlackUI + " with " + TermuxPropertyConstants.KEY_NIGHT_MODE + "=" + termuxAppTheme);
                properties.put(TermuxPropertyConstants.KEY_NIGHT_MODE, termuxAppTheme);
            }
        }

        return properties;
    }



    /**
     * A static function that should return the internal termux {@link Object} for a key/value pair
     * read from properties file.
     *
     * @param context The context for operations.
     * @param key The key for which the internal object is required.
     * @param value The literal value for the property found is the properties file.
     * @return Returns the internal termux {@link Object} object.
     */
    public static Object getInternalTermuxPropertyValueFromValue(Context context, String key, String value) {
        if (key == null) return null;
        if (TermuxPropertyConstants.TERMINAL_MULTI_FINGER_SWIPE_PROPERTIES.contains(key)) {
            if (key.endsWith("-action"))
                return getGestureActionInternalPropertyValueFromValue(key, value,
                    TermuxPropertyConstants.defaultMultiFingerSwipeAction(key)).getValue();
            if (key.endsWith("-keys")) return getKeySequenceInternalPropertyValueFromValue(key, value);
            return getGestureCommandInternalPropertyValueFromValue(value);
        }
        /*
          For keys where a MAP_* is checked by respective functions. Note that value to this function
          would actually be the key for the MAP_*:
          - If the value is currently null, then searching MAP_* should also return null and internal default value will be used.
          - If the value is not null and does not exist in MAP_*, then internal default value will be used.
          - If the value is not null and does exist in MAP_*, then internal value returned by map will be used.
         */
        switch (key) {
            /* int */
            case TermuxPropertyConstants.KEY_BELL_BEHAVIOUR:
                return (int) getBellBehaviourInternalPropertyValueFromValue(value);
            case TermuxPropertyConstants.KEY_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT:
                return (int) getDeleteTMPDIRFilesOlderThanXDaysOnExitInternalPropertyValueFromValue(value);
            case TermuxPropertyConstants.KEY_WAKE_LOCK_TIMEOUT:
                return (int) getWakeLockTimeoutInternalPropertyValueFromValue(value);
            case TermuxPropertyConstants.KEY_TERMINAL_CURSOR_BLINK_RATE:
                return (int) getTerminalCursorBlinkRateInternalPropertyValueFromValue(value);
            case TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE:
                return (int) getTerminalCursorStyleInternalPropertyValueFromValue(value);
            case TermuxPropertyConstants.KEY_TERMINAL_MARGIN_HORIZONTAL:
                return (int) getTerminalMarginHorizontalInternalPropertyValueFromValue(value);
            case TermuxPropertyConstants.KEY_TERMINAL_MARGIN_VERTICAL:
                return (int) getTerminalMarginVerticalInternalPropertyValueFromValue(value);
            case TermuxPropertyConstants.KEY_GESTURE_FEEDBACK_STYLE:
                for (String style : TermuxPropertyConstants.GESTURE_FEEDBACK_STYLES) {
                    if (style.equalsIgnoreCase(value)) return style;
                }
                return TermuxPropertyConstants.DEFAULT_GESTURE_FEEDBACK_STYLE;
            case TermuxPropertyConstants.KEY_TERMINAL_PICKER_POSITION:
                for (String position : new String[]{TermuxPropertyConstants.PICKER_POSITION_TOP,
                    TermuxPropertyConstants.PICKER_POSITION_CENTER, TermuxPropertyConstants.PICKER_POSITION_TERMINAL}) {
                    if (position.equalsIgnoreCase(value)) return position;
                }
                return TermuxPropertyConstants.DEFAULT_PICKER_POSITION;
            case TermuxPropertyConstants.KEY_TERMINAL_TMUX_SIDEBAR_STYLE:
                return TermuxPropertyConstants.TMUX_SIDEBAR_SUBTLE.equalsIgnoreCase(value)
                    ? TermuxPropertyConstants.TMUX_SIDEBAR_SUBTLE : TermuxPropertyConstants.DEFAULT_TMUX_SIDEBAR_STYLE;
            case TermuxPropertyConstants.KEY_TERMINAL_TMUX_SIDEBAR_LAYOUT:
                if (TermuxPropertyConstants.TMUX_SIDEBAR_DRILL_DOWN.equalsIgnoreCase(value))
                    return TermuxPropertyConstants.TMUX_SIDEBAR_DRILL_DOWN;
                if (TermuxPropertyConstants.TMUX_SIDEBAR_MODES.equalsIgnoreCase(value))
                    return TermuxPropertyConstants.TMUX_SIDEBAR_MODES;
                return TermuxPropertyConstants.TMUX_SIDEBAR_NESTED;
            case TermuxPropertyConstants.KEY_TERMINAL_ENGINE:
                return TermuxPropertyConstants.TERMINAL_ENGINE_GHOSTTY.equalsIgnoreCase(value)
                    ? TermuxPropertyConstants.TERMINAL_ENGINE_GHOSTTY
                    : TermuxPropertyConstants.TERMINAL_ENGINE_TERMUX;
            case TermuxPropertyConstants.KEY_TERMINAL_TRANSCRIPT_ROWS:
                return (int) getTerminalTranscriptRowsInternalPropertyValueFromValue(value);
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_COMPACT:
                return getExtraKeysCompactInternalPropertyValueFromValue(value);
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_ROW_HEIGHT:
                return (int) getExtraKeysRowHeightInternalPropertyValueFromValue(value);

            /* float */
            case TermuxPropertyConstants.KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR:
                return (float) getTerminalToolbarHeightScaleFactorInternalPropertyValueFromValue(value);

            /* Float (may be null) */
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_THEME:
                return TermuxPropertyConstants.EXTRA_KEYS_THEME_ROUNDED.equalsIgnoreCase(
                    value == null ? "" : value.trim()) ? TermuxPropertyConstants.EXTRA_KEYS_THEME_ROUNDED
                    : TermuxPropertyConstants.EXTRA_KEYS_THEME_DEFAULT;
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_FONT:
                for (String font : TermuxPropertyConstants.EXTRA_KEYS_FONTS) {
                    if (value != null && font.equalsIgnoreCase(value.trim())) return font;
                }
                return TermuxPropertyConstants.DEFAULT_EXTRA_KEYS_FONT;
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_FONT_WEIGHT:
                return (int) getExtraKeysFontWeightInternalPropertyValueFromValue(value);
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_ANIMATION:
                return ExtraKeyAnimation.parseOrDefault(value);
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_BUTTON_CORNER_RADIUS:
                return (Float) getExtraKeysCornerRadiusInternalPropertyValueFromValue(key, value);

            /* TerminalGestureAction (never null), stored as its String value since SharedProperties only caches primitives and Strings */
            case TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_ACTION:
                return getGestureActionInternalPropertyValueFromValue(key, value, TermuxPropertyConstants.DEFAULT_TERMINAL_SINGLE_TAP_ACTION).getValue();
            case TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_ACTION:
                return getGestureActionInternalPropertyValueFromValue(key, value, TermuxPropertyConstants.DEFAULT_TERMINAL_DOUBLE_TAP_ACTION).getValue();
            case TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_ACTION:
                return getGestureActionInternalPropertyValueFromValue(key, value, TermuxPropertyConstants.DEFAULT_TERMINAL_TRIPLE_TAP_ACTION).getValue();
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_LEFT_ACTION:
                return getGestureActionInternalPropertyValueFromValue(key, value, TermuxPropertyConstants.DEFAULT_EXTRA_KEYS_SWIPE_LEFT_ACTION).getValue();
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_RIGHT_ACTION:
                return getGestureActionInternalPropertyValueFromValue(key, value, TermuxPropertyConstants.DEFAULT_EXTRA_KEYS_SWIPE_RIGHT_ACTION).getValue();
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_LEFT_ACTION:
                return getGestureActionInternalPropertyValueFromValue(key, value, TermuxPropertyConstants.DEFAULT_EXTRA_KEYS_LONG_SWIPE_LEFT_ACTION).getValue();
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_RIGHT_ACTION:
                return getGestureActionInternalPropertyValueFromValue(key, value, TermuxPropertyConstants.DEFAULT_EXTRA_KEYS_LONG_SWIPE_RIGHT_ACTION).getValue();

            /* GestureCommandContext, stored as its String value since SharedProperties caches primitives and Strings */
            case TermuxPropertyConstants.KEY_GESTURE_COMMAND_CONTEXT:
                return getGestureCommandContextInternalPropertyValueFromValue(value).getValue();

            /* Volume key action (never null): "modifier" or a TerminalGestureAction value */
            case TermuxPropertyConstants.KEY_VOLUME_UP_ACTION:
            case TermuxPropertyConstants.KEY_VOLUME_DOWN_ACTION:
                return getVolumeKeyActionInternalPropertyValueFromValue(key, value);

            /* TerminalKeySequence String (may be null), stored as its normalized value */
            case TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_KEYS:
            case TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_KEYS:
            case TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_KEYS:
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_LEFT_KEYS:
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_RIGHT_KEYS:
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_LEFT_KEYS:
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_RIGHT_KEYS:
            case TermuxPropertyConstants.KEY_VOLUME_UP_KEYS:
            case TermuxPropertyConstants.KEY_VOLUME_DOWN_KEYS:
                return getKeySequenceInternalPropertyValueFromValue(key, value);

            /* Gesture command String (may be null) */
            case TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_COMMAND:
            case TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_COMMAND:
            case TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_COMMAND:
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_LEFT_COMMAND:
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_RIGHT_COMMAND:
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_LEFT_COMMAND:
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_RIGHT_COMMAND:
                return getGestureCommandInternalPropertyValueFromValue(value);

            /* Integer (may be null) */
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_ROW_BACKGROUND_COLOR:
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_BUTTON_ACTIVE_BACKGROUND_COLOR:
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_BUTTON_ACTIVE_TEXT_COLOR:
                return (Integer) getExtraKeysColorInternalPropertyValueFromValue(key, value);
            case TermuxPropertyConstants.KEY_SHORTCUT_CREATE_SESSION:
            case TermuxPropertyConstants.KEY_SHORTCUT_NEXT_SESSION:
            case TermuxPropertyConstants.KEY_SHORTCUT_PREVIOUS_SESSION:
            case TermuxPropertyConstants.KEY_SHORTCUT_RENAME_SESSION:
            case TermuxPropertyConstants.KEY_SHORTCUT_PALETTE:
                return (Integer) getCodePointForSessionShortcuts(key, value);

            /* String (may be null) */
            case TermuxPropertyConstants.KEY_BACK_KEY_BEHAVIOUR:
                return (String) getBackKeyBehaviourInternalPropertyValueFromValue(value);
            case TermuxPropertyConstants.KEY_DEFAULT_WORKING_DIRECTORY:
                return (String) getDefaultWorkingDirectoryInternalPropertyValueFromValue(value);
            case TermuxPropertyConstants.KEY_NIX_USER:
                return (String) getNixUserInternalPropertyValueFromValue(value);
            case TermuxPropertyConstants.KEY_EXTRA_KEYS:
                return (String) getExtraKeysInternalPropertyValueFromValue(value);
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE:
                return (String) getExtraKeysStyleInternalPropertyValueFromValue(value);
            case TermuxPropertyConstants.KEY_NIGHT_MODE:
                return (String) getNightModeInternalPropertyValueFromValue(value);
            case TermuxPropertyConstants.KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR:
                return (String) getSoftKeyboardToggleBehaviourInternalPropertyValueFromValue(value);
            case TermuxPropertyConstants.KEY_VOLUME_KEYS_BEHAVIOUR:
                return (String) getVolumeKeysBehaviourInternalPropertyValueFromValue(value);

            default:
                // default false boolean behaviour
                if (TermuxPropertyConstants.TERMUX_DEFAULT_FALSE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST.contains(key))
                    return (boolean) SharedProperties.getBooleanValueForStringValue(key, value, false, true, LOG_TAG);
                // default true boolean behaviour
                if (TermuxPropertyConstants.TERMUX_DEFAULT_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST.contains(key))
                    return (boolean) SharedProperties.getBooleanValueForStringValue(key, value, true, true, LOG_TAG);
                // default inverted false boolean behaviour
                //else if (TermuxPropertyConstants.TERMUX_DEFAULT_INVERETED_FALSE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST.contains(key))
                //    return (boolean) SharedProperties.getInvertedBooleanValueForStringValue(key, value, false, true, LOG_TAG);
                // default inverted true boolean behaviour
                // else if (TermuxPropertyConstants.TERMUX_DEFAULT_INVERETED_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST.contains(key))
                //    return (boolean) SharedProperties.getInvertedBooleanValueForStringValue(key, value, true, true, LOG_TAG);
                // just use String object as is (may be null)
                else
                    return value;
        }
    }





    /**
     * Returns the internal value after mapping it based on
     * {@code TermuxPropertyConstants#MAP_BELL_BEHAVIOUR} if the value is not {@code null}
     * and is valid, otherwise returns {@link TermuxPropertyConstants#DEFAULT_IVALUE_BELL_BEHAVIOUR}.
     *
     * @param value The {@link String} value to convert.
     * @return Returns the internal value for value.
     */
    public static int getBellBehaviourInternalPropertyValueFromValue(String value) {
        return (int) SharedProperties.getDefaultIfNotInMap(TermuxPropertyConstants.KEY_BELL_BEHAVIOUR, TermuxPropertyConstants.MAP_BELL_BEHAVIOUR, SharedProperties.toLowerCase(value), TermuxPropertyConstants.DEFAULT_IVALUE_BELL_BEHAVIOUR, true, LOG_TAG);
    }

    /**
     * Returns the int for the value if its not null and is between
     * {@link TermuxPropertyConstants#IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT_MIN} and
     * {@link TermuxPropertyConstants#IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT_MAX},
     * otherwise returns {@link TermuxPropertyConstants#DEFAULT_IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT}.
     *
     * @param value The {@link String} value to convert.
     * @return Returns the internal value for value.
     */
    /** The minutes after which a wake lock is released by itself, 0 for never, the default if not in range. */
    public static int getWakeLockTimeoutInternalPropertyValueFromValue(String value) {
        return SharedProperties.getDefaultIfNotInRange(TermuxPropertyConstants.KEY_WAKE_LOCK_TIMEOUT,
            DataUtils.getIntFromString(value, TermuxPropertyConstants.DEFAULT_IVALUE_WAKE_LOCK_TIMEOUT),
            TermuxPropertyConstants.DEFAULT_IVALUE_WAKE_LOCK_TIMEOUT,
            TermuxPropertyConstants.IVALUE_WAKE_LOCK_TIMEOUT_MIN,
            TermuxPropertyConstants.IVALUE_WAKE_LOCK_TIMEOUT_MAX,
            true, true, LOG_TAG);
    }

    public static int getDeleteTMPDIRFilesOlderThanXDaysOnExitInternalPropertyValueFromValue(String value) {
        return SharedProperties.getDefaultIfNotInRange(TermuxPropertyConstants.KEY_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT,
            DataUtils.getIntFromString(value, TermuxPropertyConstants.DEFAULT_IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT),
            TermuxPropertyConstants.DEFAULT_IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT,
            TermuxPropertyConstants.IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT_MIN,
            TermuxPropertyConstants.IVALUE_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT_MAX,
            true, true, LOG_TAG);
    }

    /**
     * Returns the int for the value if its not null and is between
     * {@link TermuxPropertyConstants#IVALUE_TERMINAL_CURSOR_BLINK_RATE_MIN} and
     * {@link TermuxPropertyConstants#IVALUE_TERMINAL_CURSOR_BLINK_RATE_MAX},
     * otherwise returns {@link TermuxPropertyConstants#DEFAULT_IVALUE_TERMINAL_CURSOR_BLINK_RATE}.
     *
     * @param value The {@link String} value to convert.
     * @return Returns the internal value for value.
     */
    public static int getTerminalCursorBlinkRateInternalPropertyValueFromValue(String value) {
        return SharedProperties.getDefaultIfNotInRange(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_BLINK_RATE,
            DataUtils.getIntFromString(value, TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_CURSOR_BLINK_RATE),
            TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_CURSOR_BLINK_RATE,
            TermuxPropertyConstants.IVALUE_TERMINAL_CURSOR_BLINK_RATE_MIN,
            TermuxPropertyConstants.IVALUE_TERMINAL_CURSOR_BLINK_RATE_MAX,
            true, true, LOG_TAG);
    }

    /**
     * Returns the internal value after mapping it based on
     * {@link TermuxPropertyConstants#MAP_TERMINAL_CURSOR_STYLE} if the value is not {@code null}
     * and is valid, otherwise returns {@link TermuxPropertyConstants#DEFAULT_IVALUE_TERMINAL_CURSOR_STYLE}.
     *
     * @param value The {@link String} value to convert.
     * @return Returns the internal value for value.
     */
    public static int getTerminalCursorStyleInternalPropertyValueFromValue(String value) {
        return (int) SharedProperties.getDefaultIfNotInMap(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE, TermuxPropertyConstants.MAP_TERMINAL_CURSOR_STYLE, SharedProperties.toLowerCase(value), TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_CURSOR_STYLE, true, LOG_TAG);
    }

    /**
     * Returns the int for the value if its not null and is between
     * {@link TermuxPropertyConstants#IVALUE_TERMINAL_MARGIN_HORIZONTAL_MIN} and
     * {@link TermuxPropertyConstants#IVALUE_TERMINAL_MARGIN_HORIZONTAL_MAX},
     * otherwise returns {@link TermuxPropertyConstants#DEFAULT_IVALUE_TERMINAL_MARGIN_HORIZONTAL}.
     *
     * @param value The {@link String} value to convert.
     * @return Returns the internal value for value.
     */
    public static int getTerminalMarginHorizontalInternalPropertyValueFromValue(String value) {
        return SharedProperties.getDefaultIfNotInRange(TermuxPropertyConstants.KEY_TERMINAL_MARGIN_HORIZONTAL,
            DataUtils.getIntFromString(value, TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_MARGIN_HORIZONTAL),
            TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_MARGIN_HORIZONTAL,
            TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_HORIZONTAL_MIN,
            TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_HORIZONTAL_MAX,
            true, true, LOG_TAG);
    }

    /**
     * Returns the int for the value if its not null and is between
     * {@link TermuxPropertyConstants#IVALUE_TERMINAL_MARGIN_VERTICAL_MIN} and
     * {@link TermuxPropertyConstants#IVALUE_TERMINAL_MARGIN_VERTICAL_MAX},
     * otherwise returns {@link TermuxPropertyConstants#DEFAULT_IVALUE_TERMINAL_MARGIN_VERTICAL}.
     *
     * @param value The {@link String} value to convert.
     * @return Returns the internal value for value.
     */
    public static int getTerminalMarginVerticalInternalPropertyValueFromValue(String value) {
        return SharedProperties.getDefaultIfNotInRange(TermuxPropertyConstants.KEY_TERMINAL_MARGIN_VERTICAL,
            DataUtils.getIntFromString(value, TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_MARGIN_VERTICAL),
            TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_MARGIN_VERTICAL,
            TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_VERTICAL_MIN,
            TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_VERTICAL_MAX,
            true, true, LOG_TAG);
    }

    /**
     * Returns {@link TermuxPropertyConstants#EXTRA_KEYS_COMPACT_CUSTOM} for "custom", otherwise
     * {@link TermuxPropertyConstants#EXTRA_KEYS_COMPACT_TRUE} or {@link TermuxPropertyConstants#EXTRA_KEYS_COMPACT_FALSE}
     * for a boolean value (the setting used to be a boolean) and the default for anything else.
     */
    @NonNull
    public static String getExtraKeysCompactInternalPropertyValueFromValue(String value) {
        if (value == null) return TermuxPropertyConstants.DEFAULT_EXTRA_KEYS_COMPACT;
        if (TermuxPropertyConstants.EXTRA_KEYS_COMPACT_CUSTOM.equalsIgnoreCase(value.trim()))
            return TermuxPropertyConstants.EXTRA_KEYS_COMPACT_CUSTOM;
        Boolean compact = SharedProperties.getBooleanValueForStringValue(value.trim());
        if (compact == null) return TermuxPropertyConstants.DEFAULT_EXTRA_KEYS_COMPACT;
        return compact ? TermuxPropertyConstants.EXTRA_KEYS_COMPACT_TRUE : TermuxPropertyConstants.EXTRA_KEYS_COMPACT_FALSE;
    }

    /**
     * Returns the int for the value if it is between {@link TermuxPropertyConstants#IVALUE_EXTRA_KEYS_ROW_HEIGHT_MIN}
     * and {@link TermuxPropertyConstants#IVALUE_EXTRA_KEYS_ROW_HEIGHT_MAX}, otherwise
     * {@link TermuxPropertyConstants#DEFAULT_IVALUE_EXTRA_KEYS_ROW_HEIGHT}.
     */
    public static int getExtraKeysRowHeightInternalPropertyValueFromValue(String value) {
        return SharedProperties.getDefaultIfNotInRange(TermuxPropertyConstants.KEY_EXTRA_KEYS_ROW_HEIGHT,
            DataUtils.getIntFromString(value, TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_ROW_HEIGHT),
            TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_ROW_HEIGHT,
            TermuxPropertyConstants.IVALUE_EXTRA_KEYS_ROW_HEIGHT_MIN,
            TermuxPropertyConstants.IVALUE_EXTRA_KEYS_ROW_HEIGHT_MAX,
            true, true, LOG_TAG);
    }

    /**
     * The height of one extra keys row in dp for raw property values (any may be null): exactly
     * {@code rowHeight} with compact "custom", else the normal height scaled by {@code toolbarHeight}
     * and, when compact, by {@link TermuxPropertyConstants#EXTRA_KEYS_COMPACT_ROW_SCALE}.
     */
    public static float getExtraKeysRowHeightDp(String toolbarHeight, String compact, String rowHeight) {
        String mode = getExtraKeysCompactInternalPropertyValueFromValue(compact);
        if (TermuxPropertyConstants.EXTRA_KEYS_COMPACT_CUSTOM.equals(mode))
            return getExtraKeysRowHeightInternalPropertyValueFromValue(rowHeight);
        float scale = getTerminalToolbarHeightScaleFactorInternalPropertyValueFromValue(toolbarHeight);
        if (TermuxPropertyConstants.EXTRA_KEYS_COMPACT_TRUE.equals(mode))
            scale *= TermuxPropertyConstants.EXTRA_KEYS_COMPACT_ROW_SCALE;
        return TermuxPropertyConstants.EXTRA_KEYS_DEFAULT_ROW_HEIGHT_DP * scale;
    }

    /**
     * Returns the int for the value if its not null and is between
     * {@link TermuxPropertyConstants#IVALUE_TERMINAL_TRANSCRIPT_ROWS_MIN} and
     * {@link TermuxPropertyConstants#IVALUE_TERMINAL_TRANSCRIPT_ROWS_MAX},
     * otherwise returns {@link TermuxPropertyConstants#DEFAULT_IVALUE_TERMINAL_TRANSCRIPT_ROWS}.
     *
     * @param value The {@link String} value to convert.
     * @return Returns the internal value for value.
     */
    public static int getTerminalTranscriptRowsInternalPropertyValueFromValue(String value) {
        return SharedProperties.getDefaultIfNotInRange(TermuxPropertyConstants.KEY_TERMINAL_TRANSCRIPT_ROWS,
            DataUtils.getIntFromString(value, TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_TRANSCRIPT_ROWS),
            TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_TRANSCRIPT_ROWS,
            TermuxPropertyConstants.IVALUE_TERMINAL_TRANSCRIPT_ROWS_MIN,
            TermuxPropertyConstants.IVALUE_TERMINAL_TRANSCRIPT_ROWS_MAX,
            true, true, LOG_TAG);
    }

    /**
     * Returns the int for the value if its not null and is between
     * {@link TermuxPropertyConstants#IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MIN} and
     * {@link TermuxPropertyConstants#IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MAX},
     * otherwise returns {@link TermuxPropertyConstants#DEFAULT_IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR}.
     *
     * @param value The {@link String} value to convert.
     * @return Returns the internal value for value.
     */
    public static float getTerminalToolbarHeightScaleFactorInternalPropertyValueFromValue(String value) {
        return SharedProperties.getDefaultIfNotInRange(TermuxPropertyConstants.KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR,
            DataUtils.getFloatFromString(value, TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR),
            TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR,
            TermuxPropertyConstants.IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MIN,
            TermuxPropertyConstants.IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MAX,
            true, true, LOG_TAG);
    }

    /** Value of a {@code shortcut.*} property that switches the shortcut off. */
    public static final String VALUE_SHORTCUT_DISABLED = "disabled";

    /**
     * Returns the code point for the value if key is not {@code null} and value is not {@code null} and is valid,
     * otherwise returns {@code null}.
     *
     * @param key The key for session shortcut.
     * @param value The {@link String} value to convert.
     * @return Returns the internal value for value.
     */
    public static Integer getCodePointForSessionShortcuts(String key, String value) {
        if (key == null) return null;
        if (value == null) return null;
        // "disabled" switches the shortcut off, so a configured default can be removed.
        if (value.trim().equalsIgnoreCase(VALUE_SHORTCUT_DISABLED)) return null;
        String[] parts = value.toLowerCase().trim().split("\\+");
        String input = parts.length == 2 ? parts[1].trim() : null;
        if (!(parts.length == 2 && parts[0].trim().equals("ctrl")) || input.isEmpty() || input.length() > 2) {
            Logger.logError(LOG_TAG, "Keyboard shortcut '" + key + "' is not Ctrl+<something>");
            return null;
        }

        char c = input.charAt(0);
        int codePoint = c;
        if (Character.isLowSurrogate(c)) {
            if (input.length() != 2 || Character.isHighSurrogate(input.charAt(1))) {
                Logger.logError(LOG_TAG, "Keyboard shortcut '" + key + "' is not Ctrl+<something>");
                return null;
            } else {
                codePoint = Character.toCodePoint(input.charAt(1), c);
            }
        }

        return codePoint;
    }

    /**
     * Returns the value itself if it is not {@code null}, otherwise returns {@link TermuxPropertyConstants#DEFAULT_IVALUE_BACK_KEY_BEHAVIOUR}.
     *
     * @param value {@link String} value to convert.
     * @return Returns the internal value for value.
     */
    public static String getBackKeyBehaviourInternalPropertyValueFromValue(String value) {
        return (String) SharedProperties.getDefaultIfNotInMap(TermuxPropertyConstants.KEY_BACK_KEY_BEHAVIOUR, TermuxPropertyConstants.MAP_BACK_KEY_BEHAVIOUR, SharedProperties.toLowerCase(value), TermuxPropertyConstants.DEFAULT_IVALUE_BACK_KEY_BEHAVIOUR, true, LOG_TAG);
    }

    /**
     * Returns the path itself if a directory exists at it and is readable, otherwise returns
     *  {@link TermuxPropertyConstants#DEFAULT_IVALUE_DEFAULT_WORKING_DIRECTORY}.
     *
     * @param path The {@link String} path to check.
     * @return Returns the internal value for value.
     */
    /** A valid guest user name (as nixpp accepts it), else {@link TermuxPropertyConstants#DEFAULT_IVALUE_NIX_USER}. */
    public static String getNixUserInternalPropertyValueFromValue(String value) {
        if (value != null && value.matches("[a-z_][a-z0-9_-]{0,31}")) return value;
        if (value != null && !value.isEmpty())
            Logger.logError(LOG_TAG, "Invalid value \"" + value + "\" for the key \"" + TermuxPropertyConstants.KEY_NIX_USER
                + "\". Using default value \"" + TermuxPropertyConstants.DEFAULT_IVALUE_NIX_USER + "\" instead.");
        return TermuxPropertyConstants.DEFAULT_IVALUE_NIX_USER;
    }

    /** Returns the command verbatim, but treats a blank command as unset. */
    @Nullable
    public static String getGestureCommandInternalPropertyValueFromValue(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }

    /** Returns a valid gesture command context, defaulting to the normal new-session setting. */
    @NonNull
    public static GestureCommandContext getGestureCommandContextInternalPropertyValueFromValue(String value) {
        return GestureCommandContext.parseOrDefault(value,
            TermuxPropertyConstants.DEFAULT_GESTURE_COMMAND_CONTEXT);
    }

    public static String getDefaultWorkingDirectoryInternalPropertyValueFromValue(String path) {
        if (path == null || path.isEmpty()) return TermuxPropertyConstants.DEFAULT_IVALUE_DEFAULT_WORKING_DIRECTORY;
        File workDir = new File(path);
        if (!workDir.exists() || !workDir.isDirectory() || !workDir.canRead()) {
            // Fallback to default directory if user configured working directory does not exist,
            // is not a directory or is not readable.
            Logger.logError(LOG_TAG, "The path \"" + path + "\" for the key \"" + TermuxPropertyConstants.KEY_DEFAULT_WORKING_DIRECTORY + "\" does not exist, is not a directory or is not readable. Using default value \"" + TermuxPropertyConstants.DEFAULT_IVALUE_DEFAULT_WORKING_DIRECTORY + "\" instead.");
            return TermuxPropertyConstants.DEFAULT_IVALUE_DEFAULT_WORKING_DIRECTORY;
        } else {
            return path;
        }
    }

    /**
     * Returns the value itself if it is not {@code null}, otherwise returns {@link TermuxPropertyConstants#DEFAULT_IVALUE_EXTRA_KEYS}.
     *
     * @param value The {@link String} value to convert.
     * @return Returns the internal value for value.
     */
    public static String getExtraKeysInternalPropertyValueFromValue(String value) {
        return SharedProperties.getDefaultIfNullOrEmpty(value, TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS);
    }

    /**
     * Returns the ARGB color for the value of an extra keys color property if its a valid
     * {@code #RGB}, {@code #RRGGBB} or {@code #AARRGGBB} color, otherwise returns {@code null}
     * so that the color defined by the current theme is used.
     *
     * @param key The key of the property, used for logging.
     * @param value The {@link String} value to convert.
     * @return Returns the internal value for value, which may be {@code null}.
     */
    public static Integer getExtraKeysColorInternalPropertyValueFromValue(String key, String value) {
        if (value == null) return null;
        Integer color = ExtraKeysColorParser.parseColor(value);
        if (color == null)
            Logger.logError(LOG_TAG, "The value \"" + value + "\" for the key \"" + key + "\" is invalid. Expected #RGB, #RRGGBB or #AARRGGBB. Using the theme color instead.");
        return color;
    }

    /**
     * Returns the corner radius in dp for the value of the extra keys corner radius property if
     * it is a number from 0 to {@link ExtraKeysColorParser#MAX_CORNER_RADIUS_DP}, otherwise returns
     * {@code null} so that the shape defined by the current theme is used.
     *
     * @param key The key of the property, used for logging.
     * @param value The {@link String} value to convert.
     * @return Returns the internal value for value, which may be {@code null}.
     */
    public static Float getExtraKeysCornerRadiusInternalPropertyValueFromValue(String key, String value) {
        if (value == null) return null;
        Float radius = ExtraKeysColorParser.parseCornerRadiusDp(value);
        if (radius == null)
            Logger.logError(LOG_TAG, "The value \"" + value + "\" for the key \"" + key + "\" is invalid. Expected a number of dp from 0 to " + (int) ExtraKeysColorParser.MAX_CORNER_RADIUS_DP + ". Using the theme shape instead.");
        return radius;
    }

    /**
     * Returns the {@link TerminalGestureAction} for the value of a gesture action property, or
     * {@code defaultAction} if the property is not set. An invalid or blank value is logged and
     * also uses {@code defaultAction}.
     *
     * @param key The key of the property, used for logging.
     * @param value The {@link String} value to convert.
     * @param defaultAction The action to use for a missing or invalid value.
     * @return Returns the internal value for value, which is never {@code null}.
     */
    @NonNull
    public static TerminalGestureAction getGestureActionInternalPropertyValueFromValue(String key, String value, @NonNull TerminalGestureAction defaultAction) {
        if (value == null) return defaultAction;
        TerminalGestureAction action = TerminalGestureAction.parse(value);
        if (action == null) {
            Logger.logError(LOG_TAG, "The value \"" + value + "\" for the key \"" + key + "\" is invalid. Expected one of: " + TerminalGestureAction.getValidValues() + ". Using the default \"" + defaultAction.getValue() + "\" instead.");
            return defaultAction;
        }
        return action;
    }

    /**
     * Returns the internal value of a volume key action property: {@link TermuxPropertyConstants#VOLUME_KEY_ACTION_MODIFIER}
     * or the value of a {@link TerminalGestureAction}. A missing value is the modifier. An invalid
     * value is logged and also the modifier, the behaviour of the volume buttons without configuration.
     *
     * @param key The key of the property, used for logging.
     * @param value The {@link String} value to convert.
     * @return Returns the internal value for value, which is never {@code null}.
     */
    @NonNull
    public static String getVolumeKeyActionInternalPropertyValueFromValue(String key, String value) {
        if (value == null) return TermuxPropertyConstants.VOLUME_KEY_ACTION_MODIFIER;
        String normalized = SharedProperties.toLowerCase(value.trim());
        if (TermuxPropertyConstants.VOLUME_KEY_ACTION_MODIFIER.equals(normalized))
            return TermuxPropertyConstants.VOLUME_KEY_ACTION_MODIFIER;
        TerminalGestureAction action = TerminalGestureAction.parse(value);
        if (action == null || action == TerminalGestureAction.RUN_COMMAND) {
            Logger.logError(LOG_TAG, "The value \"" + value + "\" for the key \"" + key + "\" is invalid. Expected one of: " + TermuxPropertyConstants.VOLUME_KEY_ACTION_MODIFIER + ", " + TerminalGestureAction.getValidValues().replace(", run-command", "") + ". Using the default \"" + TermuxPropertyConstants.VOLUME_KEY_ACTION_MODIFIER + "\" instead.");
            return TermuxPropertyConstants.VOLUME_KEY_ACTION_MODIFIER;
        }
        return action.getValue();
    }

    /**
     * Returns the normalized {@link TerminalKeySequence#getNormalizedValue()} for the value of a
     * {@code *-keys} property, or {@code null} if the property is not set or not valid. An invalid
     * value is logged and the action {@link TerminalGestureAction#SEND_KEYS} then does nothing.
     *
     * @param key The key of the property, used for logging.
     * @param value The {@link String} value to convert.
     */
    @Nullable
    public static String getKeySequenceInternalPropertyValueFromValue(String key, String value) {
        if (value == null) return null;
        TerminalKeySequence sequence = TerminalKeySequence.parse(value);
        if (sequence == null) {
            Logger.logError(LOG_TAG, "The value \"" + value + "\" for the key \"" + key + "\" is invalid: " + TerminalKeySequence.getError(value) + ". The send-keys action will do nothing.");
            return null;
        }
        return sequence.getNormalizedValue();
    }

    /**
     * Returns the value itself if it is not {@code null}, otherwise returns {@link TermuxPropertyConstants#DEFAULT_IVALUE_EXTRA_KEYS_STYLE}.
     *
     * @param value {@link String} value to convert.
     * @return Returns the internal value for value.
     */
    public static String getExtraKeysStyleInternalPropertyValueFromValue(String value) {
        return SharedProperties.getDefaultIfNullOrEmpty(value, TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE);
    }

    /**
     * Returns the value itself if it is not {@code null}, otherwise returns {@link TermuxPropertyConstants#DEFAULT_IVALUE_NIGHT_MODE}.
     *
     * @param value {@link String} value to convert.
     * @return Returns the internal value for value.
     */
    public static String getNightModeInternalPropertyValueFromValue(String value) {
        return (String) SharedProperties.getDefaultIfNotInMap(TermuxPropertyConstants.KEY_NIGHT_MODE,
            TermuxPropertyConstants.MAP_NIGHT_MODE, SharedProperties.toLowerCase(value),
            TermuxPropertyConstants.DEFAULT_IVALUE_NIGHT_MODE, true, LOG_TAG);
    }

    /**
     * Returns the value itself if it is not {@code null}, otherwise returns {@link TermuxPropertyConstants#DEFAULT_IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR}.
     *
     * @param value {@link String} value to convert.
     * @return Returns the internal value for value.
     */
    public static String getSoftKeyboardToggleBehaviourInternalPropertyValueFromValue(String value) {
        return (String) SharedProperties.getDefaultIfNotInMap(TermuxPropertyConstants.KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR, TermuxPropertyConstants.MAP_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR, SharedProperties.toLowerCase(value), TermuxPropertyConstants.DEFAULT_IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR, true, LOG_TAG);
    }

    public int getGestureFeedbackGravity() {
        return getGestureFeedbackGravityFromValue(
            (String) getInternalPropertyValue(TermuxPropertyConstants.KEY_GESTURE_FEEDBACK_POSITION, true));
    }

    static int getGestureFeedbackGravityFromValue(String value) {
        Integer gravity = value == null ? null
            : TermuxPropertyConstants.MAP_TERMINAL_TOAST_POSITION.get(SharedProperties.toLowerCase(value));
        return gravity == null ? TermuxPropertyConstants.DEFAULT_GESTURE_FEEDBACK_GRAVITY : gravity;
    }

    public int getTerminalToastGravity() {
        String value = (String) getInternalPropertyValue(TermuxPropertyConstants.KEY_TERMINAL_TOAST_POSITION, true);
        Integer gravity = value == null ? null
            : TermuxPropertyConstants.MAP_TERMINAL_TOAST_POSITION.get(SharedProperties.toLowerCase(value));
        return gravity == null ? TermuxPropertyConstants.DEFAULT_TERMINAL_TOAST_GRAVITY : gravity;
    }

    /** Whether "Acquire wakelock" also holds a high-performance Wi-Fi lock. */
    public boolean shouldWakeLockHoldWifi() {
        return (boolean) getInternalPropertyValue(TermuxPropertyConstants.KEY_WAKE_LOCK_WIFI, true);
    }

    /** The minutes after which a wake lock is released by itself, 0 for never. */
    public int getWakeLockTimeoutMinutes() {
        return (int) getInternalPropertyValue(TermuxPropertyConstants.KEY_WAKE_LOCK_TIMEOUT, true);
    }

    public boolean areGestureAnimationsEnabled() {
        return (boolean) getInternalPropertyValue(TermuxPropertyConstants.KEY_GESTURE_ANIMATIONS, true);
    }

    /**
     * Returns the value itself if it is not {@code null}, otherwise returns {@link TermuxPropertyConstants#DEFAULT_IVALUE_VOLUME_KEYS_BEHAVIOUR}.
     *
     * @param value {@link String} value to convert.
     * @return Returns the internal value for value.
     */
    public static String getVolumeKeysBehaviourInternalPropertyValueFromValue(String value) {
        return (String) SharedProperties.getDefaultIfNotInMap(TermuxPropertyConstants.KEY_VOLUME_KEYS_BEHAVIOUR, TermuxPropertyConstants.MAP_VOLUME_KEYS_BEHAVIOUR, SharedProperties.toLowerCase(value), TermuxPropertyConstants.DEFAULT_IVALUE_VOLUME_KEYS_BEHAVIOUR, true, LOG_TAG);
    }





    public boolean shouldAllowExternalApps() {
        return (boolean) getInternalPropertyValue(TermuxConstants.PROP_ALLOW_EXTERNAL_APPS, true);
    }

    public boolean isFileShareReceiverDisabled() {
        return (boolean) getInternalPropertyValue(TermuxPropertyConstants.KEY_DISABLE_FILE_SHARE_RECEIVER, true);
    }

    public boolean isFileViewReceiverDisabled() {
        return (boolean) getInternalPropertyValue(TermuxPropertyConstants.KEY_DISABLE_FILE_VIEW_RECEIVER, true);
    }

    public boolean areHardwareKeyboardShortcutsDisabled() {
        return (boolean) getInternalPropertyValue(TermuxPropertyConstants.KEY_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS, true);
    }

    public boolean shouldCreateSessionOnSingleSessionSwitch() {
        return (boolean) getInternalPropertyValue(TermuxPropertyConstants.KEY_CREATE_SESSION_ON_SINGLE_SESSION_SWITCH, true);
    }

    public boolean areTerminalSessionChangeToastsDisabled() {
        return (boolean) getInternalPropertyValue(TermuxPropertyConstants.KEY_DISABLE_TERMINAL_SESSION_CHANGE_TOAST, true);
    }

    public boolean isEnforcingCharBasedInput() {
        return (boolean) getInternalPropertyValue(TermuxPropertyConstants.KEY_ENFORCE_CHAR_BASED_INPUT, true);
    }

    /** The text color of an active extra keys special button, or {@code null} to use the theme color. */
    @Nullable
    public Integer getExtraKeysButtonActiveTextColor() {
        return (Integer) getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS_BUTTON_ACTIVE_TEXT_COLOR, true);
    }

    /** The background color of a pressed extra keys button, or {@code null} to use the theme color. */
    @Nullable
    public Integer getExtraKeysButtonActiveBackgroundColor() {
        return (Integer) getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS_BUTTON_ACTIVE_BACKGROUND_COLOR, true);
    }

    public String getExtraKeysTheme() {
        return (String) getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS_THEME, true);
    }

    @Nullable
    public Integer getExtraKeysRowBackgroundColor() {
        return (Integer) getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS_ROW_BACKGROUND_COLOR, true);
    }

    public String getExtraKeysFont() {
        return (String) getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS_FONT, true);
    }

    /** The weight of the extra keys labels, or 0 for the font's own weight. */
    public int getExtraKeysFontWeight() {
        return (int) getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS_FONT_WEIGHT, true);
    }

    /**
     * Returns the weight for the value if it is a whole number from
     * {@link TermuxPropertyConstants#IVALUE_EXTRA_KEYS_FONT_WEIGHT_MIN} to
     * {@link TermuxPropertyConstants#IVALUE_EXTRA_KEYS_FONT_WEIGHT_MAX}, otherwise 0 (the font's own weight).
     */
    public static int getExtraKeysFontWeightInternalPropertyValueFromValue(String value) {
        int weight = DataUtils.getIntFromString(value == null ? null : value.trim(),
            TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_FONT_WEIGHT);
        return weight >= TermuxPropertyConstants.IVALUE_EXTRA_KEYS_FONT_WEIGHT_MIN
            && weight <= TermuxPropertyConstants.IVALUE_EXTRA_KEYS_FONT_WEIGHT_MAX
            ? weight : TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_FONT_WEIGHT;
    }

    public ExtraKeyAnimation getExtraKeysAnimation() {
        return (ExtraKeyAnimation) getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS_ANIMATION, true);
    }

    /** The corner radius in dp of the extra keys button backgrounds, or {@code null} to use the theme shape. */
    @Nullable
    public Float getExtraKeysButtonCornerRadius() {
        return (Float) getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS_BUTTON_CORNER_RADIUS, true);
    }

    /** The key of the {@code *-keys} property of the key of a gesture action property. */
    @Nullable
    private static String getKeysKeyOfGestureActionKey(String actionKey) {
        if (TermuxPropertyConstants.TERMINAL_MULTI_FINGER_SWIPE_PROPERTIES.contains(actionKey)
            && actionKey.endsWith("-action"))
            return actionKey.substring(0, actionKey.length() - "action".length()) + "keys";
        switch (actionKey) {
            case TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_ACTION: return TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_KEYS;
            case TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_ACTION: return TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_KEYS;
            case TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_ACTION: return TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_KEYS;
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_LEFT_ACTION: return TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_LEFT_KEYS;
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_RIGHT_ACTION: return TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_RIGHT_KEYS;
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_LEFT_ACTION: return TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_LEFT_KEYS;
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_RIGHT_ACTION: return TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_RIGHT_KEYS;
            case TermuxPropertyConstants.KEY_VOLUME_UP_ACTION: return TermuxPropertyConstants.KEY_VOLUME_UP_KEYS;
            case TermuxPropertyConstants.KEY_VOLUME_DOWN_ACTION: return TermuxPropertyConstants.KEY_VOLUME_DOWN_KEYS;
            default: return null;
        }
    }

    /** The property key containing a command for a gesture action property, or {@code null}. */
    @Nullable
    private static String getCommandKeyOfGestureActionKey(String actionKey) {
        if (TermuxPropertyConstants.TERMINAL_MULTI_FINGER_SWIPE_PROPERTIES.contains(actionKey)
            && actionKey.endsWith("-action"))
            return actionKey.substring(0, actionKey.length() - "action".length()) + "command";
        switch (actionKey) {
            case TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_ACTION: return TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_COMMAND;
            case TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_ACTION: return TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_COMMAND;
            case TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_ACTION: return TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_COMMAND;
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_LEFT_ACTION: return TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_LEFT_COMMAND;
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_RIGHT_ACTION: return TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_RIGHT_COMMAND;
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_LEFT_ACTION: return TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_LEFT_COMMAND;
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_RIGHT_ACTION: return TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_RIGHT_COMMAND;
            default: return null;
        }
    }

    /**
     * Get the cached {@link TerminalGestureAction} of a volume key action key as configured, or
     * {@code null} for the virtual modifier (the default, also for an invalid value).
     */
    @Nullable
    private TerminalGestureAction getConfiguredVolumeKeyAction(String key) {
        Object value = getInternalPropertyValue(key, true);
        return value instanceof String ? TerminalGestureAction.parse((String) value) : null;
    }

    /** Get the cached {@link TerminalGestureAction} of a gesture key as configured, its default if it is not usable. */
    @NonNull
    private TerminalGestureAction getConfiguredGestureAction(String key) {
        Object value = getInternalPropertyValue(key, true);
        TerminalGestureAction action = value instanceof String ? TerminalGestureAction.parse((String) value) : null;
        if (action != null) return action;
        if (key.equals(TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_ACTION))
            return TermuxPropertyConstants.DEFAULT_TERMINAL_SINGLE_TAP_ACTION;
        if (key.equals(TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_RIGHT_ACTION))
            return TermuxPropertyConstants.DEFAULT_EXTRA_KEYS_SWIPE_RIGHT_ACTION;
        return TermuxPropertyConstants.defaultMultiFingerSwipeAction(key);
    }

    /** Get the cached keys of a {@code *-keys} property, {@code null} if they are not set or not valid. */
    @Nullable
    private TerminalKeySequence getKeySequence(@Nullable String keysKey) {
        if (keysKey == null) return null;
        Object value = getInternalPropertyValue(keysKey, true);
        return value instanceof String ? TerminalKeySequence.parse((String) value) : null;
    }

    /**
     * Get the {@link TerminalGestureAction} that is really performed for a gesture key: the
     * configured action, but {@link TerminalGestureAction#NONE} for {@code send-keys} without usable keys.
     */
    @NonNull
    private TerminalGestureAction getGestureAction(String key) {
        TerminalGestureAction action = getConfiguredGestureAction(key);
        String commandKey = getCommandKeyOfGestureActionKey(key);
        Object command = commandKey == null ? null : getInternalPropertyValue(commandKey, true);
        boolean hasCommand = command instanceof String && !((String) command).trim().isEmpty();
        return TerminalGestureAction.effective(action,
            getKeySequence(getKeysKeyOfGestureActionKey(key)) != null, hasCommand);
    }

    /** Logs the gestures whose action is {@code send-keys} but that have no usable keys. */
    private void logGesturesWithoutKeys() {
        String[] actionKeys = {
            TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_ACTION,
            TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_ACTION,
            TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_ACTION,
            TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_LEFT_ACTION,
            TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_RIGHT_ACTION,
            TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_LEFT_ACTION,
            TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_RIGHT_ACTION,
            TermuxPropertyConstants.KEY_VOLUME_UP_ACTION,
            TermuxPropertyConstants.KEY_VOLUME_DOWN_ACTION
        };
        for (String actionKey : actionKeys) {
            boolean volumeKey = actionKey.equals(TermuxPropertyConstants.KEY_VOLUME_UP_ACTION)
                || actionKey.equals(TermuxPropertyConstants.KEY_VOLUME_DOWN_ACTION);
            TerminalGestureAction configured = volumeKey ? getConfiguredVolumeKeyAction(actionKey) : getConfiguredGestureAction(actionKey);
            if (configured != TerminalGestureAction.SEND_KEYS) continue;
            String keysKey = getKeysKeyOfGestureActionKey(actionKey);
            // An invalid value was logged when it was loaded.
            if (getInternalPropertyValue(keysKey, true) == null)
                Logger.logError(LOG_TAG, "The action of the key \"" + actionKey + "\" is \"" + TerminalGestureAction.SEND_KEYS.getValue() + "\" but the key \"" + keysKey + "\" is not set or not valid. The action will do nothing.");
        }

        String[] commandActionKeys = {
            TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_ACTION,
            TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_ACTION,
            TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_ACTION,
            TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_LEFT_ACTION,
            TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_RIGHT_ACTION,
            TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_LEFT_ACTION,
            TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_RIGHT_ACTION
        };
        for (String actionKey : commandActionKeys) {
            if (getConfiguredGestureAction(actionKey) != TerminalGestureAction.RUN_COMMAND) continue;
            String commandKey = getCommandKeyOfGestureActionKey(actionKey);
            Object command = commandKey == null ? null : getInternalPropertyValue(commandKey, true);
            if (!(command instanceof String) || ((String) command).trim().isEmpty())
                Logger.logError(LOG_TAG, "The action of the key \"" + actionKey + "\" is \"" + TerminalGestureAction.RUN_COMMAND.getValue() + "\" but its command property is missing or blank. The action will do nothing.");
        }
    }

    /**
     * The keys that {@link TerminalGestureAction#SEND_KEYS} sends for the {@code tapCount}th tap of a
     * quick tap sequence on the terminal.
     *
     * @return The keys, or {@code null} if they are not set or not valid.
     */
    @Nullable
    public TerminalKeySequence getTerminalTapKeys(int tapCount) {
        switch (tapCount) {
            case 1: return getKeySequence(TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_KEYS);
            case 2: return getKeySequence(TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_KEYS);
            case 3: return getKeySequence(TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_KEYS);
            default: return null;
        }
    }

    /** The shell command configured for a terminal tap, or {@code null} if unset. */
    @Nullable
    public String getTerminalTapCommand(int tapCount) {
        String key;
        switch (tapCount) {
            case 1: key = TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_COMMAND; break;
            case 2: key = TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_COMMAND; break;
            case 3: key = TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_COMMAND; break;
            default: return null;
        }
        Object value = getInternalPropertyValue(key, true);
        return value instanceof String ? (String) value : null;
    }

    /** The shell command configured for the left extra-keys swipe, or {@code null} if unset. */
    @Nullable
    public String getExtraKeysSwipeLeftCommand() {
        return getExtraKeysSwipeCommand(false, false);
    }

    /**
     * The shell command configured for an extra-keys swipe, or {@code null} if unset.
     *
     * @param right Whether the swipe is the right swipe (finger moving right to left).
     * @param longSwipe Whether the swipe is the long (further) swipe.
     */
    @Nullable
    public String getExtraKeysSwipeCommand(boolean right, boolean longSwipe) {
        Object value = getInternalPropertyValue(getCommandKeyOfGestureActionKey(getExtraKeysSwipeActionKey(right, longSwipe)), true);
        return value instanceof String ? (String) value : null;
    }

    /** The action property key of an extra-keys swipe, see {@link #getExtraKeysSwipeCommand(boolean, boolean)}. */
    @NonNull
    public static String getExtraKeysSwipeActionKey(boolean right, boolean longSwipe) {
        if (right) return longSwipe ? TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_RIGHT_ACTION
            : TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_RIGHT_ACTION;
        return longSwipe ? TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_LEFT_ACTION
            : TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_LEFT_ACTION;
    }

    /** The configured command environment; defaults to the standard new-session behavior. */
    @NonNull
    public GestureCommandContext getGestureCommandContext() {
        Object value = getInternalPropertyValue(TermuxPropertyConstants.KEY_GESTURE_COMMAND_CONTEXT, true);
        return GestureCommandContext.parseOrDefault(value instanceof String ? (String) value : null,
            TermuxPropertyConstants.DEFAULT_GESTURE_COMMAND_CONTEXT);
    }

    /** The keys that {@link TerminalGestureAction#SEND_KEYS} sends for the swipe on the normal extra keys row, or {@code null}. */
    @Nullable
    public TerminalKeySequence getExtraKeysSwipeLeftKeys() {
        return getExtraKeysSwipeKeys(false, false);
    }

    /** The keys that {@link TerminalGestureAction#SEND_KEYS} sends for an extra-keys swipe, see {@link #getExtraKeysSwipeCommand(boolean, boolean)}. */
    @Nullable
    public TerminalKeySequence getExtraKeysSwipeKeys(boolean right, boolean longSwipe) {
        return getKeySequence(getKeysKeyOfGestureActionKey(getExtraKeysSwipeActionKey(right, longSwipe)));
    }

    /**
     * What the volume up button does, see {@link TermuxPropertyConstants#KEY_VOLUME_UP_ACTION}.
     *
     * @return The action that is really performed, or {@code null} for the virtual Fn modifier,
     * which is the default. {@link TerminalGestureAction#NONE} leaves the button to the system.
     */
    @Nullable
    public TerminalGestureAction getVolumeUpAction() {
        return getVolumeKeyAction(TermuxPropertyConstants.KEY_VOLUME_UP_ACTION);
    }

    /**
     * What the volume down button does, see {@link TermuxPropertyConstants#KEY_VOLUME_DOWN_ACTION}.
     *
     * @return The action that is really performed, or {@code null} for the virtual Ctrl modifier,
     * which is the default. {@link TerminalGestureAction#NONE} leaves the button to the system.
     */
    @Nullable
    public TerminalGestureAction getVolumeDownAction() {
        return getVolumeKeyAction(TermuxPropertyConstants.KEY_VOLUME_DOWN_ACTION);
    }

    @Nullable
    private TerminalGestureAction getVolumeKeyAction(String key) {
        TerminalGestureAction action = getConfiguredVolumeKeyAction(key);
        if (action == null) return null;
        return TerminalGestureAction.effective(action, getKeySequence(getKeysKeyOfGestureActionKey(key)) != null);
    }

    /** The keys that {@link TerminalGestureAction#SEND_KEYS} sends for the volume up button, or {@code null}. */
    @Nullable
    public TerminalKeySequence getVolumeUpKeys() {
        return getKeySequence(TermuxPropertyConstants.KEY_VOLUME_UP_KEYS);
    }

    /** The keys that {@link TerminalGestureAction#SEND_KEYS} sends for the volume down button, or {@code null}. */
    @Nullable
    public TerminalKeySequence getVolumeDownKeys() {
        return getKeySequence(TermuxPropertyConstants.KEY_VOLUME_DOWN_KEYS);
    }

    @NonNull
    public TerminalGestureAction getTerminalMultiFingerSwipeAction(int fingers, String direction) {
        return getGestureAction(TermuxPropertyConstants.terminalMultiFingerSwipeProperty(fingers, direction, "action"));
    }

    @Nullable
    public TerminalKeySequence getTerminalMultiFingerSwipeKeys(int fingers, String direction) {
        return getKeySequence(TermuxPropertyConstants.terminalMultiFingerSwipeProperty(fingers, direction, "keys"));
    }

    @Nullable
    public String getTerminalMultiFingerSwipeCommand(int fingers, String direction) {
        return (String) getInternalPropertyValue(
            TermuxPropertyConstants.terminalMultiFingerSwipeProperty(fingers, direction, "command"), true);
    }

    /** The action of a single finger tap on the terminal, see {@link TermuxPropertyConstants#KEY_TERMINAL_SINGLE_TAP_ACTION}. */
    @NonNull
    public TerminalGestureAction getTerminalSingleTapAction() {
        return getGestureAction(TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_ACTION);
    }

    /** The action of two quick finger taps on the terminal, see {@link TermuxPropertyConstants#KEY_TERMINAL_DOUBLE_TAP_ACTION}. */
    @NonNull
    public TerminalGestureAction getTerminalDoubleTapAction() {
        return getGestureAction(TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_ACTION);
    }

    /** The action of three quick finger taps on the terminal, see {@link TermuxPropertyConstants#KEY_TERMINAL_TRIPLE_TAP_ACTION}. */
    @NonNull
    public TerminalGestureAction getTerminalTripleTapAction() {
        return getGestureAction(TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_ACTION);
    }

    /**
     * The action of the {@code tapCount}th tap of a quick tap sequence on the terminal.
     *
     * @param tapCount {@code 1} for a single tap, {@code 2} for a double tap and {@code 3} for a
     *                 triple tap. Any other count has no action.
     */
    @NonNull
    public TerminalGestureAction getTerminalTapAction(int tapCount) {
        switch (tapCount) {
            case 1: return getTerminalSingleTapAction();
            case 2: return getTerminalDoubleTapAction();
            case 3: return getTerminalTripleTapAction();
            default: return TerminalGestureAction.NONE;
        }
    }

    /**
     * The highest tap count that has an action, {@code 0} if no tap has one. A value of {@code 1}
     * or less means that only single taps need to be recognized, so that no tap has to wait for a
     * possible following one.
     */
    public int getTerminalMaxTapActionCount() {
        for (int tapCount = 3; tapCount >= 1; tapCount--) {
            if (getTerminalTapAction(tapCount) != TerminalGestureAction.NONE) return tapCount;
        }
        return 0;
    }

    /** The action of a swipe on the normal extra keys row, see {@link TermuxPropertyConstants#KEY_EXTRA_KEYS_SWIPE_LEFT_ACTION}. */
    @NonNull
    public TerminalGestureAction getExtraKeysSwipeLeftAction() {
        return getExtraKeysSwipeAction(false, false);
    }

    /** The action of an extra-keys swipe, see {@link #getExtraKeysSwipeCommand(boolean, boolean)}. */
    @NonNull
    public TerminalGestureAction getExtraKeysSwipeAction(boolean right, boolean longSwipe) {
        return getGestureAction(getExtraKeysSwipeActionKey(right, longSwipe));
    }

    public boolean shouldExtraKeysTextBeAllCaps() {
        return (boolean) getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS_TEXT_ALL_CAPS, true);
    }

    /** Whether the extra keys toolbar follows the soft keyboard: shown only while it is visible. */
    public boolean shouldExtraKeysAutoHide() {
        return (boolean) getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS_AUTO_HIDE, true);
    }

    /** How high the extra keys rows are: one of the {@code EXTRA_KEYS_COMPACT_*} values. */
    @NonNull
    public String getExtraKeysCompact() {
        return (String) getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS_COMPACT, true);
    }

    /** The height of one extra keys row with compact "custom", in dp. */
    public int getExtraKeysRowHeight() {
        return (int) getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS_ROW_HEIGHT, true);
    }

    /** The height of one extra keys row as a factor of its normal height: the toolbar height
     * scaled down further when the rows are compact, or the custom row height. */
    public float getExtraKeysRowScaleFactor() {
        String compact = getExtraKeysCompact();
        if (TermuxPropertyConstants.EXTRA_KEYS_COMPACT_CUSTOM.equals(compact))
            return getExtraKeysRowHeight() / TermuxPropertyConstants.EXTRA_KEYS_DEFAULT_ROW_HEIGHT_DP;
        return getTerminalToolbarHeightScaleFactor()
            * (TermuxPropertyConstants.EXTRA_KEYS_COMPACT_TRUE.equals(compact)
                ? TermuxPropertyConstants.EXTRA_KEYS_COMPACT_ROW_SCALE : 1);
    }

    public boolean shouldSoftKeyboardBeHiddenOnStartup() {
        return (boolean) getInternalPropertyValue(TermuxPropertyConstants.KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP, true);
    }

    public boolean shouldRunTermuxAmSocketServer() {
        return (boolean) getInternalPropertyValue(TermuxPropertyConstants.KEY_RUN_TERMUX_AM_SOCKET_SERVER, true);
    }

    public boolean shouldOpenTerminalTranscriptURLOnClick() {
        return (boolean) getInternalPropertyValue(TermuxPropertyConstants.KEY_TERMINAL_ONCLICK_URL_OPEN, true);
    }

    public boolean isUsingCtrlSpaceWorkaround() {
        return (boolean) getInternalPropertyValue(TermuxPropertyConstants.KEY_USE_CTRL_SPACE_WORKAROUND, true);
    }

    public boolean isUsingFullScreen() {
        return (boolean) getInternalPropertyValue(TermuxPropertyConstants.KEY_USE_FULLSCREEN, true);
    }

    public boolean isUsingFullScreenWorkAround() {
        return (boolean) getInternalPropertyValue(TermuxPropertyConstants.KEY_USE_FULLSCREEN_WORKAROUND, true);
    }

    public int getBellBehaviour() {
        return (int) getInternalPropertyValue(TermuxPropertyConstants.KEY_BELL_BEHAVIOUR, true);
    }

    public int getDeleteTMPDIRFilesOlderThanXDaysOnExit() {
        return (int) getInternalPropertyValue(TermuxPropertyConstants.KEY_DELETE_TMPDIR_FILES_OLDER_THAN_X_DAYS_ON_EXIT, true);
    }

    public int getTerminalCursorBlinkRate() {
        return (int) getInternalPropertyValue(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_BLINK_RATE, true);
    }

    public int getTerminalCursorStyle() {
        return (int) getInternalPropertyValue(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE, true);
    }

    public int getTerminalMarginHorizontal() {
        return (int) getInternalPropertyValue(TermuxPropertyConstants.KEY_TERMINAL_MARGIN_HORIZONTAL, true);
    }

    public int getTerminalMarginVertical() {
        return (int) getInternalPropertyValue(TermuxPropertyConstants.KEY_TERMINAL_MARGIN_VERTICAL, true);
    }

    public boolean shouldUseLibghostty() {
        return TermuxPropertyConstants.TERMINAL_ENGINE_GHOSTTY.equals(
            getInternalPropertyValue(TermuxPropertyConstants.KEY_TERMINAL_ENGINE, true));
    }

    public int getTerminalTranscriptRows() {
        return (int) getInternalPropertyValue(TermuxPropertyConstants.KEY_TERMINAL_TRANSCRIPT_ROWS, true);
    }

    public float getTerminalToolbarHeightScaleFactor() {
        return (float) getInternalPropertyValue(TermuxPropertyConstants.KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR, true);
    }

    public boolean isBackKeyTheEscapeKey() {
        return (boolean) TermuxPropertyConstants.IVALUE_BACK_KEY_BEHAVIOUR_ESCAPE.equals(getInternalPropertyValue(TermuxPropertyConstants.KEY_BACK_KEY_BEHAVIOUR, true));
    }

    public boolean shouldStartNixSession() {
        return (boolean) getInternalPropertyValue(TermuxPropertyConstants.KEY_NIX_SESSION, true);
    }

    public String getNixUser() {
        return (String) getInternalPropertyValue(TermuxPropertyConstants.KEY_NIX_USER, true);
    }

    public String getDefaultWorkingDirectory() {
        return (String) getInternalPropertyValue(TermuxPropertyConstants.KEY_DEFAULT_WORKING_DIRECTORY, true);
    }

    public String getNightMode() {
        return (String) getInternalPropertyValue(TermuxPropertyConstants.KEY_NIGHT_MODE, true);
    }

    /** Get the {@link TermuxPropertyConstants#KEY_NIGHT_MODE} value from the properties file on disk. */
    public static String getNightMode(Context context) {
        return (String) TermuxSharedProperties.getTermuxInternalPropertyValue(context,
            TermuxPropertyConstants.KEY_NIGHT_MODE);
    }

    public boolean shouldEnableDisableSoftKeyboardOnToggle() {
        return (boolean) TermuxPropertyConstants.IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_ENABLE_DISABLE.equals(getInternalPropertyValue(TermuxPropertyConstants.KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR, true));
    }

    public boolean areVirtualVolumeKeysDisabled() {
        return (boolean) TermuxPropertyConstants.IVALUE_VOLUME_KEY_BEHAVIOUR_VOLUME.equals(getInternalPropertyValue(TermuxPropertyConstants.KEY_VOLUME_KEYS_BEHAVIOUR, true));
    }





    public void dumpPropertiesToLog() {
        Properties properties = getProperties(true);
        StringBuilder propertiesDump = new StringBuilder();

        propertiesDump.append(mLabel).append(" Termux Properties:");
        if (properties != null) {
            for (String key : properties.stringPropertyNames()) {
                propertiesDump.append("\n").append(key).append(": `")
                    .append(getPropertyValueForLog(key, String.valueOf(properties.get(key)))).append("`");
            }
        } else {
            propertiesDump.append(" null");
        }

        Logger.logVerbose(LOG_TAG, propertiesDump.toString());
    }

    public void dumpInternalPropertiesToLog() {
        HashMap<String, Object> internalProperties = (HashMap<String, Object>) getInternalProperties();
        StringBuilder internalPropertiesDump = new StringBuilder();

        internalPropertiesDump.append(mLabel).append(" Internal Properties:");
        if (internalProperties != null) {
            for (String key : internalProperties.keySet()) {
                internalPropertiesDump.append("\n").append(key).append(": `")
                    .append(getPropertyValueForLog(key, String.valueOf(internalProperties.get(key)))).append("`");
            }
        }

        Logger.logVerbose(LOG_TAG, internalPropertiesDump.toString());
    }

    /** Keep configured command text out of property dumps and logs. */
    @NonNull
    public static String getPropertyValueForLog(@NonNull String key, @Nullable String value) {
        if ((TermuxPropertyConstants.TERMINAL_MULTI_FINGER_SWIPE_PROPERTIES.contains(key)
                && key.endsWith("-command"))
            || key.equals(TermuxPropertyConstants.KEY_TERMINAL_SINGLE_TAP_COMMAND)
            || key.equals(TermuxPropertyConstants.KEY_TERMINAL_DOUBLE_TAP_COMMAND)
            || key.equals(TermuxPropertyConstants.KEY_TERMINAL_TRIPLE_TAP_COMMAND)
            || key.equals(TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_LEFT_COMMAND)
            || key.equals(TermuxPropertyConstants.KEY_EXTRA_KEYS_SWIPE_RIGHT_COMMAND)
            || key.equals(TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_LEFT_COMMAND)
            || key.equals(TermuxPropertyConstants.KEY_EXTRA_KEYS_LONG_SWIPE_RIGHT_COMMAND))
            return "[redacted]";
        if (key.equals(TermuxPropertyConstants.KEY_EXTRA_KEYS) && value != null
            && value.matches("(?s).*['\\\"]?command['\\\"]?\\s*:.*"))
            return "[redacted]";
        return value == null ? "null" : value;
    }

}
