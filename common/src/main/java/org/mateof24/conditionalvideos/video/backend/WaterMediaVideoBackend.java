package org.mateof24.conditionalvideos.video.backend;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import org.mateof24.conditionalvideos.ConditionalVideos;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Path;

public final class WaterMediaVideoBackend {
    private static final float DEFAULT_VIDEO_ASPECT_RATIO = 16.0F / 9.0F;

    private final Path videoPath;

    private Object player;
    private Method textureMethod;
    private Method releaseMethod;
    private Method videoWidthMethod;
    private Method videoHeightMethod;

    public WaterMediaVideoBackend(Path videoPath) {
        this.videoPath = videoPath;
    }

    public void init() {
        try {
            Class<?> playerApiClass = Class.forName("org.watermedia.api.player.PlayerAPI");
            Method isReadyMethod = playerApiClass.getMethod("isReady");
            boolean ready = (boolean) isReadyMethod.invoke(null);
            if (!ready) {
                ConditionalVideos.LOGGER.warn("WATERMeDIA PlayerAPI is not ready (VLC not initialized/found). Video playback skipped.");
                return;
            }

            Class<?> playerClass = Class.forName("org.watermedia.api.player.videolan.VideoPlayer");
            Constructor<?> constructor = playerClass.getConstructor(java.util.concurrent.Executor.class);
            player = constructor.newInstance(net.minecraft.client.Minecraft.getInstance());

            Method startMethod = playerClass.getMethod("start", URI.class);
            textureMethod = playerClass.getMethod("texture");
            releaseMethod = playerClass.getMethod("release");
            videoWidthMethod = resolveDimensionMethod(playerClass,
                    "videoWidth", "getVideoWidth", "width", "getWidth");
            videoHeightMethod = resolveDimensionMethod(playerClass,
                    "videoHeight", "getVideoHeight", "height", "getHeight");

            startMethod.invoke(player, videoPath.toUri());
            ConditionalVideos.LOGGER.info("Playing first-join video with WATERMeDIA: {}", videoPath);
        } catch (Exception exception) {
            ConditionalVideos.LOGGER.warn("Failed to initialize WATERMeDIA playback backend: {}", exception.getMessage());
            player = null;
            textureMethod = null;
            releaseMethod = null;
            videoWidthMethod = null;
            videoHeightMethod = null;
        }
    }

    public void render(int width, int height) {
        int textureId = getTextureId();
        if (textureId <= 0) {
            return;
        }

        RenderBounds renderBounds = calculateRenderBounds(width, height);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem._setShaderTexture(0, textureId);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        builder.vertex(renderBounds.left(), renderBounds.bottom(), 0).uv(0f, 1f).endVertex();
        builder.vertex(renderBounds.right(), renderBounds.bottom(), 0).uv(1f, 1f).endVertex();
        builder.vertex(renderBounds.right(), renderBounds.top(), 0).uv(1f, 0f).endVertex();
        builder.vertex(renderBounds.left(), renderBounds.top(), 0).uv(0f, 0f).endVertex();
        BufferUploader.drawWithShader(builder.end());
    }

    private RenderBounds calculateRenderBounds(int screenWidth, int screenHeight) {
        float videoAspectRatio = resolveVideoAspectRatio();
        float screenAspectRatio = (float) screenWidth / (float) screenHeight;

        int drawWidth = screenWidth;
        int drawHeight = screenHeight;
        int left = 0;
        int top = 0;

        if (screenAspectRatio > videoAspectRatio) {
            drawWidth = Math.round(screenHeight * videoAspectRatio);
            left = (screenWidth - drawWidth) / 2;
        } else if (screenAspectRatio < videoAspectRatio) {
            drawHeight = Math.round(screenWidth / videoAspectRatio);
            top = (screenHeight - drawHeight) / 2;
        }

        return new RenderBounds(left, top, left + drawWidth, top + drawHeight);
    }

    private float resolveVideoAspectRatio() {
        int videoWidth = invokeDimensionMethod(videoWidthMethod);
        int videoHeight = invokeDimensionMethod(videoHeightMethod);
        if (videoWidth > 0 && videoHeight > 0) {
            return (float) videoWidth / (float) videoHeight;
        }
        return DEFAULT_VIDEO_ASPECT_RATIO;
    }

    private int invokeDimensionMethod(Method method) {
        if (player == null || method == null) {
            return -1;
        }

        try {
            Object result = method.invoke(player);
            if (result instanceof Number number) {
                return number.intValue();
            }
        } catch (Exception ignored) {
            return -1;
        }

        return -1;
    }

    private Method resolveDimensionMethod(Class<?> playerClass, String... methodNames) {
        for (String methodName : methodNames) {
            try {
                Method method = playerClass.getMethod(methodName);
                if (Number.class.isAssignableFrom(method.getReturnType()) || method.getReturnType() == int.class) {
                    return method;
                }
            } catch (NoSuchMethodException ignored) {
                // Try next candidate.
            }
        }

        return null;
    }

    private int getTextureId() {
        if (player == null || textureMethod == null) {
            return -1;
        }

        try {
            return (int) textureMethod.invoke(player);
        } catch (Exception exception) {
            ConditionalVideos.LOGGER.warn("Failed to fetch WATERMeDIA texture: {}", exception.getMessage());
            return -1;
        }
    }

    public void close() {
        if (player == null || releaseMethod == null) {
            return;
        }

        try {
            releaseMethod.invoke(player);
        } catch (Exception exception) {
            ConditionalVideos.LOGGER.warn("Failed to release WATERMeDIA player: {}", exception.getMessage());
        }
    }

    private record RenderBounds(int left, int top, int right, int bottom) {
    }
}