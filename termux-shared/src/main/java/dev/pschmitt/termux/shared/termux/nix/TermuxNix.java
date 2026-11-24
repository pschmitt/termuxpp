package dev.pschmitt.termux.shared.termux.nix;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import dev.pschmitt.termux.shared.termux.TermuxConstants;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import dev.pschmitt.termux.shared.termux.settings.properties.TermuxPropertyConstants;

/**
 * The nixpp guest that a provisioning bootstrap ships: {@code $PREFIX/bin/nixpp} with its
 * launcher, bootstrap seed and session manifest in {@code $PREFIX/share/nixpp}. {@code nixpp init}
 * sets up the runtime in {@link #RUNTIME_DIR_PATH}; {@code nixpp login} finishes an interrupted
 * setup and enters the guest.
 */
public final class TermuxNix {

    public static final String NIXPP_PATH = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/nixpp";
    public static final String ASSETS_DIR_PATH = TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/share/nixpp";
    /** nixpp's default runtime directory, {@code $HOME/.local/share/termuxpp-nix}. */
    public static final String RUNTIME_DIR_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.local/share/termuxpp-nix";

    /**
     * Stable copy of the APK's {@code libtermux-pkg-redirect.so}. The APK's own library directory
     * ({@code /data/app/~~hash/.../lib/arm64}) changes on every update, which broke every native
     * process that kept it in {@code LD_PRELOAD}; this path never changes. The app replaces the
     * file atomically on start, so running processes keep their mapped copy.
     */
    public static final String REDIRECT_LIB_PATH = TermuxConstants.TERMUX_FILES_DIR_PATH + "/lib/libtermux-pkg-redirect.so";

    private static final String[] ASSETS = {"launcher", "bootstrap-aarch64.zip", "manifest.json"};

    private TermuxNix() {}

    /**
     * Install {@code nativeLibraryDir/libtermux-pkg-redirect.so} at {@link #REDIRECT_LIB_PATH}
     * when it differs: write a temporary file, then rename it over the old one.
     *
     * @return the path to put in {@code LD_PRELOAD}: the stable copy, or the APK's library if the
     *     copy cannot be written
     */
    @NonNull
    public static String installRedirectLibrary(@NonNull String nativeLibraryDir) {
        File source = new File(nativeLibraryDir, "libtermux-pkg-redirect.so");
        File target = new File(REDIRECT_LIB_PATH);
        try {
            if (sameContent(source, target)) return target.getPath();
            File directory = target.getParentFile();
            if (directory != null && !directory.isDirectory() && !directory.mkdirs())
                throw new IOException("cannot create " + directory);
            File temporary = new File(directory, "." + target.getName() + ".tmp");
            try (InputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
                out.getFD().sync();
            }
            if (!temporary.setReadable(true, true) || !temporary.setExecutable(true, true))
                throw new IOException("cannot set permissions on " + temporary);
            if (!temporary.renameTo(target)) throw new IOException("cannot replace " + target);
            return target.getPath();
        } catch (IOException e) {
            return source.getPath();
        }
    }

    private static boolean sameContent(@NonNull File a, @NonNull File b) throws IOException {
        if (!b.isFile() || a.length() != b.length()) return false;
        try (InputStream x = new FileInputStream(a); InputStream y = new FileInputStream(b)) {
            byte[] bx = new byte[65536], by = new byte[65536];
            int nx;
            while ((nx = x.read(bx)) > 0) {
                int ny = 0;
                while (ny < nx) {
                    int r = y.read(by, ny, nx - ny);
                    if (r <= 0) return false;
                    ny += r;
                }
                if (!Arrays.equals(Arrays.copyOf(bx, nx), Arrays.copyOf(by, nx))) return false;
            }
            return y.read() < 0;
        }
    }

    /** Whether the prefix can set up a Nix runtime: nixpp and all of its bundled assets are present. */
    public static boolean isProvisioned() {
        return isProvisioned(new File(NIXPP_PATH), new File(ASSETS_DIR_PATH));
    }

    static boolean isProvisioned(@NonNull File nixpp, @NonNull File assets) {
        if (!nixpp.canExecute()) return false;
        for (String asset : ASSETS)
            if (!new File(assets, asset).isFile()) return false;
        return true;
    }

    /** Whether the runtime finished its setup. */
    public static boolean isReady() {
        return new File(RUNTIME_DIR_PATH, "ready").isFile();
    }

    /** Prefer the app configuration in the ready guest's actual home. */
    @NonNull
    public static List<String> configPaths(@NonNull List<String> nativePaths) {
        File guest = guestHome(new File(RUNTIME_DIR_PATH));
        if (guest == null) return nativePaths;
        List<String> paths = new ArrayList<>(nativePaths.size() * 2);
        String nativeHome = TermuxConstants.TERMUX_HOME_DIR_PATH + "/";
        try {
            String guestPrefix = guest.getCanonicalPath() + "/";
            for (String path : nativePaths) {
                if (!path.startsWith(nativeHome)) return nativePaths;
                File candidate = new File(guest, path.substring(nativeHome.length()));
                if (candidate.getCanonicalPath().startsWith(guestPrefix)) paths.add(candidate.getPath());
                else return nativePaths;
            }
        } catch (IOException e) {
            return nativePaths;
        }
        paths.addAll(nativePaths);
        return paths;
    }

    @Nullable
    static File guestHome(@NonNull File runtime) {
        File session = new File(runtime, "session.env");
        if (!new File(runtime, "ready").isFile() || !session.isFile() || session.length() > 16384)
            return null;
        try (BufferedReader reader = new BufferedReader(new FileReader(session))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("SESSION_HOME=")) continue;
                String home = line.substring("SESSION_HOME=".length());
                if (!home.matches("/home/[a-z_][a-z0-9_-]*")) return null;
                File homes = new File(runtime, "root/home");
                File guest = new File(homes, home.substring("/home/".length()));
                if (guest.isDirectory() && guest.getCanonicalFile().getParentFile().equals(homes.getCanonicalFile()))
                    return guest;
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    /** Whether new default sessions enter the guest: provisioned and not opted out with {@code nix-session=false}. */
    public static boolean shouldEnterGuest(@Nullable TermuxAppSharedProperties properties) {
        return isProvisioned() && (properties == null || properties.shouldStartNixSession());
    }

    /** The guest user name for a new runtime ({@code nix-user}). */
    @NonNull
    public static String user(@Nullable TermuxAppSharedProperties properties) {
        String user = properties == null ? null : properties.getNixUser();
        return user == null ? TermuxPropertyConstants.DEFAULT_IVALUE_NIX_USER : user;
    }

    /** The arguments after {@link #NIXPP_PATH} of a session that enters the guest. */
    @NonNull
    public static String[] loginArguments(@NonNull String user) {
        return new String[]{"login", "--user", user};
    }

    /** The arguments after {@link #NIXPP_PATH} that set up the runtime with JSON progress. */
    @NonNull
    public static String[] initArguments(@NonNull String user) {
        return new String[]{"--json", "init", "--user", user};
    }
}
