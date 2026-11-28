package com.termux.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CommandPaletteSearchTest {

    private static CommandPalette.Entry entry(CommandPalette.Kind kind, String title, String detail) {
        return new CommandPalette.Entry(kind, title, detail, () -> {});
    }

    private static List<String> titles(List<CommandPalette.Entry> entries) {
        List<String> titles = new ArrayList<>();
        for (CommandPalette.Entry entry : entries) titles.add(entry.title);
        return titles;
    }

    private final List<CommandPalette.Entry> entries = Arrays.asList(
        entry(CommandPalette.Kind.SESSION, "1  build", "Session"),
        entry(CommandPalette.Kind.WINDOW, "0  shell", "tmux window · build"),
        entry(CommandPalette.Kind.WINDOW, "1  nvim", "tmux window · build"),
        entry(CommandPalette.Kind.ACTION, "New session", "Action"),
        entry(CommandPalette.Kind.ACTION, "Larger text", "Action"),
        entry(CommandPalette.Kind.SETTING, "Font size", "Setting · Appearance"),
        entry(CommandPalette.Kind.SETTING, "Terminal font", "Setting · Appearance"));

    @Test
    public void anEmptyQueryKeepsEverythingInOrder() {
        assertEquals(titles(entries), titles(CommandPalette.filter(entries, "  ", 60)));
    }

    @Test
    public void everyWordMustMatchTitleOrDetail() {
        assertEquals(Arrays.asList("1  nvim"), titles(CommandPalette.filter(entries, "nvim build", 60)));
        assertEquals(Arrays.asList("Font size", "Terminal font"),
            titles(CommandPalette.filter(entries, "appearance font", 60)));
        assertEquals(Arrays.asList(), titles(CommandPalette.filter(entries, "nothing here", 60)));
    }

    @Test
    public void titlesStartingWithTheQueryComeFirst() {
        // "Font size" starts with it, "Terminal font" has a word that does.
        assertEquals(Arrays.asList("Font size", "Terminal font"), titles(CommandPalette.filter(entries, "FONT", 60)));
        // A session ranks above an action on an equal match.
        assertEquals(Arrays.asList("1  build", "0  shell", "1  nvim"),
            titles(CommandPalette.filter(entries, "build", 60)));
    }

    @Test
    public void keywordsMatchWithoutBeingShown() {
        List<CommandPalette.Entry> withKeywords = Arrays.asList(
            new CommandPalette.Entry(CommandPalette.Kind.ACTION, "Larger text", "Action", "font-size-up", () -> {}),
            entry(CommandPalette.Kind.ACTION, "New session", "Action"));
        assertEquals(Arrays.asList("Larger text"), titles(CommandPalette.filter(withKeywords, "font", 60)));
    }

    @Test
    public void theLimitCutsTheList() {
        assertEquals(2, CommandPalette.filter(entries, "", 2).size());
    }
}
