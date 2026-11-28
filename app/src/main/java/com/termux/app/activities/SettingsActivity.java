package com.termux.app.activities;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.Observer;
import androidx.preference.DialogPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;

import com.termux.R;
import com.termux.app.settings.AppAccent;
import com.termux.app.settings.SettingsNavigationPreference;
import com.termux.app.settings.SettingsSearchIndex;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import dev.pschmitt.termux.shared.activity.media.AppCompatActivityUtils;
import dev.pschmitt.termux.shared.theme.NightMode;

public class SettingsActivity extends AppCompatActivity
        implements PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    public static final String EXTRA_SEARCH_QUERY = "dev.pschmitt.termux.settings.SEARCH_QUERY";
    /** Open one search result directly: the value of {@link SettingsSearchIndex.Result#id()}. */
    public static final String EXTRA_OPEN_SETTING = "dev.pschmitt.termux.settings.OPEN_SETTING";

    /** The open Settings screen, so that {@code tpp ui settings hide} and {@code tpp ui clear} can close it. */
    @Nullable private static java.lang.ref.WeakReference<SettingsActivity> open;

    /** Whether a Settings screen is open. */
    public static boolean isOpen() {
        SettingsActivity activity = open == null ? null : open.get();
        return activity != null && !activity.isFinishing();
    }

    /** Close the open Settings screen, if any. */
    public static void finishOpen() {
        SettingsActivity activity = open == null ? null : open.get();
        if (activity != null && !activity.isFinishing()) activity.finish();
    }

    private final Map<Preference, Boolean> searchVisibility = new LinkedHashMap<>();
    private PreferenceGroup searchScreen;
    private PreferenceCategory searchResults;
    private List<SettingsSearchIndex.Result> searchIndex;

    private View settingsIntro;
    private View searchBar;
    private EditText searchQuery;
    private View clearSearchButton;
    private final OnBackPressedCallback searchBack = new OnBackPressedCallback(false) {
        @Override public void handleOnBackPressed() {
            resetSearch();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        open = new java.lang.ref.WeakReference<>(this);

        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);

        AppAccent.apply(this, AppAccent.get(this));
        setContentView(R.layout.activity_settings);
        settingsIntro = findViewById(R.id.settings_intro);
        configureSearch();
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings, new RootPreferencesFragment())
                .runOnCommit(() -> {
                    updateNavigation();
                    String query = getIntent().getStringExtra(EXTRA_SEARCH_QUERY);
                    if (query != null) searchQuery.setText(query);
                    String setting = getIntent().getStringExtra(EXTRA_OPEN_SETTING);
                    // After this transaction: opening a setting runs transactions of its own.
                    if (setting != null) getWindow().getDecorView().post(() -> openSetting(setting));
                })
                .commit();
        }

        AppCompatActivityUtils.setToolbar(this, dev.pschmitt.termux.shared.R.id.toolbar);
        AppCompatActivityUtils.setShowBackButtonInActionBar(this, true);
        getSupportFragmentManager().addOnBackStackChangedListener(this::updateNavigation);
        updateNavigation();
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    public boolean onPreferenceStartFragment(@NonNull PreferenceFragmentCompat caller,
                                             @NonNull Preference preference) {
        String fragmentClass = preference.getFragment();
        if (fragmentClass == null) return false;
        Fragment destination = getSupportFragmentManager().getFragmentFactory()
                .instantiate(getClassLoader(), fragmentClass);
        destination.setArguments(preference.getExtras());
        // Preserve the caller relationship for preference fragments expecting a target fragment.
        destination.setTargetFragment(caller, 0);
        CharSequence title = preference.getTitle();
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.settings, destination, title == null ? null : title.toString())
                .addToBackStack(null)
                .commit();
        return true;
    }

    private void updateNavigation() {
        resetSearch();
        Fragment screen = getSupportFragmentManager().findFragmentById(R.id.settings);
        CharSequence title = null;
        if (screen instanceof PreferenceFragmentCompat) {
            PreferenceGroup preferences = ((PreferenceFragmentCompat) screen).getPreferenceScreen();
            if (preferences != null) title = preferences.getTitle();
        }
        if (title == null && screen != null) title = screen.getTag();
        boolean home = screen instanceof RootPreferencesFragment;
        settingsIntro.setVisibility(home ? View.VISIBLE : View.GONE);
        searchBar.setVisibility(screen instanceof PreferenceFragmentCompat ? View.VISIBLE : View.GONE);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) actionBar.setDisplayShowTitleEnabled(!home);
        setTitle(title == null ? getString(R.string.title_activity_termux_settings) : title);
    }

    private void configureSearch() {
        searchBar = findViewById(R.id.settings_search_bar);
        searchQuery = findViewById(R.id.settings_search_query);
        clearSearchButton = findViewById(R.id.settings_search_clear);
        getOnBackPressedDispatcher().addCallback(this, searchBack);
        findViewById(R.id.settings_search_button).setOnClickListener(view -> {
            searchQuery.requestFocus();
            WindowCompat.getInsetsController(getWindow(), searchQuery).show(WindowInsetsCompat.Type.ime());
        });
        clearSearchButton.setOnClickListener(view -> searchQuery.setText(""));
        searchQuery.setOnFocusChangeListener((view, focused) ->
            searchBack.setEnabled(focused || searchQuery.length() > 0));
        searchQuery.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            hideSearchKeyboard();
            return true;
        });
        searchQuery.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                clearSearchButton.setVisibility(text.length() == 0 ? View.INVISIBLE : View.VISIBLE);
                searchBack.setEnabled(searchQuery.hasFocus() || text.length() > 0);
                Fragment screen = getSupportFragmentManager().findFragmentById(R.id.settings);
                if (searchScreen == null && text.length() > 0 && screen instanceof PreferenceFragmentCompat)
                    startSearch((PreferenceFragmentCompat) screen);
                filterSearch(text.toString());
            }
            @Override public void afterTextChanged(Editable text) {}
        });
    }

    private void resetSearch() {
        if (searchQuery.hasFocus()) hideSearchKeyboard();
        searchQuery.clearFocus();
        if (searchQuery.length() > 0) searchQuery.setText("");
        clearSearch();
        searchBack.setEnabled(false);
    }

    private void hideSearchKeyboard() {
        WindowCompat.getInsetsController(getWindow(), searchQuery).hide(WindowInsetsCompat.Type.ime());
    }

    private static boolean animationsEnabled() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled();
    }

    private void rememberVisibility(PreferenceGroup group) {
        if (group == null) return;
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference preference = group.getPreference(i);
            searchVisibility.put(preference, preference.isVisible());
            if (preference instanceof PreferenceGroup) rememberVisibility((PreferenceGroup) preference);
        }
    }

    void startSearch(@NonNull PreferenceFragmentCompat fragment) {
        searchScreen = fragment.getPreferenceScreen();
        rememberVisibility(searchScreen);
        if (searchIndex == null) searchIndex = SettingsSearchIndex.build(this);
    }

    void filterSearch(String query) {
        if (searchScreen == null) return;
        String normalized = query == null ? "" : query.trim();
        if (normalized.isEmpty()) {
            removeSearchResults();
            restoreVisibility(false);
            return;
        }

        for (Map.Entry<Preference, Boolean> entry : searchVisibility.entrySet())
            entry.getKey().setVisible(false);
        boolean showingResults = searchResults != null;
        if (!showingResults) {
            searchResults = new PreferenceCategory(this);
            searchResults.setTitle(R.string.settings_search_results);
            searchScreen.addPreference(searchResults);
        }
        searchResults.removeAll();
        int resultNumber = 0;
        for (SettingsSearchIndex.Result result : searchIndex) {
            if (!result.matches(normalized)) continue;
            Preference item = new SettingsNavigationPreference(this);
            item.setTitle(result.title);
            item.setSummary(result.breadcrumb);
            item.setKey("settings-search-result-" + resultNumber++);
            item.setOnPreferenceClickListener(clicked -> {
                navigateToSearchResult(result);
                return true;
            });
            searchResults.addPreference(item);
        }
        if (resultNumber == 0) {
            Preference empty = new SettingsNavigationPreference(this);
            empty.setTitle(R.string.settings_search_empty);
            empty.setSelectable(false);
            searchResults.addPreference(empty);
        }
        Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.settings);
        if (!showingResults && animationsEnabled() && fragment instanceof PreferenceFragmentCompat) {
            View list = ((PreferenceFragmentCompat) fragment).getListView();
            list.animate().cancel();
            list.setAlpha(0f);
            list.animate().alpha(1f).setDuration(160).start();
        }
    }

    private void clearSearch() {
        removeSearchResults();
        restoreVisibility(true);
        searchScreen = null;
    }

    private void removeSearchResults() {
        if (searchResults != null && searchScreen != null) searchScreen.removePreference(searchResults);
        searchResults = null;
    }

    private void restoreVisibility(boolean clearSnapshot) {
        for (Map.Entry<Preference, Boolean> entry : searchVisibility.entrySet())
            entry.getKey().setVisible(entry.getValue());
        if (clearSnapshot) searchVisibility.clear();
    }

    /** Open the search result with {@code id}, as a tap on it in the search results does. */
    private void openSetting(@NonNull String id) {
        if (searchIndex == null) searchIndex = SettingsSearchIndex.build(this);
        for (SettingsSearchIndex.Result result : searchIndex) {
            if (result.id().equals(id)) {
                navigateToSearchResult(result);
                return;
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (open != null && open.get() == this) open = null;
        super.onDestroy();
    }

    private void navigateToSearchResult(@NonNull SettingsSearchIndex.Result result) {
        Fragment caller = getSupportFragmentManager().findFragmentById(R.id.settings);
        if (caller == null) return;
        resetSearch();
        Fragment destination = caller;
        for (int i = 0; i < result.fragmentPath.size(); i++) {
            String fragmentClass = result.fragmentPath.get(i);
            destination = getSupportFragmentManager().getFragmentFactory()
                .instantiate(getClassLoader(), fragmentClass);
            destination.setTargetFragment(caller, 0);
            getSupportFragmentManager().beginTransaction()
                .replace(R.id.settings, destination, result.title)
                .addToBackStack(null)
                .commit();
            getSupportFragmentManager().executePendingTransactions();
            caller = destination;
        }
        if (result.preferenceKey != null && destination instanceof PreferenceFragmentCompat)
            openPreference((PreferenceFragmentCompat) destination, result.preferenceKey);
    }

    private void openPreference(@NonNull PreferenceFragmentCompat fragment, @NonNull String key) {
        Observer<LifecycleOwner> observer = new Observer<LifecycleOwner>() {
            @Override public void onChanged(LifecycleOwner owner) {
                if (owner == null) return;
                fragment.getViewLifecycleOwnerLiveData().removeObserver(this);
                fragment.getListView().post(() -> {
                    if (!fragment.isResumed()) return;
                    Preference target = fragment.findPreference(key);
                    if (target == null || !target.isVisible()) return;
                    fragment.scrollToPreference(key);
                    if (target instanceof DialogPreference || "extra-keys".equals(key))
                        target.performClick();
                });
            }
        };
        fragment.getViewLifecycleOwnerLiveData().observe(this, observer);
    }

    public static class RootPreferencesFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            Context context = getContext();
            if (context == null) return;

            setPreferencesFromResource(R.xml.root_preferences, rootKey);
        }
    }

}
