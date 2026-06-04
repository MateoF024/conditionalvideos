package org.mateof24.conditionalvideos.video;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.mateof24.conditionalvideos.config.ActiveConfigResolver;

import java.util.function.BooleanSupplier;

public final class VideoLoadingScreen extends Screen {
    private static final int FULLSCREEN_KEYCODE = 300;

    private final BooleanSupplier readyCheck;
    private final Runnable onReady;
    private final Runnable onTimeout;
    private final int timeoutTicks;
    private final int videoCount;

    private int elapsedTicks;
    private boolean transitioned;
    private boolean settled;

    public VideoLoadingScreen(BooleanSupplier readyCheck, Runnable onReady, Runnable onTimeout, int timeoutTicks, int videoCount) {
        super(Component.literal("Conditional Video Loading"));
        this.readyCheck = readyCheck;
        this.onReady = onReady;
        this.onTimeout = onTimeout;
        this.timeoutTicks = timeoutTicks;
        this.videoCount = videoCount;
    }

    @Override
    protected void init() {
        boolean allowSounds = ActiveConfigResolver.effectiveAllowGameSounds(minecraft);
        VideoAudioState.setAllowGameSounds(allowSounds);
        VideoAudioState.setLoadingActive(true);
        if (minecraft != null && !allowSounds) {
            minecraft.getSoundManager().stop();
        }
    }

    @Override
    public void tick() {
        elapsedTicks++;
        if (settled) {
            return;
        }

        if (readyCheck.getAsBoolean()) {
            settled = true;
            transitioned = true;
            // Bridge audio mute through the swap so SoundManagerMixin keeps cancelling sounds
            // during the one-tick gap between removed() and VideoPlaybackScreen.init().
            VideoAudioState.setVideoPlaying(true);
            onReady.run();
            return;
        }

        if (timeoutTicks > 0 && elapsedTicks >= timeoutTicks) {
            settled = true;
            onTimeout.run();
            if (minecraft != null && minecraft.screen == this) {
                minecraft.setScreen(null);
            }
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        VideoLoadingOverlay.render(guiGraphics, font, width, height, videoCount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == FULLSCREEN_KEYCODE) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        return true;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        super.removed();
        VideoAudioState.setLoadingActive(false);
    }

    @Override
    public void onClose() {
        VideoAudioState.setLoadingActive(false);
        super.onClose();
    }

    public boolean hasTransitioned() {
        return transitioned;
    }
}
