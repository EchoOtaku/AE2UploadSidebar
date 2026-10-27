package dev.echo.uploadsidebar.smoke;

import appeng.blockentity.crafting.PatternProviderBlockEntity;
import appeng.client.gui.me.items.PatternEncodingTermScreen;
import appeng.menu.SlotSemantics;
import appeng.parts.encoding.PatternEncodingTerminalPart;
import com.mojang.logging.LogUtils;
import dev.echo.uploadsidebar.client.SidebarClient;
import dev.echo.uploadsidebar.client.SidebarNotes;
import dev.echo.uploadsidebar.net.SidebarPackets;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.Util;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Real carried-stack icon selection, isolated from both production inventories and release code. */
final class PrivateIconsAssertions {
    private static final Path ISOLATED_RUN = SmokePaths.runDirectory()
        .toAbsolutePath().normalize();
    private static final int INVENTORY_INDEX = 9;
    private static final BlockPos TERMINAL_POS = new BlockPos(0, 180, 0);
    private static volatile int serverRequest;
    private static volatile int serverCompleted;
    private static ItemStack serverPattern;
    private static int phase;
    private static long phaseStarted;
    private static PatternEncodingTermScreen<?> terminalScreen;
    private static Object terminalMenu;
    private static ItemStack originalPattern;
    private static Slot inventorySlot;
    private static String lastServerReturnState;
    private static String targetLocation;
    static final String FIXTURE_NOTE = "icon-note-survives";
    private static String originalNote;
    private static JeiIconAssertions normalGhost;
    private static JeiIconAssertions wirelessGhost;

    static void serverTick(ServerPlayer player, PatternEncodingTerminalPart terminal) {
        int request = serverRequest;
        if (request == 0 || request == serverCompleted) return;
        require(player.serverLevel().getServer().isSameThread()
            && player.serverLevel().getServer().getWorldData().getLevelName().startsWith("ae2-sidebar-smoke-"),
            "icon inventory fixture runs only on its newly generated server world");
        var inventory = player.getInventory();
        var source = terminal.getLogic().getEncodedPatternInv();
        if (request == 1) {
            require(inventory.getItem(INVENTORY_INDEX).isEmpty() && player.containerMenu.getCarried().isEmpty(),
                "icon fixture refuses to overwrite an occupied inventory slot or carried stack");
            serverPattern = source.getStackInSlot(0).copy();
            require(serverPattern.getCount() == 3, "icon fixture starts before the original upload regression");
            inventory.setItem(INVENTORY_INDEX, sample());
            player.containerMenu.broadcastChanges();
        } else if (request == 2) {
            if (!ItemStack.matches(player.containerMenu.getCarried(), sample())
                    || !inventory.getItem(INVENTORY_INDEX).isEmpty()) return;
        } else if (request == 3) {
            require(ItemStack.matches(player.containerMenu.getCarried(), sample())
                && inventory.getItem(INVENTORY_INDEX).isEmpty(), "choosing an icon preserves all seven real carried items");
        } else if (request == 4) {
            String observed = "carried=" + player.containerMenu.getCarried().getCount()
                + ", inventory9=" + inventory.getItem(INVENTORY_INDEX).getCount();
            if (!observed.equals(lastServerReturnState)) {
                LogUtils.getLogger().info("EMBEDDED_SMOKE: icon native-return server {}", observed);
                lastServerReturnState = observed;
            }
            if (!player.containerMenu.getCarried().isEmpty()
                    || !ItemStack.matches(inventory.getItem(INVENTORY_INDEX), sample())) return;
        } else throw new AssertionError("Unknown private-icon fixture request: " + request);
        require(ItemStack.matches(source.getStackInSlot(0), serverPattern), "icon input never consumes the real encoded source");
        for (var position : List.of(TERMINAL_POS.east(), TERMINAL_POS.north())) {
            var provider = (PatternProviderBlockEntity) player.serverLevel().getBlockEntity(position);
            var patterns = provider.getLogic().getPatternInv();
            for (int slot = 0; slot < patterns.size(); slot++) {
                require(patterns.getStackInSlot(slot).isEmpty(), "icon click never uploads a pattern to either real provider");
            }
        }
        serverCompleted = request;
    }

    static boolean tick(Minecraft client) throws Exception {
        require(client.gameDirectory.toPath().toAbsolutePath().normalize().equals(ISOLATED_RUN)
            && FMLPaths.CONFIGDIR.get().toAbsolutePath().normalize().equals(ISOLATED_RUN.resolve("config")),
            "private icon smoke refuses any directory except the new D-drive run-test");
        if (phaseStarted != 0) require(System.nanoTime() - phaseStarted < 15_000_000_000L,
            "private icon phase " + phase + " timed out waiting for actual client/server inventory synchronization");
        switch (phase) {
            case 0 -> {
                if (!(client.screen instanceof PatternEncodingTermScreen<?> screen)
                        || SidebarClient.exclusionZone(screen) == null || entries().size() != 2) return false;
                terminalScreen = screen;
                terminalMenu = client.player.containerMenu;
                originalPattern = source().copy();
                IconCodecAssertions.verify(client);
                require(originalPattern.getCount() == 3 && terminalScreen.getMenu().getCarried().isEmpty(),
                    "real icon test begins with an empty cursor and three encoded patterns");
                serverRequest = 1;
                next(1);
            }
            case 1 -> {
                if (serverCompleted != 1 || !ItemStack.matches(client.player.getInventory().getItem(INVENTORY_INDEX), sample())) return false;
                inventorySlot = terminalScreen.getMenu().slots.stream()
                    .filter(slot -> slot.container == client.player.getInventory()
                        && slot.getContainerSlot() == INVENTORY_INDEX && slot.isActive()).findFirst().orElseThrow();
                clickInventorySlot();
                serverRequest = 2;
                next(2);
            }
            case 2 -> {
                if (serverCompleted != 2 || !ItemStack.matches(terminalScreen.getMenu().getCarried(), sample())
                        || entries().size() != 2) return false;
                Object panel = state("panel");
                var visible = panel.getClass().getDeclaredMethod("visibleEntries");
                visible.setAccessible(true);
                @SuppressWarnings("unchecked")
                var target = ((List<SidebarPackets.Entry>) visible.invoke(panel)).getFirst();
                targetLocation = SidebarNotes.locationLabel(target);
                originalNote = SidebarNotes.note(target);
                require(SidebarNotes.save(target, FIXTURE_NOTE), "guarded fixture note is saved before icon selection");
                require(!displayIcon(target).is(Items.DIAMOND), "hand-selected icon visibly differs from the server-provided icon");
                ItemStack originalWireIcon = target.icon().copy();
                var bounds = LayoutInteractionAssertions.controlBounds("row0");
                require(bounds != null, "real first provider row is visible");
                double x = bounds.x() + 12d, y = bounds.y() + 13d;
                var press = new ScreenEvent.MouseButtonPressed.Pre(terminalScreen, x, y, 0);
                NeoForge.EVENT_BUS.post(press);
                require(press.isCanceled(), "provider icon selection cannot fall through to an inventory slot");
                var release = new ScreenEvent.MouseButtonReleased.Pre(terminalScreen, x, y, 0);
                NeoForge.EVENT_BUS.post(release);
                // The 0.4 baseline uploads instead of changing this icon. Missing 0.5 API deliberately
                // falls back to its real displayed server icon so RED proves missing behavior, not missing classes.
                require(ItemStack.matches(displayIcon(target), sample().copyWithCount(1)),
                    "left-clicking a real provider icon with seven carried items saves a one-item private display icon");
                require(SidebarNotes.note(target).equals(FIXTURE_NOTE), "carried icon selection preserves the provider's private note");
                require(client.screen == terminalScreen && client.player.containerMenu == terminalMenu
                    && ItemStack.matches(terminalScreen.getMenu().getCarried(), sample())
                    && ItemStack.matches(source(), originalPattern), "icon selection preserves the terminal, cursor count and encoded patterns");
                require(ItemStack.matches(target.icon(), originalWireIcon), "private icon selection does not mutate the server Entry icon");
                serverRequest = 3;
                next(3);
            }
            case 3 -> {
                if (serverCompleted != 3) return false;
                // Vanilla treats another left click on this same slot within 250 ms as PICKUP_ALL.
                // The intercepted icon click intentionally does not alter vanilla's lastClickSlot/time.
                // Wait on natural ticks until this fixture's intended independent deposit is unambiguous.
                if (Util.getMillis() - (long) containerState("lastClickTime") < 300L) return false;
                clickInventorySlot();
                serverRequest = 4;
                next(4);
            }
            case 4 -> {
                if (serverCompleted != 4 || !terminalScreen.getMenu().getCarried().isEmpty()
                        || !ItemStack.matches(client.player.getInventory().getItem(INVENTORY_INDEX), sample())) return false;
                require(ItemStack.matches(source(), originalPattern), "native return-to-inventory restores all seven items without consuming patterns");
                LogUtils.getLogger().info("EMBEDDED_SMOKE: real carried inventory private-icon selection and server quantities PASS");
                next(5);
                normalGhost = new JeiIconAssertions("normal", targetLocation, FIXTURE_NOTE, Items.EMERALD, false);
            }
            case 5 -> {
                if (!normalGhost.tick(client, terminalScreen)) return false;
                next(6);
                return true;
            }
            default -> { return true; }
        }
        return false;
    }

    private static void clickInventorySlot() throws Exception {
        double x = terminalScreen.getGuiLeft() + inventorySlot.x + 8d;
        double y = terminalScreen.getGuiTop() + inventorySlot.y + 8d;
        logContainerState("before click");
        require(!SidebarClient.contains(terminalScreen, x, y), "fixture inventory slot is not hidden under the sidebar");
        var press = new ScreenEvent.MouseButtonPressed.Pre(terminalScreen, x, y, 0);
        NeoForge.EVENT_BUS.post(press);
        require(!press.isCanceled(), "original inventory slot click reaches the actual AE2 screen");
        terminalScreen.mouseClicked(x, y, 0);
        logContainerState("after native press");
        var release = new ScreenEvent.MouseButtonReleased.Pre(terminalScreen, x, y, 0);
        NeoForge.EVENT_BUS.post(release);
        if (!release.isCanceled()) terminalScreen.mouseReleased(x, y, 0);
        logContainerState("after native release canceled=" + release.isCanceled());
    }
    private static Object containerState(String name) throws Exception {
        Field field = AbstractContainerScreen.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(terminalScreen);
    }
    private static void logContainerState(String boundary) throws Exception {
        LogUtils.getLogger().info("EMBEDDED_SMOKE: icon native inventory phase={} {}, carried={}, slot={}, doubleclick={}, lastClickAgeMs={}, quickCraft={}, skipRelease={}",
            phase, boundary, terminalScreen.getMenu().getCarried().getCount(), inventorySlot.getItem().getCount(),
            containerState("doubleclick"), Util.getMillis() - (long) containerState("lastClickTime"),
            containerState("isQuickCrafting"), containerState("skipNextRelease"));
    }
    private static ItemStack displayIcon(SidebarPackets.Entry entry) throws Exception {
        try { return (ItemStack) SidebarNotes.class.getMethod("icon", SidebarPackets.Entry.class).invoke(null, entry); }
        catch (NoSuchMethodException baseline) { return entry.icon(); }
    }
    static boolean tickWireless(Minecraft client, PatternEncodingTermScreen<?> screen) throws Exception {
        if (wirelessGhost == null) {
            if (entries().size() != 2 || SidebarClient.exclusionZone(screen) == null) return false;
            var target = entries().stream().filter(entry -> SidebarNotes.locationLabel(entry).equals(targetLocation)).findFirst().orElseThrow();
            require(SidebarNotes.icon(target).is(Items.EMERALD) && SidebarNotes.note(target).equals(FIXTURE_NOTE),
                "a genuinely new WET menu preserves the earlier private icon and note at the same provider location");
            wirelessGhost = new JeiIconAssertions("wireless", targetLocation, FIXTURE_NOTE, Items.AMETHYST_SHARD, true);
        }
        if (!wirelessGhost.tick(client, screen)) return false;
        var target = entries().stream().filter(entry -> SidebarNotes.locationLabel(entry).equals(targetLocation)).findFirst().orElseThrow();
        require(SidebarNotes.resetIcon(target), "explicit reset consumer restores the server-provided icon");
        require(ItemStack.matches(SidebarNotes.icon(target), target.icon().copyWithCount(1))
            && SidebarNotes.note(target).equals(FIXTURE_NOTE), "restoring the default icon does not delete its private note");
        require(SidebarNotes.save(target, originalNote), "icon fixture restores the provider's original private note");
        return true;
    }
    private static ItemStack sample() {
        ItemStack stack = new ItemStack(Items.DIAMOND, 7);
        stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(105));
        return stack;
    }
    private static ItemStack source() { return terminalScreen.getMenu().getSlots(SlotSemantics.ENCODED_PATTERN).getFirst().getItem(); }
    @SuppressWarnings("unchecked")
    private static List<SidebarPackets.Entry> entries() throws Exception { return (List<SidebarPackets.Entry>) state("entries"); }
    private static Object state(String name) throws Exception {
        Field field = SidebarClient.class.getDeclaredField(name); field.setAccessible(true); return field.get(null);
    }
    private static void next(int next) { phase = next; phaseStarted = System.nanoTime(); }
    static String report() {
        return "private_icon_real_inventory_pickup_and_click=PASS\n"
            + "private_icon_seven_carried_items_and_encoded_source_unchanged=PASS\n"
            + "private_icon_real_server_provider_inventories_unchanged=PASS\n"
            + "private_icon_actual_new_wet_menu_retains_icon_and_note=PASS\n"
            + "private_icon_reset_consumer_restores_default_preserving_note=PASS\n"
            + IconCodecAssertions.report() + JeiIconAssertions.report();
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError("SMOKE_PRIVATE_ICON_ASSERT: " + message);
    }
}
