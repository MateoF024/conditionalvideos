package org.mateof24.conditionalvideos.config;

import java.util.Locale;

// How a text lines up, as in a word processor: its lines inside its box, and its box among those in the same position.
public enum TextAlignment {
    LEFT, CENTER, RIGHT;

    // Null when it is none of them.
    public static TextAlignment parse(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "left" -> LEFT;
            case "center" -> CENTER;
            case "right" -> RIGHT;
            default -> null;
        };
    }

    public static TextAlignment resolve(String value, TextAlignment fallback) {
        TextAlignment parsed = parse(value);
        return parsed == null ? fallback : parsed;
    }

    public static boolean isValid(String value) {
        return value == null || value.isBlank() || parse(value) != null;
    }

    // How far something this wide starts from the left edge of a space that wide.
    public int offset(int space, int width) {
        return switch (this) {
            case LEFT -> 0;
            case CENTER -> (space - width) / 2;
            case RIGHT -> space - width;
        };
    }
}
