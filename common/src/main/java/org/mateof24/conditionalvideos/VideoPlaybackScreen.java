package org.mateof24.conditionalvideos;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

final class VideoPlaybackScreen extends Screen {
    private static final Component SKIP_HINT = Component.literal("Presiona ESC para saltar");

    private final WaterMediaVideoBackend backend;

    VideoPlaybackScreen(Path videoPath) {
        super(Component.literal("Conditional Video"));
        this.backend = new WaterMediaVideoBackend(videoPath);
    }

    @Override
    protected void init() {
        backend.init();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics);
        backend.render(width, height);

        int x = width / 2;
        int y = height - 24;
        guiGraphics.drawCenteredString(font, SKIP_HINT, x, y, 0xFFFFFF);
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