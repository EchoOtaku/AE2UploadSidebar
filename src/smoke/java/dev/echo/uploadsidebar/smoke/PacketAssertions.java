package dev.echo.uploadsidebar.smoke;

import dev.echo.uploadsidebar.net.SidebarPackets;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Actual registry-backed wire-codec checks, excluded from release builds. */
final class PacketAssertions {
    static void verify(ServerPlayer player, ItemStack pattern) {
        var query = new SidebarPackets.Query(7, 999);
        require(query.equals(roundTrip(player, SidebarPackets.Query.CODEC, query)), "query round-trip");
        var result = new SidebarPackets.Result(7, 1000, false, "目标已失效");
        require(result.equals(roundTrip(player, SidebarPackets.Result.CODEC, result)), "result round-trip");
        UUID token = UUID.randomUUID();
        var upload = new SidebarPackets.Upload(7, 1001, token, pattern.copyWithCount(1));
        var decodedUpload = roundTrip(player, SidebarPackets.Upload.CODEC, upload);
        require(decodedUpload.menuId() == 7 && decodedUpload.operationId() == 1001
                && token.equals(decodedUpload.token())
                && ItemStack.matches(upload.expectedPattern(), decodedUpload.expectedPattern()), "upload preserves components");
        var providers = new SidebarPackets.Providers(7, 999,
                List.of(new SidebarPackets.Entry(token, "测试供应器", 8, 9, ItemStack.EMPTY)), "");
        var decoded = roundTrip(player, SidebarPackets.Providers.CODEC, providers);
        require(decoded.menuId() == 7 && decoded.requestId() == 999 && decoded.entries().size() == 1
                && decoded.entries().getFirst().token().equals(token)
                && decoded.entries().getFirst().name().equals("测试供应器")
                && decoded.entries().getFirst().freeSlots() == 8
                && decoded.entries().getFirst().totalSlots() == 9
                && decoded.entries().getFirst().icon().isEmpty(), "provider identity and display round-trip");
        for (int size : new int[] {-1, 513}) {
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
            try {
                buffer.writeVarInt(7); buffer.writeLong(999); buffer.writeVarInt(size);
                boolean rejected = false;
                try { SidebarPackets.Providers.CODEC.decode(buffer); }
                catch (IllegalArgumentException expected) { rejected = true; }
                require(rejected, "reject invalid list length " + size);
            } finally { buffer.release(); }
        }
    }

    private static <T> T roundTrip(ServerPlayer player, StreamCodec<RegistryFriendlyByteBuf, T> codec, T packet) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
        try {
            codec.encode(buffer, packet);
            T decoded = codec.decode(buffer);
            require(buffer.readableBytes() == 0, "codec consumed full payload");
            return decoded;
        } finally { buffer.release(); }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError("SMOKE_PACKET_ASSERT: " + message);
    }
}
