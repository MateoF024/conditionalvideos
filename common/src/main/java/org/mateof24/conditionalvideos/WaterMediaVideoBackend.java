package org.mateof24.conditionalvideos;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Path;

final class WaterMediaVideoBackend {
    private final Path videoPath;

    private Object player;
    private Method textureMethod;
    private Method releaseMethod;

    WaterMediaVideoBackend(Path videoPath) {
        this.videoPath = videoPath;
    }

    void init() {
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

            startMethod.invoke(player, videoPath.toUri());
            ConditionalVideos.LOGGER.info("Playing first-join video with WATERMeDIA: {}", videoPath);
        } catch (Exception exception) {
            ConditionalVideos.LOGGER.warn("Failed to initialize WATERMeDIA playback backend: {}", exception.getMessage());
            player = null;
            textureMethod = null;
            releaseMethod = null;
        }
    }

    void render(int width, int height) {
        int textureId = getTextureId();
        if (textureId <= 0) {
            return;
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem._setShaderTexture(0, textureId);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        builder.vertex(0, height, 0).uv(0f, 1f).endVertex();
        builder.vertex(width, height, 0).uv(1f, 1f).endVertex();
        builder.vertex(width, 0, 0).uv(1f, 0f).endVertex();
        builder.vertex(0, 0, 0).uv(0f, 0f).endVertex();
        BufferUploader.drawWithShader(builder.end());
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

    void close() {
        if (player == null || releaseMethod == null) {
            return;
        }

        try {
            releaseMethod.invoke(player);
        } catch (Exception exception) {
            ConditionalVideos.LOGGER.warn("Failed to release WATERMeDIA player: {}", exception.getMessage());
        }
    }
}
