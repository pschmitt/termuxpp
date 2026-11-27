package com.termux.app.terminal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dev.pschmitt.termux.shared.termux.settings.properties.GestureCommandContext;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Explicit executable and argv for a configured gesture command. */
public final class GestureCommandLaunch {

    public final String executable;
    public final String[] arguments;

    private GestureCommandLaunch(String executable, String[] arguments) {
        this.executable = executable;
        this.arguments = arguments;
    }

    /**
     * Build argv without tokenizing or concatenating the configured command with executable names.
     * The command is one {@code -c} argument, so shell syntax and quoted spaces are preserved.
     */
    @Nullable
    public static GestureCommandLaunch create(@NonNull String command,
                                       @NonNull GestureCommandContext context,
                                       boolean sessionStartsInNix,
                                       boolean nixIsProvisioned,
                                       @NonNull String nixppPath,
                                       @NonNull String prefixPath,
                                       @NonNull String nixUser) {
        if (command.trim().isEmpty()) return null;
        boolean enterNix = context.shouldEnterNix(sessionStartsInNix);
        if (enterNix && !nixIsProvisioned) return null;

        // Outside the guest there is no /bin/sh; the slim prefix's bin/sh is Android's shell.
        String fallbackShell = enterNix ? "/bin/sh" : prefixPath + "/bin/sh";
        String shellCommand = command + (command.endsWith("\n") ? "" : "\n")
            + "__termuxpp_command_status=$?\n"
            + "printf '\\n[gesture command exited with status %s]\\n' \"$__termuxpp_command_status\"\n"
            + "exec \"${SHELL:-" + fallbackShell + "}\" -l\n";

        List<String> arguments = new ArrayList<>();
        String executable;
        if (enterNix) {
            executable = nixppPath;
            arguments.add("login");
            arguments.add("--user");
            arguments.add(nixUser);
            arguments.add("--");
            arguments.add("bash");
            arguments.add("-lc");
        } else if (new File(prefixPath, "bin/bash").canExecute()) {
            executable = prefixPath + "/bin/bash";
            arguments.add("--noprofile");
            arguments.add("--norc");
            arguments.add("-lc");
        } else {
            // The slim outer prefix has no bash: bin/sh links to Android's shell.
            executable = prefixPath + "/bin/sh";
            arguments.add("-c");
        }
        arguments.add(shellCommand);
        return new GestureCommandLaunch(executable, arguments.toArray(new String[0]));
    }
}
