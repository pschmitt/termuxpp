package com.termux.app.terminal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.pschmitt.termux.shared.shell.command.ExecutionCommand;

import java.io.File;
import dev.pschmitt.termux.shared.termux.settings.properties.GestureCommandContext;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class GestureCommandLaunchTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void outerCommandKeepsTheShellTextInOneArgument() throws Exception {
        File prefix = temporary.newFolder("usr");
        File bash = new File(prefix, "bin/bash");
        assertTrue(bash.getParentFile().mkdirs() && bash.createNewFile() && bash.setExecutable(true));
        String command = "printf '%s' 'two words' && printf '\\n'";
        GestureCommandLaunch launch = GestureCommandLaunch.create(command,
            GestureCommandContext.OUTER, true, true, "/prefix/bin/nixpp", prefix.getPath(), "guest");

        assertEquals(prefix.getPath() + "/bin/bash", launch.executable);
        assertArrayEquals(new String[] {"--noprofile", "--norc", "-lc", launch.arguments[3]}, launch.arguments);
        assertTrue(launch.arguments[3].startsWith(command + "\n"));
        assertTrue(launch.arguments[3].contains("exec \"${SHELL:-" + prefix.getPath() + "/bin/sh}\" -l"));
    }

    @Test
    public void outerCommandUsesTheSlimPrefixShellWithoutBash() {
        String command = "printf '%s' 'two words'";
        GestureCommandLaunch launch = GestureCommandLaunch.create(command,
            GestureCommandContext.OUTER, true, true, "/prefix/bin/nixpp", "/prefix", "guest");

        assertEquals("/prefix/bin/sh", launch.executable);
        assertArrayEquals(new String[] {"-c", launch.arguments[1]}, launch.arguments);
        assertTrue(launch.arguments[1].startsWith(command + "\n"));
        assertTrue(launch.arguments[1].contains("exec \"${SHELL:-/prefix/bin/sh}\" -l"));
    }

    @Test
    public void guestCommandPassesExplicitNixppArgumentsAndPreservesShellText() {
        String command = "cd '/home/guest/my project' && pwd";
        GestureCommandLaunch launch = GestureCommandLaunch.create(command,
            GestureCommandContext.NIX, false, true, "/prefix/bin/nixpp", "/prefix", "guest");

        assertEquals("/prefix/bin/nixpp", launch.executable);
        assertArrayEquals(new String[] {"login", "--user", "guest", "--", "bash", "-lc", launch.arguments[6]}, launch.arguments);
        assertTrue(launch.arguments[6].startsWith(command + "\n"));
    }

    @Test
    public void sessionContextFollowsTheNormalNixSessionSetting() {
        GestureCommandLaunch guest = GestureCommandLaunch.create("pwd", GestureCommandContext.SESSION,
            true, true, "/prefix/bin/nixpp", "/prefix", "guest");
        GestureCommandLaunch outer = GestureCommandLaunch.create("pwd", GestureCommandContext.SESSION,
            false, true, "/prefix/bin/nixpp", "/prefix", "guest");
        assertEquals("/prefix/bin/nixpp", guest.executable);
        assertEquals("/prefix/bin/sh", outer.executable);
    }

    @Test
    public void missingGuestAndBlankCommandsAreNotLaunched() {
        assertNull(GestureCommandLaunch.create("pwd", GestureCommandContext.NIX,
            false, false, "/prefix/bin/nixpp", "/prefix", "guest"));
        assertNull(GestureCommandLaunch.create("  ", GestureCommandContext.OUTER,
            false, false, "/prefix/bin/nixpp", "/prefix", "guest"));
    }

    @Test
    public void commandArgumentsAreRedactedFromExecutionLogs() {
        ExecutionCommand command = new ExecutionCommand();
        command.arguments = new String[] {"-c", "private shell text"};
        command.redactArgumentsInLogs = true;
        assertEquals("Arguments: [redacted]", command.getArgumentsLogString());
        assertFalse(command.getArgumentsLogString().contains("private shell text"));
    }
}
