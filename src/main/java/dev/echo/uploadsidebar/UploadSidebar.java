package dev.echo.uploadsidebar;

import dev.echo.uploadsidebar.net.SidebarPackets;
import dev.echo.uploadsidebar.server.ServerUploads;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@Mod("ae2_upload_sidebar")
public final class UploadSidebar {
    public UploadSidebar(IEventBus bus) {
        bus.addListener(UploadSidebar::register);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> ServerUploads.forget(event.getEntity().getUUID()));
    }

    private static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("2").optional();
        registrar.playToServer(SidebarPackets.Query.TYPE, SidebarPackets.Query.CODEC, (packet, context) -> {
            if (context.player() instanceof ServerPlayer player)
                PacketDistributor.sendToPlayer(player, ServerUploads.query(player, packet));
        });
        registrar.playToServer(SidebarPackets.NamedQuery.TYPE, SidebarPackets.NamedQuery.CODEC, (packet, context) -> {
            if (context.player() instanceof ServerPlayer player)
                PacketDistributor.sendToPlayer(player, ServerUploads.queryNamed(player, packet));
        });
        registrar.playToServer(SidebarPackets.Upload.TYPE, SidebarPackets.Upload.CODEC, (packet, context) -> {
            if (context.player() instanceof ServerPlayer player)
                PacketDistributor.sendToPlayer(player, ServerUploads.upload(player, packet));
        });
        registrar.playToClient(SidebarPackets.Providers.TYPE, SidebarPackets.Providers.CODEC,
                (packet, context) -> SidebarPackets.providersReceiver.accept(packet));
        registrar.playToClient(SidebarPackets.NamedProviders.TYPE, SidebarPackets.NamedProviders.CODEC,
                (packet, context) -> SidebarPackets.namedProvidersReceiver.accept(packet));
        registrar.playToClient(SidebarPackets.Result.TYPE, SidebarPackets.Result.CODEC,
                (packet, context) -> SidebarPackets.resultReceiver.accept(packet));
    }
}
