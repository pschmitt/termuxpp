package dev.pschmitt.termux.shared.termux.extrakeys;

import androidx.annotation.Nullable;

/** Motion applied to an extra key during a press and release. */
public enum ExtraKeyAnimation {
    KEY_PREVIEW("key-preview", 1, 0, 0),
    PRESS_IN("press-in", 0.91f, 0, 150),
    POP("pop", 1.10f, 0, 180),
    LIFT("lift", 1, -4, 160),
    OFF("off", 1, 0, 0);

    public static final ExtraKeyAnimation DEFAULT = KEY_PREVIEW;

    public final String value;
    final float pressedScale;
    final float pressedOffsetDp;
    final long releaseDurationMs;

    ExtraKeyAnimation(String value, float pressedScale, float pressedOffsetDp, long releaseDurationMs) {
        this.value = value;
        this.pressedScale = pressedScale;
        this.pressedOffsetDp = pressedOffsetDp;
        this.releaseDurationMs = releaseDurationMs;
    }

    @Nullable
    public static ExtraKeyAnimation parse(@Nullable String value) {
        if (value == null) return null;
        String normalized = value.trim();
        for (ExtraKeyAnimation animation : values()) {
            if (animation.value.equalsIgnoreCase(normalized)) return animation;
        }
        return null;
    }

    public static ExtraKeyAnimation parseOrDefault(@Nullable String value) {
        ExtraKeyAnimation animation = parse(value);
        return animation == null ? DEFAULT : animation;
    }
}
