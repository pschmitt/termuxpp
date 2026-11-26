package com.termux.app.fragments.settings.termux;

import android.content.ClipData;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.button.MaterialButton;
import com.termux.R;
import com.termux.app.settings.ExtraKeysLayout;
import com.termux.app.terminal.TerminalAppearance;

import dev.pschmitt.termux.shared.interact.DialogUtils;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysColorParser;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysConstants;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysInfo;
import dev.pschmitt.termux.shared.termux.extrakeys.ExtraKeysView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Properties;

/**
 * WYSIWYG editor for the {@code extra-keys} layout. The preview is the real toolbar view: tap a key
 * to select it, drag it to move it, and edit every attribute in the inspector below. Nothing is
 * written until Save.
 */
public class ExtraKeysEditorDialog extends DialogFragment {
    private static final int MAX_UNDO = 100;

    private ExtraKeysLayout layout;
    private String source;
    private String initial;
    private boolean useDefault;
    private int selectedRow = -1;
    private int selectedKey = -1;
    private ArrayList<String> undo = new ArrayList<>();
    private ArrayList<String> redo = new ArrayList<>();
    /** Inspector edits of one key form a single undo step until something else happens. */
    @Nullable private String editSession;

    private FrameLayout previewHost;
    private ScrollView previewScroll;
    private LinearLayout gutter;
    @Nullable private ExtraKeysView keyboard;
    /** {row, key} for each preview child; key -1 is the placeholder of an empty row. */
    private final ArrayList<int[]> cells = new ArrayList<>();
    private TextView emptyPreview;
    private TextView selectionTitle;
    private View selectionBar;
    private MaterialButton[] selectionButtons;
    private TextView inspectorEmpty;
    private ExtraKeyInspector inspector;
    private MaterialButton undoButton;
    private MaterialButton redoButton;
    private MaterialButton addRowButton;
    @Nullable private View dropTarget;
    @Nullable private Drawable dropIndicator;

    static void show(PropertiesPreferencesFragment parent, Properties values) {
        String value = values.getProperty("extra-keys", "");
        ExtraKeysEditorDialog dialog = new ExtraKeysEditorDialog();
        Bundle args = new Bundle();
        args.putString("value", value);
        for (String key : new String[]{"extra-keys-style", "extra-keys-font", "extra-keys-theme", "extra-keys-row-background-color", "extra-keys-text-all-caps", "terminal-toolbar-height",
                "extra-keys-button-active-text-color", "extra-keys-button-active-background-color", "extra-keys-button-corner-radius"}) {
            args.putString(key, values.getProperty(key));
        }
        dialog.setArguments(args);
        dialog.setTargetFragment(parent, 0);
        dialog.show(parent.getParentFragmentManager(), "extra_keys_editor");
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setCancelable(false);
        source = state == null ? requireArguments().getString("value", "") : state.getString("value", "");
        useDefault = state == null ? source.isEmpty() : state.getBoolean("default");
        try {
            layout = source.isEmpty() ? ExtraKeysLayout.defaults() : new ExtraKeysLayout(source);
        } catch (JSONException e) {
            layout = null;
        }
        if (state == null) {
            initial = currentValue();
        } else {
            initial = state.getString("initial", currentValue());
            selectedRow = state.getInt("row", -1);
            selectedKey = state.getInt("key", -1);
            ArrayList<String> savedUndo = state.getStringArrayList("undo");
            ArrayList<String> savedRedo = state.getStringArrayList("redo");
            if (savedUndo != null) undo = savedUndo;
            if (savedRedo != null) redo = savedRedo;
        }
        clampSelection();
    }

    @Override public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("value", layout == null ? source : layout.toString());
        out.putBoolean("default", useDefault);
        out.putString("initial", initial);
        out.putInt("row", selectedRow);
        out.putInt("key", selectedKey);
        out.putStringArrayList("undo", undo);
        out.putStringArrayList("redo", redo);
    }

    @NonNull @Override public android.app.Dialog onCreateDialog(Bundle state) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(4), dp(16), 0);

        LinearLayout bar = new LinearLayout(requireContext());
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(requireContext());
        title.setText(R.string.extra_preview);
        title.setTextAppearance(requireContext(), com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle1);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        undoButton = iconButton(bar, "↶", R.string.extra_editor_undo, v -> undo());
        redoButton = iconButton(bar, "↷", R.string.extra_editor_redo, v -> redo());
        iconButton(bar, "⋮", R.string.extra_more, this::moreMenu);
        root.addView(bar);

        TextView help = new TextView(requireContext());
        help.setText(R.string.extra_editor_preview_help);
        root.addView(help);

        LinearLayout stage = new LinearLayout(requireContext());
        gutter = new LinearLayout(requireContext());
        gutter.setOrientation(LinearLayout.VERTICAL);
        stage.addView(gutter, new LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.MATCH_PARENT));
        previewHost = new FrameLayout(requireContext());
        stage.addView(previewHost, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        previewScroll = new ScrollView(requireContext());
        previewScroll.addView(stage);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        previewParams.setMargins(0, dp(8), 0, 0);
        root.addView(previewScroll, previewParams);
        emptyPreview = new TextView(requireContext());
        emptyPreview.setPadding(0, dp(12), 0, dp(12));
        root.addView(emptyPreview);

        addRowButton = textButton(R.string.extra_add_row, v -> {
            checkpoint();
            layout.addRow();
            changed();
        });
        LinearLayout addRowLine = new LinearLayout(requireContext());
        addRowLine.addView(addRowButton);
        root.addView(addRowLine);

        LinearLayout selection = new LinearLayout(requireContext());
        selection.setOrientation(LinearLayout.VERTICAL);
        selectionTitle = new TextView(requireContext());
        selectionTitle.setTextAppearance(requireContext(), com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle1);
        selection.addView(selectionTitle);
        LinearLayout actions = new LinearLayout(requireContext());
        selectionButtons = new MaterialButton[]{
            iconButton(actions, "←", R.string.extra_editor_move_left, v -> moveSelected(0, -1)),
            iconButton(actions, "→", R.string.extra_editor_move_right, v -> moveSelected(0, 1)),
            iconButton(actions, "↑", R.string.extra_editor_move_up, v -> moveSelected(-1, 0)),
            iconButton(actions, "↓", R.string.extra_editor_move_down, v -> moveSelected(1, 0)),
            iconButton(actions, "⇤+", R.string.extra_editor_insert_before, v -> insertAt(selectedRow, selectedKey)),
            iconButton(actions, "+⇥", R.string.extra_editor_insert_after, v -> insertAt(selectedRow, selectedKey + 1)),
            iconButton(actions, "⧉", R.string.extra_editor_duplicate, v -> duplicateSelected()),
            iconButton(actions, "🗑", R.string.extra_editor_delete, v -> deleteSelected()),
        };
        HorizontalScrollView actionScroll = new HorizontalScrollView(requireContext());
        actionScroll.setHorizontalScrollBarEnabled(false);
        actionScroll.addView(actions);
        selection.addView(actionScroll);
        selectionBar = selection;
        root.addView(selection);

        LinearLayout details = new LinearLayout(requireContext());
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(0, 0, 0, dp(16));
        inspectorEmpty = new TextView(requireContext());
        inspectorEmpty.setPadding(0, dp(8), 0, 0);
        details.addView(inspectorEmpty);
        inspector = new ExtraKeyInspector(requireContext());
        inspector.setListener(this::onKeyEdited);
        details.addView(inspector);
        TextView draft = new TextView(requireContext());
        draft.setText(R.string.extra_draft_help);
        draft.setPadding(0, dp(16), 0, 0);
        details.addView(draft);
        ScrollView detailScroll = new ScrollView(requireContext());
        detailScroll.addView(details);
        root.addView(detailScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        render();
        AlertDialog dialog = DialogUtils.builder(requireContext()).setTitle(R.string.extra_editor_title)
            .setView(root).setPositiveButton(R.string.extra_save, null)
            .setNegativeButton(android.R.string.cancel, null).create();
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK || event.getAction() != KeyEvent.ACTION_UP) return false;
            requestClose();
            return true;
        });
        return dialog;
    }

    @Override public void onStart() {
        super.onStart();
        requireDialog().getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        AlertDialog dialog = (AlertDialog) requireDialog();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (layout == null) {
                Toast.makeText(requireContext(), R.string.extra_invalid, Toast.LENGTH_LONG).show();
                return;
            }
            PropertiesPreferencesFragment parent = (PropertiesPreferencesFragment) getTargetFragment();
            if (parent != null && parent.saveExtraKeys(currentValue())) dismiss();
        });
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> requestClose());
    }

    /** Whether the draft differs from what was opened. */
    boolean hasChanges() { return !currentValue().equals(initial); }

    private String currentValue() {
        if (layout == null) return source;
        return useDefault ? "" : layout.toString();
    }

    private void requestClose() {
        if (!hasChanges()) { dismiss(); return; }
        DialogUtils.builder(requireContext()).setMessage(R.string.extra_editor_discard_confirm)
            .setPositiveButton(R.string.extra_editor_discard, (d, w) -> dismiss())
            .setNegativeButton(R.string.extra_editor_keep_editing, null).show();
    }

    // region Rendering

    private void render() {
        clampSelection();
        renderPreview();
        renderSelection();
        updateUndoButtons();
    }

    private void renderPreview() {
        previewHost.removeAllViews();
        gutter.removeAllViews();
        cells.clear();
        keyboard = null;
        dropTarget = null;
        boolean empty = layout == null || layout.rowCount() == 0;
        addRowButton.setVisibility(layout == null ? View.GONE : View.VISIBLE);
        emptyPreview.setVisibility(empty ? View.VISIBLE : View.GONE);
        previewScroll.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            emptyPreview.setText(layout == null ? R.string.extra_invalid_help : R.string.extra_empty);
            return;
        }
        try {
            Bundle options = requireArguments();
            ExtraKeysView view = new ExtraKeysView(requireContext(), null);
            view.setKeyTypeface(TerminalAppearance.loadExtraKeysFont(requireContext(), options.getString("extra-keys-font")));
            view.setButtonTextAllCaps(!"false".equalsIgnoreCase(options.getString("extra-keys-text-all-caps")));
            view.setAppearance(options.getString("extra-keys-theme"),
                ExtraKeysColorParser.parseColor(options.getString("extra-keys-row-background-color")),
                ExtraKeysColorParser.parseColor(options.getString("extra-keys-button-active-text-color")),
                ExtraKeysColorParser.parseColor(options.getString("extra-keys-button-active-background-color")),
                ExtraKeysColorParser.parseCornerRadiusDp(options.getString("extra-keys-button-corner-radius")));
            float scale = 1;
            try { scale = Float.parseFloat(options.getString("terminal-toolbar-height", "1")); }
            catch (NumberFormatException | NullPointerException ignored) { }
            if (Float.isNaN(scale) || scale < 0.4f || scale > 3) scale = 1;
            int rowHeight = dp(37.5f * scale);
            int height = rowHeight * layout.rowCount();
            previewHost.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
            previewScroll.getLayoutParams().height = Math.min(height, getResources().getDisplayMetrics().heightPixels / 3);

            // Empty rows would collapse; give each one a placeholder key that adds a key there.
            JSONArray preview = new JSONArray(layout.toString());
            for (int r = 0; r < preview.length(); r++) {
                if (preview.getJSONArray(r).length() == 0) preview.put(r, new JSONArray().put(""));
            }
            view.reload(new ExtraKeysInfo(preview.toString(), options.getString("extra-keys-style", "default"),
                ExtraKeysConstants.CONTROL_CHARS_ALIASES), height);
            keyboard = view;

            int child = 0;
            for (int r = 0; r < layout.rowCount(); r++) {
                int count = layout.keyCount(r);
                for (int k = 0; k < Math.max(1, count); k++) {
                    if (child >= view.getChildCount()) break;
                    bindPreviewKey(view.getChildAt(child++), r, count == 0 ? -1 : k);
                }
                MaterialButton handle = new MaterialButton(requireContext(), null, com.google.android.material.R.attr.borderlessButtonStyle);
                handle.setText(getString(R.string.extra_editor_row_number, r + 1));
                handle.setMinWidth(0);
                handle.setMinimumWidth(0);
                handle.setMinHeight(0);
                handle.setMinimumHeight(0);
                handle.setInsetTop(0);
                handle.setInsetBottom(0);
                handle.setPadding(0, 0, 0, 0);
                handle.setContentDescription(getString(R.string.extra_editor_row_handle, r + 1));
                final int row = r;
                handle.setOnClickListener(v -> rowMenu(v, row));
                gutter.addView(handle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight));
            }
            view.setOnDragListener(this::onDrag);
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }

    private void bindPreviewKey(View button, int row, int key) throws JSONException {
        cells.add(new int[]{row, key});
        // Preview keys edit the draft; they must never start terminal input or repeat timers.
        button.setOnTouchListener(null);
        button.setOnLongClickListener(null);
        if (key < 0) {
            button.setVisibility(View.VISIBLE);
            button.setAlpha(0.5f);
            ((TextView) button).setText("+");
            button.setContentDescription(getString(R.string.extra_editor_add_key_to_row, row + 1));
            button.setOnClickListener(v -> insertAt(row, 0));
            return;
        }
        if (button.getVisibility() == View.INVISIBLE) {
            // A spacer: show it faintly so it can be selected.
            button.setVisibility(View.VISIBLE);
            button.setAlpha(0.35f);
            ((TextView) button).setText("␣");
        }
        button.setContentDescription(getString(R.string.extra_preview_key, layout.label(row, key), row + 1));
        button.setOnClickListener(v -> select(row, key));
        String label = layout.label(row, key);
        button.setOnLongClickListener(v -> {
            // Drag the laid-out view as is; the selection follows the key when it is dropped.
            if (v.getWidth() <= 0 || v.getHeight() <= 0) return false;
            ClipData data = ClipData.newPlainText("extra-key", label);
            return ViewCompat.startDragAndDrop(v, data, new View.DragShadowBuilder(v), new int[]{row, key}, 0);
        });
        if (row == selectedRow && key == selectedKey) {
            GradientDrawable outline = new GradientDrawable();
            outline.setColor(0x00000000);
            outline.setStroke(dp(2), accent());
            outline.setCornerRadius(dp(6));
            button.getOverlay().add(outline);
            button.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> outline.setBounds(0, 0, r - l, b - t));
            outline.setBounds(0, 0, button.getWidth(), button.getHeight());
        }
    }

    private void renderSelection() {
        boolean selected = selectedRow >= 0 && selectedKey >= 0;
        selectionBar.setVisibility(selected ? View.VISIBLE : View.GONE);
        inspector.setVisibility(selected ? View.VISIBLE : View.GONE);
        inspectorEmpty.setVisibility(selected || layout == null ? View.GONE : View.VISIBLE);
        inspectorEmpty.setText(R.string.extra_editor_select_key);
        if (!selected) return;
        selectionTitle.setText(getString(R.string.extra_editor_selected, selectedRow + 1, selectedKey + 1));
        int count = layout.keyCount(selectedRow);
        selectionButtons[0].setEnabled(selectedKey > 0);
        selectionButtons[1].setEnabled(selectedKey + 1 < count);
        selectionButtons[2].setEnabled(selectedRow > 0);
        selectionButtons[3].setEnabled(selectedRow + 1 < layout.rowCount());
        try { inspector.bind(layout.key(selectedRow, selectedKey)); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }

    private void updateUndoButtons() {
        undoButton.setEnabled(!undo.isEmpty());
        redoButton.setEnabled(!redo.isEmpty());
    }

    // endregion

    // region Editing

    private void select(int row, int key) {
        if (row == selectedRow && key == selectedKey) return;
        selectedRow = row;
        selectedKey = key;
        editSession = null;
        renderPreview();
        renderSelection();
    }

    private void onKeyEdited(@NonNull JSONObject key) {
        if (selectedRow < 0 || selectedKey < 0) return;
        String session = selectedRow + ":" + selectedKey;
        if (!session.equals(editSession)) {
            checkpoint();
            editSession = session;
        }
        try { layout.putKey(selectedRow, selectedKey, key); }
        catch (JSONException e) { return; }
        useDefault = false;
        renderPreview();
        updateUndoButtons();
    }

    private void insertAt(int row, int index) {
        checkpoint();
        try { layout.insertKey(row, index, new JSONObject().put("key", "")); }
        catch (JSONException e) { throw new IllegalStateException(e); }
        selectedRow = row;
        selectedKey = index;
        changed();
        inspector.startNewKey();
    }

    private void moveSelected(int rowDelta, int keyDelta) {
        checkpoint();
        if (rowDelta == 0) {
            layout.moveKey(selectedRow, selectedKey, keyDelta);
            selectedKey += keyDelta;
        } else {
            int target = selectedRow + rowDelta;
            int index = Math.min(selectedKey, layout.keyCount(target));
            layout.moveKeyTo(selectedRow, selectedKey, target, index);
            selectedRow = target;
            selectedKey = index;
        }
        changed();
    }

    private void duplicateSelected() {
        checkpoint();
        try { layout.duplicateKey(selectedRow, selectedKey); }
        catch (JSONException e) { throw new IllegalStateException(e); }
        selectedKey++;
        changed();
    }

    private void deleteSelected() {
        checkpoint();
        layout.removeKey(selectedRow, selectedKey);
        if (layout.keyCount(selectedRow) == 0) selectedKey = -1;
        else selectedKey = Math.min(selectedKey, layout.keyCount(selectedRow) - 1);
        changed();
    }

    private boolean onDrag(View view, DragEvent event) {
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return event.getLocalState() instanceof int[];
            case DragEvent.ACTION_DRAG_LOCATION:
                showDropIndicator(event.getX(), event.getY());
                return true;
            case DragEvent.ACTION_DROP: {
                clearDropIndicator();
                int[] from = (int[]) event.getLocalState();
                int[] to = dropPosition(event.getX(), event.getY());
                if (to == null) return false;
                int index = to[1];
                if (to[0] == from[0] && from[1] < index) index--;
                if (to[0] == from[0] && index == from[1]) return true;
                checkpoint();
                layout.moveKeyTo(from[0], from[1], to[0], index);
                selectedRow = to[0];
                selectedKey = index;
                view.post(this::changed);
                return true;
            }
            case DragEvent.ACTION_DRAG_EXITED:
            case DragEvent.ACTION_DRAG_ENDED:
                clearDropIndicator();
                return true;
            default:
                return true;
        }
    }

    /** The {row, insert index} for a drop at view coordinates, or {@code null}. */
    @Nullable
    private int[] dropPosition(float x, float y) {
        int best = nearestChild(x, y);
        if (best < 0) return null;
        int[] cell = cells.get(best);
        if (cell[1] < 0) return new int[]{cell[0], 0};
        View child = keyboard.getChildAt(best);
        boolean after = x > (child.getLeft() + child.getRight()) / 2f;
        return new int[]{cell[0], cell[1] + (after ? 1 : 0)};
    }

    private int nearestChild(float x, float y) {
        if (keyboard == null) return -1;
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(cells.size(), keyboard.getChildCount()); i++) {
            View child = keyboard.getChildAt(i);
            float dx = Math.max(0, Math.max(child.getLeft() - x, x - child.getRight()));
            float dy = Math.max(0, Math.max(child.getTop() - y, y - child.getBottom()));
            // Prefer the row under the finger over a closer key in a neighbouring row.
            float distance = dy * 4 + dx;
            if (distance < bestDistance) { bestDistance = distance; best = i; }
        }
        return best;
    }

    private void showDropIndicator(float x, float y) {
        clearDropIndicator();
        int best = nearestChild(x, y);
        if (best < 0) return;
        View child = keyboard.getChildAt(best);
        boolean after = cells.get(best)[1] >= 0 && x > (child.getLeft() + child.getRight()) / 2f;
        ColorDrawable bar = new ColorDrawable(accent());
        int width = dp(3);
        int left = after ? child.getWidth() - width : 0;
        if (cells.get(best)[1] < 0) bar.setBounds(0, 0, child.getWidth(), child.getHeight());
        else bar.setBounds(left, 0, left + width, child.getHeight());
        child.getOverlay().add(bar);
        dropTarget = child;
        dropIndicator = bar;
    }

    private void clearDropIndicator() {
        if (dropTarget != null && dropIndicator != null) dropTarget.getOverlay().remove(dropIndicator);
        dropTarget = null;
        dropIndicator = null;
    }

    private void rowMenu(View anchor, int row) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(0, 1, 0, R.string.extra_editor_row_add_key);
        menu.getMenu().add(0, 2, 1, R.string.extra_editor_row_insert_above);
        menu.getMenu().add(0, 3, 2, R.string.extra_editor_row_insert_below);
        menu.getMenu().add(0, 4, 3, R.string.extra_editor_row_duplicate);
        if (row > 0) menu.getMenu().add(0, 5, 4, R.string.extra_row_up);
        if (row + 1 < layout.rowCount()) menu.getMenu().add(0, 6, 5, R.string.extra_row_down);
        menu.getMenu().add(0, 7, 6, R.string.extra_row_columns);
        menu.getMenu().add(0, 8, 7, R.string.extra_remove_row);
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 1) { insertAt(row, layout.keyCount(row)); return true; }
            if (id == 7) { selectColumns(row); return true; }
            if (id == 8) {
                Runnable remove = () -> {
                    checkpoint();
                    layout.removeRow(row);
                    if (selectedRow == row) { selectedRow = -1; selectedKey = -1; }
                    else if (selectedRow > row) selectedRow--;
                    changed();
                };
                if (layout.keyCount(row) == 0) remove.run();
                else DialogUtils.builder(requireContext()).setMessage(R.string.extra_remove_row_confirm)
                    .setPositiveButton(R.string.extra_remove_row, (d, w) -> remove.run())
                    .setNegativeButton(android.R.string.cancel, null).show();
                return true;
            }
            checkpoint();
            try {
                if (id == 2) {
                    layout.insertRow(row);
                    if (selectedRow >= row) selectedRow++;
                } else if (id == 3) {
                    layout.insertRow(row + 1);
                    if (selectedRow > row) selectedRow++;
                } else if (id == 4) {
                    layout.duplicateRow(row);
                    if (selectedRow > row) selectedRow++;
                } else {
                    int direction = id == 5 ? -1 : 1;
                    layout.moveRow(row, direction);
                    if (selectedRow == row) selectedRow += direction;
                    else if (selectedRow == row + direction) selectedRow = row;
                }
            } catch (JSONException e) { throw new IllegalStateException(e); }
            changed();
            return true;
        });
        menu.show();
    }

    private void selectColumns(int row) {
        int previous = layout.keyCount(row);
        String[] choices = new String[Math.max(12, previous)];
        for (int i = 0; i < choices.length; i++) choices[i] = Integer.toString(i + 1);
        DialogUtils.builder(requireContext()).setTitle(R.string.extra_row_columns)
            .setSingleChoiceItems(choices, previous - 1, (dialog, item) -> {
                dialog.dismiss();
                int columns = item + 1;
                if (columns == previous) return;
                Runnable resize = () -> { checkpoint(); layout.resizeRow(row, columns); changed(); };
                if (columns < previous) {
                    DialogUtils.builder(requireContext()).setMessage(R.string.extra_fewer_columns)
                        .setPositiveButton(R.string.extra_apply, (d, w) -> resize.run())
                        .setNegativeButton(android.R.string.cancel, null).show();
                } else {
                    resize.run();
                }
            }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void moreMenu(View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(0, 1, 0, R.string.extra_raw);
        menu.getMenu().add(0, 2, 1, R.string.extra_reset);
        if (layout != null && layout.rowCount() > 0) menu.getMenu().add(0, 3, 2, R.string.extra_editor_clear_all);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 2) {
                DialogUtils.builder(requireContext()).setMessage(R.string.extra_reset_confirm)
                    .setPositiveButton(R.string.extra_reset, (d, w) -> {
                        checkpoint();
                        try { layout = ExtraKeysLayout.defaults(); }
                        catch (JSONException e) { throw new IllegalStateException(e); }
                        useDefault = true;
                        render();
                    }).setNegativeButton(android.R.string.cancel, null).show();
            } else if (item.getItemId() == 3) {
                DialogUtils.builder(requireContext()).setMessage(R.string.extra_editor_clear_all_confirm)
                    .setPositiveButton(R.string.extra_editor_clear_all, (d, w) -> {
                        checkpoint();
                        while (layout.rowCount() > 0) layout.removeRow(0);
                        changed();
                    }).setNegativeButton(android.R.string.cancel, null).show();
            } else {
                RawDialog raw = new RawDialog();
                Bundle args = new Bundle();
                args.putString("value", layout == null ? source : layout.toString());
                raw.setArguments(args);
                raw.setTargetFragment(this, 0);
                raw.show(getParentFragmentManager(), "extra_keys_raw");
            }
            return true;
        });
        menu.show();
    }

    private void changed() {
        useDefault = false;
        editSession = null;
        render();
    }

    // endregion

    // region Undo

    private String snapshot() {
        return (useDefault ? "D" : "C") + (layout == null ? source : layout.toString());
    }

    /** Records the current draft so the next change can be undone. */
    private void checkpoint() {
        undo.add(snapshot());
        if (undo.size() > MAX_UNDO) undo.remove(0);
        redo.clear();
        editSession = null;
        updateUndoButtons();
    }

    private void undo() {
        if (undo.isEmpty()) return;
        redo.add(snapshot());
        restore(undo.remove(undo.size() - 1));
    }

    private void redo() {
        if (redo.isEmpty()) return;
        undo.add(snapshot());
        restore(redo.remove(redo.size() - 1));
    }

    private void restore(String snapshot) {
        useDefault = snapshot.charAt(0) == 'D';
        try { layout = new ExtraKeysLayout(snapshot.substring(1)); }
        catch (JSONException e) { layout = null; source = snapshot.substring(1); }
        editSession = null;
        render();
    }

    // endregion

    private void clampSelection() {
        if (layout == null || selectedRow < 0 || selectedRow >= layout.rowCount()
            || selectedKey < 0 || selectedKey >= layout.keyCount(selectedRow)) {
            selectedRow = -1;
            selectedKey = -1;
        }
    }

    private MaterialButton iconButton(LinearLayout parent, String glyph, int description, View.OnClickListener action) {
        MaterialButton button = new MaterialButton(requireContext(), null, com.google.android.material.R.attr.borderlessButtonStyle);
        button.setText(glyph);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(dp(48));
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setContentDescription(getString(description));
        if (android.os.Build.VERSION.SDK_INT >= 26) button.setTooltipText(getString(description));
        button.setOnClickListener(action);
        parent.addView(button);
        return button;
    }

    private MaterialButton textButton(int title, View.OnClickListener action) {
        MaterialButton button = new MaterialButton(requireContext(), null, com.google.android.material.R.attr.borderlessButtonStyle);
        button.setText(title);
        button.setOnClickListener(action);
        return button;
    }

    private int accent() {
        TypedValue value = new TypedValue();
        return requireContext().getTheme().resolveAttribute(androidx.appcompat.R.attr.colorPrimary, value, true)
            && value.type >= TypedValue.TYPE_FIRST_COLOR_INT ? value.data : 0xFF80DEEA;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    public static class RawDialog extends DialogFragment {
        private EditText text;
        @NonNull @Override public android.app.Dialog onCreateDialog(Bundle state) {
            text = new EditText(requireContext());
            text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            text.setMinLines(5);
            text.setText(state == null ? requireArguments().getString("value") : state.getString("value"));
            ScrollView scroll = new ScrollView(requireContext());
            scroll.addView(text);
            return DialogUtils.builder(requireContext()).setTitle(R.string.extra_raw)
                .setMessage(R.string.extra_raw_help).setView(scroll)
                .setPositiveButton(R.string.extra_apply, null).setNegativeButton(android.R.string.cancel, null).create();
        }
        @Override public void onStart() {
            super.onStart();
            ((AlertDialog) requireDialog()).getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    ExtraKeysLayout value = new ExtraKeysLayout(text.getText().toString());
                    ExtraKeysEditorDialog parent = (ExtraKeysEditorDialog) getTargetFragment();
                    if (parent == null) return;
                    parent.checkpoint();
                    parent.layout = value;
                    parent.changed();
                    dismiss();
                } catch (JSONException e) { text.setError(getString(R.string.extra_invalid)); }
            });
        }
        @Override public void onSaveInstanceState(@NonNull Bundle out) {
            super.onSaveInstanceState(out);
            out.putString("value", text.getText().toString());
        }
    }
}
