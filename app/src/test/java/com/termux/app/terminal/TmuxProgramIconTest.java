package com.termux.app.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.termux.R;

import org.junit.Test;

public class TmuxProgramIconTest {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int DARK = 0xFF291F33;
    private static final int LIGHT = 0xFFEFE6FA;

    @Test
    public void agentsGetTheirOwnLogos() {
        assertEquals(R.drawable.ui_ic_program_claude, TmuxProgramIcon.forCommand("claude").drawable);
        assertEquals(R.drawable.ui_ic_program_codex, TmuxProgramIcon.forCommand("codex").drawable);
        assertEquals(R.drawable.ui_ic_program_gemini, TmuxProgramIcon.forCommand("gemini").drawable);
        assertEquals(R.drawable.ui_ic_program_agent, TmuxProgramIcon.forCommand("aider").drawable);
    }

    @Test
    public void claudeIsRecognisedByItsVersionAsProcessName() {
        assertEquals(R.drawable.ui_ic_program_claude, TmuxProgramIcon.forCommand("2.1.17").drawable);
    }

    @Test
    public void shellsHaveTheirLogosAndUnknownCommandsFallBackToAShell() {
        assertEquals(R.drawable.ui_ic_program_zsh, TmuxProgramIcon.forCommand("zsh").drawable);
        assertEquals(R.drawable.ui_ic_program_bash, TmuxProgramIcon.forCommand("Bash").drawable);
        assertEquals(R.drawable.ui_ic_program_shell, TmuxProgramIcon.forCommand("dash").drawable);
        assertEquals(R.drawable.ui_ic_program_shell, TmuxProgramIcon.forCommand("some-tool").drawable);
    }

    @Test
    public void windowsWithoutACommandHaveNoIcon() {
        assertNull(TmuxProgramIcon.forCommand(null));
        assertNull(TmuxProgramIcon.forCommand(""));
    }

    @Test
    public void brandColoursGiveWayToTheTextColourWhenTheyWouldDisappear() {
        TmuxProgramIcon claude = TmuxProgramIcon.forCommand("claude");
        assertEquals(claude.color, TmuxProgramIcon.tint(claude, WHITE, DARK));

        TmuxProgramIcon codex = TmuxProgramIcon.forCommand("codex");
        assertEquals(WHITE, TmuxProgramIcon.tint(codex, WHITE, DARK));

        // Neovim's green is readable on the dark shelf; on a near-white one the text colour takes over.
        TmuxProgramIcon neovim = TmuxProgramIcon.forCommand("nvim");
        assertEquals(neovim.color, TmuxProgramIcon.tint(neovim, WHITE, DARK));
        assertTrue(TmuxProgramIcon.contrast(neovim.color, LIGHT) < 2.5
            ? TmuxProgramIcon.tint(neovim, DARK, LIGHT) == DARK
            : TmuxProgramIcon.tint(neovim, DARK, LIGHT) == neovim.color);
    }
}
