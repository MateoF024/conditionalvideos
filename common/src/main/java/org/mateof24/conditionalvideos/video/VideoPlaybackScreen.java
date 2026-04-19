package org.mateof24.conditionalvideos.video;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.mateof24.conditionalvideos.video.backend.WaterMediaVideoBackend;

import java.nio.file.Path;

public final class VideoPlaybackScreen extends Screen {
    private static final Component SKIP_HINT = Component.literal("Presiona ESC para saltar");
    private static final int HINT_VISIBLE_TICKS = 120;
    private static final int HINT_FADE_IN_TICKS = 30;
    private static final int HINT_FADE_OUT_TICKS = 40;

    private final WaterMediaVideoBackend backend;
    private int elapsedTicks;

    public VideoPlaybackScreen(Path videoPath) {
        super(Component.literal("Conditional Video"));
        this.backend = new WaterMediaVideoBackend(videoPath);
    }

    @Override
    protected void init() {
        backend.init();
        elapsedTicks = 0;
    }

    @Override
    public void tick() {
        elapsedTicks++;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, width, height, 0xFF000000);
        backend.render(width, height);

        int alpha = calculateHintAlpha();
        if (alpha > 0) {
            int x = width / 2;
            int y = height - 24;
            int color = (alpha << 24) | 0x00FFFFFF;
            guiGraphics.drawCenteredString(font, SKIP_HINT, x, y, color);
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
        if (keyCode == 256) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        backend.close();
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}