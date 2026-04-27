package org.mateof24.conditionalvideos.network;

import dev.architectury.injectables.annotations.ExpectPlatform;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.function.Consumer;

public final class NetworkHelper {
    private NetworkHelper() {
    }

    public record RawS2C(ResourceLocation id, byte[] data) implements CustomPacketPayload {
        public static final Type<RawS2C> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath("conditionalvideos", "raw_s2c"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RawS2C> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.id().getNamespace());
                    buf.writeUtf(p.id().getPath());
                    buf.writeVarInt(p.data().length);
                    buf.writeBytes(p.data());
                },
                buf -> {
                    ResourceLocation id = ResourceLocation.fromNamespaceAndPath(buf.readUtf(), buf.readUtf());
                    int len = buf.readVarInt();
                    byte[] data = new byte[len];
                    buf.readBytes(data);
                    return new RawS2C(id, data);
                }
        );

        public Type<RawS2C> type() {
            return TYPE;
        }
    }

    public record RawC2S(ResourceLocation id, byte[] data) implements CustomPacketPayload {
        public static final Type<RawC2S> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath("conditionalvideos", "raw_c2s"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RawC2S> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.id().getNamespace());
                    buf.writeUtf(p.id().getPath());
                    buf.writeVarInt(p.data().length);
                    buf.writeBytes(p.data());
                },
                buf -> {
                    ResourceLocation id = ResourceLocation.fromNamespaceAndPath(buf.readUtf(), buf.readUtf());
                    int len = buf.readVarInt();
                    byte[] data = new byte[len];
                    buf.readBytes(data);
                    return new RawC2S(id, data);
                }
        );

        public Type<RawC2S> type() {
            return TYPE;
        }
    }

    @FunctionalInterface
    public interface S2CPacketHandler {
        void handle(byte[] data);
    }

    @FunctionalInterface
    public interface C2SPacketHandler {
        void handle(byte[] data, ServerPlayer player);
    }

    @ExpectPlatform
    public static void registerPackets(
            Map<ResourceLocation, S2CPacketHandler> s2cHandlers,
            Map<ResourceLocation, C2SPacketHandler> c2sHandlers) {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static void registerS2CClientHandlers(Map<ResourceLocation, S2CPacketHandler> s2cHandlers) {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static void sendToPlayer(ServerPlayer player, ResourceLocation id, byte[] data) {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static void sendToServer(ResourceLocation id, byte[] data) {
        throw new AssertionError();
    }

    public static byte[] toBytes(Consumer<FriendlyByteBuf> writer) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        writer.accept(buf);
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
        return data;
    }

    public static FriendlyByteBuf fromBytes(byte[] data) {
        return new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
    }
}