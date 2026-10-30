package org.mateof24.conditionalvideos.config;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Where a text box goes on screen: one of the four corners, or "X/Y" in real window pixels (the ones of a
// screenshot). A positive coordinate measures from the left/top edge to the box's top-left corner; a
// negative one (also "-0") from the right/bottom edge to its bottom-right corner.
public record TextPosition(boolean exact, double x, boolean fromRight, double y, boolean fromBottom) {
    public static final TextPosition BOTTOM_LEFT = new TextPosition(false, 0d, false, 0d, true);
    private static final String NUMBER = "(-?)(\\d+(?:\\.\\d*)?|\\.\\d+)";
    private static final Pattern PIXELS = Pattern.compile("\\s*" + NUMBER + "\\s*/\\s*" + NUMBER + "\\s*");

    // Returns null when the text is not a valid position; a missing or blank one means bottomLeft.
    public static TextPosition parse(String value) {
        if (value == null || value.isBlank()) {
            return BOTTOM_LEFT;
        }
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "topleft" -> {
                return new TextPosition(false, 0d, false, 0d, false);
            }
            case "topright" -> {
                return new TextPosition(false, 0d, true, 0d, false);
            }
            case "bottomleft" -> {
                return BOTTOM_LEFT;
            }
            case "bottomright" -> {
                return new TextPosition(false, 0d, true, 0d, true);
            }
            default -> {
            }
        }
        Matcher pixels = PIXELS.matcher(value);
        if (!pixels.matches()) {
            return null;
        }
        return new TextPosition(true, Double.parseDouble(pixels.group(2)), !pixels.group(1).isEmpty(),
                Double.parseDouble(pixels.group(4)), !pixels.group(3).isEmpty());
    }

    public static TextPosition resolve(String value) {
        TextPosition position = parse(value);
        return position == null ? BOTTOM_LEFT : position;
    }

    // Left edge of a box in GUI units. Exact positions convert real pixels with the GUI scale and move the
    // box just enough to keep it whole on screen.
    public float left(int screenWidth, int boxWidth, int margin, double guiScale) {
        if (!exact) {
            return fromRight ? screenWidth - margin - boxWidth : margin;
        }
        return clamp(fromRight ? screenWidth - x / guiScale - boxWidth : x / guiScale, screenWidth - boxWidth);
    }

    // Top edge of a box in GUI units; bottomLimit is where the bottom corners must end above the skip hint.
    public float top(int screenHeight, int boxHeight, int margin, int bottomLimit, double guiScale) {
        if (!exact) {
            return fromBottom ? Math.max(margin, bottomLimit - boxHeight) : margin;
        }
        return clamp(fromBottom ? screenHeight - y / guiScale - boxHeight : y / guiScale, screenHeight - boxHeight);
    }

    private static float clamp(double value, int max) {
        return (float) Math.max(0d, Math.min(value, Math.max(0, max)));
    }
}
