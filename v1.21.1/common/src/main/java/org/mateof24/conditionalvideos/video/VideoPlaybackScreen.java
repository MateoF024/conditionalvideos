package org.mateof24.conditionalvideos.video;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;
import org.mateof24.conditionalvideos.ConditionalVideos;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig;
import org.mateof24.conditionalvideos.config.ConditionalVideosConfig.VideoEntry;
import org.mateof24.conditionalvideos.video.backend.WaterMediaVideoBackend;
import org.watermedia.api.media.MRL;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class VideoPlaybackScreen extends Screen {
    private static final Component SKIP_HINT = Component.translatable("screen.conditionalvideos.skip_hint");
    private static final int HINT_VISIBLE_TICKS = 120;
    private static final int HINT_FADE_IN_TICKS = 30;
    private static final int HINT_FADE_OUT_TICKS = 40;
    private static final int TEXT_BOX_WIDTH = 220;
    private static final int TEXT_BOX_PADDING = 6;
    private static final int TEXT_BOX_MARGIN = 10;
    private static final int LINE_SPACING = 2;
    private static final int LEGACY_BOX_ALPHA_CAP = 100;

    private static final int CROSSFADE_TICKS = 40;
    private static final int CROSSFADE_WAIT_MAX_TICKS = 200;
    private static final int FIRST_FRAME_TIMEOUT_TICKS = 20 * 90;
    private static final int ESC_LONG_PRESS_TICKS = 20;
    private static final int ESCAPE_KEY = 256;
    private static final int CURSOR_HIDE_DELAY_TICKS = 100;
    private static final int LOOP_PRELOAD_LEAD_TICKS = 20 * 20;
    private static final int PRE_RESUME_LEAD_TICKS = 5;
    private static final int TRAILING_MAX_TICKS = 40;
    private static final double TRAILING_RELEASE_POSITION_SECONDS = 0.04;

    private final ConditionalVideosConfig config;
    private final List<VideoEntry> playlist;
    private final Function<VideoEntry, URI> sourceResolver;
    private final boolean playlistLoop;
    private final Runnable onPlaylistEnd;

    private State state = State.PLAYING;

    private int currentIndex;
    private VideoEntry currentEntry;
    private WaterMediaVideoBackend currentBackend;
    private boolean currentFirstFrameSeen;
    private int currentEntryTicks;
    private int hintTicks;

    private Component currentTitle;
    private TextAnchor currentTitleAnchor;
    private float currentTitleScale;
    private Component currentDescription;
    private TextAnchor currentDescriptionAnchor;
    private float currentDescriptionScale;
    private float currentTextBoxOpacity;
    private boolean currentEnableBackground;
    private int currentBackgroundColor;

    private int nextIndex = -1;
    private VideoEntry nextEntry;
    private WaterMediaVideoBackend nextBackend;
    private int waitingForNextTicks;

    private int skipNextIndex = -1;
    private VideoEntry skipNextEntry;
    private WaterMediaVideoBackend skipNextBackend;

    private WaterMediaVideoBackend trailingBackend;
    private int trailingBackendTicks;
    private WaterMediaVideoBackend preResumedBackend;

    private int crossfadeTicks;

    private boolean closed;
    private boolean currentBackendInitialised;
    private int firstFrameWaitTicks;
    private int escHoldTicks;
    private boolean wasEscDownLastTick;
    private int consecutiveFailures;

    private double lastMouseX = Double.NaN;
    private double lastMouseY = Double.NaN;
    private int ticksSinceMouseMoved;
    private boolean cursorHidden;

    public VideoPlaybackScreen(
            ConditionalVideosConfig config,
            ConditionalVideosConfig.ConditionConfig conditionConfig,
            List<VideoEntry> playlist,
            Function<VideoEntry, URI> sourceResolver,
            URI firstSourceUri,
            Runnable onPlaylistEnd
    ) {
        super(Component.literal("Conditional Video"));
        this.config = config;
        this.playlist = playlist;
        this.sourceResolver = sourceResolver;
        this.playlistLoop = conditionConfig.resolvedPlaylistLoop();
        this.onPlaylistEnd = onPlaylistEnd == null ? () -> { } : onPlaylistEnd;

        this.currentIndex = 0;
        this.currentEntry = playlist.get(0);
        applyCurrentEntryVisuals();
        this.currentBackend = new WaterMediaVideoBackend(firstSourceUri, currentEntry.resolvedVolume());

        warmAllPlaylistMrls(firstSourceUri);
    }

    private void warmAllPlaylistMrls(URI firstSourceUri) {
        List<URI> uris = new ArrayList<>(playlist.size());
        uris.add(firstSourceUri);
        for (int i = 1; i < playlist.size(); i++) {
            URI uri = sourceResolver.apply(playlist.get(i));
            if (uri != null) {
                uris.add(uri);
            }
        }
        if (uris.isEmpty()) {
            return;
        }
        try {
            MRL.preload(uris.toArray(new URI[0]));
        } catch (Throwable t) {
            ConditionalVideos.LOGGER.debug("MRL.preload failed: {}", t.toString());
        }
    }

    private void applyCurrentEntryVisuals() {
        currentTitle = LegacyFormatParser.parse(currentEntry.resolvedVideoTitle());
        currentTitleAnchor = TextAnchor.fromConfig(currentEntry.resolvedVideoTitlePosition());
        currentTitleScale = currentEntry.resolvedTitleScale();
        currentDescription = LegacyFormatParser.parse(currentEntry.resolvedVideoDescription());
        currentDescriptionAnchor = TextAnchor.fromConfig(currentEntry.resolvedVideoDescriptionPosition());
        currentDescriptionScale = currentEntry.resolvedDescriptionScale();
        currentTextBoxOpacity = currentEntry.resolvedTextBoxOpacity();
        currentEnableBackground = currentEntry.resolvedEnableBackground();
        currentBackgroundColor = config.resolveBackgroundColor(currentEntry, 0xFF000000);
    }

    @Override
    protected void init() {
        if (!currentBackendInitialised) {
            ensureBackendInitialised(currentBackend);
            currentBackendInitialised = true;
        }
        VideoAudioState.setVideoPlaying(true);
        if (minecraft != null) {
            minecraft.getSoundManager().stop();
            wasEscDownLastTick = InputConstants.isKeyDown(minecraft.getWindow().getWindow(), ESCAPE_KEY);
        }
    }

    private boolean isEscapeDownNow() {
        return minecraft != null && InputConstants.isKeyDown(minecraft.getWindow().getWindow(), ESCAPE_KEY);
    }

    private void handleEscapePolling() {
        if (!isCurrentSkippable()) {
            wasEscDownLastTick = false;
            escHoldTicks = 0;
            return;
        }
        boolean escDown = isEscapeDownNow();
        if (escDown && !wasEscDownLastTick) {
            escHoldTicks = 1;
        } else if (escDown) {
            escHoldTicks++;
        } else if (wasEscDownLastTick) {
            if (state == State.PLAYING) {
                if (escHoldTicks >= ESC_LONG_PRESS_TICKS) {
                    escHoldTicks = 0;
                    wasEscDownLastTick = false;
                    onClose();
                    return;
                }
                if (escHoldTicks > 0) {
                    skipToNextEntry();
                }
            }
            escHoldTicks = 0;
        }
        wasEscDownLastTick = escDown;
    }

    private void advanceAfterFailure() {
        firstFrameWaitTicks = 0;
        consecutiveFailures++;
        if (consecutiveFailures >= playlist.size()) {
            ConditionalVideos.LOGGER.warn("All playlist entries failed to play; closing playback.");
            onClose();
            return;
        }
        int target = computeSkipTargetIndex();
        if (target < 0) {
            onClose();
            return;
        }
        if (nextBackend != null && nextIndex != target) {
            nextBackend.close();
            nextBackend = null;
            nextEntry = null;
            nextIndex = -1;
            waitingForNextTicks = 0;
        }
        if (nextBackend == null) {
            startPreload(target);
        }
        if (nextBackend == null) {
            onClose();
            return;
        }
        promoteNextEntry();
    }

    private void skipToNextEntry() {
        int target = computeSkipTargetIndex();
        if (target < 0) {
            onClose();
            return;
        }
        if (skipNextBackend != null && skipNextIndex == target) {
            if (nextBackend != null && nextBackend != skipNextBackend) {
                nextBackend.close();
            }
            nextBackend = skipNextBackend;
            nextEntry = skipNextEntry;
            nextIndex = skipNextIndex;
            skipNextBackend = null;
            skipNextEntry = null;
            skipNextIndex = -1;
            waitingForNextTicks = 0;
        } else if (nextBackend == null || nextIndex != target) {
            if (nextBackend != null) {
                nextBackend.close();
                nextBackend = null;
                nextEntry = null;
                nextIndex = -1;
            }
            waitingForNextTicks = 0;
            startPreload(target);
        }
        if (nextBackend == null) {
            onClose();
            return;
        }
        promoteNextEntry();
    }

    private int computeSkipTargetIndex() {
        int candidate;
        if (currentIndex + 1 < playlist.size()) {
            candidate = currentIndex + 1;
        } else if (playlistLoop) {
            candidate = 0;
        } else {
            return -1;
        }
        if (candidate == currentIndex) {
            return -1;
        }
        return candidate;
    }

    @Override
    public void tick() {
        if (closed) {
            return;
        }

        handleEscapePolling();
        if (closed) {
            return;
        }

        tickCursorAutoHide();

        currentBackend.tick();
        if (nextBackend != null) {
            nextBackend.tick();
        }
        if (skipNextBackend != null && skipNextBackend != nextBackend) {
            skipNextBackend.tick();
        }
        tickTrailingBackend();

        if (currentBackend.hasError()) {
            ConditionalVideos.LOGGER.warn("Playlist entry '{}' reported error; advancing.", currentEntry.source());
            advanceAfterFailure();
            return;
        }

        hintTicks++;
        if (!currentFirstFrameSeen && currentBackend.hasRenderedAnyFrame()) {
            currentFirstFrameSeen = true;
            currentEntryTicks = 0;
            firstFrameWaitTicks = 0;
            consecutiveFailures = 0;
        } else if (currentFirstFrameSeen) {
            currentEntryTicks++;
        } else {
            firstFrameWaitTicks++;
            if (firstFrameWaitTicks >= FIRST_FRAME_TIMEOUT_TICKS) {
                ConditionalVideos.LOGGER.warn("Playlist entry '{}' produced no frame in {}s; advancing.",
                        currentEntry.source(), FIRST_FRAME_TIMEOUT_TICKS / 20);
                advanceAfterFailure();
                return;
            }
        }

        switch (state) {
            case PLAYING -> handlePlaying();
            case CROSSFADING -> handleCrossfading();
            case OUTGOING_FADE -> handleOutgoingFade();
            case CLOSED -> { }
        }
    }

    private void handlePlaying() {
        int nextIdx = computeNextIndex();
        boolean hasNext = nextIdx >= 0;
        boolean isLoopIteration = hasNext && nextIdx == currentIndex;
        boolean isFade = hasNext && isFadeTransition(nextIdx);
        int endTick = computeCurrentEndTick();

        boolean inPreloadWindow;
        if (isLoopIteration) {
            inPreloadWindow = endTick > 0 && currentEntryTicks >= endTick - LOOP_PRELOAD_LEAD_TICKS;
        } else {
            inPreloadWindow = currentFirstFrameSeen;
        }

        if (hasNext && nextBackend == null && inPreloadWindow) {
            startPreload(nextIdx);
        }
        if (currentFirstFrameSeen) {
            ensureSkipPreload();
        }

        if (hasNext && !isFade && nextBackend != null && preResumedBackend != nextBackend
                && endTick > 0 && currentEntryTicks >= endTick - PRE_RESUME_LEAD_TICKS
                && nextBackend.hasTextureValid()) {
            nextBackend.resumePlayback();
            nextBackend.setVolumeMultiplier(0f);
            preResumedBackend = nextBackend;
        }

        if (!hasNext) {
            boolean naturallyEnded = currentBackend.hasFinished();
            boolean cueReached = endTick > 0 && currentEntryTicks >= endTick;
            if (naturallyEnded || cueReached) {
                state = State.OUTGOING_FADE;
                crossfadeTicks = 0;
            }
            return;
        }

        boolean reachedTransitionWindow;
        if (endTick > 0) {
            int triggerTick = endTick - (isFade ? CROSSFADE_TICKS : 0);
            reachedTransitionWindow = currentEntryTicks >= triggerTick;
        } else {
            reachedTransitionWindow = currentBackend.hasFinished();
        }

        if (!reachedTransitionWindow) {
            return;
        }

        if (nextBackend == null) {
            startPreload(nextIdx);
        }

        if (!isFade) {
            if (nextBackend != null && nextBackend.hasTextureValid()) {
                promoteNextEntry();
                return;
            }
            waitingForNextTicks++;
            if (waitingForNextTicks >= CROSSFADE_WAIT_MAX_TICKS) {
                ConditionalVideos.LOGGER.warn("Next playlist entry '{}' did not load in time; promoting anyway.",
                        nextEntry == null ? "<unresolved>" : nextEntry.source());
                promoteNextEntry();
            }
            return;
        }

        if (nextBackend != null && nextBackend.isReadyToRender()) {
            enterCrossfading();
            return;
        }
        waitingForNextTicks++;
        if (waitingForNextTicks >= CROSSFADE_WAIT_MAX_TICKS) {
            ConditionalVideos.LOGGER.warn("Next playlist entry '{}' did not produce a frame in time; cutting without fade.",
                    nextEntry == null ? "<unresolved>" : nextEntry.source());
            promoteNextEntry();
        }
    }

    private void handleCrossfading() {
        crossfadeTicks++;
        float progress = Math.min(1f, crossfadeTicks / (float) CROSSFADE_TICKS);
        currentBackend.setVolumeMultiplier(1f - progress);
        if (nextBackend != null) {
            nextBackend.setVolumeMultiplier(progress);
        }
        if (crossfadeTicks >= CROSSFADE_TICKS) {
            promoteNextEntry();
        }
    }

    private void handleOutgoingFade() {
        crossfadeTicks++;
        float progress = Math.min(1f, crossfadeTicks / (float) CROSSFADE_TICKS);
        currentBackend.setVolumeMultiplier(1f - progress);
        if (crossfadeTicks >= CROSSFADE_TICKS) {
            onClose();
        }
    }

    private void ensureSkipPreload() {
        int target = computeSkipTargetIndex();
        if (target < 0) {
            disposeSkipPreload();
            return;
        }
        if (nextBackend != null && nextIndex == target) {
            disposeSkipPreload();
            return;
        }
        if (skipNextBackend != null && skipNextIndex == target) {
            return;
        }
        disposeSkipPreload();
        VideoEntry entry = playlist.get(target);
        URI uri = sourceResolver.apply(entry);
        if (uri == null) {
            return;
        }
        skipNextIndex = target;
        skipNextEntry = entry;
        skipNextBackend = new WaterMediaVideoBackend(uri, entry.resolvedVolume());
        skipNextBackend.setVolumeMultiplier(0f);
        skipNextBackend.setStartPaused(true);
        ensureBackendInitialised(skipNextBackend);
    }

    private void disposeSkipPreload() {
        if (skipNextBackend != null) {
            try {
                skipNextBackend.close();
            } catch (Throwable ignored) {
            }
            skipNextBackend = null;
        }
        skipNextEntry = null;
        skipNextIndex = -1;
    }

    private void startPreload(int targetIndex) {
        VideoEntry entry = playlist.get(targetIndex);
        URI uri = sourceResolver.apply(entry);
        if (uri == null) {
            ConditionalVideos.LOGGER.warn("Cannot preload playlist entry {} ('{}'): source not resolvable. Skipping.",
                    targetIndex, entry.source());
            int skipNext = computeNextIndexAfter(targetIndex);
            if (skipNext < 0 || skipNext == targetIndex) {
                return;
            }
            startPreload(skipNext);
            return;
        }
        nextIndex = targetIndex;
        nextEntry = entry;
        nextBackend = new WaterMediaVideoBackend(uri, entry.resolvedVolume());
        nextBackend.setVolumeMultiplier(0f);
        nextBackend.setStartPaused(true);
        ensureBackendInitialised(nextBackend);
    }

    private void enterCrossfading() {
        if (nextBackend != null) {
            nextBackend.resumePlayback();
        }
        state = State.CROSSFADING;
        crossfadeTicks = 0;
        waitingForNextTicks = 0;
    }

    private void promoteNextEntry() {
        boolean fromCrossfade = state == State.CROSSFADING;
        performSwap(fromCrossfade);
    }

    private void performSwap(boolean fromCrossfade) {
        boolean loopSwap = nextIndex == currentIndex;

        WaterMediaVideoBackend previous = currentBackend;

        if (nextBackend == null) {
            if (previous != null) {
                try { previous.close(); } catch (Throwable ignored) { }
            }
            onClose();
            return;
        }

        boolean wasPreResumed = preResumedBackend == nextBackend;
        if (!wasPreResumed && !fromCrossfade) {
            nextBackend.resumePlayback();
        }
        preResumedBackend = null;

        currentBackend = nextBackend;
        currentEntry = nextEntry;
        currentIndex = nextIndex;
        if (!loopSwap) {
            applyCurrentEntryVisuals();
            hintTicks = 0;
        }
        currentFirstFrameSeen = currentBackend.hasRenderedAnyFrame() || currentBackend.hasTextureValid();
        currentEntryTicks = 0;
        firstFrameWaitTicks = 0;
        currentBackend.setVolumeMultiplier(1f);

        if (trailingBackend != null && trailingBackend != previous) {
            try { trailingBackend.close(); } catch (Throwable ignored) { }
        }
        if (previous != null && previous != currentBackend) {
            trailingBackend = previous;
            trailingBackendTicks = 0;
            try { trailingBackend.setVolumeMultiplier(0f); } catch (Throwable ignored) { }
        } else {
            trailingBackend = null;
        }

        nextBackend = null;
        nextEntry = null;
        nextIndex = -1;
        waitingForNextTicks = 0;

        state = State.PLAYING;
        crossfadeTicks = 0;
    }

    private boolean isFadeTransition(int nextIdx) {
        return ConditionalVideosConfig.TRANSITION_FADE.equals(playlist.get(nextIdx).resolvedTransition());
    }

    private int computeNextIndex() {
        if (currentEntry != null && currentEntry.resolvedVideoLoop()) {
            return currentIndex;
        }
        if (currentIndex + 1 < playlist.size()) {
            return currentIndex + 1;
        }
        if (playlistLoop) {
            return 0;
        }
        return -1;
    }

    private int computeNextIndexAfter(int idx) {
        if (idx + 1 < playlist.size()) {
            return idx + 1;
        }
        if (playlistLoop) {
            return 0;
        }
        return -1;
    }

    private int computeCurrentEndTick() {
        Float nextAt = currentEntry.resolvedNextAt();
        if (nextAt != null) {
            return Math.round(nextAt * 20f);
        }
        double duration = currentBackend.durationSeconds();
        if (duration > 0d) {
            return (int) Math.round(duration * 20d);
        }
        return -1;
    }

    private void ensureBackendInitialised(WaterMediaVideoBackend backend) {
        try {
            backend.init();
        } catch (Throwable throwable) {
            ConditionalVideos.LOGGER.warn("Failed to init backend for '{}': {}", backend.source(), throwable.toString());
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        WaterMediaVideoBackend renderTarget = chooseRenderTarget();

        if (!currentFirstFrameSeen && renderTarget == currentBackend) {
            if (currentBackend.hasRenderedAnyFrame() && currentEnableBackground) {
                guiGraphics.fill(0, 0, width, height, currentBackgroundColor);
            } else {
                VideoLoadingOverlay.render(guiGraphics, font, width, height);
            }
            currentBackend.render(width, height, 1f);
            return;
        }

        if (currentEnableBackground) {
            guiGraphics.fill(0, 0, width, height, currentBackgroundColor);
        }

        if (state == State.CROSSFADING && nextBackend != null) {
            float progress = Math.min(1f, crossfadeTicks / (float) CROSSFADE_TICKS);
            currentBackend.render(width, height, 1f - progress);
            nextBackend.render(width, height, progress);
        } else {
            renderTarget.render(width, height, 1f);
        }

        int hintAlpha = calculateHintAlpha();
        if (hintAlpha > 0 && state != State.OUTGOING_FADE) {
            int x = width / 2;
            int y = height - 24;
            int color = (hintAlpha << 24) | 0x00FFFFFF;
            guiGraphics.drawCenteredString(font, SKIP_HINT, x, y, color);
            renderVideoMetadata(guiGraphics, hintAlpha);
        }

        if (state == State.OUTGOING_FADE) {
            float progress = Math.min(1f, crossfadeTicks / (float) CROSSFADE_TICKS);
            int alpha = Math.round(255f * progress);
            if (alpha > 0) {
                guiGraphics.fill(0, 0, width, height, (alpha << 24));
            }
        }
    }

    private void renderVideoMetadata(GuiGraphics guiGraphics, int alpha) {
        boolean hasTitle = !currentTitle.getString().isBlank();
        boolean hasDescription = !currentDescription.getString().isBlank();
        if (!hasTitle && !hasDescription) {
            return;
        }

        if (hasTitle && hasDescription && currentTitleAnchor == currentDescriptionAnchor) {
            drawCombinedTextBlock(guiGraphics, alpha);
            return;
        }

        if (hasTitle) {
            drawTextBlock(guiGraphics, currentTitle, currentTitleAnchor, currentTitleScale, alpha);
        }
        if (hasDescription) {
            drawTextBlock(guiGraphics, currentDescription, currentDescriptionAnchor, currentDescriptionScale, alpha);
        }
    }

    private void drawTextBlock(GuiGraphics guiGraphics, Component text, TextAnchor anchor, float scale, int alpha) {
        int innerWidth = Math.max(1, Math.round((TEXT_BOX_WIDTH - TEXT_BOX_PADDING * 2) / scale));
        List<FormattedCharSequence> lines = font.split(text, innerWidth);
        if (lines.isEmpty()) {
            return;
        }

        int baseTextHeight = lines.size() * (font.lineHeight + LINE_SPACING) - LINE_SPACING;
        int scaledTextHeight = Math.round(baseTextHeight * scale);
        int boxHeight = scaledTextHeight + TEXT_BOX_PADDING * 2;

        int x = anchor.isRightAligned()
                ? width - TEXT_BOX_MARGIN - TEXT_BOX_WIDTH
                : TEXT_BOX_MARGIN;
        int y = anchor.resolveY(height, boxHeight);

        fillTextBox(guiGraphics, x, y, x + TEXT_BOX_WIDTH, y + boxHeight, alpha);

        drawScaledLines(guiGraphics, lines, x + TEXT_BOX_PADDING, y + TEXT_BOX_PADDING, scale, alpha);
    }

    private void drawCombinedTextBlock(GuiGraphics guiGraphics, int alpha) {
        int titleInnerWidth = Math.max(1, Math.round((TEXT_BOX_WIDTH - TEXT_BOX_PADDING * 2) / currentTitleScale));
        int descInnerWidth = Math.max(1, Math.round((TEXT_BOX_WIDTH - TEXT_BOX_PADDING * 2) / currentDescriptionScale));
        List<FormattedCharSequence> titleLines = font.split(currentTitle, titleInnerWidth);
        List<FormattedCharSequence> descLines = font.split(currentDescription, descInnerWidth);

        int titleBlockHeight = Math.round((titleLines.size() * (font.lineHeight + LINE_SPACING) - LINE_SPACING) * currentTitleScale);
        int descBlockHeight = Math.round((descLines.size() * (font.lineHeight + LINE_SPACING) - LINE_SPACING) * currentDescriptionScale);
        int gap = Math.round((font.lineHeight + LINE_SPACING) * Math.max(currentTitleScale, currentDescriptionScale));
        int boxHeight = titleBlockHeight + gap + descBlockHeight + TEXT_BOX_PADDING * 2;

        int x = currentTitleAnchor.isRightAligned()
                ? width - TEXT_BOX_MARGIN - TEXT_BOX_WIDTH
                : TEXT_BOX_MARGIN;
        int y = currentTitleAnchor.resolveY(height, boxHeight);

        fillTextBox(guiGraphics, x, y, x + TEXT_BOX_WIDTH, y + boxHeight, alpha);

        drawScaledLines(guiGraphics, titleLines, x + TEXT_BOX_PADDING, y + TEXT_BOX_PADDING, currentTitleScale, alpha);
        int descTopY = y + TEXT_BOX_PADDING + titleBlockHeight + gap;
        drawScaledLines(guiGraphics, descLines, x + TEXT_BOX_PADDING, descTopY, currentDescriptionScale, alpha);
    }

    private void drawScaledLines(GuiGraphics guiGraphics, List<FormattedCharSequence> lines, int originX, int originY, float scale, int alpha) {
        int textColor = (alpha << 24) | 0x00FFFFFF;
        int unscaledLineHeight = font.lineHeight + LINE_SPACING;
        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(originX, originY, 0);
        pose.scale(scale, scale, 1.0f);
        for (int i = 0; i < lines.size(); i++) {
            guiGraphics.drawString(font, lines.get(i), 0, i * unscaledLineHeight, textColor);
        }
        pose.popPose();
    }

    private void fillTextBox(GuiGraphics guiGraphics, int left, int top, int right, int bottom, int textAlpha) {
        int boxAlpha = resolveBoxAlpha(textAlpha);
        if (boxAlpha <= 0) {
            return;
        }
        guiGraphics.fill(left, top, right, bottom, boxAlpha << 24);
    }

    private int resolveBoxAlpha(int textAlpha) {
        if (currentTextBoxOpacity < 0f) {
            return Math.min(textAlpha, LEGACY_BOX_ALPHA_CAP);
        }
        int cap = Math.round(currentTextBoxOpacity * 255f);
        return Math.min(textAlpha, cap);
    }

    private int calculateHintAlpha() {
        if (hintTicks >= HINT_VISIBLE_TICKS) {
            return 0;
        }
        if (hintTicks < HINT_FADE_IN_TICKS) {
            return (int) (255.0F * hintTicks / HINT_FADE_IN_TICKS);
        }
        int fadeOutStartTick = HINT_VISIBLE_TICKS - HINT_FADE_OUT_TICKS;
        if (hintTicks >= fadeOutStartTick) {
            int ticksIntoFadeOut = hintTicks - fadeOutStartTick;
            return (int) (255.0F * (HINT_FADE_OUT_TICKS - ticksIntoFadeOut) / HINT_FADE_OUT_TICKS);
        }
        return 255;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 300) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (keyCode == ESCAPE_KEY) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(mouseX, mouseY);
        if (Double.isNaN(lastMouseX) || mouseX != lastMouseX || mouseY != lastMouseY) {
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            ticksSinceMouseMoved = 0;
            if (cursorHidden) {
                setCursorVisibility(true);
            }
        }
    }

    private void tickTrailingBackend() {
        if (trailingBackend == null) {
            return;
        }
        trailingBackend.tick();
        trailingBackendTicks++;
        boolean newReady = false;
        try {
            double pos = currentBackend.positionSeconds();
            newReady = pos >= TRAILING_RELEASE_POSITION_SECONDS;
        } catch (Throwable ignored) {
        }
        if (newReady || trailingBackendTicks >= TRAILING_MAX_TICKS) {
            try {
                trailingBackend.close();
            } catch (Throwable ignored) {
            }
            trailingBackend = null;
        }
    }

    private WaterMediaVideoBackend chooseRenderTarget() {
        if (trailingBackend == null) {
            return currentBackend;
        }
        boolean newHasTexture = currentBackend.hasTextureValid();
        boolean newHasContent;
        try {
            newHasContent = currentBackend.positionSeconds() >= TRAILING_RELEASE_POSITION_SECONDS;
        } catch (Throwable ignored) {
            newHasContent = false;
        }
        if (newHasTexture && newHasContent) {
            return currentBackend;
        }
        if (trailingBackend.hasTextureValid()) {
            return trailingBackend;
        }
        return currentBackend;
    }

    private void tickCursorAutoHide() {
        if (cursorHidden) {
            return;
        }
        ticksSinceMouseMoved++;
        if (ticksSinceMouseMoved >= CURSOR_HIDE_DELAY_TICKS) {
            setCursorVisibility(false);
        }
    }

    private void setCursorVisibility(boolean visible) {
        if (minecraft == null) {
            return;
        }
        long window = minecraft.getWindow().getWindow();
        int mode = visible ? GLFW.GLFW_CURSOR_NORMAL : GLFW.GLFW_CURSOR_HIDDEN;
        try {
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, mode);
        } catch (Throwable t) {
            ConditionalVideos.LOGGER.debug("Failed to set cursor visibility ({}): {}", visible, t.toString());
            return;
        }
        cursorHidden = !visible;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    private boolean isCurrentSkippable() {
        return currentEntry.resolvedSkippable();
    }

    @Override
    public void onClose() {
        if (closed) {
            return;
        }
        closed = true;
        state = State.CLOSED;
        if (cursorHidden) {
            setCursorVisibility(true);
        }
        if (currentBackend != null) {
            currentBackend.close();
        }
        if (nextBackend != null) {
            nextBackend.close();
            nextBackend = null;
        }
        if (trailingBackend != null) {
            try { trailingBackend.close(); } catch (Throwable ignored) { }
            trailingBackend = null;
        }
        disposeSkipPreload();
        VideoAudioState.setVideoPlaying(false);
        onPlaylistEnd.run();
        if (minecraft != null && minecraft.screen == this) {
            minecraft.setScreen(null);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private enum State {
        PLAYING,
        CROSSFADING,
        OUTGOING_FADE,
        CLOSED
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
