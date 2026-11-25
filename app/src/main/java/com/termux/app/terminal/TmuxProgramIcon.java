package com.termux.app.terminal;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The icon of the program in the active pane of a tmux window, from tmux's {@code pane_current_command}.
 * The groups follow the tmux status bar's window icons, with real logos where a program has one.
 */
public final class TmuxProgramIcon {
    /** Use the row's text colour instead of a brand colour. */
    public static final int TEXT_COLOR = 0;

    /** Claude Code names its process after its version, for example {@code 2.1.17}. */
    private static final Pattern VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");
    private static final Map<String, TmuxProgramIcon> COMMANDS = new HashMap<>();
    private static final TmuxProgramIcon SHELL = new TmuxProgramIcon(R.drawable.ui_ic_program_shell, TEXT_COLOR);
    private static final TmuxProgramIcon CLAUDE = new TmuxProgramIcon(R.drawable.ui_ic_program_claude, 0xFFD97757);

    @DrawableRes public final int drawable;
    /** An ARGB brand colour, or {@link #TEXT_COLOR}. */
    public final int color;

    private TmuxProgramIcon(@DrawableRes int drawable, int color) {
        this.drawable = drawable;
        this.color = color;
    }

    private static void add(@DrawableRes int drawable, int color, String... commands) {
        TmuxProgramIcon icon = new TmuxProgramIcon(drawable, color);
        for (String command : commands) COMMANDS.put(command, icon);
    }

    static {
        // AI coding agents
        COMMANDS.put("claude", CLAUDE);
        add(R.drawable.ui_ic_program_codex, TEXT_COLOR, "codex");
        add(R.drawable.ui_ic_program_gemini, 0xFF3186FF, "gemini");
        add(R.drawable.ui_ic_program_copilot, TEXT_COLOR, "copilot");
        add(R.drawable.ui_ic_program_opencode, TEXT_COLOR, "opencode");
        add(R.drawable.ui_ic_program_agent, TEXT_COLOR, "aider", "mimo", "agy");
        // Editors
        add(R.drawable.ui_ic_program_neovim, 0xFF57A143, "nvim");
        add(R.drawable.ui_ic_program_vim, 0xFF019733, "vim", "vi");
        add(R.drawable.ui_ic_program_helix, 0xFF8E6CCF, "hx");
        add(R.drawable.ui_ic_program_editor, TEXT_COLOR, "nano", "emacs", "code", "code-insiders", "zed", "subl");
        // Shells and terminals
        add(R.drawable.ui_ic_program_zsh, 0xFFF15A24, "zsh");
        add(R.drawable.ui_ic_program_bash, 0xFF4EAA25, "bash");
        add(R.drawable.ui_ic_program_fish, 0xFF34C534, "fish");
        add(R.drawable.ui_ic_program_nushell, 0xFF4E9A06, "nu");
        add(R.drawable.ui_ic_program_shell, TEXT_COLOR, "sh", "dash", "wezterm", "ghostty", "kitty", "alacritty");
        add(R.drawable.ui_ic_program_ssh, TEXT_COLOR, "ssh", "scp", "sftp", "mosh");
        add(R.drawable.ui_ic_program_tmux, 0xFF1BB91F, "tmux");
        // Version control
        add(R.drawable.ui_ic_program_git, 0xFFF03C2E, "git", "lazygit", "tig", "gitui");
        add(R.drawable.ui_ic_program_github, TEXT_COLOR, "gh");
        // Languages and their tools
        add(R.drawable.ui_ic_program_node, 0xFF5FA04E, "node", "yarn", "pnpm", "vite", "next", "npm", "npx");
        add(R.drawable.ui_ic_program_python, 0xFF4B8BBE, "python", "python3", "pip", "pipenv", "poetry", "uv",
            "pytest", "ipython");
        add(R.drawable.ui_ic_program_rust, 0xFFDEA584, "cargo", "rustc");
        add(R.drawable.ui_ic_program_go, 0xFF00ADD8, "go");
        add(R.drawable.ui_ic_program_build, TEXT_COLOR, "make", "cmake", "gradle", "mvn");
        // Containers and databases
        add(R.drawable.ui_ic_program_docker, 0xFF2496ED, "docker", "podman");
        add(R.drawable.ui_ic_program_kubernetes, 0xFF326CE5, "kubectl", "k9s", "kubecolor", "helm");
        add(R.drawable.ui_ic_program_database, TEXT_COLOR, "psql", "mysql", "mongosh", "redis-cli");
        // System, files, search and network tools
        add(R.drawable.ui_ic_program_monitor, TEXT_COLOR, "htop", "top", "btop", "btm", "glances");
        add(R.drawable.ui_ic_program_files, TEXT_COLOR, "ranger", "nnn", "yazi", "lf", "mc");
        add(R.drawable.ui_ic_program_search, TEXT_COLOR, "fzf", "rg", "fd");
        add(R.drawable.ui_ic_program_pager, TEXT_COLOR, "bat", "man", "less");
        add(R.drawable.ui_ic_program_web, TEXT_COLOR, "curl");
        add(R.drawable.ui_ic_program_download, TEXT_COLOR, "wget");
    }

    /** The icon for a command, a shell for unknown commands, or null when tmux did not name one. */
    @Nullable
    public static TmuxProgramIcon forCommand(@Nullable String command) {
        if (command == null || command.isEmpty()) return null;
        String name = command.toLowerCase(Locale.ROOT);
        TmuxProgramIcon icon = COMMANDS.get(name);
        if (icon != null) return icon;
        return VERSION.matcher(name).matches() ? CLAUDE : SHELL;
    }

    /**
     * The colour to tint the icon with: its brand colour, unless that is too close to the background to see,
     * then the text colour.
     */
    public static int tint(@NonNull TmuxProgramIcon icon, int text, int background) {
        if (icon.color == TEXT_COLOR) return text;
        return contrast(icon.color, background) >= 2.5 ? icon.color : text;
    }

    static double contrast(int first, int second) {
        double a = luminance(first) + 0.05;
        double b = luminance(second) + 0.05;
        return Math.max(a, b) / Math.min(a, b);
    }

    private static double luminance(int color) {
        return 0.2126 * channel(color >> 16) + 0.7152 * channel(color >> 8) + 0.0722 * channel(color);
    }

    private static double channel(int value) {
        double c = (value & 0xFF) / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
