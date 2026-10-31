package org.mateof24.conditionalvideos.config;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Where a text box goes on screen: one of the four corners, the center, or "X/Y" in real window pixels (the ones of a
// screenshot). A positive coordinate measures from the left/top edge to the box's top-left corner; a
// negative one (also "-0") from the right/bottom edge to its bottom-right corner.
public record TextPosition(boolean exact, double x, boolean fromRight, double y, boolean fromBottom, boolean centered) {
    public static final TextPosition BOTTOM_LEFT = new TextPosition(false, 0d, false, 0d, true, false);
    public static final TextPosition CENTER = new TextPosition(false, 0d, false, 0d, false, true);
    private static final String NUMBER = "(-?)(\\d+(?:\\.\\d*)?|\\.\\d+)";
    private static final Pattern PIXELS = Pattern.compile("\\s*" + NUMBER + "\\s*/\\s*" + NUMBER + "\\s*");

    // Returns null when the text is not a valid position; a missing or blank one means bottomLeft.
    public static TextPosition parse(String value) {
        if (value == null || value.isBlank()) {
            return BOTTOM_LEFT;
        }
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "topleft" -> {
                return new TextPosition(false, 0d, false, 0d, false, false);
            }
            case "topright" -> {
                return new TextPosition(false, 0d, true, 0d, false, false);
            }
            case "bottomleft" -> {
                return BOTTOM_LEFT;
            }
            case "bottomright" -> {
                return new TextPosition(false, 0d, true, 0d, true, false);
            }
            case "center" -> {
                return CENTER;
            }
            default -> {
            }
        }
        Matcher pixels = PIXELS.matcher(value);
        if (!pixels.matches()) {
            return null;
        }
        return new TextPosition(true, Double.parseDouble(pixels.group(2)), !pixels.group(1).isEmpty(),
                Double.parseDouble(pixels.group(4)), !pixels.group(3).isEmpty(), false);
    }

    public static TextPosition resolve(String value) {
        TextPosition position = parse(value);
        return position == null ? BOTTOM_LEFT : position;
    }

    // Left edge of a box in GUI units. Exact positions convert real pixels with the GUI scale and move the
    // box just enough to keep it whole on screen.
    public float left(int screenWidth, int boxWidth, int margin, double guiScale) {
        if (centered) {
            return (screenWidth - boxWidth) / 2f;
        }
        if (!exact) {
            return fromRight ? screenWidth - margin - boxWidth : margin;
        }
        return clamp(fromRight ? screenWidth - x / guiScale - boxWidth : x / guiScale, screenWidth - boxWidth);
    }

    // Top edge of a box in GUI units; bottomLimit is where the bottom corners must end above the skip hint.
    public float top(int screenHeight, int boxHeight, int margin, int bottomLimit, double guiScale) {
        if (centered) {
            return (screenHeight - boxHeight) / 2f;
        }
        if (!exact) {
            return fromBottom ? Math.max(margin, bottomLimit - boxHeight) : margin;
        }
        return clamp(fromBottom ? screenHeight - y / guiScale - boxHeight : y / guiScale, screenHeight - boxHeight);
    }

    // Boxes that really overlap are moved apart: the title goes right above the description or, without room
    // above, the description right below the title. Returns the new tops {title, description}.
    // Texts in the same preset position line up as one block as wide as the widest of them, though each keeps its own
    // box. An exact position, or no alignment, leaves a text where it is; a null position is a text that is not there.
    public static float[] alignShared(TextPosition[] positions, TextAlignment[] alignments, float[] lefts, int[] widths) {
        float[] aligned = lefts.clone();
        for (int index = 0; index < positions.length; index++) {
            if (positions[index] == null || positions[index].exact() || alignments[index] == null) {
                continue;
            }
            float blockLeft = lefts[index];
            int blockWidth = widths[index];
            for (int other = 0; other < positions.length; other++) {
                if (positions[index].equals(positions[other])) {
                    blockLeft = Math.min(blockLeft, lefts[other]);
                    blockWidth = Math.max(blockWidth, widths[other]);
                }
            }
            aligned[index] = blockLeft + alignments[index].offset(blockWidth, widths[index]);
        }
        return aligned;
    }

    public static float[] separate(float titleLeft, float titleTop, int titleWidth, int titleHeight,
                                   float descriptionLeft, float descriptionTop, int descriptionWidth, int descriptionHeight, int gap) {
        boolean overlap = titleLeft < descriptionLeft + descriptionWidth && descriptionLeft < titleLeft + titleWidth
                && titleTop < descriptionTop + descriptionHeight && descriptionTop < titleTop + titleHeight;
        if (!overlap) {
            return new float[]{titleTop, descriptionTop};
        }
        float raised = descriptionTop - gap - titleHeight;
        if (raised >= 0f) {
            return new float[]{raised, descriptionTop};
        }
        float top = Math.max(0f, Math.min(titleTop, descriptionTop));
        return new float[]{top, top + titleHeight + gap};
    }

    private static float clamp(double value, int max) {
        return (float) Math.max(0d, Math.min(value, Math.max(0, max)));
    }
}
