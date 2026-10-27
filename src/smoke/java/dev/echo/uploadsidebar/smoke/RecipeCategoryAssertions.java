package dev.echo.uploadsidebar.smoke;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.ids.AEComponents;
import appeng.core.definitions.AEItems;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.client.gui.me.items.PatternEncodingTermScreen;
import appeng.menu.SlotSemantics;
import appeng.menu.me.items.PatternEncodingTermMenu;

import com.mojang.logging.LogUtils;
import dev.echo.uploadsidebar.client.SidebarClient;
import dev.echo.uploadsidebar.client.SidebarMappings;
import dev.echo.uploadsidebar.client.SidebarNotes;
import dev.echo.uploadsidebar.client.SidebarRecipeContext;
import dev.echo.uploadsidebar.core.ProviderLocation;
import dev.echo.uploadsidebar.net.SidebarPackets;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.common.Internal;
import mezz.jei.common.transfer.RecipeTransferUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import appeng.blockentity.crafting.PatternProviderBlockEntity;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/** Real JEI category -> original AE2 filter packets -> real encoding -> sidebar, for both menu classes. */
final class RecipeCategoryAssertions {
    private final String mode;
    private final AtomicInteger uploads = new AtomicInteger();
    private int phase;
    private long since;
    private PatternEncodingTermScreen<?> screen;
    private PatternEncodingTermMenu menu;
    private ItemStack original, blank, expected;
    private String title, note;
    private ProviderLocation target;
    private boolean passed;
    private List<ProviderLocation> autoLocations;
    private List<String> autoNotes;
    private List<List<ItemStack>> autoInventories;

    RecipeCategoryAssertions(String mode) { this.mode = mode; }

    boolean tick(Minecraft client) throws Exception {
        require(client.gameDirectory.toPath().toAbsolutePath().normalize().equals(
            SmokePaths.runDirectory()), "only isolated game directory");
        switch (phase) {
            case 0 -> {
                if (!(client.screen instanceof PatternEncodingTermScreen<?> terminal) || SidebarClient.iconTargets(terminal).size() != 2) return false;
                screen = terminal; menu = terminal.getMenu();
                original = encoded().copy();
                blank = menu.getSlots(SlotSemantics.BLANK_PATTERN).getFirst().getItem().copy();
                var runtime = Internal.getJeiRuntime();
                var manager = runtime.getRecipeManager();
                var category = manager.getRecipeCategory(RecipeTypes.SMELTING);
                title = category.getTitle().getString();
                for (var mapping : SidebarMappings.availableMappings()) {
                    if (List.of("minecraft:smelting", "smelting", title).contains(mapping.key()))
                        SidebarMappings.removeAlias(mapping.key());
                }
                var row = rows().getFirst();
                target = SidebarNotes.providerLocation(row).orElseThrow(); note = SidebarNotes.note(row);
                require(SidebarNotes.save(row, title + "子网"), "label one real provider for category-name search");
                var holder = manager.createRecipeLookup(RecipeTypes.SMELTING).get()
                    .filter(recipe -> recipe.value().getResultItem(client.level.registryAccess()).is(Items.GLASS))
                    .findFirst().orElseThrow();
                expected = PatternDetailsHelper.encodeProcessingPattern(
                    List.of(new GenericStack(AEItemKey.of(Items.SAND), 1)), List.of(new GenericStack(AEItemKey.of(Items.GLASS), 1)));
                var layout = manager.createRecipeLayoutDrawable(category, holder, runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup()).orElseThrow();
                client.getConnection().getConnection().channel().pipeline().addLast("sidebar-category-" + mode, new ChannelOutboundHandlerAdapter() {
                    @Override public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) throws Exception {
                        if (message instanceof ServerboundCustomPayloadPacket packet && packet.payload() instanceof SidebarPackets.Upload) uploads.incrementAndGet();
                        super.write(ctx, message, promise);
                    }
                });
                SidebarRecipeContext.clear();
                RecipeTransferUtil.getTransferRecipeError(runtime.getRecipeTransferManager(), menu, layout, client.player);
                var capturedCategory = SidebarRecipeContext.class.getDeclaredField("category");
                capturedCategory.setAccessible(true);
                require(capturedCategory.get(null) == null, "preview does not capture even a pending category");
                setServerSource(client, ItemStack.EMPTY, blank.isEmpty() ? AEItems.BLANK_PATTERN.stack(2) : blank);
                runtime.getRecipesGui().showRecipes(category, List.of(holder), List.of());
                require(client.screen != screen && client.player.containerMenu == menu, "real JEI recipes screen retains the same live menu");
                require(RecipeTransferUtil.transferRecipe(runtime.getRecipeTransferManager(), menu, layout, client.player, false), "original JEI transfer succeeds");
                client.screen.onClose();
                require(client.screen == screen, "JEI returns to the actual original terminal");
                next(1);
            }
            case 1 -> {
                if (!elapsed() || !encoded().isEmpty() || menu.getSlots(SlotSemantics.BLANK_PATTERN).getFirst().getItem().isEmpty()
                        || SidebarRecipeContext.forPattern(menu, expected) == null) return false;
                menu.encode();
                next(2);
            }
            case 2 -> {
                if (!elapsed() || !encoded().has(AEComponents.ENCODED_PROCESSING_PATTERN)) return false;
                require(SidebarRecipeContext.forPattern(menu, encoded()) != null, "real encoded inputs and outputs match the captured transfer");
                SidebarMappings.tick(menu, encoded());
                require(SidebarMappings.hint().equals("配方类别：" + title), "unmapped processing uses localized JEI category");
                var row = targetRow();
                if (row == null) return false;
                require(SidebarMappings.matches(row), "actual provider note matches the category with no mapping");
                require(SidebarMappings.sortedEntries(rows()).getFirst().token().equals(row.token()), "category match sorts the actual provider first");
                next(3);
            }
            case 3 -> {
                if (!elapsed()) return false;
                SmokeScreenshots.request(client, "sidebar-category-" + mode + ".png");
                next(4);
            }
            case 4 -> {
                if (!SmokeScreenshots.saved("sidebar-category-" + mode + ".png")) return false;
                require(SidebarMappings.saveAlias(title, "映射测试熔炼炉"), "create category-title alias in isolated mapping file");
                SidebarMappings.tick(menu, encoded());
                require(SidebarMappings.hint().equals("自动映射：映射测试熔炼炉"), "explicit category-name alias overrides fallback immediately");
                require(!SidebarMappings.matches(targetRow()), "mapped search does not keep matching the old raw category");
                SidebarMappings.removeAlias(title);
                SidebarMappings.tick(menu, encoded());
                require(SidebarMappings.hint().equals("配方类别：" + title), "removing the alias restores category search");
                var changed = PatternDetailsHelper.encodeProcessingPattern(
                    List.of(new GenericStack(AEItemKey.of(Items.COBBLESTONE), 1)), List.of(new GenericStack(AEItemKey.of(Items.STONE), 1)));
                SidebarMappings.tick(menu, changed);
                require(SidebarMappings.hint().startsWith("机器未知"), "another pattern cannot inherit the transferred recipe category");
                require(SidebarRecipeContext.forPattern(new Object(), encoded()) == null, "different menu cannot inherit the category");
                require(uploads.get() == 0, "unmapped and disabled mappings never upload");
                SidebarMappings.tick(menu, encoded());
                autoLocations = rows().stream().map(row -> SidebarNotes.providerLocation(row).orElseThrow()).toList();
                autoNotes = rows().stream().map(SidebarNotes::note).toList();
                autoInventories = inventories(client, false);
                require(SidebarMappings.saveAlias(title, "自动上传测试炉"), "create explicit auto mapping");
                require(SidebarNotes.save(targetRow(), "自动上传测试炉"), "unique provider matches auto mapping");
                toggleAuto(client, true);
                next(40);
            }
            case 40 -> {
                if (!elapsed()) return false;
                SmokeScreenshots.request(client, "sidebar-auto-upload-" + mode + ".png");
                next(401);
            }
            case 401 -> {
                if (!elapsed() || !SmokeScreenshots.saved("sidebar-auto-upload-" + mode + ".png")) return false;
                require(uploads.get() == 0 && !encoded().isEmpty(), "enabling does not upload existing output");
                client.screen.onClose();
                next(41);
            }
            case 41 -> {
                if (!elapsed() || rows().size() != 2) return false;
                require(uploads.get() == 0, "returning and refreshing do not upload");
                setServerSource(client, ItemStack.EMPTY, AEItems.BLANK_PATTERN.stack(2));
                next(42);
            }
            case 42 -> {
                if (!elapsed() || !encoded().isEmpty()) return false;
                menu.encode();
                next(43);
            }
            case 43 -> {
                if (!elapsed() || uploads.get() != 1 || !encoded().isEmpty()) return false;
                var after = inventories(client, false);
                for (int i = 0; i < autoLocations.size(); i++) {
                    int delta = after.get(i).stream().mapToInt(ItemStack::getCount).sum()
                        - autoInventories.get(i).stream().mapToInt(ItemStack::getCount).sum();
                    require(delta == (autoLocations.get(i).equals(target) ? 1 : 0), "only exact unique provider gains one real pattern");
                }
                if (rows().size() != 2) return false;
                for (var row : rows()) require(SidebarNotes.save(row, "自动上传测试炉"), "both providers match");
                setServerSource(client, ItemStack.EMPTY, AEItems.BLANK_PATTERN.stack(2));
                next(44);
            }
            case 44 -> {
                if (!elapsed() || !encoded().isEmpty()) return false;
                menu.encode();
                next(45);
            }
            case 45 -> {
                if (System.nanoTime() - since < 3_500_000_000L || encoded().isEmpty()) return false;
                require(uploads.get() == 1, "multiple matching targets and subsequent refresh never upload");
                toggleAuto(client, false);
                client.screen.onClose();
                next(46);
            }
            case 46 -> {
                if (!elapsed() || rows().size() != 2) return false;
                for (var row : rows()) {
                    int index = autoLocations.indexOf(SidebarNotes.providerLocation(row).orElseThrow());
                    require(SidebarNotes.save(row, autoNotes.get(index)), "restore provider labels");
                }
                inventories(client, true);
                SidebarMappings.removeAlias(title);
                menu.clear();
                next(5);
            }
            case 5 -> {
                if (!elapsed()) return false;
                require(SidebarRecipeContext.forPattern(menu, encoded()) == null, "editing the real recipe grid invalidates captured category");
                var row = targetRow();
                if (row == null) return false;
                require(SidebarNotes.save(row, note), "restore isolated provider note");
                require(uploads.get() == 1, "only the explicitly enabled unique encoding uploaded");
                setServerSource(client, original, blank);
                next(6);
            }
            case 6 -> {
                if (!elapsed() || !ItemStack.matches(encoded(), original)) return false;
                client.getConnection().getConnection().channel().pipeline().remove("sidebar-category-" + mode);
                passed = true;
                next(7);
                return true;
            }
            default -> { return passed; }
        }
        return false;
    }

    private void setServerSource(Minecraft client, ItemStack source, ItemStack blanks) {
        var playerId = client.player.getUUID();
        int menuId = menu.containerId;
        client.getSingleplayerServer().execute(() -> {
            var player = client.getSingleplayerServer().getPlayerList().getPlayer(playerId);
            require(player != null && player.containerMenu instanceof PatternEncodingTermMenu && player.containerMenu.containerId == menuId,
                "isolated server mutation belongs to the exact active fixture menu");
            var serverMenu = (PatternEncodingTermMenu) player.containerMenu;
            serverMenu.getSlots(SlotSemantics.ENCODED_PATTERN).getFirst().set(source.copy());
            serverMenu.getSlots(SlotSemantics.BLANK_PATTERN).getFirst().set(blanks.copy());
            serverMenu.broadcastChanges();
        });
    }
    private ItemStack encoded() { return menu.getSlots(SlotSemantics.ENCODED_PATTERN).getFirst().getItem(); }
    private void toggleAuto(Minecraft client, boolean expectedEnabled) {
        SidebarMappings.openSelector(screen);
        for (int page = 0; page < 100; page++) {
            var buttons = client.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).toList();
            var choice = buttons.stream().filter(button -> button.getMessage().getString().equalsIgnoreCase(title + " → 自动上传测试炉")).findFirst();
            if (choice.isPresent()) {
                buttons.stream().filter(button -> button.getY() == choice.get().getY()
                    && button.getMessage().getString().startsWith("自动上传：")).findFirst().orElseThrow().onPress();
                var mapping = SidebarMappings.availableMappings().stream().filter(m -> m.key().equalsIgnoreCase(title)).findFirst().orElseThrow();
                require(SidebarMappings.autoUploadEnabled(mapping) == expectedEnabled, "actual per-row toggle saves expected option");
                return;
            }
            var next = buttons.stream().filter(button -> button.getMessage().getString().equals("下一页")).findFirst().orElseThrow();
            require(next.active, "test mapping can be reached");
            next.onPress();
        }
        throw new AssertionError("mapping not found");
    }
    private List<List<ItemStack>> inventories(Minecraft client, boolean restore) throws Exception {
        return client.getSingleplayerServer().submit(() -> {
            var result = new ArrayList<List<ItemStack>>();
            for (int i = 0; i < autoLocations.size(); i++) {
                var location = autoLocations.get(i);
                var block = (PatternProviderBlockEntity) client.getSingleplayerServer().overworld()
                    .getBlockEntity(new BlockPos(location.x(), location.y(), location.z()));
                var inventory = block.getLogic().getPatternInv();
                var stacks = new ArrayList<ItemStack>();
                for (int slot = 0; slot < inventory.size(); slot++) {
                    if (restore) inventory.setItemDirect(slot, autoInventories.get(i).get(slot).copy());
                    stacks.add(inventory.getStackInSlot(slot).copy());
                }
                result.add((List<ItemStack>) stacks);
            }
            return (List<List<ItemStack>>) result;
        }).get(2, TimeUnit.SECONDS);
    }
    private SidebarPackets.Entry targetRow() {
        return rows().stream().filter(row -> SidebarNotes.providerLocation(row).filter(target::equals).isPresent()).findFirst().orElse(null);
    }
    private List<SidebarPackets.Entry> rows() { return SidebarClient.iconTargets(screen).stream().map(row -> row.entry()).toList(); }
    private boolean elapsed() { return System.nanoTime() - since >= 600_000_000L; }
    private void next(int value) {
        phase = value; since = System.nanoTime();
        LogUtils.getLogger().info("SMOKE_CATEGORY: {} phase {}", mode, phase);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError("SMOKE_CATEGORY: " + message);
    }
    String report() {
        return "jei_category_" + mode + "_fallback_mapping_toggle_unique_auto_upload_multiple_manual=" + (passed ? "PASS" : "NOT_RUN") + "\n";
    }
}
