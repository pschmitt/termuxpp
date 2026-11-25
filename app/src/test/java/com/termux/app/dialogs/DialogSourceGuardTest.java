package com.termux.app.dialogs;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Keeps every dialog on the Material path. The framework dialogs ignore the app theme, so one of
 * them in a source file is a dialog that silently looks different from all the others.
 */
public class DialogSourceGuardTest {

    private static final String[] MODULES = {"app", "termux-shared", "terminal-view", "terminal-emulator"};

    /** Source text that must not appear in code (comments are ignored). */
    private static final String[] FORBIDDEN = {
        "import android.app.AlertDialog;",
        "import android.app.ProgressDialog;",
        "import android.app.Dialog;",
        "android.app.AlertDialog.Builder",
        "new AlertDialog.Builder(",
        "new ProgressDialog(",
        "ProgressDialog.show(",
        "new Dialog(",
        "new android.app.Dialog(",
    };

    private static File repositoryRoot() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null && !new File(dir, "settings.gradle").isFile()) dir = dir.getParentFile();
        Assert.assertNotNull("settings.gradle not found above " + System.getProperty("user.dir"), dir);
        return dir;
    }

    private static List<Path> mainSources() throws IOException {
        List<Path> files = new ArrayList<>();
        for (String module : MODULES) {
            Path src = new File(repositoryRoot(), module + "/src/main/java").toPath();
            if (!Files.isDirectory(src)) continue;
            try (Stream<Path> walk = Files.walk(src)) {
                files.addAll(walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList()));
            }
        }
        Assert.assertTrue("no sources found", files.size() > 100);
        return files;
    }

    private static String code(Path file) throws IOException {
        StringBuilder out = new StringBuilder();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String source(String path) throws IOException {
        return code(new File(repositoryRoot(), path).toPath());
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) count++;
        return count;
    }

    @Test
    public void noFrameworkDialogIsCreatedAnywhere() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : mainSources()) {
            String code = code(file);
            for (String forbidden : FORBIDDEN) {
                if (code.contains(forbidden)) offenders.add(file.getFileName() + ": " + forbidden);
            }
        }
        Assert.assertEquals("framework dialogs ignore the app theme, use DialogUtils", new ArrayList<String>(), offenders);
    }

    /** Every creation path of the inventory still goes through the shared dialog code. */
    @Test
    public void everyInventoriedCreationPathUsesTheSharedDialogs() throws IOException {
        String app = "app/src/main/java/com/termux/app/";
        String shared = "termux-shared/src/main/java/dev/pschmitt/termux/shared/";

        String activity = source(app + "TermuxActivity.java");
        Assert.assertEquals("kill session dialog and session and tmux switchers", 3, count(activity, "DialogUtils.builder("));
        Assert.assertTrue("named session input", activity.contains("TextInputDialogUtils.textInput("));

        String installer = source(app + "TermuxInstaller.java");
        // The first-start progress is a card over the terminal area, not a dialog.
        Assert.assertTrue("bootstrap progress", installer.contains("BootstrapProgressOverlay.show("));
        Assert.assertTrue("bootstrap error", installer.contains("DialogUtils.builder("));
        Assert.assertTrue("bootstrap messages", installer.contains("MessageDialogUtils.showMessage(")
            && installer.contains("MessageDialogUtils.exitAppWithErrorMessage("));

        String viewClient = source(app + "terminal/TermuxTerminalViewClient.java");
        Assert.assertEquals("hyperlink consent, no URL found and URL selection", 3, count(viewClient, "DialogUtils.builder("));
        Assert.assertTrue("report issue", viewClient.contains("MessageDialogUtils.showMessage("));

        String sessionClient = source(app + "terminal/TermuxTerminalSessionActivityClient.java");
        Assert.assertTrue("maximum terminals", sessionClient.contains("DialogUtils.builder("));
        Assert.assertTrue("rename session", sessionClient.contains("TextInputDialogUtils.textInput("));

        Assert.assertTrue("update confirmation",
            source(app + "fragments/settings/UpdatesPreferencesFragment.java").contains("DialogUtils.builder("));

        String receiver = source(app + "api/file/FileReceiverActivity.java");
        Assert.assertTrue("file receiver message", receiver.contains("MessageDialogUtils.showMessage("));
        Assert.assertTrue("file receiver name input", receiver.contains("TextInputDialogUtils.textInput("));

        Assert.assertTrue("package utils", source(shared + "android/PackageUtils.java").contains("MessageDialogUtils."));

        Assert.assertTrue("helpers build with the shared builder",
            source(shared + "interact/MessageDialogUtils.java").contains("DialogUtils.builder(")
                && source(shared + "termux/interact/TextInputDialogUtils.java").contains("DialogUtils.builder("));
    }

    @Test
    public void theContextMenuSheetKeepsItsRoundedCornersWhenExpanded() throws IOException {
        Assert.assertTrue(source("app/src/main/java/com/termux/app/terminal/TermuxContextMenuSheet.java")
            .contains("setShouldRemoveExpandedCorners(false)"));
    }

    /** The preference lists (update channel, log levels) are appcompat dialogs themed by the overlay. */
    @Test
    public void everyListPreferenceHasItsDialogThemedByTheOverlay() throws IOException {
        String themes = new String(Files.readAllBytes(new File(repositoryRoot(), "app/src/main/res/values/themes.xml").toPath()),
            StandardCharsets.UTF_8);
        Assert.assertTrue(themes.contains("name=\"alertDialogTheme\">@style/ThemeOverlay.TermuxPP.Dialog<"));
        Assert.assertTrue(themes.contains("name=\"materialAlertDialogTheme\">@style/ThemeOverlay.TermuxPP.Dialog<"));
    }
}
