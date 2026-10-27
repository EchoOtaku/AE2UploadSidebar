package dev.echo.uploadsidebar.smoke;

import appeng.api.parts.PartHelper;
import appeng.api.util.AEColor;
import appeng.blockentity.crafting.PatternProviderBlockEntity;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.implementations.PatternProviderScreen;
import appeng.client.gui.me.items.PatternEncodingTermScreen;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEParts;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.menu.MenuOpener;
import appeng.menu.implementations.PatternProviderMenu;
import appeng.menu.locator.MenuLocators;
import appeng.menu.me.items.PatternEncodingTermMenu;
import dev.echo.uploadsidebar.client.MappingManagerScreen;
import dev.echo.uploadsidebar.client.SidebarMappings;
import com.mojang.logging.LogUtils;
import dev.echo.uploadsidebar.client.NoteEditorScreen;
import dev.echo.uploadsidebar.client.SidebarClient;
import dev.echo.uploadsidebar.client.SidebarNotes;
import dev.echo.uploadsidebar.client.SidebarPanel;
import dev.echo.uploadsidebar.core.ProviderIdentity;
import dev.echo.uploadsidebar.core.ProviderLocation;
import dev.echo.uploadsidebar.core.SidebarSession;
import dev.echo.uploadsidebar.net.SidebarPackets;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import mezz.jei.common.Internal;
import mezz.jei.gui.input.IDragHandler;
import mezz.jei.gui.input.InputType;
import mezz.jei.gui.input.UserInput;
import mezz.jei.gui.overlay.IngredientListOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Direct-provider management in real server-opened menus; all fixture writes stay inside run-test. */
final class ProviderEditorAssertions {
    private static final BlockPos[] POSITIONS = {new BlockPos(1, 180, 0), new BlockPos(3, 180, 2),
        new BlockPos(3, 180, 3), new BlockPos(3, 180, 4)};
    private static final String[] NAMES = {"connected", "new-unpowered", "extended", "cable-part"};
    private final AtomicInteger requests = new AtomicInteger();
    private int index, phase;
    private long since;
    private volatile Throwable failure;
    private volatile ProviderIdentity serverIdentity;
    private volatile boolean fixtureReady;
    private PatternProviderScreen<?> screen;
    private SidebarPanel.IconTarget captured;
    private List<ItemStack> slots;
    private Slot inventorySlot;
    private ItemStack heldBefore;
    private IDragHandler drag;
    private String oldFilter;
    private boolean passed;

    boolean tick(Minecraft client, appeng.parts.encoding.PatternEncodingTerminalPart terminalPart) throws Exception {
        require(client.gameDirectory.toPath().toAbsolutePath().normalize().equals(
            SmokePaths.runDirectory()), "only isolated test game");
        if (failure != null) throw new AssertionError("Provider editor server fixture failed", failure);
        require(client.player != null && client.level != null && client.getConnection() != null,
            "test connection stays live at sample " + index + " phase " + phase);
        switch (phase) {
            case 0 -> {
                client.getConnection().getConnection().channel().pipeline().addLast("sidebar-provider-editor", new ChannelOutboundHandlerAdapter() {
                    @Override public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) throws Exception {
                        if (message instanceof ServerboundCustomPayloadPacket packet && (packet.payload() instanceof SidebarPackets.Upload
                                || packet.payload() instanceof SidebarPackets.Query || packet.payload() instanceof SidebarPackets.NamedQuery)) requests.incrementAndGet();
                        super.write(ctx, message, promise);
                    }
                });
                openProvider(client);
                next(12);
            }
            case 1 -> {
                if (!(client.screen instanceof PatternProviderScreen<?> current) || SidebarClient.iconTargets(current).size() != 1 || serverIdentity == null) {
                    if (System.nanoTime() - since > 5_000_000_000L) {
                        String detail = "screen=" + client.screen + ", identity=" + serverIdentity;
                        if (client.screen instanceof PatternProviderScreen<?> actual) {
                            detail += ", validMenu=" + actual.getMenu().isValidMenu() + ", host=" + actual.getMenu().getTarget();
                            if (actual.getMenu().getTarget() instanceof appeng.parts.AEBasePart part) detail += ", side=" + part.getSide()
                                + ", hostInWorld=" + part.getHost().isInWorld() + ", currentPart=" + (part.getHost().getPart(part.getSide()) == part)
                                + ", removed=" + part.getBlockEntity().isRemoved() + ", currentEntity=" + (client.level.getBlockEntity(POSITIONS[index]) == part.getBlockEntity());
                        }
                        throw new AssertionError("Provider panel unavailable: " + detail);
                    }
                    return false;
                }
                screen = current;
                if (index == 3) require(!((appeng.parts.AEBasePart) screen.getMenu().getTarget()).getHost().isInWorld(),
                    "client cable host has no server-only inWorld flag; actual live part still binds correctly");
                var row = row(); captured = row;
                require(SidebarNotes.providerLocation(row.entry()).orElseThrow().equals(ProviderLocation.parse(serverIdentity.key()).orElseThrow()),
                    "client host identity equals canonical server upload identity, including block/part type and face");
                require(SidebarNotes.canEdit(row.entry()) && SidebarNotes.canEditIcon(row.entry()), "direct target is editable without a terminal query");
                slots = screen.getMenu().slots.stream().map(slot -> slot.getItem().copy()).toList();
                click(row.bounds().x() + 32, row.bounds().y() + 8, 0);
                require(client.screen instanceof NoteEditorScreen, "left click opens actual private note editor");
                var editor = client.screen;
                editor.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow().setValue("直开供应器-" + NAMES[index]);
                editor.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(button -> button.getMessage().getString().equals("保存")).findFirst().orElseThrow().onPress();
                require(client.screen == screen && SidebarNotes.note(row.entry()).equals("直开供应器-" + NAMES[index]), "save returns to exact provider and persists note");
                next(2);
            }
            case 2 -> {
                var bounds = control("mapping_selector");
                click(bounds.x() + 4, bounds.y() + 4, 0);
                require(client.screen.getTitle().getString().equals("选择机器映射"), "provider opens per-mapping options");
                client.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(button -> button.getMessage().getString().equals("管理映射…")).findFirst().orElseThrow().onPress();
                require(SidebarMappings.usesEaep()
                    ? client.screen.getClass().getName().equals("com.extendedae_plus.client.screen.RecipeTypeMappingScreen")
                    : client.screen instanceof MappingManagerScreen, "selector opens the active backend manager");
                next(3);
            }
            case 3 -> {
                if (!elapsed()) return false;
                require(client.player.containerMenu == screen.getMenu(), "mapping manager retains actual provider menu");
                if (index == 0) SmokeScreenshots.request(client, "sidebar-independent-mapping-manager.png");
                client.screen.onClose();
                client.screen.onClose();
                require(client.screen == screen, "mapping manager returns to same provider");
                inventorySlot = screen.getMenu().slots.stream().filter(slot -> slot.container == client.player.getInventory()
                    && slot.getContainerSlot() == 9).findFirst().orElseThrow();
                heldBefore = inventorySlot.getItem().copy();
                require(heldBefore.is(Items.DIAMOND) && heldBefore.getCount() == 7, "seven real inventory items are available");
                inventoryClick();
                require(ItemStack.matches(screen.getMenu().getCarried(), heldBefore), "native provider screen picks up actual inventory items");
                next(4);
            }
            case 4 -> {
                if (!elapsed()) return false;
                var row = row();
                click(row.bounds().x() + 8, row.bounds().y() + 8, 0);
                require(SidebarNotes.icon(row.entry()).is(Items.DIAMOND), "held-item click updates direct provider icon");
                require(ItemStack.matches(screen.getMenu().getCarried(), heldBefore), "icon selection consumes no carried items");
                inventoryClick();
                next(5);
            }
            case 5 -> {
                if (!elapsed()) return false;
                require(ItemStack.matches(inventorySlot.getItem(), heldBefore) && screen.getMenu().getCarried().isEmpty(), "native return preserves all seven items");
                var runtime = Internal.getJeiRuntime();
                oldFilter = runtime.getIngredientFilter().getFilterText();
                runtime.getIngredientFilter().setFilterText("@minecraft " + new ItemStack(Items.GOLD_INGOT).getHoverName().getString());
                next(6);
            }
            case 6 -> {
                if (!beginDrag()) return false;
                next(7);
            }
            case 7 -> {
                if (!elapsed()) return false;
                var row = row();
                require(drag.handleDragComplete(screen, input(row.bounds().x() + 8, row.bounds().y() + 8)), "native JEI drag accepts direct provider icon");
                require(SidebarNotes.icon(row.entry()).is(Items.GOLD_INGOT), "JEI item becomes direct provider icon");
                for (int i = 0; i < slots.size(); i++) require(ItemStack.matches(slots.get(i), screen.getMenu().slots.get(i).getItem()), "management preserves every original slot");
                Internal.getJeiRuntime().getIngredientFilter().setFilterText(oldFilter);
                next(8);
            }
            case 8 -> {
                if (!elapsed()) return false;
                if (index == 0 && !CozyUiAssertions.capture(client, screen)) return false;
                SmokeScreenshots.request(client, "sidebar-provider-" + NAMES[index] + ".png");
                next(9);
            }
            case 9 -> {
                if (!SmokeScreenshots.saved("sidebar-provider-" + NAMES[index] + ".png")) return false;
                require(requests.get() == 0, "direct management emits no sidebar queries or uploads");
                SidebarClient.setEnabled(false);
                require(SidebarClient.iconTargets(screen).isEmpty(), "runtime off removes provider panel");
                require(!SidebarClient.acceptIcon(screen, screen.getMenu(), captured, new ItemStack(Items.EMERALD)), "late icon drop cannot write after disable");
                SidebarClient.setEnabled(true);
                next(10);
            }
            case 10 -> {
                if (!elapsed() || SidebarClient.iconTargets(screen).size() != 1) return false;
                require(SidebarNotes.note(row().entry()).equals("直开供应器-" + NAMES[index]) && SidebarNotes.icon(row().entry()).is(Items.GOLD_INGOT), "re-enable restores exact provider private data");
                var previousMenu = screen.getMenu();
                client.player.closeContainer();
                require(!SidebarClient.acceptIcon(screen, previousMenu, captured, new ItemStack(Items.EMERALD)), "closed menu rejects captured icon destination");
                if (++index < POSITIONS.length) { openProvider(client); next(12); }
                else {
                    require(requests.get() == 0, "all direct provider sessions avoid sidebar network requests");
                    client.getConnection().getConnection().channel().pipeline().remove("sidebar-provider-editor");
                    server(client, player -> require(MenuOpener.open(PatternEncodingTermMenu.TYPE, player,
                        MenuLocators.forPart(terminalPart)), "reopen original terminal"));
                    next(11);
                }
            }
            case 11 -> {
                if (!(client.screen instanceof PatternEncodingTermScreen<?> terminal)) return false;
                var match = SidebarClient.iconTargets(terminal).stream().filter(row -> SidebarNotes.providerLocation(row.entry())
                    .filter(location -> location.x() == 1 && location.y() == 180 && location.z() == 0).isPresent()).findFirst();
                if (match.isEmpty()) return false;
                require(SidebarNotes.note(match.get().entry()).equals("直开供应器-connected") && SidebarNotes.icon(match.get().entry()).is(Items.GOLD_INGOT),
                    "terminal server-query identity reuses annotations saved from direct provider screen");
                passed = true;
                client.player.closeContainer();
                return true;
            }
            case 12 -> {
                if (!fixtureReady) return false;
                var entity = client.level.getBlockEntity(POSITIONS[index]);
                if (index == 3) {
                    if (!(entity instanceof appeng.api.parts.IPartHost cable)
                            || !(cable.getPart(Direction.SOUTH) instanceof PatternContainer)) return false;
                } else if (!(entity instanceof PatternContainer)) return false;
                // A player can only click the new block after it has arrived on the client.
                // setBlockAndUpdate queues tracking updates; opening in the same server tick is too early.
                int sample = index;
                server(client, player -> openSynchronizedMenu(player, sample));
                next(1);
            }
            default -> { return passed; }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private void openProvider(Minecraft client) {
        serverIdentity = null;
        fixtureReady = false;
        int sample = index;
        server(client, player -> {
            var level = player.serverLevel();
            var pos = POSITIONS[sample];
            PatternContainer host;
            if (sample == 3) {
                require(PartHelper.setPart(level, pos, null, player, AEParts.GLASS_CABLE.item(AEColor.TRANSPARENT)) != null, "place disconnected cable");
                var part = PartHelper.setPart(level, pos, Direction.SOUTH, player, AEParts.PATTERN_PROVIDER.get());
                require(part != null, "place disconnected provider part");
                host = part;
            } else {
                if (sample == 1) level.setBlockAndUpdate(pos, AEBlocks.PATTERN_PROVIDER.block().defaultBlockState());
                if (sample == 2) {
                    var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("extendedae:ex_pattern_provider"));
                    require(block != net.minecraft.world.level.block.Blocks.AIR, "installed extended provider exists");
                    level.setBlockAndUpdate(pos, block.defaultBlockState());
                }
                var entity = level.getBlockEntity(pos);
                host = (PatternContainer) entity;
                if (sample == 1) require(!((PatternProviderBlockEntity) entity).getMainNode().isActive(), "new provider is not powered or connected to the original grid");
            }
            fixtureReady = true;
        });
    }
    @SuppressWarnings("unchecked")
    private void openSynchronizedMenu(ServerPlayer player, int sample) throws Exception {
        var entity = player.serverLevel().getBlockEntity(POSITIONS[sample]);
        PatternContainer host;
        if (sample == 3) {
            var part = (appeng.parts.AEBasePart) ((appeng.api.parts.IPartHost) entity).getPart(Direction.SOUTH);
            host = (PatternContainer) part;
            require(MenuOpener.open(PatternProviderMenu.TYPE, player, MenuLocators.forPart(part)), "open real part provider menu");
        } else {
            host = (PatternContainer) entity;
            var type = sample == 2 ? (MenuType<PatternProviderMenu>) Class.forName("com.glodblock.github.extendedae.container.ContainerExPatternProvider").getField("TYPE").get(null) : PatternProviderMenu.TYPE;
            require(MenuOpener.open(type, player, MenuLocators.forBlockEntity(entity)), "open real provider menu");
        }
        var resolver = Class.forName("dev.echo.uploadsidebar.server.ProviderIdentityResolver").getDeclaredMethod("resolve", PatternContainer.class);
        resolver.setAccessible(true);
        serverIdentity = ((java.util.Optional<ProviderIdentity>) resolver.invoke(null, host)).orElseThrow();
    }
    private void server(Minecraft client, ServerAction action) {
        var id = client.player.getUUID();
        var server = client.getSingleplayerServer();
        server.execute(() -> { try { action.run(server.getPlayerList().getPlayer(id)); } catch (Throwable error) { failure = error; } });
    }
    private interface ServerAction { void run(ServerPlayer player) throws Exception; }
    private SidebarPanel.IconTarget row() { return SidebarClient.iconTargets(screen).getFirst(); }
    private SidebarSession.Layout control(String name) throws Exception {
        var field = SidebarClient.class.getDeclaredField("panel"); field.setAccessible(true);
        var method = SidebarPanel.class.getDeclaredMethod("controlBounds", String.class); method.setAccessible(true);
        return (SidebarSession.Layout) method.invoke(field.get(null), name);
    }
    private void click(double x, double y, int button) {
        var press = new ScreenEvent.MouseButtonPressed.Pre(screen, x, y, button); NeoForge.EVENT_BUS.post(press);
        require(press.isCanceled(), "panel consumes direct provider click");
        NeoForge.EVENT_BUS.post(new ScreenEvent.MouseButtonReleased.Pre(screen, x, y, button));
    }
    private void inventoryClick() {
        double x = screen.getGuiLeft() + inventorySlot.x + 8, y = screen.getGuiTop() + inventorySlot.y + 8;
        screen.mouseClicked(x, y, 0); screen.mouseReleased(x, y, 0);
    }
    private boolean beginDrag() {
        var overlay = (IngredientListOverlay) Internal.getJeiRuntime().getIngredientListOverlay();
        for (int y = 4; y < screen.height; y += 8) for (int x = 4; x < screen.width; x += 8) {
            if (overlay.getDraggableIngredientUnderMouse(x, y).noneMatch(value -> value.getTypedIngredient().getItemStack().filter(stack -> stack.is(Items.GOLD_INGOT)).isPresent())) continue;
            var result = overlay.createDragHandler().handleDragStart(screen, input(x, y));
            if (result.isPresent()) { drag = result.get(); return true; }
        }
        return false;
    }
    private static UserInput input(double x, double y) { return UserInput.fromVanilla(x, y, 0, InputType.IMMEDIATE).orElseThrow(); }
    private boolean elapsed() { return System.nanoTime() - since >= 350_000_000L; }
    private void next(int value) { phase = value; since = System.nanoTime(); LogUtils.getLogger().info("SMOKE_PROVIDER_EDITOR: sample {} phase {}", index, phase); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError("SMOKE_PROVIDER_EDITOR: " + message); }
    String report() { return "provider_direct_connected_unpowered_extended_and_part_annotation_jei_mapping_no_upload=" + (passed ? "PASS" : "NOT_RUN") + "\n"
        + "provider_direct_and_terminal_share_canonical_identity_and_private_data=" + (passed ? "PASS" : "NOT_RUN") + "\n"; }
}
