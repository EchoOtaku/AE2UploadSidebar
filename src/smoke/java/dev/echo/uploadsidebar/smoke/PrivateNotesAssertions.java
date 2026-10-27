package dev.echo.uploadsidebar.smoke;

import appeng.blockentity.crafting.PatternProviderBlockEntity;
import appeng.client.gui.me.items.PatternEncodingTermScreen;
import appeng.menu.MenuOpener;
import appeng.menu.SlotSemantics;
import appeng.menu.locator.MenuLocators;
import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.parts.encoding.PatternEncodingTerminalPart;
import com.mojang.logging.LogUtils;
import dev.echo.uploadsidebar.client.SidebarClient;
import dev.echo.uploadsidebar.client.SidebarMappings;
import dev.echo.uploadsidebar.core.SidebarSession;
import dev.echo.uploadsidebar.net.SidebarPackets;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.ClientCommandHandler;
import net.neoforged.neoforge.client.ClientCommandSourceStack;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** Real UI/network regression. Fixture inventory changes execute only on the isolated server thread. */
final class PrivateNotesAssertions {
    private static final Path ISOLATED_RUN = SmokePaths.runDirectory()
        .toAbsolutePath().normalize();
    private static final BlockPos TERMINAL_POS = new BlockPos(0, 180, 0);
    private static final String SAME_NAME = "smoke-shared-provider";
    private static final String NOTE = "smoke-grinder-A";
    private static final String OBSERVER = "sidebar-smoke-private-notes";
    private static final AtomicInteger uploads = new AtomicInteger();
    private static final AtomicInteger observedQueries = new AtomicInteger();
    private static volatile int serverRequest;
    private static volatile int serverCompleted;
    private static ItemStack serverPattern;
    private static int phase;
    private static long phaseStarted;
    private static long refreshStarted;
    private static int captureTicks;
    private static PatternEncodingTermScreen<?> terminalScreen;
    private static Object terminalMenu;
    private static ItemStack originalPattern;
    private static String targetLocation;
    private static String otherLocation;
    private static String expectedNote = NOTE;
    private static Map<String, UUID> refreshTokens;
    private static Channel observedChannel;
    private static Properties baselineNotes;

    /** Called after the existing destructive server-ticket tests, using the replacement provider. */
    static void prepareServerFixture(ServerPlayer player, PatternEncodingTerminalPart terminal) {
        require(player.serverLevel().getServer().getWorldData().getLevelName().startsWith("ae2-sidebar-smoke-"),
            "same-name fixture only changes the newly generated smoke world");
        for (BlockPos position : List.of(TERMINAL_POS.east(), TERMINAL_POS.north())) {
            var provider = (PatternProviderBlockEntity) player.serverLevel().getBlockEntity(position);
            require(provider != null, "same-name fixture uses the two actual powered providers");
            provider.setName(SAME_NAME);
            provider.saveChanges();
        }
        serverPattern = terminal.getLogic().getEncodedPatternInv().getStackInSlot(0).copy();
        require(serverPattern.getCount() == 3, "private-note fixture begins with the restored three-pattern source");
    }

    /** Handshake avoids client-side inventory fakes for the empty-source and actual menu-reopen checks. */
    static void serverTick(ServerPlayer player, PatternEncodingTerminalPart terminal) {
        int request = serverRequest;
        if (request == 0 || request == serverCompleted) return;
        require(player.serverLevel().getServer().isSameThread(), "note fixture mutations run on the actual server thread");
        for (BlockPos position : List.of(TERMINAL_POS.east(), TERMINAL_POS.north())) {
            var provider = (PatternProviderBlockEntity) player.serverLevel().getBlockEntity(position);
            var inventory = provider.getLogic().getPatternInv();
            for (int slot = 0; slot < inventory.size(); slot++) {
                require(inventory.getStackInSlot(slot).isEmpty(), "editing a private note never inserts provider patterns");
            }
        }
        var source = terminal.getLogic().getEncodedPatternInv();
        if (request == 1) {
            require(ItemStack.matches(source.getStackInSlot(0), serverPattern), "note editor leaves the real server source unchanged");
            source.setItemDirect(0, ItemStack.EMPTY);
            player.containerMenu.broadcastChanges();
        } else if (request == 2) {
            require(source.getStackInSlot(0).isEmpty(), "empty-source editing does not create an encoded pattern");
            source.setItemDirect(0, serverPattern.copy());
            player.containerMenu.broadcastChanges();
        } else if (request == 3) {
            require(ItemStack.matches(source.getStackInSlot(0), serverPattern), "restored source still contains exactly three patterns");
            Object previousMenu = player.containerMenu;
            player.closeContainer();
            require(MenuOpener.open(PatternEncodingTermMenu.TYPE, player, MenuLocators.forPart(terminal)),
                "the real server reopens its original encoding-terminal part");
            require(player.containerMenu != previousMenu, "server menu reopen creates a new actual menu identity");
            player.containerMenu.broadcastChanges();
        } else if (request == 4) {
            require(ItemStack.matches(source.getStackInSlot(0), serverPattern), "all private-note gestures preserve the real server source");
        } else throw new AssertionError("Unknown note server fixture request: " + request);
        serverCompleted = request;
    }

    static boolean tick(Minecraft client) throws Exception {
        notesPath(client); // Check the exact isolated path before every file read or UI write.
        require(uploads.get() == 0, "private-note interactions must send zero upload packets");
        if (phaseStarted != 0) require(System.nanoTime() - phaseStarted < 15_000_000_000L,
            "private-note phase " + phase + " timed out waiting for a real screen/server response");
        switch (phase) {
            case 0 -> {
                if (!(client.screen instanceof PatternEncodingTermScreen<?> screen)
                        || SidebarClient.exclusionZone(screen) == null || entries().size() != 2) return false;
                if (entries().stream().anyMatch(entry -> !SAME_NAME.equals(entry.name()))) return false;
                terminalScreen = screen;
                terminalMenu = client.player.containerMenu;
                originalPattern = source().copy();
                require(originalPattern.getCount() == 3, "actual client source begins with three encoded patterns");
                normalizePanel();
                var snapshot = entries();
                var first = visibleEntries().getFirst();
                var second = snapshot.stream().filter(entry -> !entry.token().equals(first.token())).findFirst().orElseThrow();
                baselineNotes = Files.exists(notesPath(client)) ? persistedNotes(client) : new Properties();
                observedChannel = client.getConnection().getConnection().channel();
                observedChannel.pipeline().addLast(OBSERVER, new ChannelOutboundHandlerAdapter() {
                    @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                        if (message instanceof ServerboundCustomPayloadPacket packet
                                && packet.payload() instanceof SidebarPackets.Upload) uploads.incrementAndGet();
                        if (message instanceof ServerboundCustomPayloadPacket packet
                                && (packet.payload() instanceof SidebarPackets.Query
                                    || packet.payload() instanceof SidebarPackets.NamedQuery)) observedQueries.incrementAndGet();
                        super.write(context, message, promise);
                    }
                });
                // Preserve the original RED: absent right-click behavior fails before new note API reflection.
                openEditor(client, first);
                targetLocation = location(first);
                otherLocation = location(second);
                require(!targetLocation.isBlank() && !otherLocation.isBlank() && !targetLocation.equals(otherLocation),
                    "same-named real providers expose distinct stable locations");
                require(note(first).isEmpty() && note(second).isEmpty(), "fresh smoke world cannot inherit a previous world's private notes");
                next(1);
            }
            case 1 -> {
                require(isEditor(client.screen), "note editor remains open across real client ticks");
                if (!capture(client, "sidebar-private-note-editor.png")) return false;
                String emoji = "\uD83D\uDE03";
                noteInput(client.screen).setValue(emoji.repeat(65));
                require(noteInput(client.screen).getValue().equals(emoji.repeat(64)),
                    "editor accepts 64 Unicode code points without splitting a surrogate pair");
                noteInput(client.screen).setValue(NOTE);
                client.screen.resize(client, client.screen.width, client.screen.height);
                require(noteInput(client.screen).getValue().equals(NOTE), "note editor resize preserves its current draft");
                clickEditor(client, "save");
                assertTerminal(client, originalPattern);
                next(2);
            }
            case 2 -> {
                if (!ready(client)) return false;
                verifyOnlyTargetNote();
                verifyPersistedTargetNote(client);
                verifySearchAndMapping();
                verifyPinyinAndFavorites();
                refreshTokens = tokensByLocation();
                refreshStarted = System.nanoTime();
                next(3);
            }
            case 3 -> {
                if (!ready(client)) return false;
                verifyOnlyTargetNote();
                if (!capture(client, "sidebar-private-note-highlight.png")) return false;
                if (System.nanoTime() - refreshStarted < 3_000_000_000L
                        || tokensByLocation().entrySet().stream().anyMatch(entry -> entry.getValue().equals(refreshTokens.get(entry.getKey())))) return false;
                require(tokensByLocation().keySet().equals(refreshTokens.keySet()), "natural refresh retains both stable provider locations");
                require(dev.echo.uploadsidebar.client.SidebarNotes.favorite(other()), "favorite follows stable identity after token refresh");
                require(!dev.echo.uploadsidebar.client.SidebarNotes.favorite(target()), "same-name target remains unfavorited");
                require(dev.echo.uploadsidebar.client.SidebarNotes.toggleFavorite(other()), "remove isolated favorite fixture");
                verifyOnlyTargetNote();
                SidebarMappings.setManualKey("");
                byte[] before = notesBytes(client);
                openEditor(client, target());
                noteInput(client.screen).setValue("cancel-must-not-persist");
                clickEditor(client, "cancel");
                assertTerminal(client, originalPattern);
                require(Arrays.equals(before, notesBytes(client)), "Cancel does not write the notes file");
                next(4);
            }
            case 4 -> {
                if (!ready(client)) return false;
                byte[] before = notesBytes(client);
                openEditor(client, target());
                noteInput(client.screen).setValue("escape-must-not-persist");
                Screen editor = client.screen;
                var escape = new ScreenEvent.KeyPressed.Pre(editor, GLFW.GLFW_KEY_ESCAPE, 0, 0);
                NeoForge.EVENT_BUS.post(escape);
                if (!escape.isCanceled()) editor.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
                assertTerminal(client, originalPattern);
                require(Arrays.equals(before, notesBytes(client)), "Escape closes the editor without persisting its draft");
                next(5);
            }
            case 5 -> {
                if (!ready(client)) return false;
                byte[] before = notesBytes(client);
                openEditor(client, target());
                noteInput(client.screen).setValue("disabled-must-not-persist");
                command(client, "ae2sidebar off");
                require(!SidebarClient.isEnabled() && client.screen == terminalScreen
                    && client.player.containerMenu == terminalMenu, "off closes the note auxiliary screen and preserves the original server menu");
                require(state("panel") == null && state("session") == null && SidebarClient.exclusionZone(terminalScreen) == null,
                    "off removes the panel and request session immediately during note editing");
                require(Arrays.equals(before, notesBytes(client)), "off does not persist the unsaved editor draft");
                command(client, "ae2sidebar on");
                next(6);
            }
            case 6 -> {
                if (!ready(client)) return false;
                verifyOnlyTargetNote();
                byte[] before = notesBytes(client);
                openEditor(client, target());
                noteInput(client.screen).setValue("stale-menu-must-not-persist");
                try {
                    // A controlled identity race, separate from the real server menu reopen below.
                    client.player.containerMenu = client.player.inventoryMenu;
                    clickEditor(client, "save");
                    require(Arrays.equals(before, notesBytes(client)), "editor refuses Save after the actual client menu identity changes");
                } finally {
                    client.player.containerMenu = terminalScreen.getMenu();
                    client.setScreen(terminalScreen);
                }
                assertTerminal(client, originalPattern);
                serverRequest = 1;
                next(7);
            }
            case 7 -> {
                if (serverCompleted != 1 || !source().isEmpty() || !ready(client)) return false;
                require(!(boolean) method(SidebarClient.class, "canUpload"), "empty real encoded-output slot disables upload");
                openEditor(client, target());
                require(source().isEmpty(), "empty-source right click still opens the note editor");
                expectedNote = NOTE + "-empty-source";
                noteInput(client.screen).setValue(expectedNote);
                clickEditor(client, "save");
                assertTerminal(client, ItemStack.EMPTY);
                next(8);
            }
            case 8 -> {
                if (!ready(client)) return false;
                verifyOnlyTargetNote();
                require(source().isEmpty(), "editing without any encoded pattern does not create source items");
                verifyPersistedTargetNote(client);
                serverRequest = 2;
                next(9);
            }
            case 9 -> {
                if (serverCompleted != 2 || !ItemStack.matches(source(), originalPattern)) return false;
                serverRequest = 3;
                next(10);
            }
            case 10 -> {
                if (serverCompleted != 3 || !(client.screen instanceof PatternEncodingTermScreen<?> reopened)
                        || client.player.containerMenu == terminalMenu || SidebarClient.exclusionZone(reopened) == null
                        || entries().size() != 2) return false;
                require(reopened != terminalScreen && reopened.getMenu() == client.player.containerMenu,
                    "client receives a genuinely new terminal screen and menu from the server reopen");
                terminalScreen = reopened;
                terminalMenu = reopened.getMenu();
                require(ready(client), "real reopened terminal receives editable stable provider identities");
                verifyOnlyTargetNote();
                assertTerminal(client, originalPattern);
                next(11);
            }
            case 11 -> {
                if (!ready(client)) return false;
                if (!capture(client, "sidebar-private-note-reopened.png")) return false;
                openEditor(client, target());
                require(noteInput(client.screen).getValue().equals(expectedNote), "reopened editor loads the persisted provider-specific note");
                clickEditor(client, "delete");
                assertTerminal(client, originalPattern);
                next(12);
            }
            case 12 -> {
                if (!ready(client)) return false;
                require(entries().stream().allMatch(entry -> uncheckedNote(entry).isEmpty()), "Delete removes only the edited provider note");
                Properties after = persistedNotes(client);
                Properties before = new Properties();
                before.putAll(baselineNotes);
                after.remove("format"); before.remove("format");
                require(after.equals(before), "Delete restores all original note entries without erasing another world's private notes");
                require(entries().stream().allMatch(entry -> uncheckedDisplayName(entry).equals(SAME_NAME)),
                    "deleting a private note restores both original provider display names");
                serverRequest = 4;
                next(13);
            }
            case 13 -> {
                if (serverCompleted != 4 || !ready(client) || !(boolean) method(SidebarClient.class, "canUpload")) return false;
                // Protocol-consumer boundary check, not a claim that an old-version server was launched.
                var session = (SidebarSession) state("session");
                long requestId = session.begin(SidebarSession.Operation.QUERY, System.nanoTime() / 1_000_000L);
                require(requestId != 0, "legacy fallback check owns a matching pending query");
                SidebarClient.acceptProviders(new SidebarPackets.Providers(terminalScreen.getMenu().containerId,
                    requestId, entries(), ""));
                require(entries().size() == 2 && (boolean) method(SidebarClient.class, "canUpload"),
                    "accepted legacy provider response preserves original upload capability");
                for (var entry : entries()) require(!(boolean) notes("canEdit", entry),
                    "legacy response without stable metadata must disable private-note editing");
                Field queryAt = SidebarClient.class.getDeclaredField("nextQueryAt");
                queryAt.setAccessible(true); queryAt.setLong(null, 0);
                next(14);
            }
            case 14 -> {
                if (!ready(client)) return false;
                assertTerminal(client, originalPattern);
                require(uploads.get() == 0 && observedQueries.get() > 0,
                    "live outbound observer saw real queries but zero uploads throughout note editing");
                observedChannel.pipeline().remove(OBSERVER);
                SidebarMappings.setManualKey("");
                LogUtils.getLogger().info("EMBEDDED_SMOKE: private notes same-name identity, real UI persistence, refresh/reopen, empty source and zero uploads PASS");
                next(15);
                return true;
            }
            default -> { return true; }
        }
        return false;
    }

    private static void openEditor(Minecraft client, SidebarPackets.Entry entry) throws Exception {
        normalizePanel();
        List<SidebarPackets.Entry> visible = visibleEntries();
        int index = visible.indexOf(entry);
        require(index >= 0, "note target is a visible real provider row");
        var row = LayoutInteractionAssertions.controlBounds("row" + index);
        require(row != null, "actual provider row is visible before the note edit gesture");
        double x = row.x() + row.width() / 2d, y = row.y() + row.height() / 2d;
        ItemStack before = source().copy();
        var press = new ScreenEvent.MouseButtonPressed.Pre(terminalScreen, x, y, 1);
        NeoForge.EVENT_BUS.post(press);
        require(press.isCanceled(), "provider right click must not reach the underlying terminal");
        require(client.screen != terminalScreen && isEditor(client.screen),
            "right-clicking a real provider row opens the private note editor");
        var release = new ScreenEvent.MouseButtonReleased.Pre(client.screen, x, y, 1);
        NeoForge.EVENT_BUS.post(release);
        if (!release.isCanceled()) client.screen.mouseReleased(x, y, 1);
        require(client.player.containerMenu == terminalMenu && ItemStack.matches(before, source()),
            "opening the private note editor retains the original server menu and consumes no encoded pattern");
    }

    private static void normalizePanel() throws Exception {
        if (LayoutInteractionAssertions.layout().collapsed()) LayoutInteractionAssertions.clickControl(terminalScreen, "collapse");
        if (LayoutInteractionAssertions.controlBounds("theme") != null) LayoutInteractionAssertions.clickControl(terminalScreen, "settings");
    }

    private static boolean ready(Minecraft client) throws Exception {
        if (client.screen != terminalScreen || client.player.containerMenu != terminalMenu
                || SidebarClient.exclusionZone(terminalScreen) == null || entries().size() != 2) return false;
        for (var entry : entries()) if (!(boolean) notes("canEdit", entry)) return false;
        return true;
    }

    private static void verifyOnlyTargetNote() throws Exception {
        require(entries().stream().allMatch(entry -> SAME_NAME.equals(entry.name())), "private notes never change packet Entry.name");
        require(note(target()).equals(expectedNote) && note(other()).isEmpty(),
            "only one of two identically named real providers carries the private note");
        require(((String) notes("displayName", target())).contains(expectedNote)
                && ((String) notes("displayName", other())).equals(SAME_NAME), "display name uses only the correct provider's private note");
    }

    private static void verifySearchAndMapping() throws Exception {
        search(expectedNote);
        require(visibleEntries().size() == 1 && location(visibleEntries().getFirst()).equals(targetLocation),
            "actual sidebar search finds only the provider whose private note matches");
        search(SAME_NAME);
        require(visibleEntries().size() == 2, "original server name still finds both providers after adding a note");
        search("");
        SidebarMappings.setManualKey(expectedNote);
        require(SidebarMappings.matches(target()) && !SidebarMappings.matches(other()),
            "mapping by private note highlights only the concrete target among same-named providers");
        require(SidebarMappings.sortedEntries(entries()).getFirst().token().equals(target().token()),
            "private-note recommendation moves the same concrete token to the first row");
        SidebarMappings.setManualKey(SAME_NAME);
        require(SidebarMappings.matches(target()) && SidebarMappings.matches(other()),
            "original-name mappings remain valid after adding a private note");
        SidebarMappings.setManualKey(expectedNote);
    }

    private static void verifyPinyinAndFavorites() throws Exception {
        var target = target();
        require(dev.echo.uploadsidebar.client.SidebarNotes.save(target, "注魔祭坛"), "save Chinese search fixture");
        for (String query : List.of("zhumojitan", "zmjt")) {
            search(query);
            var rows = visibleEntries();
            require(rows.size() == 1 && rows.getFirst().equals(target), "real search matches Chinese private note by pinyin");
        }
        search("");
        require(dev.echo.uploadsidebar.client.SidebarNotes.save(target, NOTE), "restore note fixture");
        var rows = visibleEntries();
        int row = rows.indexOf(other());
        require(row >= 0, "other same-name target is visible");
        LayoutInteractionAssertions.clickControl(terminalScreen, "favorite" + row);
        require(dev.echo.uploadsidebar.client.SidebarNotes.favorite(other()), "star click favorites exact target");
        rows = visibleEntries();
        require(rows.getFirst().equals(other()), "favorite is pinned above recommended target");
        require(uploads.get() == 0, "favorite click sends no upload");
    }

    private static void search(String value) throws Exception {
        LayoutInteractionAssertions.clickControl(terminalScreen, "search");
        EditBox input = (EditBox) field(state("panel"), "search");
        var end = new ScreenEvent.KeyPressed.Pre(terminalScreen, GLFW.GLFW_KEY_END, 0, 0);
        NeoForge.EVENT_BUS.post(end);
        require(end.isCanceled(), "sidebar owns moving its search caret to the end");
        // Native end/backspace avoids pretending GLFW Ctrl is held for select-all.
        for (int i = 0; !input.getValue().isEmpty() && i < 80; i++) {
            var erase = new ScreenEvent.KeyPressed.Pre(terminalScreen, GLFW.GLFW_KEY_BACKSPACE, 0, 0);
            NeoForge.EVENT_BUS.post(erase);
            require(erase.isCanceled(), "sidebar owns search deletion");
        }
        for (char character : value.toCharArray()) {
            var typed = new ScreenEvent.CharacterTyped.Pre(terminalScreen, character, 0);
            NeoForge.EVENT_BUS.post(typed);
            require(typed.isCanceled(), "sidebar consumes its actual search text input");
        }
        require(input.getValue().equals(value), "real search field contains the requested text");
        var escape = new ScreenEvent.KeyPressed.Pre(terminalScreen, GLFW.GLFW_KEY_ESCAPE, 0, 0);
        NeoForge.EVENT_BUS.post(escape);
        require(escape.isCanceled(), "Escape leaves note-search focus without closing the terminal");
    }

    private static void clickEditor(Minecraft client, String id) throws Exception {
        Screen editor = client.screen;
        var method = editor.getClass().getDeclaredMethod("controlBounds", String.class);
        method.setAccessible(true);
        var box = (SidebarSession.Layout) method.invoke(editor, id);
        require(box != null, "note editor control is visible: " + id);
        double x = box.x() + box.width() / 2d, y = box.y() + box.height() / 2d;
        var press = new ScreenEvent.MouseButtonPressed.Pre(editor, x, y, 0);
        NeoForge.EVENT_BUS.post(press);
        if (!press.isCanceled()) require(editor.mouseClicked(x, y, 0), "actual editor button handles its click: " + id);
        Screen after = client.screen;
        if (after != null) {
            var release = new ScreenEvent.MouseButtonReleased.Pre(after, x, y, 0);
            NeoForge.EVENT_BUS.post(release);
            if (!release.isCanceled()) after.mouseReleased(x, y, 0);
        }
    }

    private static EditBox noteInput(Screen editor) throws Exception { return (EditBox) field(editor, "noteInput"); }
    private static boolean isEditor(Screen screen) { return screen != null && screen.getClass().getSimpleName().equals("NoteEditorScreen"); }

    private static void command(Minecraft client, String command) throws Exception {
        var player = client.player;
        var source = new ClientCommandSourceStack(player, player.position(), player.getRotationVector(), 0,
            player.getName().getString(), player.getDisplayName(), player);
        require(ClientCommandHandler.getDispatcher().execute(command, source) > 0, "real no-OP client command succeeds: " + command);
    }

    private static SidebarPackets.Entry target() throws Exception { return byLocation(targetLocation); }
    private static SidebarPackets.Entry other() throws Exception { return byLocation(otherLocation); }
    private static SidebarPackets.Entry byLocation(String location) throws Exception {
        for (var entry : entries()) if (location(entry).equals(location)) return entry;
        throw new AssertionError("Stable provider location disappeared: " + location);
    }
    private static String location(SidebarPackets.Entry entry) throws Exception { return (String) notes("locationLabel", entry); }
    private static String note(SidebarPackets.Entry entry) throws Exception { return (String) notes("note", entry); }
    private static String uncheckedNote(SidebarPackets.Entry entry) {
        try { return note(entry); } catch (Exception error) { throw new AssertionError(error); }
    }
    private static String uncheckedDisplayName(SidebarPackets.Entry entry) {
        try { return (String) notes("displayName", entry); } catch (Exception error) { throw new AssertionError(error); }
    }
    private static Object notes(String name, SidebarPackets.Entry entry) throws Exception {
        return Class.forName("dev.echo.uploadsidebar.client.SidebarNotes").getMethod(name, SidebarPackets.Entry.class).invoke(null, entry);
    }
    private static Map<String, UUID> tokensByLocation() throws Exception {
        return entries().stream().collect(Collectors.toMap(entry -> {
            try { return location(entry); } catch (Exception error) { throw new AssertionError(error); }
        }, SidebarPackets.Entry::token));
    }
    @SuppressWarnings("unchecked")
    private static List<SidebarPackets.Entry> entries() throws Exception { return (List<SidebarPackets.Entry>) state("entries"); }
    @SuppressWarnings("unchecked")
    private static List<SidebarPackets.Entry> visibleEntries() throws Exception {
        Object panel = state("panel");
        var method = panel.getClass().getDeclaredMethod("visibleEntries");
        method.setAccessible(true);
        return (List<SidebarPackets.Entry>) method.invoke(panel);
    }
    private static Object state(String name) throws Exception {
        var field = SidebarClient.class.getDeclaredField(name); field.setAccessible(true); return field.get(null);
    }
    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static Object method(Class<?> type, String name) throws Exception {
        var method = type.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(null);
    }
    private static ItemStack source() { return terminalScreen.getMenu().getSlots(SlotSemantics.ENCODED_PATTERN).getFirst().getItem(); }
    private static void assertTerminal(Minecraft client, ItemStack expected) {
        require(client.screen == terminalScreen && client.player.containerMenu == terminalMenu && ItemStack.matches(expected, source()),
            "note interaction returns to the same terminal/menu with exactly the expected encoded source");
    }
    private static Path notesPath(Minecraft client) {
        require(client.gameDirectory.toPath().toAbsolutePath().normalize().equals(ISOLATED_RUN)
            && FMLPaths.CONFIGDIR.get().toAbsolutePath().normalize().equals(ISOLATED_RUN.resolve("config")),
            "private-note fixture can only access its isolated run-test config");
        return ISOLATED_RUN.resolve("config/ae2-upload-sidebar-notes.properties");
    }
    private static byte[] notesBytes(Minecraft client) throws Exception { return Files.readAllBytes(notesPath(client)); }
    private static void verifyPersistedTargetNote(Minecraft client) throws Exception {
        Properties after = persistedNotes(client);
        Properties before = new Properties();
        before.putAll(baselineNotes);
        after.remove("format"); before.remove("format");
        for (String key : before.stringPropertyNames()) {
            require(before.getProperty(key).equals(after.getProperty(key)),
                "saving the fixture note preserves every preexisting isolated note-file entry");
        }
        require(after.size() == before.size() + 1, "saving one provider adds exactly one scoped note entry");
        after.keySet().removeAll(before.keySet());
        require(after.containsValue(expectedNote), "the added provider-specific file entry contains the current note");
    }
    private static Properties persistedNotes(Minecraft client) throws Exception {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(notesPath(client))) { properties.load(reader); }
        return properties;
    }
    private static boolean capture(Minecraft client, String name) {
        if (++captureTicks < 8) return false;
        SmokeScreenshots.request(client, name);
        return SmokeScreenshots.saved(name);
    }
    private static void next(int value) {
        phase = value; phaseStarted = System.nanoTime(); captureTicks = 0;
        LogUtils.getLogger().info("EMBEDDED_SMOKE: private notes phase {}", phase);
    }
    static String report() {
        return "pinyin_full_and_initials_real_search=PASS\n"
            + "favorite_real_star_click_pins_exact_target_zero_uploads=PASS\n"
            + "favorite_refresh_identity_and_same_name_isolation=PASS\n"
            + "private_note_real_same_name_provider_editor_save_cancel_delete=PASS\n"
            + "private_note_unicode_limit_and_resize_draft=PASS\n"
            + "private_note_original_name_search_and_mapping_preserved=PASS\n"
            + "private_note_only_target_search_and_mapping_highlight=PASS\n"
            + "private_note_natural_refresh_changed_tokens_stable_location=PASS\n"
            + "private_note_real_server_menu_reopen_persistence=PASS\n"
            + "private_note_empty_encoded_source_edit_save=PASS\n"
            + "private_note_off_and_changed_menu_reject_unsaved_draft=PASS\n"
            + "private_note_zero_outbound_uploads_and_server_inventories_unchanged=PASS\n"
            + "private_note_legacy_response_upload_only_and_named_requery_restored=PASS\n"
            + "private_note_files_confined_to_isolated_run_test=PASS\n";
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError("SMOKE_PRIVATE_NOTES_ASSERT: " + message);
    }
}
