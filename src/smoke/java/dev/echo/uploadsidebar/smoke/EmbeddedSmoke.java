package dev.echo.uploadsidebar.smoke;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.config.Actionable;
import appeng.api.orientation.IOrientationStrategy;
import appeng.api.parts.PartHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.util.AEColor;
import appeng.blockentity.crafting.PatternProviderBlockEntity;
import appeng.blockentity.networking.WirelessAccessPointBlockEntity;
import appeng.client.gui.me.items.PatternEncodingTermScreen;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import appeng.core.definitions.AEParts;
import appeng.items.tools.powered.WirelessTerminalItem;
import appeng.menu.MenuOpener;
import appeng.menu.SlotSemantics;
import appeng.menu.locator.ItemMenuHostLocator;
import appeng.menu.locator.MenuLocators;
import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.parts.encoding.PatternEncodingTerminalPart;
import com.mojang.logging.LogUtils;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Isolated integration fixture; the smoke source set is never part of the release JAR. */
@Mod(value = "sidebar_smoke", dist = Dist.CLIENT)
public final class EmbeddedSmoke {
    private static final boolean MINIMAL = Boolean.getBoolean("sidebar.smoke.minimal");
    private static final Path EXPECTED_RUN_DIRECTORY = SmokePaths.runDirectory();
    private static final String SCREENSHOT_NAME = "embedded-sidebar.png";
    private static final String WIRELESS_SCREENSHOT = "wireless-wet-sidebar.png";
    private static final BlockPos TERMINAL_POS = new BlockPos(0, 180, 0);
    private final long startedAt = System.nanoTime();
    private final String worldId = "ae2-sidebar-smoke-" + UUID.randomUUID();
    private volatile String stage = "waiting for title screen";
    private volatile Throwable failure;
    private volatile boolean worldRequested;
    private volatile boolean assertionsPassed;
    private volatile boolean screenshotSaved;
    private PatternEncodingTerminalPart terminal;
    private PatternProviderBlockEntity first;
    private PatternProviderBlockEntity second;
    private boolean menuOpened;
    private boolean assertionsStarted;
    private boolean screenshotRequested;
    private int screenTicks;
    private int shutdownTicks;
    private volatile boolean clientInteractionPassed;
    private volatile boolean minimalServerInventoryVerified;
    private boolean layoutInteractionPassed;
    private boolean mappingAssertionsPassed;
    private boolean runtimeToggleAssertionsPassed;
    private boolean privateNotesAssertionsPassed;
    private boolean privateIconsAssertionsPassed;
    private boolean normalLocatorPassed;
    private boolean worldLocatorPassed;
    private final ProviderEditorAssertions providerEditor = new ProviderEditorAssertions();
    private boolean providerEditorPassed;
    private final RecipeCategoryAssertions normalCategory = MINIMAL ? null : new RecipeCategoryAssertions("normal");
    private final RecipeCategoryAssertions wirelessCategory = MINIMAL ? null : new RecipeCategoryAssertions("wireless");
    private boolean normalCategoryPassed;
    private boolean wirelessCategoryPassed;
    private volatile boolean wirelessRequested;
    private volatile boolean wirelessOpened;
    private volatile boolean wirelessInventoryPrepared;
    private volatile boolean wirelessInventorySynchronized;
    private ItemStack expectedWirelessStack = ItemStack.EMPTY;
    private volatile boolean wirelessClientPassed;
    private boolean wirelessIconsPassed;
    private volatile boolean wirelessServerPassed;
    private int wirelessRenderTicks;
    private int wirelessProvidersBefore;

    public EmbeddedSmoke() {
        String artifact = System.getProperty("sidebar.smoke.artifact");
        if (artifact != null) {
            try {
                Path loaded = net.neoforged.fml.ModList.get().getModFileById("ae2_upload_sidebar")
                    .getFile().getFilePath();
                if (!Files.isSameFile(Path.of(artifact), loaded))
                    throw new AssertionError("Expected packaged JAR, actually loaded " + loaded);
                String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(loaded)));
                LogUtils.getLogger().info("EMBEDDED_SMOKE: packaged artifact PASS: {} SHA256={}", loaded, hash);
            } catch (Exception e) {
                throw new IllegalStateException("Cannot verify packaged artifact", e);
            }
        }
        if (MINIMAL) {
            var actual = net.neoforged.fml.ModList.get().getMods().stream()
                .map(info -> info.getModId()).collect(java.util.stream.Collectors.toSet());
            var expected = new java.util.HashSet<>(java.util.Set.of("minecraft", "neoforge", "ae2", "ae2_upload_sidebar", "sidebar_smoke"));
            if (Boolean.parseBoolean(System.getProperty("sidebar.smoke.guideMe", "true"))) expected.add("guideme");
            if (!actual.equals(expected)) throw new AssertionError("Minimal mod allowlist mismatch: " + actual);
            LogUtils.getLogger().info("EMBEDDED_SMOKE: minimal mod allowlist PASS: {}", actual);
        }
        NeoForge.EVENT_BUS.addListener(this::clientTick);
        NeoForge.EVENT_BUS.addListener(this::serverTick);
        LogUtils.getLogger().info("EMBEDDED_SMOKE: fixture registered; isolated world {}", worldId);
    }

    private void clientTick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (failure != null) {
            failAndStop(client, failure);
        }
        try {
            if (System.nanoTime() - startedAt > TimeUnit.SECONDS.toNanos(CozyUiAssertions.ENABLED ? 180 : 120)) {
                throw new AssertionError("Bounded smoke timeout at stage: " + stage);
            }
            Path runDirectory = client.gameDirectory.toPath().toAbsolutePath().normalize();
            if (!runDirectory.equals(EXPECTED_RUN_DIRECTORY.toAbsolutePath().normalize())) {
                throw new AssertionError("Refusing smoke fixture outside isolated run-test: " + runDirectory);
            }
            if (!worldRequested && client.getOverlay() == null) {
                if (client.screen instanceof AccessibilityOnboardingScreen) {
                    client.setScreen(new TitleScreen());
                    return;
                }
                if (client.screen instanceof TitleScreen) {
                    if (!CozyUiAssertions.prepare(client)) return;
                    client.options.guiScale().set(2);
                    client.resizeDisplay();
                    if (Files.exists(runDirectory.resolve("saves").resolve(worldId))) {
                        throw new AssertionError("Smoke world unexpectedly already exists: " + worldId);
                    }
                    worldRequested = true;
                    stage = "creating fresh world";
                    LogUtils.getLogger().info("EMBEDDED_SMOKE: creating {}", worldId);
                    client.createWorldOpenFlows().createFreshLevel(
                        worldId,
                        new LevelSettings(worldId, GameType.CREATIVE, false,
                            Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                        new WorldOptions(20260906L, false, false),
                        WorldPresets::createNormalWorldDimensions, client.screen);
                }
            }
            if (mappingAssertionsPassed && !privateNotesAssertionsPassed && client.getOverlay() == null) {
                stage = "checking private provider notes through real sidebar gestures";
                privateNotesAssertionsPassed = PrivateNotesAssertions.tick(client);
                if (!privateNotesAssertionsPassed) return;
                LogUtils.getLogger().info("EMBEDDED_SMOKE: private provider note interaction assertions PASS");
            }
            if (privateNotesAssertionsPassed && !privateIconsAssertionsPassed && client.getOverlay() == null) {
                stage = "checking private provider icon selection from actual carried inventory";
                privateIconsAssertionsPassed = MINIMAL || PrivateIconsAssertions.tick(client);
                if (!privateIconsAssertionsPassed) return;
            }
            if (assertionsPassed && !screenshotRequested
                    && client.screen instanceof PatternEncodingTermScreen<?> encodingScreen
                    && client.getOverlay() == null) {
                if (!clientInteractionPassed) {
                    if (!runtimeToggleAssertionsPassed) {
                        stage = "checking runtime client off/on/status commands and disabled event/network behavior";
                        runtimeToggleAssertionsPassed = RuntimeToggleAssertions.tick(client, encodingScreen);
                        if (!runtimeToggleAssertionsPassed) return;
                    }
                    if (!mappingAssertionsPassed) {
                        if (dev.echo.uploadsidebar.client.SidebarClient.exclusionZone(encodingScreen) == null) return;
                        stage = "checking native EAEP mapping selector and manager integration";
                        MappingAssertions.verify(client, encodingScreen);
                        mappingAssertionsPassed = true;
                        LogUtils.getLogger().info("EMBEDDED_SMOKE: native EAEP mapping assertions PASS");
                    }
                    if (!privateNotesAssertionsPassed || !privateIconsAssertionsPassed) return;
                    if (!normalLocatorPassed) {
                        LocatorAssertions.verifyInMenu(client, encodingScreen);
                        normalLocatorPassed = true;
                    }
                    if (!layoutInteractionPassed) {
                        stage = "checking 0.3 drag, collapse, lock, sizing and theme controls";
                        layoutInteractionPassed = LayoutInteractionAssertions.tick(client, encodingScreen);
                        if (!layoutInteractionPassed) return;
                        LogUtils.getLogger().info("EMBEDDED_SMOKE: 0.3 layout and input assertions PASS");
                    }
                    stage = "checking embedded client input and real network upload";
                    clientInteractionPassed = ClientInteractionAssertions.tick(client, encodingScreen);
                    if (!clientInteractionPassed) return;
                    LogUtils.getLogger().info("EMBEDDED_SMOKE: client input and C2S/S2C upload assertions PASS");
                }
                stage = "waiting for embedded terminal to render";
                if (++screenTicks >= 20) {
                    screenshotRequested = true;
                    stage = "saving embedded terminal screenshot";
                    SmokeScreenshots.request(client, SCREENSHOT_NAME);
                }
            } else if (!screenshotRequested) {
                screenTicks = 0;
            }
            if (screenshotRequested) screenshotSaved = SmokeScreenshots.saved(SCREENSHOT_NAME);
            if (MINIMAL && screenshotSaved) {
                if (!minimalServerInventoryVerified) return;
                if (++shutdownTicks >= 20) {
                    stage = "complete minimal environment";
                    writeReport(client, "SMOKE_PASS\nminimal_mode=true\nworld=" + worldId
                        + "\nloaded_mods=" + net.neoforged.fml.ModList.get().getMods().stream()
                            .map(info -> info.getModId()).sorted().collect(java.util.stream.Collectors.joining(",")) + "\n"
                        + "exact_mod_allowlist=PASS\npacket_codec=PASS\nserver_transaction_invariants=PASS\n"
                        + "embedded_real_network_upload_3_to_2=PASS\n"
                        + "minimal_server_source_two_provider_total_one=PASS\n"
                        + RuntimeToggleAssertions.report() + MappingAssertions.report() + PrivateNotesAssertions.report()
                        + LayoutInteractionAssertions.report() + SmokeScreenshots.report()
                        + "optional_jei_wireless_extended_tests=NOT_APPLICABLE\n");
                    client.stop();
                }
                return;
            }
            if (screenshotSaved && !normalCategoryPassed) {
                stage = "checking real JEI category transfer and unmapped recipe search on normal terminal";
                normalCategoryPassed = normalCategory.tick(client);
                if (!normalCategoryPassed) return;
            }
            if (screenshotSaved && !wirelessRequested) {
                ClientInteractionAssertions.reset();
                wirelessRequested = true;
                stage = "opening a real linked and charged wireless encoding terminal";
            }
            if (wirelessInventoryPrepared && !wirelessInventorySynchronized && client.player != null
                    && ItemStack.matches(expectedWirelessStack, client.player.getInventory().getItem(0))) {
                wirelessInventorySynchronized = true;
                LogUtils.getLogger().info("EMBEDDED_SMOKE: client received the real linked/charged wireless item in inventory slot 0");
            }
            if (wirelessOpened && client.screen instanceof PatternEncodingTermScreen<?> wirelessScreen
                    && wirelessScreen.getClass().getName().equals("de.mari_023.ae2wtlib.wet.WETScreen")
                    && client.getOverlay() == null) {
                if (!wirelessIconsPassed) {
                    stage = "checking actual JEI icon drags on the WET screen";
                    wirelessIconsPassed = PrivateIconsAssertions.tickWireless(client, wirelessScreen);
                    if (!wirelessIconsPassed) return;
                }
                if (!wirelessClientPassed) {
                    stage = "checking real WET screen input and C2S/S2C upload";
                    wirelessClientPassed = ClientInteractionAssertions.tick(client, wirelessScreen);
                    if (!wirelessClientPassed) return;
                    LogUtils.getLogger().info("EMBEDDED_SMOKE: real WET client input and upload assertions PASS");
                }
                if (++wirelessRenderTicks >= 20) SmokeScreenshots.request(client, WIRELESS_SCREENSHOT);
            }
            if (wirelessServerPassed && SmokeScreenshots.saved(WIRELESS_SCREENSHOT) && !worldLocatorPassed) {
                if (!wirelessCategoryPassed) {
                    stage = "checking real JEI category transfer and unmapped recipe search on wireless terminal";
                    wirelessCategoryPassed = wirelessCategory.tick(client);
                    if (!wirelessCategoryPassed) return;
                }
                stage = "checking middle-click locator and real world highlight after closing WET";
                worldLocatorPassed = LocatorAssertions.tickWorld(client);
                if (!worldLocatorPassed) return;
            }
            if (worldLocatorPassed && !providerEditorPassed) {
                stage = "checking direct provider note, icon and mapping management on connected, unpowered, extended and part hosts";
                providerEditorPassed = providerEditor.tick(client, terminal);
                if (!providerEditorPassed) return;
            }
            if (wirelessServerPassed && worldLocatorPassed && providerEditorPassed && SmokeScreenshots.saved(WIRELESS_SCREENSHOT) && ++shutdownTicks >= 20) {
                stage = "complete";
                if (net.neoforged.fml.ModList.get().isLoaded("extendedae_plus") != Boolean.getBoolean("sidebar.smoke.plus"))
                    throw new AssertionError("Unexpected EAEP test mode");
                writeReport(client, "SMOKE_PASS\nexpected_eaep_presence=PASS\neaep_mode=" + Boolean.getBoolean("sidebar.smoke.plus") + "\nworld=" + worldId
                    + "\npacket_codec=PASS\nserver_transaction_invariants=PASS\n"
                    + "embedded_real_network_upload_3_to_2=PASS\n"
                    + "wireless_wet_real_screen_input_and_upload_3_to_2=PASS\n"
                    + "wireless_provider_inventory_delta_one=PASS\n"
                    + RuntimeToggleAssertions.report() + MappingAssertions.report() + PrivateNotesAssertions.report() + PrivateIconsAssertions.report()
                    + LayoutInteractionAssertions.report() + LocatorAssertions.report() + normalCategory.report() + wirelessCategory.report() + providerEditor.report() + CozyUiAssertions.report() + SmokeScreenshots.report());
                LogUtils.getLogger().info("EMBEDDED_SMOKE: SMOKE_PASS; stopping isolated client");
                client.stop();
            }
        } catch (Throwable error) {
            failure = error;
            failAndStop(client, error);
        }
    }

    private void serverTick(ServerTickEvent.Post event) {
        if (!worldRequested || failure != null
                || !worldId.equals(event.getServer().getWorldData().getLevelName())) {
            return;
        }
        try {
            List<ServerPlayer> players = event.getServer().getPlayerList().getPlayers();
            if (players.isEmpty()) {
                return;
            }
            ServerPlayer player = players.getFirst();
            ServerLevel level = event.getServer().overworld();
            if (wirelessRequested) {
                verifyWireless(level, player);
                return;
            }
            if (assertionsStarted) {
                if (MINIMAL && clientInteractionPassed) {
                    if (!minimalServerInventoryVerified) {
                        int patterns = 0;
                        for (var position : List.of(TERMINAL_POS.east(), TERMINAL_POS.north())) {
                            var provider = (PatternProviderBlockEntity) level.getBlockEntity(position);
                            for (var stack : provider.getLogic().getPatternInv()) patterns += stack.getCount();
                        }
                        if (terminal.getLogic().getEncodedPatternInv().getStackInSlot(0).getCount() != 2 || patterns != 1)
                            throw new AssertionError("Minimal real upload must leave two source patterns and one provider pattern");
                        minimalServerInventoryVerified = true;
                    }
                    return;
                }
                PrivateNotesAssertions.serverTick(player, terminal);
                if (!MINIMAL) PrivateIconsAssertions.serverTick(player, terminal);
                return;
            }
            if (terminal == null) {
                stage = "building isolated powered AE2 fixture";
                buildFixture(level, player);
                return;
            }
            if (!terminal.getMainNode().isActive() || !first.getMainNode().isActive()
                    || !second.getMainNode().isActive()) {
                stage = "waiting for AE2 grid power and channels";
                return;
            }
            if (!menuOpened) {
                stage = "opening real pattern encoding terminal menu";
                menuOpened = MenuOpener.open(PatternEncodingTermMenu.TYPE, player,
                    MenuLocators.forPart(terminal));
                if (!menuOpened) {
                    throw new AssertionError("Could not open PatternEncodingTermMenu");
                }
                return;
            }
            if (!(player.containerMenu instanceof PatternEncodingTermMenu)) {
                throw new AssertionError("The real PatternEncodingTermMenu was not retained");
            }
            assertionsStarted = true;
            stage = "checking server upload invariants";
            PacketAssertions.verify(player, terminal.getLogic().getEncodedPatternInv().getStackInSlot(0));
            LogUtils.getLogger().info("EMBEDDED_SMOKE: packet codec assertions PASS");
            ServerUploadAssertions.verify(player, terminal, first, second);
            PrivateNotesAssertions.prepareServerFixture(player, terminal);
            assertionsPassed = true;
            stage = "waiting for real terminal client screen";
            LogUtils.getLogger().info("EMBEDDED_SMOKE: server upload assertions PASS");
        } catch (Throwable error) {
            failure = error;
            LogUtils.getLogger().error("EMBEDDED_SMOKE: SMOKE_FAILED at {}", stage, error);
        }
    }

    private void buildFixture(ServerLevel level, ServerPlayer player) {
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                level.setBlockAndUpdate(new BlockPos(x, 179, z), Blocks.SMOOTH_STONE.defaultBlockState());
            }
        }
        level.setDayTime(6000);
        level.setBlockAndUpdate(TERMINAL_POS.west(),
            AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState());
        level.setBlockAndUpdate(TERMINAL_POS.east(),
            AEBlocks.PATTERN_PROVIDER.block().defaultBlockState());
        level.setBlockAndUpdate(TERMINAL_POS.north(),
            AEBlocks.PATTERN_PROVIDER.block().defaultBlockState());
        var accessPointState = AEBlocks.WIRELESS_ACCESS_POINT.block().defaultBlockState();
        level.setBlockAndUpdate(TERMINAL_POS.above(),
            IOrientationStrategy.get(accessPointState).setFacing(accessPointState, Direction.UP));
        if (PartHelper.setPart(level, TERMINAL_POS, null, player,
                AEParts.GLASS_CABLE.item(AEColor.TRANSPARENT)) == null) {
            throw new AssertionError("Could not place fixture cable");
        }
        terminal = PartHelper.setPart(level, TERMINAL_POS, Direction.SOUTH, player,
            AEParts.PATTERN_ENCODING_TERMINAL.get());
        if (terminal == null) {
            throw new AssertionError("Could not place fixture pattern encoding terminal");
        }
        first = (PatternProviderBlockEntity) level.getBlockEntity(TERMINAL_POS.east());
        second = (PatternProviderBlockEntity) level.getBlockEntity(TERMINAL_POS.north());
        if (first == null || second == null) {
            throw new AssertionError("Fixture providers did not create block entities");
        }
        ItemStack source = PatternDetailsHelper.encodeProcessingPattern(
            List.of(new GenericStack(AEItemKey.of(Items.COBBLESTONE), 1)),
            List.of(new GenericStack(AEItemKey.of(Items.STONE), 1)));
        source.setCount(3);
        terminal.getLogic().getEncodedPatternInv().setItemDirect(0, source);
        terminal.getLogic().getBlankPatternInv().setItemDirect(0, AEItems.BLANK_PATTERN.stack(16));
        player.teleportTo(level, 0.5, 180, 3.5, 180, 15);
        LogUtils.getLogger().info("EMBEDDED_SMOKE: powered fixture placed at {}; source count {}",
            TERMINAL_POS, terminal.getLogic().getEncodedPatternInv().getStackInSlot(0).getCount());
    }

    private void verifyWireless(ServerLevel level, ServerPlayer player) throws ReflectiveOperationException {
        if (!wirelessOpened) {
            if (!(level.getBlockEntity(TERMINAL_POS.above()) instanceof WirelessAccessPointBlockEntity accessPoint)
                    || !accessPoint.isActive()) {
                stage = "waiting for wireless access point on the fixture grid";
                return;
            }
            if (!wirelessInventoryPrepared) {
                ItemStack wireless = new ItemStack(BuiltInRegistries.ITEM.get(
                    ResourceLocation.fromNamespaceAndPath("ae2wtlib", "wireless_pattern_encoding_terminal")));
                if (!(wireless.getItem() instanceof WirelessTerminalItem item)) {
                    throw new AssertionError("Real AE2WTLib wireless encoding terminal is unavailable");
                }
                WirelessTerminalItem.LINKABLE_HANDLER.link(wireless, GlobalPos.of(level.dimension(), TERMINAL_POS.above()));
                item.injectAEPower(wireless, item.getAEMaxPower(wireless), Actionable.MODULATE);
                if (item.getLinkedGrid(wireless, level, null) != accessPoint.getGrid()) {
                    throw new AssertionError("Wireless fixture did not link to its real powered access-point grid");
                }
                player.closeContainer();
                player.getInventory().setItem(0, wireless);
                expectedWirelessStack = wireless.copy();
                player.inventoryMenu.broadcastChanges();
                wirelessInventoryPrepared = true;
                stage = "waiting for actual client wireless item inventory synchronization";
                LogUtils.getLogger().info("EMBEDDED_SMOKE: prepared wireless fixture slot=0, item={}, power={}/{}, link={}, activeAccessPoint={}",
                    BuiltInRegistries.ITEM.getKey(wireless.getItem()), item.getAECurrentPower(wireless),
                    item.getAEMaxPower(wireless), item.getLinkedPosition(wireless), accessPoint.isActive());
                return;
            }
            if (!wirelessInventorySynchronized) return;
            ItemStack wireless = player.getInventory().getItem(0);
            if (!(wireless.getItem() instanceof WirelessTerminalItem item)) {
                throw new AssertionError("Synchronized wireless fixture is no longer in inventory slot 0");
            }
            var locator = MenuLocators.forInventorySlot(0);
            // ItemWT inherits AE2's openFromInventory, which opens the base wireless menu.
            // Its own public tryOpen dispatches getMenuType(locator, player) to ItemWET.
            MenuType<?> expectedType = (MenuType<?>) item.getClass()
                .getMethod("getMenuType", ItemMenuHostLocator.class, Player.class).invoke(item, locator, player);
            stage = "opening the synchronized item through the real WTlib tryOpen API";
            LogUtils.getLogger().info("EMBEDDED_SMOKE: opening WTlib tryOpen; baseMenuType={}, resolvedMenuType={}, synchronizedInventory={}",
                BuiltInRegistries.MENU.getKey(item.getMenuType()), BuiltInRegistries.MENU.getKey(expectedType), wirelessInventorySynchronized);
            boolean opened = (boolean) item.getClass().getMethod("tryOpen", Player.class, ItemMenuHostLocator.class, boolean.class)
                .invoke(item, player, locator, false);
            LogUtils.getLogger().info("EMBEDDED_SMOKE: WTlib tryOpen returned {}; actualMenuClass={}, actualMenuType={}",
                opened, player.containerMenu.getClass().getName(), player.containerMenu == player.inventoryMenu
                    ? "player_inventory" : BuiltInRegistries.MENU.getKey(player.containerMenu.getType()));
            if (!opened) {
                throw new AssertionError("WTlib tryOpen returned false after the linked and charged item was synchronized");
            }
            if (!player.containerMenu.getClass().getName().equals("de.mari_023.ae2wtlib.wet.WETMenu")
                    || player.containerMenu.getType() != expectedType) {
                throw new AssertionError("WTlib tryOpen opened a different menu: " + player.containerMenu.getClass().getName());
            }
            PatternEncodingTermMenu wirelessMenu = (PatternEncodingTermMenu) player.containerMenu;
            wirelessMenu.getSlots(SlotSemantics.ENCODED_PATTERN).getFirst()
                .set(terminal.getLogic().getEncodedPatternInv().getStackInSlot(0).copyWithCount(3));
            wirelessMenu.broadcastChanges();
            wirelessProvidersBefore = providerPatternCount(level);
            wirelessOpened = true;
            LogUtils.getLogger().info("EMBEDDED_SMOKE: opened real linked WETMenu, provider patterns={}", wirelessProvidersBefore);
        } else if (wirelessClientPassed && !wirelessServerPassed) {
            if (!(player.containerMenu instanceof PatternEncodingTermMenu menu)
                    || !menu.getClass().getName().equals("de.mari_023.ae2wtlib.wet.WETMenu")
                    || menu.getSlots(SlotSemantics.ENCODED_PATTERN).getFirst().getItem().getCount() != 2) {
                throw new AssertionError("Wireless upload did not retain its real menu and consume exactly one pattern");
            }
            if (providerPatternCount(level) != wirelessProvidersBefore + 1) {
                throw new AssertionError("Wireless upload did not add exactly one pattern to the real providers");
            }
            if (terminal.getLogic().getEncodedPatternInv().getStackInSlot(0).getCount() != 2) {
                throw new AssertionError("Wireless upload touched the separate block terminal inventory");
            }
            wirelessServerPassed = true;
            LogUtils.getLogger().info("EMBEDDED_SMOKE: real WET server inventory assertions PASS");
        }
    }

    private static int providerPatternCount(ServerLevel level) {
        int count = 0;
        for (BlockPos position : List.of(TERMINAL_POS.east(), TERMINAL_POS.north())) {
            PatternProviderBlockEntity provider = (PatternProviderBlockEntity) level.getBlockEntity(position);
            var inventory = provider.getLogic().getPatternInv();
            for (int slot = 0; slot < inventory.size(); slot++) count += inventory.getStackInSlot(slot).getCount();
        }
        return count;
    }

    private void failAndStop(Minecraft client, Throwable error) {
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        try {
            writeReport(client, "SMOKE_FAILED\nworld=" + worldId + "\nstage=" + stage + "\n" + trace);
        } catch (Throwable reportError) {
            error.addSuppressed(reportError);
        }
        LogUtils.getLogger().error("EMBEDDED_SMOKE: SMOKE_FAILED at {}", stage, error);
        client.stop();
        throw new AssertionError("EMBEDDED_SMOKE: SMOKE_FAILED at " + stage, error);
    }

    private static void writeReport(Minecraft client, String report) throws java.io.IOException {
        Path runDirectory = client.gameDirectory.toPath().toAbsolutePath().normalize();
        if (!runDirectory.equals(EXPECTED_RUN_DIRECTORY.toAbsolutePath().normalize())) {
            throw new java.io.IOException("Refusing smoke report outside isolated run-test: " + runDirectory);
        }
        Files.writeString(runDirectory.resolve("embedded-smoke-result.txt"), report);
    }
}
