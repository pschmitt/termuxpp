package com.termux.app.fragments.settings;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;

import org.commonmark.node.FencedCodeBlock;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import dev.pschmitt.termux.shared.android.AndroidUtils;
import dev.pschmitt.termux.shared.interact.ShareUtils;
import dev.pschmitt.termux.shared.markdown.MarkdownUtils;
import dev.pschmitt.termux.shared.termux.TermuxUtils;
import io.noties.markwon.recycler.MarkwonAdapter;
import io.noties.markwon.recycler.SimpleEntry;

@Keep
public class AppDetailsFragment extends Fragment {

    @Nullable private String details;

    private final ActivityResultLauncher<String> saveDocument =
        registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"), this::saveDetails);

    public AppDetailsFragment() {
        super(R.layout.ui_app_details);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        RecyclerView list = view.findViewById(R.id.app_details_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        MarkwonAdapter adapter = MarkwonAdapter.builderTextViewIsRoot(
                dev.pschmitt.termux.shared.R.layout.markdown_adapter_node_default)
            .include(FencedCodeBlock.class, SimpleEntry.create(
                dev.pschmitt.termux.shared.R.layout.markdown_adapter_node_code_block,
                dev.pschmitt.termux.shared.R.id.code_text_view))
            .build();
        list.setAdapter(adapter);

        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
                inflater.inflate(dev.pschmitt.termux.shared.R.menu.menu_report, menu);
                menu.findItem(dev.pschmitt.termux.shared.R.id.menu_item_share_report).setEnabled(details != null);
                menu.findItem(dev.pschmitt.termux.shared.R.id.menu_item_copy_report).setEnabled(details != null);
                menu.findItem(dev.pschmitt.termux.shared.R.id.menu_item_save_report_to_file).setEnabled(details != null);
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem item) {
                if (details == null) return false;
                int id = item.getItemId();
                Context context = requireContext();
                if (id == dev.pschmitt.termux.shared.R.id.menu_item_share_report) {
                    ShareUtils.shareText(context, getString(R.string.about_information_action_title), details);
                } else if (id == dev.pschmitt.termux.shared.R.id.menu_item_copy_report) {
                    ShareUtils.copyTextToClipboard(context, details, Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                        ? getString(R.string.about_information_copied) : null);
                } else if (id == dev.pschmitt.termux.shared.R.id.menu_item_save_report_to_file) {
                    saveDocument.launch("TermuxPP-about.log");
                } else {
                    return false;
                }
                return true;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);

        Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            String markdown = loadDetails(app);
            ContextCompat.getMainExecutor(app).execute(() -> {
                if (getView() != view) return;
                details = markdown;
                adapter.setMarkdown(MarkdownUtils.getRecyclerMarkwonBuilder(requireContext()), markdown);
                adapter.notifyDataSetChanged();
                view.findViewById(R.id.app_details_loading).setVisibility(View.GONE);
                requireActivity().invalidateOptionsMenu();
            });
        }, "termux-app-details").start();
    }

    private static String loadDetails(Context context) {
        return TermuxUtils.getAppInfoMarkdownString(context, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES)
            + "\n\n" + AndroidUtils.getDeviceInfoMarkdownString(context, true)
            + "\n\n" + TermuxUtils.getImportantLinksMarkdownString(context);
    }

    private void saveDetails(@Nullable Uri uri) {
        if (uri == null) return;
        Context app = requireContext().getApplicationContext();
        String snapshot = details;
        new Thread(() -> {
            int message;
            try (OutputStream output = app.getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new IOException("Could not open selected document");
                String markdown = snapshot == null ? loadDetails(app) : snapshot;
                output.write(markdown.getBytes(StandardCharsets.UTF_8));
                message = R.string.about_information_saved;
            } catch (IOException | SecurityException e) {
                message = R.string.about_information_save_failed;
            }
            int result = message;
            ContextCompat.getMainExecutor(app).execute(() -> Toast.makeText(app, result, Toast.LENGTH_SHORT).show());
        }, "termux-save-app-details").start();
    }
}
