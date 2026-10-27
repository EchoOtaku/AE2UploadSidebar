package dev.echo.uploadsidebar.smoke;

import appeng.client.gui.me.items.PatternEncodingTermScreen;
import dev.echo.uploadsidebar.client.SidebarClient;
import dev.echo.uploadsidebar.client.SidebarLocator;
import dev.echo.uploadsidebar.client.SidebarNotes;
import dev.echo.uploadsidebar.client.SidebarPanel;
import dev.echo.uploadsidebar.core.ProviderIdentity;
import dev.echo.uploadsidebar.core.ProviderLocation;
import dev.echo.uploadsidebar.net.SidebarPackets;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.ClientCommandHandler;
import net.neoforged.neoforge.client.ClientCommandSourceStack;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

final class LocatorAssertions {
    private static final Path RUN = SmokePaths.runDirectory().toAbsolutePath().normalize();
    private static final AtomicInteger uploads = new AtomicInteger();
    private static boolean observed;
    private static int phase;
    private static long started;
    private static ProviderLocation selected;
    private static PatternEncodingTermScreen<?> oldScreen;

    static void verifyInMenu(Minecraft client, PatternEncodingTermScreen<?> screen) throws Exception {
        require(client.gameDirectory.toPath().toAbsolutePath().normalize().equals(RUN), "only run in isolated test game");
        if (!observed) {
            client.getConnection().getConnection().channel().pipeline().addLast("sidebar-smoke-locator", new ChannelOutboundHandlerAdapter() {
                @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                    if (message instanceof ServerboundCustomPayloadPacket packet && packet.payload() instanceof SidebarPackets.Upload) uploads.incrementAndGet();
                    super.write(context, message, promise);
                }
            });
            observed = true;
        }
        int beforeUploads = uploads.get();
        var rows = SidebarClient.iconTargets(screen);
        require(rows.size() == 2, "two actual same-name providers are available");
        var slots = screen.getMenu().slots.stream().map(slot -> slot.getItem().copy()).toList();
        ItemStack carried = screen.getMenu().getCarried().copy();
        byte[] notes = privateFile("notes"), icons = privateFile("icons");
        var first = rows.getFirst();
        var second = rows.getLast();
        require(first.entry().name().equals(second.entry().name()), "same display names cannot determine physical location");
        click(screen, first);
        require(SidebarLocator.current().orElseThrow().location().equals(SidebarNotes.providerLocation(first.entry()).orElseThrow()),
            "real middle click locates the concrete first provider");
        click(screen, second);
        require(SidebarLocator.current().orElseThrow().location().equals(SidebarNotes.providerLocation(second.entry()).orElseThrow()),
            "another same-name provider replaces the current location");
        click(screen, second);
        require(SidebarLocator.current().isEmpty(), "middle click on the same provider cancels");
        click(screen, first);
        var player = client.player;
        var source = new ClientCommandSourceStack(player, player.position(), player.getRotationVector(), 0,
            player.getName().getString(), player.getDisplayName(), player);
        require(ClientCommandHandler.getDispatcher().execute("ae2sidebar unhighlight", source) > 0,
            "unhighlight is a real client command with no operator permission");
        require(SidebarLocator.current().isEmpty(), "unhighlight clears the marker immediately");
        require(client.screen == screen && client.player.containerMenu == screen.getMenu(), "locating preserves the actual terminal menu");
        for (int i = 0; i < slots.size(); i++) require(ItemStack.matches(slots.get(i), screen.getMenu().slots.get(i).getItem()),
            "middle click leaves all menu slots unchanged: " + i);
        require(ItemStack.matches(carried, screen.getMenu().getCarried()) && beforeUploads == uploads.get(),
            "middle click never changes cursor inventory or emits an upload");
        require(Arrays.equals(notes, privateFile("notes")) && Arrays.equals(icons, privateFile("icons")),
            "locating does not write private icons or notes");
        verifyPartBounds();
    }

    static boolean tickWorld(Minecraft client) throws Exception {
        switch (phase) {
            case 0 -> {
                if (!(client.screen instanceof PatternEncodingTermScreen<?> screen) || SidebarClient.iconTargets(screen).size() != 2) return false;
                verifyInMenu(client, screen);
                uploads.set(0);
                oldScreen = screen;
                // The northern provider lies behind the original terminal from this fixture's camera.
                var target = SidebarClient.iconTargets(screen).stream()
                    .filter(t -> SidebarNotes.providerLocation(t.entry()).orElseThrow().z() == -1).findFirst().orElseThrow();
                click(screen, target);
                selected = SidebarLocator.current().orElseThrow().location();
                client.player.closeContainer();
                require(SidebarLocator.current().isPresent(), "closing the real menu does not clear the marker");
                Vec3 delta = new Vec3(selected.x() + .5, selected.y() + .5, selected.z() + .5).subtract(client.player.getEyePosition());
                client.player.setYRot((float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90));
                client.player.setXRot((float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z))));
                next(1);
            }
            case 1 -> {
                if (System.nanoTime() - started < 500_000_000L) return false;
                require(client.screen == null && client.player.containerMenu == client.player.inventoryMenu,
                    "player returned to the real world after closing WET");
                require(SidebarClient.iconTargets(oldScreen).isEmpty() && SidebarLocator.current().orElseThrow().location().equals(selected),
                    "menu bindings clear naturally while the selected physical location survives");
                SmokeScreenshots.request(client, "sidebar-world-provider-highlight.png");
                next(2);
            }
            case 2 -> {
                if (!SmokeScreenshots.saved("sidebar-world-provider-highlight.png")) return false;
                require(uploads.get() == 0, "world highlighting never sends upload packets");
                SidebarClient.setEnabled(false);
                require(SidebarLocator.current().isEmpty(), "runtime off immediately clears the world marker");
                SidebarClient.setEnabled(true);
                require(SidebarLocator.current().isEmpty(), "runtime on cannot resurrect a cleared marker");
                client.getConnection().getConnection().channel().pipeline().remove("sidebar-smoke-locator");
                next(3);
                return true;
            }
            default -> { return true; }
        }
        return false;
    }

    private static void click(PatternEncodingTermScreen<?> screen, SidebarPanel.IconTarget row) {
        double x = row.bounds().x() + 8d, y = row.bounds().y() + 8d;
        var press = new ScreenEvent.MouseButtonPressed.Pre(screen, x, y, 2);
        NeoForge.EVENT_BUS.post(press);
        require(press.isCanceled(), "middle press must not reach terminal clone/pick behavior");
        var release = new ScreenEvent.MouseButtonReleased.Pre(screen, x, y, 2);
        NeoForge.EVENT_BUS.post(release);
        require(release.isCanceled(), "middle release stays captured by the sidebar");
    }

    private static void verifyPartBounds() {
        for (String face : List.of("down", "up", "north", "south", "west", "east")) {
            var location = ProviderLocation.parse(ProviderIdentity.part("minecraft:overworld", "ae2:cable_pattern_provider", 5, 70, -8, face).key()).orElseThrow();
            var box = SidebarLocator.bounds(location);
            require(box.minX >= 5 && box.maxX <= 6 && box.minY >= 70 && box.maxY <= 71 && box.minZ >= -8 && box.maxZ <= -7,
                "part outline stays inside its own cable host");
            require(switch (face) {
                case "down" -> box.minY == 70 && box.maxY == 70.125;
                case "up" -> box.minY == 70.875 && box.maxY == 71;
                case "north" -> box.minZ == -8 && box.maxZ == -7.875;
                case "south" -> box.minZ == -7.125 && box.maxZ == -7;
                case "west" -> box.minX == 5 && box.maxX == 5.125;
                default -> box.minX == 5.875 && box.maxX == 6;
            }, "part outline corresponds to the exact server-provided face");
        }
    }

    private static byte[] privateFile(String kind) throws Exception {
        Path path = RUN.resolve("config/ae2-upload-sidebar-" + kind + ".properties");
        return Files.exists(path) ? Files.readAllBytes(path) : new byte[0];
    }
    private static void next(int value) { phase = value; started = System.nanoTime(); }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError("SMOKE_LOCATOR: " + message);
    }
    static String report() {
        return "locator_normal_and_wet_middle_click_exact_same_name_provider=PASS\n"
            + "locator_toggle_replace_and_no_op_client_clear_command=PASS\n"
            + "locator_zero_upload_packets_no_menu_cursor_or_private_file_mutation=PASS\n"
            + "locator_real_menu_close_preserves_world_highlight=PASS\n"
            + "locator_six_part_faces_match_actual_world_bounds=PASS\n"
            + "locator_runtime_off_clears_without_resurrection=PASS\n";
    }
}
