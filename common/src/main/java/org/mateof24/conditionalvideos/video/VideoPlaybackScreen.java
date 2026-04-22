package org.mateof24.conditionalvideos.video;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.sounds.SoundSource;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.mateof24.conditionalvideos.video.backend.WaterMediaVideoBackend;

import java.lang.reflect.Method;
import java.util.EnumMap;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

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
    private boolean backendInitialized;
    private final EnumMap<SoundSource, Float> previousVolumes = new EnumMap<>(SoundSource.class);
    private final boolean enableBackground;
    private final int backgroundColor;
    private final Component videoTitle;
    private final TextAnchor videoTitleAnchor;
    private final Component videoDescription;
    private final TextAnchor videoDescriptionAnchor;
    private final Runnable onClosed;
    private boolean closed;

    public VideoPlaybackScreen(
            Path videoPath,
            boolean skippable,
            boolean enableBackground,
            int backgroundColor,
            String videoTitle,
            String videoTitlePosition,
            String videoDescription,
            String videoDescriptionPosition,
            Runnable onClosed
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
        this.onClosed = onClosed == null ? () -> { } : onClosed;
    }

    @Override
    protected void init() {
        if (!backendInitialized) {
            backend.init();
            backendInitialized = true;
        }
        pauseWorldAudio();
        elapsedTicks = 0;
        if (backend.hasFinished()) {
            onClose();
            return;
        }
        enforceAudioMute();
    }

    @Override
    public void tick() {
        elapsedTicks++;
        enforceAudioMute();
        if (backend.hasFinished()) {
            onClose();
        }
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
        // Prevent fullscreen toggles while VLC/WATERMeDIA is active. Recreating/resizing the
        // output target during playback can crash the process on some setups.
        if (keyCode == 300) {
            return true;
        }
        if (skippable && keyCode == 256) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (closed) {
            return;
        }
        closed = true;
        backend.close();
        resumeWorldAudio();
        onClosed.run();
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

        previousVolumes.clear();
        for (SoundSource source : SoundSource.values()) {
            previousVolumes.put(source, minecraft.options.getSoundSourceVolume(source));
            setSoundSourceVolume(source, 0.0F);
            applyVolumeToSoundManager(source, 0.0F);
        }
        invokeNoArgMethod(minecraft.getSoundManager(), "pause");
        invokeNoArgMethod(minecraft.getSoundManager(), "pauseAll");
        minecraft.getSoundManager().stop();
        worldAudioPaused = true;
    }

    private void resumeWorldAudio() {
        if (minecraft == null || !worldAudioPaused) {
            return;
        }

        for (SoundSource source : SoundSource.values()) {
            Float value = previousVolumes.get(source);
            float resolved = value == null ? 1.0F : value;
            setSoundSourceVolume(source, resolved);
            applyVolumeToSoundManager(source, resolved);
        }
        invokeNoArgMethod(minecraft.getSoundManager(), "resume");
        invokeNoArgMethod(minecraft.getSoundManager(), "resumeAll");
        previousVolumes.clear();
        worldAudioPaused = false;
    }

    private void enforceAudioMute() {
        if (minecraft == null || !worldAudioPaused) {
            return;
        }
        for (SoundSource source : SoundSource.values()) {
            if (minecraft.options.getSoundSourceVolume(source) > 0.0F) {
                setSoundSourceVolume(source, 0.0F);
            }
            applyVolumeToSoundManager(source, 0.0F);
        }
    }

    private void applyVolumeToSoundManager(SoundSource source, float value) {
        if (minecraft == null) {
            return;
        }
        Object soundManager = minecraft.getSoundManager();
        invokeSoundManagerSetter(soundManager, "updateSourceVolume", source, value);
        invokeSoundManagerSetter(soundManager, "setSoundCategoryVolume", source, value);
        invokeSoundManagerSetter(soundManager, "setSoundSourceVolume", source, value);
    }

    private void invokeSoundManagerSetter(Object target, String methodName, SoundSource source, float value) {
        try {
            for (Method method : target.getClass().getMethods()) {
                if (!Objects.equals(method.getName(), methodName) || method.getParameterCount() != 2) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params[0] != SoundSource.class) {
                    continue;
                }
                if (params[1] == float.class || params[1] == Float.class) {
                    method.invoke(target, source, value);
                    return;
                }
                if (params[1] == double.class || params[1] == Double.class) {
                    method.invoke(target, source, (double) value);
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void stopAndPauseAllGameAudio() {
        if (minecraft == null) {
            return;
        }
        minecraft.getSoundManager().stop();
        invokeNoArgMethod(minecraft.getSoundManager(), "pause");
        invokeNoArgMethod(minecraft.getSoundManager(), "pauseAll");
    }

    private void setSoundSourceVolume(SoundSource source, float value) {
        if (minecraft == null) {
            return;
        }

        Object options = minecraft.options;
        if (invokeSetter(options, "setSoundCategoryVolume", source, value)
                || invokeSetter(options, "setSoundCategoryVolume", source, (double) value)
                || invokeSetter(options, "setSoundSourceVolume", source, value)
                || invokeSetter(options, "setSoundSourceVolume", source, (double) value)) {
            return;
        }

        try {
            Method getOptionMethod = options.getClass().getMethod("getSoundSourceOptionInstance", SoundSource.class);
            Object optionInstance = getOptionMethod.invoke(options, source);
            if (optionInstance == null) {
                return;
            }

            for (Method method : optionInstance.getClass().getMethods()) {
                if (!"set".equals(method.getName()) || method.getParameterCount() != 1) {
                    continue;
                }
                Class<?> paramType = method.getParameterTypes()[0];
                if (paramType == double.class || paramType == Double.class) {
                    method.invoke(optionInstance, (double) value);
                    return;
                }
                if (paramType == float.class || paramType == Float.class) {
                    method.invoke(optionInstance, value);
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private boolean invokeSetter(Object target, String methodName, SoundSource source, Object value) {
        try {
            for (Method method : target.getClass().getMethods()) {
                if (!methodName.equals(method.getName()) || method.getParameterCount() != 2) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params[0] != SoundSource.class) {
                    continue;
                }

                if (isCompatibleNumberParam(params[1], value)) {
                    Number number = (Number) value;
                    if (params[1] == double.class || params[1] == Double.class) {
                        method.invoke(target, source, number.doubleValue());
                    } else if (params[1] == float.class || params[1] == Float.class) {
                        method.invoke(target, source, number.floatValue());
                    } else {
                        method.invoke(target, source, value);
                    }
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private boolean isCompatibleNumberParam(Class<?> paramType, Object value) {
        if (!(value instanceof Number)) {
            return false;
        }
        return paramType == float.class
                || paramType == Float.class
                || paramType == double.class
                || paramType == Double.class
                || Number.class.isAssignableFrom(paramType);
    }

    private void invokeNoArgMethod(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            method.invoke(target);
        } catch (Exception ignored) {
        }
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