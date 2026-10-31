package org.mateof24.conditionalvideos.video;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.mateof24.conditionalvideos.network.PlaybackControlNetworking;
import org.mateof24.conditionalvideos.runtime.ConditionalVideosKeybinds;
import org.mateof24.conditionalvideos.runtime.WaitingRoomClient;

// Takes the input while in the waiting room, which draws through WaitingRoomOverlay. ESC opens the pause menu over it
// as in the game. Where leaving is allowed, holding the skip key fills a bar that leaves on release; if that key is
// ESC, only a short press opens the menu. An operator starts it with SPACE.
public final class WaitingRoomScreen extends Screen {
    private static final int ESCAPE_KEY = 256;
    private static final int SPACE_KEY = 32;
    private static final int FULLSCREEN_KEY = 300;
    private static final int SHORT_PRESS_TICKS = 6;
    private static final int HOLD_TICKS = 20;

    private boolean skipKeyIsEsc = true;
    private boolean skipKeyDown;
    private int holdTicks;
    private Component releaseHint = Component.empty();

    public WaitingRoomScreen() {
        super(Component.literal("Waiting Room"));
    }

    @Override
    protected void init() {
        KeyMapping skip = ConditionalVideosKeybinds.SKIP;
        skipKeyIsEsc = skip.isUnbound() || skip.matches(new KeyEvent(ESCAPE_KEY, -1, 0));
        Component keyName = skipKeyIsEsc ? InputConstants.getKey(new KeyEvent(ESCAPE_KEY, -1, 0)).getDisplayName() : skip.getTranslatedKeyMessage();
        releaseHint = Component.translatable("screen.conditionalvideos.waiting_room.release_to_leave", keyName);
    }

    @Override
    public void tick() {
        if (skipKeyDown && holdTicks < HOLD_TICKS) {
            holdTicks++;
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        WaitingRoomOverlay overlay = WaitingRoomClient.overlay();
        if (overlay == null || !WaitingRoomClient.canDraw()) {
            guiGraphics.fill(0, 0, width, height, 0xFF000000);
            return;
        }
        overlay.render(guiGraphics, font, width, height, WaitingRoomClient.current(), WaitingRoomClient.required(), WaitingRoomClient.missing());
        if (skipKeyDown && WaitingRoomClient.canLeave() && holdTicks > SHORT_PRESS_TICKS) {
            int barWidth = 80;
            int x = (width - barWidth) / 2;
            int y = height - 12;
            int filled = Math.round(barWidth * Math.min(1f, (holdTicks - SHORT_PRESS_TICKS) / (float) (HOLD_TICKS - SHORT_PRESS_TICKS)));
            guiGraphics.fill(x, y, x + filled, y + 3, 0xFFFFFFFF);
            if (holdTicks >= HOLD_TICKS) {
                guiGraphics.drawCenteredString(font, releaseHint, width / 2, y - 14, 0xFFFFFFFF);
            }
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        if (keyCode == FULLSCREEN_KEY) {
            return super.keyPressed(event);
        }
        boolean skipKey = skipKeyIsEsc ? keyCode == ESCAPE_KEY : ConditionalVideosKeybinds.SKIP.matches(event);
        if (skipKey && WaitingRoomClient.canLeave()) {
            if (!skipKeyDown) {
                skipKeyDown = true;
                holdTicks = 0;
            }
        } else if (keyCode == ESCAPE_KEY) {
            minecraft.setScreen(new PauseScreen(true));
        } else if (keyCode == SPACE_KEY && WaitingRoomClient.canStart()) {
            PlaybackControlNetworking.sendRoomStart();
        } else if (WaitingRoomClient.canChat() && minecraft.options.keyChat.matches(event)) {
            minecraft.setScreen(new ChatScreen("", false));
        }
        return true;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        int keyCode = event.key();
        boolean skipKey = skipKeyIsEsc ? keyCode == ESCAPE_KEY : ConditionalVideosKeybinds.SKIP.matches(event);
        if (skipKey && skipKeyDown) {
            boolean filled = holdTicks >= HOLD_TICKS;
            boolean shortPress = holdTicks <= SHORT_PRESS_TICKS;
            skipKeyDown = false;
            holdTicks = 0;
            if (filled && WaitingRoomClient.canLeave()) {
                PlaybackControlNetworking.sendRoomLeave();
            } else if (shortPress && skipKeyIsEsc) {
                minecraft.setScreen(new PauseScreen(true));
            }
            return true;
        }
        return super.keyReleased(event);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
