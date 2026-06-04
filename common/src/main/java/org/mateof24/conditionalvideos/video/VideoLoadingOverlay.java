package org.mateof24.conditionalvideos.video;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

// Shared visual for the "video is loading" state. Used by both VideoLoadingScreen (download
// wait in MP) and VideoPlaybackScreen (backend init / MRL resolution / URL fetch) so the swap
// between them is pixel-identical and the user never sees a frame of world peek-through.
public final class VideoLoadingOverlay {
    private static final Component LOADING_TEXT_SINGLE = Component.translatable("screen.conditionalvideos.loading");
    private static final Component LOADING_TEXT_MULTIPLE = Component.translatable("screen.conditionalvideos.loading.plural");
    private static final int SPINNER_RADIUS = 16;
    private static final int SPINNER_DOT_HALF_SIZE = 2;
    private static final int SPINNER_DOT_COUNT = 8;
    private static final int LOADING_TEXT_OFFSET_Y = 28;

    // ~100ms per active dot — matches the previous 2-ticks-per-dot animation but driven by
    // wall clock so the spinner stays continuous across screen swaps (loading→playback).
    private static final long DOT_STEP_MS = 100L;

    private VideoLoadingOverlay() {
    }

    public static void render(GuiGraphics guiGraphics, Font font, int width, int height, int videoCount) {
        guiGraphics.fill(0, 0, width, height, 0xFF000000);
        renderSpinner(guiGraphics, width, height);
        renderLoadingText(guiGraphics, font, width, height, videoCount);
    }

    private static void renderSpinner(GuiGraphics guiGraphics, int width, int height) {
        int centerX = width / 2;
        int centerY = height / 2 - 10;
        int activeDot = (int) ((System.nanoTime() / 1_000_000L / DOT_STEP_MS) % SPINNER_DOT_COUNT);
        for (int i = 0; i < SPINNER_DOT_COUNT; i++) {
            double angle = (2.0 * Math.PI * i / SPINNER_DOT_COUNT) - (Math.PI / 2.0);
            int dx = centerX + (int) Math.round(Math.cos(angle) * SPINNER_RADIUS);
            int dy = centerY + (int) Math.round(Math.sin(angle) * SPINNER_RADIUS);
            int offset = (i - activeDot + SPINNER_DOT_COUNT) % SPINNER_DOT_COUNT;
            int alpha = Math.max(60, 255 - offset * 30);
            int color = (alpha << 24) | 0x00FFFFFF;
            guiGraphics.fill(
                    dx - SPINNER_DOT_HALF_SIZE,
                    dy - SPINNER_DOT_HALF_SIZE,
                    dx + SPINNER_DOT_HALF_SIZE,
                    dy + SPINNER_DOT_HALF_SIZE,
                    color
            );
        }
    }

    private static void renderLoadingText(GuiGraphics guiGraphics, Font font, int width, int height, int videoCount) {
        int centerX = width / 2;
        int textY = height / 2 + LOADING_TEXT_OFFSET_Y;
        Component text = videoCount > 1 ? LOADING_TEXT_MULTIPLE : LOADING_TEXT_SINGLE;
        guiGraphics.drawCenteredString(font, text, centerX, textY, 0xFFFFFFFF);
    }
}
