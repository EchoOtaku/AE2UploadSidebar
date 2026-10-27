package dev.echo.uploadsidebar;

import dev.echo.uploadsidebar.client.SidebarClient;
import dev.echo.uploadsidebar.net.SidebarPackets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

@Mod(value = "ae2_upload_sidebar", dist = Dist.CLIENT)
public final class UploadSidebarClient {
    public UploadSidebarClient() {
        SidebarClient.init();
        SidebarPackets.providersReceiver = SidebarClient::acceptProviders;
        SidebarPackets.namedProvidersReceiver = SidebarClient::acceptNamedProviders;
        SidebarPackets.resultReceiver = SidebarClient::acceptResult;
    }
}
