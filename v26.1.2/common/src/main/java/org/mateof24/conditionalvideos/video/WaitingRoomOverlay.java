package org.mateof24.conditionalvideos.video;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig.WaitingRoomConfig;
import org.mateof24.conditionalvideos.config.TextAlignment;
import org.mateof24.conditionalvideos.config.TextPosition;
import org.mateof24.conditionalvideos.config.WaitingRoomText;
import org.mateof24.conditionalvideos.debug.DebugLog;
import org.joml.Matrix3x2fStack;
import org.mateof24.conditionalvideos.video.backend.WaterMediaVideoBackend;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

// What covers the world while in the waiting room: the background, a color or an image or GIF fitted over black, and
// the title, description and counter, whose placeholders follow who is in. All of it follows the file while someone
// waits. A video is refused as background and black stays.
public final class WaitingRoomOverlay {
    private static final int TEXT_BOX_PADDING = 6;
    private static final int TEXT_BOX_MARGIN = 10;
    private static final int TEXT_BOX_GAP = 4;
    private static final int LINE_SPACING = 2;
    private static final int BOX_ALPHA = 100;

    private WaitingRoomConfig room;
    private int backgroundColor;
    private final Function<String, URI> backgroundSource;
    private final int backgroundWaitLimit;
    private int backgroundWaitTicks;
    private WaterMediaVideoBackend background;
    private boolean backgroundChecked;

    private String filledFor;
    private Component title;
    private Component description;
    private Component counter;

    // A background sent by the server may still be downloading, so its source is asked for a while.
    public WaitingRoomOverlay(WaitingRoomConfig room, Function<String, URI> backgroundSource, int backgroundWaitLimit) {
        this.room = room;
        this.backgroundColor = room.backgroundColor();
        this.backgroundSource = backgroundSource;
        this.backgroundWaitLimit = backgroundWaitLimit;
        this.backgroundWaitTicks = room.backgroundSource() != null ? backgroundWaitLimit : 0;
    }

    // Texts, positions and colors apply at once; only a different background has to load.
    public void update(WaitingRoomConfig newRoom) {
        if (newRoom.equals(room)) {
            return;
        }
        boolean sameBackground = Objects.equals(newRoom.backgroundSource(), room.backgroundSource());
        room = newRoom;
        backgroundColor = newRoom.backgroundColor();
        filledFor = null;
        if (!sameBackground) {
            close();
            backgroundChecked = false;
            backgroundWaitTicks = newRoom.backgroundSource() != null ? backgroundWaitLimit : 0;
        }
        DebugLog.client(DebugLog.Area.ROOM, "The waiting room changed; showing it as it is now.");
    }

    private void openBackground() {
        if (backgroundWaitTicks <= 0) {
            return;
        }
        backgroundWaitTicks--;
        URI source = backgroundSource.apply(room.backgroundSource());
        if (source == null) {
            if (backgroundWaitTicks == 0) {
                ConditionalVideos.LOGGER.warn("The waiting room background '{}' was not found; using black.", room.backgroundSource());
            }
            return;
        }
        backgroundWaitTicks = 0;
        background = new WaterMediaVideoBackend(source, 0f);
        background.setVolumeMultiplier(0f);
        try {
            background.init();
        } catch (Throwable throwable) {
            ConditionalVideos.LOGGER.warn("Failed to init the waiting room background '{}': {}", source, throwable.toString());
            background = null;
        }
    }

    public void tick() {
        if (background == null) {
            openBackground();
            return;
        }
        background.tick();
        if (background.hasError()) {
            ConditionalVideos.LOGGER.warn("The waiting room background '{}' could not be loaded; using black.", background.source());
            close();
        } else if (!backgroundChecked && background.isPlayerCreated()) {
            backgroundChecked = true;
            if (!background.isImage()) {
                ConditionalVideos.LOGGER.warn("The waiting room background '{}' is a video, and only images and GIFs are accepted; using black.", background.source());
                close();
            } else {
                DebugLog.client(DebugLog.Area.ROOM, "The waiting room background '{}' is ready.", background.source());
            }
        }
    }

    public void close() {
        if (background != null) {
            try {
                background.close();
            } catch (Throwable ignored) {
            }
            background = null;
        }
    }

    public void render(GuiGraphicsExtractor guiGraphics, Font font, int width, int height, int current, int required, List<String> missing) {
        closeLayer(guiGraphics);
        guiGraphics.fill(0, 0, width, height, backgroundColor);
        if (background != null && backgroundChecked && background.hasTextureValid()) {
            background.render(guiGraphics, width, height, 1f);
        }
        String state = current + "/" + required + "/" + missing;
        if (!state.equals(filledFor)) {
            filledFor = state;
            title = LegacyFormatParser.parse(WaitingRoomText.fill(room.title(), current, required, missing));
            description = LegacyFormatParser.parse(WaitingRoomText.fill(room.description(), current, required, missing));
            counter = LegacyFormatParser.parse(WaitingRoomText.fill(room.counter(), current, required, missing));
        }
        TextBox[] boxes = {
                measure(font, title, room.resolvedTitlePosition(), room.resolvedTitleScale(), room.resolvedTitleAlignment(), width, height),
                measure(font, description, room.resolvedDescriptionPosition(), room.resolvedDescriptionScale(), room.resolvedDescriptionAlignment(), width, height),
                measure(font, counter, room.resolvedCounterPosition(), room.resolvedCounterScale(), room.resolvedCounterAlignment(), width, height)
        };
        alignShared(boxes);
        keepApart(boxes, 1, 2);
        keepApart(boxes, 0, 2);
        keepApart(boxes, 0, 1);
        for (TextBox box : boxes) {
            draw(guiGraphics, font, box);
        }
        closeLayer(guiGraphics);
    }

    private static void alignShared(TextBox[] boxes) {
        TextPosition[] positions = new TextPosition[boxes.length];
        TextAlignment[] alignments = new TextAlignment[boxes.length];
        float[] lefts = new float[boxes.length];
        int[] widths = new int[boxes.length];
        for (int index = 0; index < boxes.length; index++) {
            if (boxes[index] != null) {
                positions[index] = boxes[index].position();
                alignments[index] = boxes[index].alignment();
                lefts[index] = boxes[index].x();
                widths[index] = boxes[index].width();
            }
        }
        float[] aligned = TextPosition.alignShared(positions, alignments, lefts, widths);
        for (int index = 0; index < boxes.length; index++) {
            if (boxes[index] != null) {
                boxes[index] = boxes[index].atLeft(aligned[index]);
            }
        }
    }

    // Where they would overlap, the upper one goes above the lower one.
    private static void keepApart(TextBox[] boxes, int upper, int lower) {
        TextBox above = boxes[upper];
        TextBox below = boxes[lower];
        if (above != null && below != null) {
            float[] tops = TextPosition.separate(above.x(), above.y(), above.width(), above.height(),
                    below.x(), below.y(), below.width(), below.height(), TEXT_BOX_GAP);
            boxes[upper] = above.atTop(tops[0]);
            boxes[lower] = below.atTop(tops[1]);
        }
    }

    // Layers do not mix: nothing drawn before shows through the room, and the room's text stays under what comes next.
    public static void closeLayer(GuiGraphicsExtractor guiGraphics) {
        guiGraphics.nextStratum();
    }

    // Null when there is nothing to show.
    private static TextBox measure(Font font, Component component, String position, float scale, TextAlignment alignment, int width, int height) {
        if (component.getString().isBlank()) {
            return null;
        }
        List<FormattedCharSequence> lines = font.split(component, Integer.MAX_VALUE);
        int longest = 0;
        for (FormattedCharSequence line : lines) {
            longest = Math.max(longest, font.width(line));
        }
        int boxWidth = (int) Math.ceil(longest * scale) + TEXT_BOX_PADDING * 2;
        int textHeight = lines.size() * (font.lineHeight + LINE_SPACING) - LINE_SPACING;
        int boxHeight = Math.round(textHeight * scale) + TEXT_BOX_PADDING * 2;
        TextPosition parsed = TextPosition.parse(position);
        TextPosition resolved = parsed == null ? TextPosition.CENTER : parsed;
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        return new TextBox(lines, longest, alignment, resolved, scale, resolved.left(width, boxWidth, TEXT_BOX_MARGIN, guiScale),
                resolved.top(height, boxHeight, TEXT_BOX_MARGIN, height - TEXT_BOX_MARGIN, guiScale), boxWidth, boxHeight);
    }

    private static void draw(GuiGraphicsExtractor guiGraphics, Font font, TextBox box) {
        if (box == null) {
            return;
        }
        Matrix3x2fStack pose = guiGraphics.pose();
        pose.pushMatrix();
        pose.translate(box.x(), box.y());
        guiGraphics.fill(0, 0, box.width(), box.height(), BOX_ALPHA << 24);
        drawLines(guiGraphics, font, box, TEXT_BOX_PADDING, TEXT_BOX_PADDING);
        pose.popMatrix();
    }

    private static void drawLines(GuiGraphicsExtractor guiGraphics, Font font, TextBox box, int originX, int originY) {
        Matrix3x2fStack pose = guiGraphics.pose();
        pose.pushMatrix();
        pose.translate(originX, originY);
        pose.scale(box.scale(), box.scale());
        List<FormattedCharSequence> lines = box.lines();
        for (int index = 0; index < lines.size(); index++) {
            int x = box.alignment().offset(box.longest(), font.width(lines.get(index)));
            guiGraphics.text(font, lines.get(index), x, index * (font.lineHeight + LINE_SPACING), 0xFFFFFFFF);
        }
        pose.popMatrix();
    }

    private record TextBox(List<FormattedCharSequence> lines, int longest, TextAlignment alignment, TextPosition position, float scale, float x, float y, int width, int height) {
        TextBox atLeft(float left) {
            return new TextBox(lines, longest, alignment, position, scale, left, y, width, height);
        }

        TextBox atTop(float top) {
            return new TextBox(lines, longest, alignment, position, scale, x, top, width, height);
        }
    }
}
