package dev.echo.uploadsidebar.smoke;

import appeng.client.gui.me.items.PatternEncodingTermScreen;
import dev.echo.uploadsidebar.client.SidebarClient;
import dev.echo.uploadsidebar.client.SidebarNotes;
import dev.echo.uploadsidebar.client.SidebarPanel;
import dev.echo.uploadsidebar.net.SidebarPackets;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.common.Internal;
import mezz.jei.gui.input.IDragHandler;
import mezz.jei.gui.input.InputType;
import mezz.jei.gui.input.UserInput;
import mezz.jei.gui.overlay.IngredientListOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Uses JEI's actual visible ingredient list, registered handler order and native drag handler. */
final class JeiIconAssertions {
    private final String mode;
    private final String location;
    private final String note;
    private final Item item;
    private final boolean overlap;
    private final AtomicInteger uploads = new AtomicInteger();
    private int phase;
    private long started;
    private String oldFilter;
    private IJeiRuntime runtime;
    private IngredientListOverlay overlay;
    private IDragHandler drag;
    private SidebarPanel.IconTarget captured;
    private List<ItemStack> inventory;
    private UUID priorToken;
    private ItemStack otherIcon;
    private String otherLocation;
    private final String observer;

    JeiIconAssertions(String mode, String location, String note, Item item, boolean overlap) {
        this.mode = mode;
        this.location = location;
        this.note = note;
        this.item = item;
        this.overlap = overlap;
        this.observer = "sidebar-smoke-jei-icons-" + mode;
    }

    boolean tick(Minecraft client, PatternEncodingTermScreen<?> screen) throws Exception {
        switch (phase) {
            case 0 -> {
                runtime = Internal.getJeiRuntime();
                overlay = (IngredientListOverlay) runtime.getIngredientListOverlay();
                if (!overlay.isListDisplayed() || SidebarClient.iconTargets(screen).size() != 2) return false;
                var handlers = runtime.getScreenHelper().getGhostIngredientHandlers(screen);
                require(handlers.stream().anyMatch(h -> h.getClass().getName().equals(
                    "tamaized.ae2jeiintegration.integration.modules.jei.GhostIngredientHandler")),
                    "AE2's original ghost handler remains registered");
                require(handlers.getFirst().getClass().getName().contains("SidebarJeiPlugin"),
                    "the exact terminal icon handler precedes inherited AE2 ghost handlers");
                require(client.player.getInventory().items.stream().noneMatch(stack -> stack.is(item)),
                    "player does not own the item selected from JEI");
                require(screen.getMenu().getCarried().isEmpty(), "JEI selection starts with an empty real cursor");
                inventory = screen.getMenu().slots.stream().map(slot -> slot.getItem().copy()).toList();
                var other = entries().stream().filter(e -> !SidebarNotes.locationLabel(e).equals(location)).findFirst().orElseThrow();
                otherLocation = SidebarNotes.locationLabel(other);
                otherIcon = SidebarNotes.icon(other);
                client.getConnection().getConnection().channel().pipeline().addLast(observer, new ChannelOutboundHandlerAdapter() {
                    @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                        if (message instanceof ServerboundCustomPayloadPacket packet
                                && packet.payload() instanceof SidebarPackets.Upload) uploads.incrementAndGet();
                        super.write(context, message, promise);
                    }
                });
                if (overlap) placeOverGhostSlot(screen);
                oldFilter = runtime.getIngredientFilter().getFilterText();
                runtime.getIngredientFilter().setFilterText("@minecraft " + new ItemStack(item).getHoverName().getString());
                next(1);
            }
            case 1 -> {
                if (!beginDrag(screen)) return false;
                captured = target(screen);
                next(2);
            }
            case 2 -> {
                if (System.nanoTime() - started < 180_000_000L) return false;
                require(finishDrag(screen, captured), "native JEI drag accepts the provider icon destination");
                require(SidebarNotes.icon(byLocation(location)).is(item), "JEI drag changes the intended provider icon");
                unchanged(screen);
                next(6);
            }
            case 6 -> {
                // Screenshot.grab reads the last framebuffer. Let the new icon actually render first.
                if (System.nanoTime() - started < 250_000_000L) return false;
                SmokeScreenshots.request(client, "sidebar-jei-" + mode + "-icon.png");
                if (!SmokeScreenshots.saved("sidebar-jei-" + mode + "-icon.png")) return false;
                // Capture another real JEI drag, then let the actual server refresh expire its token.
                if (!beginDrag(screen)) return false;
                captured = target(screen);
                priorToken = captured.entry().token();
                next(3);
            }
            case 3 -> {
                if (byLocation(location).token().equals(priorToken)) return false;
                // Make accepting the stale item observable even though the earlier successful drop used it too.
                require(SidebarNotes.saveIcon(byLocation(location), new ItemStack(Items.DIAMOND)), "stale-drop sentinel saved locally");
                require(finishDrag(screen, captured), "JEI consumes a stale target instead of falling through to AE2");
                require(SidebarNotes.icon(byLocation(location)).is(Items.DIAMOND), "refreshed token rejects an in-flight old drop");
                verifyRejectedConsumers(client, screen);
                require(SidebarNotes.saveIcon(byLocation(location), new ItemStack(item)), "restore the successfully selected JEI icon");
                unchanged(screen);
                runtime.getIngredientFilter().setFilterText(oldFilter);
                if (overlap) {
                    LayoutInteractionAssertions.clickControl(screen, "settings");
                    LayoutInteractionAssertions.clickControl(screen, "reset");
                }
                next(4);
            }
            case 4 -> {
                if (!SmokeScreenshots.saved("sidebar-jei-" + mode + "-icon.png")) return false;
                unchanged(screen);
                client.getConnection().getConnection().channel().pipeline().remove(observer);
                next(5);
                return true;
            }
            default -> { return true; }
        }
        return false;
    }

    private boolean beginDrag(PatternEncodingTermScreen<?> screen) {
        // Locate an actual rendered JEI ingredient cell; do not synthesize a draggable ingredient.
        for (int y = 4; y < screen.height; y += 8) {
            for (int x = 4; x < screen.width; x += 8) {
                var candidate = overlay.getDraggableIngredientUnderMouse(x, y)
                    .filter(value -> value.getTypedIngredient().getItemStack().filter(stack -> stack.is(item)).isPresent())
                    .findFirst();
                if (candidate.isEmpty()) continue;
                var selected = overlay.createDragHandler().handleDragStart(screen, input(x, y));
                require(selected.isPresent(), "actual JEI list begins its native ingredient drag");
                drag = selected.orElseThrow();
                return true;
            }
        }
        return false;
    }

    private boolean finishDrag(PatternEncodingTermScreen<?> screen, SidebarPanel.IconTarget destination) {
        var area = destination.bounds();
        return drag.handleDragComplete(screen, input(area.x() + 8, area.y() + 8));
    }

    private void verifyRejectedConsumers(Minecraft client, PatternEncodingTermScreen<?> screen) throws Exception {
        var handler = runtime.getScreenHelper().<PatternEncodingTermScreen<?>>getGhostIngredientHandlers(screen).stream()
            .filter(h -> h.getClass().getName().contains("SidebarJeiPlugin")).findFirst().orElseThrow();
        var typed = runtime.getIngredientManager().createTypedIngredient(VanillaTypes.ITEM_STACK, new ItemStack(item), false).orElseThrow();
        var destination = target(screen);
        IGhostIngredientHandler.Target<ItemStack> receiver = handler.getTargetsTyped(screen, typed, true).stream()
            .filter(t -> t.getArea().contains(destination.bounds().x() + 8, destination.bounds().y() + 8)).findFirst().orElseThrow();
        Object panel = state("panel");
        Field searchField = panel.getClass().getDeclaredField("search");
        searchField.setAccessible(true);
        EditBox search = (EditBox) searchField.get(panel);
        String originalSearch = search.getValue();
        search.setValue("no_such_sidebar_provider_95dfb3");
        receiver.accept(new ItemStack(item));
        require(SidebarNotes.icon(byLocation(location)).is(Items.DIAMOND), "search change rejects captured destination");
        search.setValue(originalSearch);
        LayoutInteractionAssertions.clickControl(screen, "collapse");
        receiver.accept(new ItemStack(item));
        require(handler.getTargetsTyped(screen, typed, false).isEmpty()
            && SidebarNotes.icon(byLocation(location)).is(Items.DIAMOND), "collapsed panel rejects ghost drops");
        LayoutInteractionAssertions.clickControl(screen, "collapse");
        Object realMenu = client.player.containerMenu;
        try {
            client.player.containerMenu = client.player.inventoryMenu;
            receiver.accept(new ItemStack(item));
            require(handler.getTargetsTyped(screen, typed, false).isEmpty(), "different real menu has no icon targets");
        } finally {
            client.player.containerMenu = (net.minecraft.world.inventory.AbstractContainerMenu) realMenu;
        }
        require(SidebarNotes.icon(byLocation(location)).is(Items.DIAMOND), "changed-menu drop cannot write private icon");
        unchanged(screen);
    }

    private void placeOverGhostSlot(PatternEncodingTermScreen<?> screen) throws Exception {
        var fake = screen.getMenu().slots.stream().filter(slot -> slot.getClass().getSimpleName().equals("FakeSlot") && slot.isActive())
            .findFirst().orElseThrow();
        var box = LayoutInteractionAssertions.layout();
        var icon = target(screen).bounds();
        double dx = screen.getGuiLeft() + fake.x + 8 - (icon.x() + 8);
        double dy = screen.getGuiTop() + fake.y + 8 - (icon.y() + 8);
        var method = LayoutInteractionAssertions.class.getDeclaredMethod("drag", PatternEncodingTermScreen.class,
            double.class, double.class, double.class, double.class);
        method.setAccessible(true);
        method.invoke(null, screen, box.x() + 12d, box.y() + 10d, box.x() + 12d + dx, box.y() + 10d + dy);
        var moved = target(screen).bounds();
        require(moved.contains(screen.getGuiLeft() + fake.x + 8, screen.getGuiTop() + fake.y + 8),
            "floating icon overlaps an actual underlying AE2 ghost ingredient slot");
    }

    private void unchanged(PatternEncodingTermScreen<?> screen) throws Exception {
        require(uploads.get() == 0, "JEI icon operations send zero upload packets");
        require(screen.getMenu().getCarried().isEmpty(), "JEI icon drag never creates a real cursor item");
        require(inventory.size() == screen.getMenu().slots.size(), "menu retains its original slots");
        for (int i = 0; i < inventory.size(); i++) require(ItemStack.matches(inventory.get(i), screen.getMenu().slots.get(i).getItem()),
            "JEI drag preserves every real inventory, encoded pattern and recipe ghost slot: " + i);
        require(SidebarNotes.note(byLocation(location)).equals(note), "icon operations preserve the private note");
        require(ItemStack.matches(SidebarNotes.icon(byLocation(otherLocation)), otherIcon), "other same-name provider icon remains unchanged");
    }

    private SidebarPanel.IconTarget target(PatternEncodingTermScreen<?> screen) {
        return SidebarClient.iconTargets(screen).stream().filter(t -> SidebarNotes.locationLabel(t.entry()).equals(location)).findFirst().orElseThrow();
    }
    private SidebarPackets.Entry byLocation(String value) throws Exception {
        return entries().stream().filter(e -> SidebarNotes.locationLabel(e).equals(value)).findFirst().orElseThrow();
    }
    @SuppressWarnings("unchecked")
    private static List<SidebarPackets.Entry> entries() throws Exception { return (List<SidebarPackets.Entry>) state("entries"); }
    private static Object state(String name) throws Exception {
        Field field = SidebarClient.class.getDeclaredField(name); field.setAccessible(true); return field.get(null);
    }
    private static UserInput input(double x, double y) { return UserInput.fromVanilla(x, y, 0, InputType.IMMEDIATE).orElseThrow(); }
    private void next(int value) { phase = value; started = System.nanoTime(); }
    private void require(boolean condition, String message) {
        if (!condition) throw new AssertionError("SMOKE_JEI_ICON_" + mode + ": " + message);
    }
    static String report() {
        return "private_icon_normal_and_wet_actual_jei_list_native_drag=PASS\n"
            + "private_icon_wet_overlap_keeps_original_ae2_ghost_handler_and_recipe_slots=PASS\n"
            + "private_icon_jei_zero_upload_packets_inventory_cursor_and_other_provider_unchanged=PASS\n"
            + "private_icon_stale_drag_after_natural_refresh_consumed_without_write=PASS\n"
            + "private_icon_search_collapse_and_changed_menu_reject_captured_drop=PASS\n";
    }
}
