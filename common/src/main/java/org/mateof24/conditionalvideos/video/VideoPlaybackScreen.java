package org.mateof24.conditionalvideos.video;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.mateof24.conditionalvideos.video.backend.WaterMediaVideoBackend;

import java.nio.file.Path;
import java.util.List;

public final class VideoPlaybackScreen extends Screen {
    private static final Component SKIP_HINT = Component.translatable("screen.conditionalvideos.skip_hint");
    private static final int HINT_VISIBLE_TICKS = 120;
    private static final int HINT_FADE_IN_TICKS = 30;
    private static final int HINT_FADE_OUT_TICKS = 40;
    private static final int TEXT_BOX_WIDTH = 220;
    private static final int TEXT_BOX_PADDING = 6;
    private static final int TEXT_BOX_MARGIN = 10;
    private static final int LINE_SPACING = 2;

    private final WaterMediaVideoBackend backend;
    private int elapsedTicks;
    private final boolean skippable;
    private boolean worldAudioPaused;
    private final boolean enableBackground;
    private final int backgroundColor;
    private final Component videoTitle;
    private final TextAnchor videoTitleAnchor;
    private final Component videoDescription;
    private final TextAnchor videoDescriptionAnchor;

    public VideoPlaybackScreen(
            Path videoPath,
            boolean skippable,
            boolean enableBackground,
            int backgroundColor,
            String videoTitle,
            String videoTitlePosition,
            String videoDescription,
            String videoDescriptionPosition
    ) {
        super(Component.literal("Conditional Video"));
        this.backend = new WaterMediaVideoBackend(videoPath);
        this.skippable = skippable;
        this.enableBackground = enableBackground;
        this.backgroundColor = backgroundColor;
        this.videoTitle = LegacyFormatParser.parse(videoTitle);
        this.videoTitleAnchor = TextAnchor.fromConfig(videoTitlePosition);
        this.videoDescription = LegacyFormatParser.parse(videoDescription);
        this.videoDescriptionAnchor = TextAnchor.fromConfig(videoDescriptionPosition);
    }

    @Override
    protected void init() {
        backend.init();
        pauseWorldAudio();
        elapsedTicks = 0;
    }

    @Override
    public void tick() {
        elapsedTicks++;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (enableBackground) {
            guiGraphics.fill(0, 0, width, height, backgroundColor);
        }
        backend.render(width, height);

        int alpha = calculateHintAlpha();
        if (alpha > 0) {
            int x = width / 2;
            int y = height - 24;
            int color = (alpha << 24) | 0x00FFFFFF;
            guiGraphics.drawCenteredString(font, SKIP_HINT, x, y, color);
            renderVideoMetadata(guiGraphics, alpha);
        }
    }

    private void renderVideoMetadata(GuiGraphics guiGraphics, int alpha) {
        boolean hasTitle = !videoTitle.getString().isBlank();
        boolean hasDescription = !videoDescription.getString().isBlank();
        if (!hasTitle && !hasDescription) {
            return;
        }

        if (hasTitle && hasDescription && videoTitleAnchor == videoDescriptionAnchor) {
            drawCombinedTextBlock(
                    guiGraphics,
                    font.split(videoTitle, TEXT_BOX_WIDTH - (TEXT_BOX_PADDING * 2)),
                    font.split(videoDescription, TEXT_BOX_WIDTH - (TEXT_BOX_PADDING * 2)),
                    videoTitleAnchor,
                    alpha
            );
            return;
        }

        if (hasTitle) {
            drawTextBlock(guiGraphics, font.split(videoTitle, TEXT_BOX_WIDTH - (TEXT_BOX_PADDING * 2)), videoTitleAnchor, alpha);
        }
        if (hasDescription) {
            drawTextBlock(guiGraphics, font.split(videoDescription, TEXT_BOX_WIDTH - (TEXT_BOX_PADDING * 2)), videoDescriptionAnchor, alpha);
        }
    }

    private void drawTextBlock(GuiGraphics guiGraphics, List<FormattedCharSequence> lines, TextAnchor anchor, int alpha) {
        if (lines.isEmpty()) {
            return;
        }

        int lineHeight = font.lineHeight + LINE_SPACING;
        int textHeight = (lines.size() * lineHeight) - LINE_SPACING;
        int boxHeight = textHeight + (TEXT_BOX_PADDING * 2);
        int x = anchor.isRightAligned()
                ? width - TEXT_BOX_MARGIN - TEXT_BOX_WIDTH
                : TEXT_BOX_MARGIN;
        int y = anchor.resolveY(height, boxHeight);

        int backgroundAlpha = Math.min(alpha, 100);
        int boxColor = (backgroundAlpha << 24);
        guiGraphics.fill(x, y, x + TEXT_BOX_WIDTH, y + boxHeight, boxColor);

        int color = (alpha << 24) | 0x00FFFFFF;
        for (int i = 0; i < lines.size(); i++) {
            guiGraphics.drawString(
                    font,
                    lines.get(i),
                    x + TEXT_BOX_PADDING,
                    y + TEXT_BOX_PADDING + (i * lineHeight),
                    color
            );
        }
    }

    private void drawCombinedTextBlock(
            GuiGraphics guiGraphics,
            List<FormattedCharSequence> titleLines,
            List<FormattedCharSequence> descriptionLines,
            TextAnchor anchor,
            int alpha
    ) {
        int lineHeight = font.lineHeight + LINE_SPACING;
        int titleHeight = (titleLines.size() * lineHeight) - LINE_SPACING;
        int descriptionHeight = (descriptionLines.size() * lineHeight) - LINE_SPACING;
        int totalHeight = titleHeight + lineHeight + descriptionHeight;
        int boxHeight = totalHeight + (TEXT_BOX_PADDING * 2);
        int x = anchor.isRightAligned()
                ? width - TEXT_BOX_MARGIN - TEXT_BOX_WIDTH
                : TEXT_BOX_MARGIN;
        int y = anchor.resolveY(height, boxHeight);

        int backgroundAlpha = Math.min(alpha, 100);
        int boxColor = (backgroundAlpha << 24);
        guiGraphics.fill(x, y, x + TEXT_BOX_WIDTH, y + boxHeight, boxColor);

        int color = (alpha << 24) | 0x00FFFFFF;
        int currentY = y + TEXT_BOX_PADDING;
        for (FormattedCharSequence titleLine : titleLines) {
            guiGraphics.drawString(font, titleLine, x + TEXT_BOX_PADDING, currentY, color);
            currentY += lineHeight;
        }
        currentY += lineHeight;
        for (FormattedCharSequence descriptionLine : descriptionLines) {
            guiGraphics.drawString(font, descriptionLine, x + TEXT_BOX_PADDING, currentY, color);
            currentY += lineHeight;
        }
    }

    private int calculateHintAlpha() {
        if (elapsedTicks >= HINT_VISIBLE_TICKS) {
            return 0;
        }

        if (elapsedTicks < HINT_FADE_IN_TICKS) {
            return (int) (255.0F * elapsedTicks / HINT_FADE_IN_TICKS);
        }

        int fadeOutStartTick = HINT_VISIBLE_TICKS - HINT_FADE_OUT_TICKS;
        if (elapsedTicks >= fadeOutStartTick) {
            int ticksIntoFadeOut = elapsedTicks - fadeOutStartTick;
            return (int) (255.0F * (HINT_FADE_OUT_TICKS - ticksIntoFadeOut) / HINT_FADE_OUT_TICKS);
        }

        return 255;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (skippable && keyCode == 256) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        backend.close();
        resumeWorldAudio();
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void pauseWorldAudio() {
        if (minecraft == null || worldAudioPaused) {
            return;
        }

        minecraft.getSoundManager().pause();
        worldAudioPaused = true;
    }

    private void resumeWorldAudio() {
        if (minecraft == null || !worldAudioPaused) {
            return;
        }

        minecraft.getSoundManager().resume();
        worldAudioPaused = false;
    }

    private enum TextAnchor {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT;

        static TextAnchor fromConfig(String value) {
            if (value == null) {
                return BOTTOM_LEFT;
            }

            return switch (value.trim().toLowerCase()) {
                case "topleft" -> TOP_LEFT;
                case "topright" -> TOP_RIGHT;
                case "bottomright" -> BOTTOM_RIGHT;
                default -> BOTTOM_LEFT;
            };
        }

        boolean isRightAligned() {
            return this == TOP_RIGHT || this == BOTTOM_RIGHT;
        }

        int resolveY(int screenHeight, int boxHeight) {
            if (this == TOP_LEFT || this == TOP_RIGHT) {
                return TEXT_BOX_MARGIN;
            }

            int skipHintTopY = screenHeight - 24;
            int bottomLimit = skipHintTopY - TEXT_BOX_MARGIN;
            return Math.max(TEXT_BOX_MARGIN, bottomLimit - boxHeight);
        }
    }


}