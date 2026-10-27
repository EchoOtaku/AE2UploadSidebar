package dev.echo.uploadsidebar.smoke;

import appeng.client.gui.me.items.PatternEncodingTermScreen;
import appeng.menu.SlotSemantics;
import com.mojang.logging.LogUtils;
import dev.echo.uploadsidebar.client.SidebarClient;
import dev.echo.uploadsidebar.client.SidebarNotes;
import dev.echo.uploadsidebar.net.SidebarPackets;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.ClientCommandHandler;
import net.neoforged.neoforge.client.ClientCommandSourceStack;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** Real client commands and real outgoing query packets; the observer never intercepts a packet. */
final class RuntimeToggleAssertions {
    private static final String OBSERVER_NAME = "sidebar-smoke-runtime-toggle";
    private static final AtomicInteger queries = new AtomicInteger();
    private static final AtomicReference<SidebarPackets.Query> latestQuery = new AtomicReference<>();
    private static int phase;
    private static int disabledTicks;
    private static int queriesAtDisable;
    private static long waitStarted;
    private static Channel observedChannel;
    private static PatternEncodingTermScreen<?> originalScreen;
    private static Object originalMenu;
    private static Object originalSession;
    private static ItemStack originalPattern;
    private static Rect2i formerPanel;
    private static SidebarPackets.Query oldQuery;
    private static List<SidebarPackets.Entry> oldEntries;
    private static SidebarPackets.NamedProviders oldNamed;

    static boolean tick(Minecraft client, PatternEncodingTermScreen<?> screen) throws Exception {
        switch (phase) {
            case 0 -> {
                // Removing the actual client command registration must fail here, before any reflection/API assumptions.
                require(ClientCommandHandler.runCommand("ae2sidebar status"),
                    "/ae2sidebar status must be handled by the real client dispatcher");
                originalScreen = screen;
                originalMenu = client.player.containerMenu;
                originalPattern = source(screen).copy();
                observedChannel = client.getConnection().getConnection().channel();
                observedChannel.pipeline().addLast(OBSERVER_NAME, new ChannelOutboundHandlerAdapter() {
                    @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                        if (message instanceof ServerboundCustomPayloadPacket packet) {
                            Object payload = packet.payload();
                            SidebarPackets.Query query = payload instanceof SidebarPackets.Query original ? original : null;
                            // Optional 0.4 packets do not exist in the RED baseline. Observe their real
                            // identity without introducing a compile-time dependency on the new schema.
                            if (payload.getClass().getName().equals("dev.echo.uploadsidebar.net.SidebarPackets$NamedQuery")) {
                                query = new SidebarPackets.Query(
                                    (int) payload.getClass().getMethod("menuId").invoke(payload),
                                    (long) payload.getClass().getMethod("requestId").invoke(payload));
                            }
                            if (query != null) {
                                latestQuery.set(query);
                                queries.incrementAndGet();
                            }
                        }
                        super.write(context, message, promise);
                    }
                });
                // A prior interrupted smoke may have persisted off. Recover through the same real command.
                dispatchWithoutOperator(client, "ae2sidebar on");
                require(enabled(), "/ae2sidebar on enables the client at permission level zero");
                verifyPersistedFlag(client, true);
                waitStarted = System.nanoTime();
                phase = 1;
            }
            case 1 -> {
                require(System.nanoTime() - waitStarted < 15_000_000_000L,
                    "initial enabled state did not produce a real provider query and response");
                if (latestQuery.get() == null || !readyToUpload()) return false;
                formerPanel = SidebarClient.exclusionZone(screen);
                require(formerPanel != null && formerPanel.getHeight() > 20,
                    "off fixture starts with the real expanded sidebar and usable providers");
                originalSession = state("session");
                oldEntries = entries();
                oldQuery = latestQuery.get();
                Field bindings = SidebarNotes.class.getDeclaredField("identities");
                bindings.setAccessible(true);
                @SuppressWarnings("unchecked")
                var identities = (Map<java.util.UUID, SidebarPackets.Identity>) bindings.get(null);
                require(identities.size() == 2 && oldEntries.stream().allMatch(SidebarNotes::canEdit),
                    "runtime-off fixture captured the real named-provider identity snapshot");
                oldNamed = new SidebarPackets.NamedProviders(new SidebarPackets.Providers(
                    oldQuery.menuId(), oldQuery.requestId(), oldEntries, ""), List.copyOf(identities.values()));
                verifyStatusFeedback(client, true);

                // Omitting synchronous clear/reset in the off handler must fail immediately, without another tick.
                dispatchWithoutOperator(client, "ae2sidebar off");
                require(!enabled(), "/ae2sidebar off disables the active client without operator permission");
                verifyDisabledState(client, screen);
                verifyPersistedFlag(client, false);
                verifyStatusFeedback(client, false);
                queriesAtDisable = queries.get();
                verifyDisabledInput(client, screen);

                // Exercise the public packet consumers with the real prior query identity/provider snapshot.
                // These packets are deliberately late; they must neither recreate the panel nor restore old tickets.
                SidebarClient.acceptProviders(new SidebarPackets.Providers(oldQuery.menuId(), oldQuery.requestId(), oldEntries, ""));
                SidebarClient.acceptResult(new SidebarPackets.Result(oldQuery.menuId(), oldQuery.requestId(), false, "late smoke response"));
                SidebarClient.acceptNamedProviders(oldNamed);
                verifyDisabledState(client, screen);
                require(oldEntries.stream().noneMatch(SidebarNotes::canEdit)
                    && oldEntries.stream().allMatch(entry -> SidebarNotes.note(entry).isEmpty()),
                    "late named-provider consumer cannot restore private-note bindings while disabled");
                phase = 2;
                LogUtils.getLogger().info("EMBEDDED_SMOKE: runtime off immediately cleared the panel and rejected late responses");
            }
            case 2 -> {
                // Ninety natural client ticks span the normal three-second provider refresh interval.
                // This catches a render/tick/packet path that silently recreates context while disabled.
                verifyDisabledState(client, screen);
                require(queries.get() == queriesAtDisable, "disabled sidebar must send no provider query packets");
                if (++disabledTicks < 90) return false;
                SmokeScreenshots.request(client, "sidebar-runtime-disabled.png");
                if (!SmokeScreenshots.saved("sidebar-runtime-disabled.png")) return false;
                dispatchWithoutOperator(client, "ae2sidebar on");
                require(enabled(), "on re-enables the same open terminal");
                verifyPersistedFlag(client, true);
                waitStarted = System.nanoTime();
                phase = 3;
            }
            case 3 -> {
                require(System.nanoTime() - waitStarted < 15_000_000_000L,
                    "re-enabled sidebar did not issue and receive a new real provider query");
                require(client.screen == originalScreen && client.player.containerMenu == originalMenu,
                    "on preserves the original terminal screen and server menu");
                if (!readyToUpload()) return false;
                require(queries.get() > queriesAtDisable && latestQuery.get().requestId() != oldQuery.requestId(),
                    "on gets a fresh real server query instead of reusing stale providers");
                require(state("session") != originalSession && SidebarClient.exclusionZone(screen) != null,
                    "on creates a fresh usable sidebar session");
                require(ItemStack.matches(originalPattern, source(screen)), "runtime commands never alter encoded patterns");
                verifyStatusFeedback(client, true);
                observedChannel.pipeline().remove(OBSERVER_NAME);
                phase = 4;
                LogUtils.getLogger().info("EMBEDDED_SMOKE: runtime toggle commands, no-OP access, disabled packet silence and fresh on-query PASS");
                return true;
            }
            default -> { return true; }
        }
        return false;
    }

    static String report() {
        return "runtime_client_off_on_status_commands_without_op=PASS\n"
            + "runtime_off_immediately_clears_open_terminal_sidebar=PASS\n"
            + "runtime_off_rejects_late_provider_and_result_consumers=PASS\n"
            + "runtime_off_rejects_late_named_provider_and_note_bindings=PASS\n"
            + "runtime_off_sends_no_queries_across_refresh_interval=PASS\n"
            + "runtime_off_releases_screen_input_and_exclusion_regions=PASS\n"
            + "runtime_on_requests_fresh_server_providers_and_can_upload=PASS\n"
            + "runtime_enabled_flag_persisted_separately=PASS\n";
    }

    private static void dispatchWithoutOperator(Minecraft client, String command) throws Exception {
        var player = client.player;
        var source = new ClientCommandSourceStack(player, player.position(), player.getRotationVector(), 0,
            player.getName().getString(), player.getDisplayName(), player);
        require(ClientCommandHandler.getDispatcher().execute(command, source) > 0,
            "real client command succeeds at permission level zero: /" + command);
    }

    private static void verifyStatusFeedback(Minecraft client, boolean expectedEnabled) throws Exception {
        Object messageBefore = newestChatMessage(client);
        dispatchWithoutOperator(client, "ae2sidebar status");
        require(newestChatMessage(client) != messageBefore, "status emits visible client feedback");
        require(enabled() == expectedEnabled, "status does not mutate the enabled flag");
    }

    private static Object newestChatMessage(Minecraft client) throws Exception {
        Field messages = ChatComponent.class.getDeclaredField("allMessages");
        messages.setAccessible(true);
        List<?> list = (List<?>) messages.get(client.gui.getChat());
        return list.isEmpty() ? null : list.getFirst();
    }

    private static void verifyDisabledState(Minecraft client, PatternEncodingTermScreen<?> screen) throws Exception {
        require(client.screen == originalScreen && client.player.containerMenu == originalMenu,
            "off retains the original terminal and menu");
        require(state("panel") == null && state("session") == null && entries().isEmpty() && !readyToUpload(),
            "off clears the sidebar panel, active session and upload snapshot immediately");
        require(SidebarClient.exclusionZone(screen) == null,
            "disabled sidebar exposes no JEI exclusion rectangle");
        require(!SidebarClient.contains(screen, formerPanel.getX() + 10d, formerPanel.getY() + 10d),
            "disabled sidebar exposes no mouse or carried-item protection region");
        require(screen.getExclusionZones().stream().noneMatch(region -> sameBounds(region, formerPanel)),
            "the real AE2 screen no longer excludes the disabled sidebar");
        require(ItemStack.matches(originalPattern, source(screen)), "off and late responses leave encoded patterns untouched");
    }

    private static void verifyDisabledInput(Minecraft client, PatternEncodingTermScreen<?> screen) throws Exception {
        double x = formerPanel.getX() + 12d, y = formerPanel.getY() + 12d;
        var press = new ScreenEvent.MouseButtonPressed.Pre(screen, x, y, 0);
        var drag = new ScreenEvent.MouseDragged.Pre(screen, x + 8, y + 8, 0, 8, 8);
        var release = new ScreenEvent.MouseButtonReleased.Pre(screen, x + 8, y + 8, 0);
        var scroll = new ScreenEvent.MouseScrolled.Pre(screen, x, y, 0, 1);
        NeoForge.EVENT_BUS.post(press);
        NeoForge.EVENT_BUS.post(drag);
        NeoForge.EVENT_BUS.post(release);
        NeoForge.EVENT_BUS.post(scroll);
        require(!press.isCanceled() && !drag.isCanceled() && !release.isCanceled() && !scroll.isCanceled(),
            "disabled sidebar does not capture mouse press, drag, release or scroll");
        Field mouseX = client.mouseHandler.getClass().getDeclaredField("xpos");
        Field mouseY = client.mouseHandler.getClass().getDeclaredField("ypos");
        mouseX.setAccessible(true);
        mouseY.setAccessible(true);
        double previousX = mouseX.getDouble(client.mouseHandler), previousY = mouseY.getDouble(client.mouseHandler);
        try {
            mouseX.setDouble(client.mouseHandler, x * client.getWindow().getScreenWidth() / screen.width);
            mouseY.setDouble(client.mouseHandler, y * client.getWindow().getScreenHeight() / screen.height);
            var hotkey = new ScreenEvent.KeyPressed.Pre(screen, GLFW.GLFW_KEY_Q, 0, 0);
            var typed = new ScreenEvent.CharacterTyped.Pre(screen, 'x', 0);
            NeoForge.EVENT_BUS.post(hotkey);
            NeoForge.EVENT_BUS.post(typed);
            require(!hotkey.isCanceled() && !typed.isCanceled(), "disabled sidebar releases keys and characters over its former location");
        } finally {
            mouseX.setDouble(client.mouseHandler, previousX);
            mouseY.setDouble(client.mouseHandler, previousY);
        }
    }

    private static void verifyPersistedFlag(Minecraft client, boolean expected) throws Exception {
        Path config = FMLPaths.CONFIGDIR.get().toAbsolutePath().normalize();
        require(config.equals(client.gameDirectory.toPath().toAbsolutePath().normalize().resolve("config")),
            "toggle smoke only inspects its own isolated config");
        Properties values = new Properties();
        try (var reader = Files.newBufferedReader(config.resolve("ae2-upload-sidebar-enabled.properties"))) {
            values.load(reader);
        }
        require(Boolean.toString(expected).equals(values.getProperty("enabled")),
            "on/off persists the enabled flag in its dedicated preferences file");
    }

    private static boolean enabled() throws Exception {
        return (boolean) SidebarClient.class.getMethod("isEnabled").invoke(null);
    }

    private static boolean readyToUpload() throws Exception {
        var method = SidebarClient.class.getDeclaredMethod("canUpload");
        method.setAccessible(true);
        return entries().size() == 2 && (boolean) method.invoke(null);
    }

    @SuppressWarnings("unchecked")
    private static List<SidebarPackets.Entry> entries() throws Exception {
        return (List<SidebarPackets.Entry>) state("entries");
    }

    private static Object state(String name) throws Exception {
        Field field = SidebarClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static ItemStack source(PatternEncodingTermScreen<?> screen) {
        return screen.getMenu().getSlots(SlotSemantics.ENCODED_PATTERN).getFirst().getItem();
    }

    private static boolean sameBounds(Rect2i a, Rect2i b) {
        return a.getX() == b.getX() && a.getY() == b.getY()
            && a.getWidth() == b.getWidth() && a.getHeight() == b.getHeight();
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError("SMOKE_RUNTIME_TOGGLE_ASSERT: " + message);
    }
}
