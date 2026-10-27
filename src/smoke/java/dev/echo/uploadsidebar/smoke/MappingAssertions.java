package dev.echo.uploadsidebar.smoke;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.client.gui.me.items.PatternEncodingTermScreen;
import appeng.menu.SlotSemantics;
import dev.echo.uploadsidebar.client.MappingManagerScreen;

import dev.echo.uploadsidebar.client.SidebarJeiPlugin;
import dev.echo.uploadsidebar.client.SidebarClient;
import dev.echo.uploadsidebar.client.SidebarMappings;
import dev.echo.uploadsidebar.net.SidebarPackets;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.fml.loading.FMLPaths;

/** Real-client mapping boundary checks; no terminal or provider inventory is ever written here. */
final class MappingAssertions {
    private static final Path EXPECTED_RUN_DIRECTORY = SmokePaths.runDirectory();
    private static final String FIXTURE_MACHINE = "映射测试装配室";
    private static boolean passed;

    static void verify(Minecraft client, PatternEncodingTermScreen<?> terminal) throws Exception {
        if (passed) return;
        Path expected = EXPECTED_RUN_DIRECTORY.toAbsolutePath().normalize();
        require(client.gameDirectory.toPath().toAbsolutePath().normalize().equals(expected),
            "mapping fixture refuses a non-isolated game directory");
        require(FMLPaths.CONFIGDIR.get().toAbsolutePath().normalize().equals(expected.resolve("config")),
            "mapping fixture refuses any configuration directory outside isolated run-test/config");
        require(client.player != null && client.level != null && client.player.containerMenu == terminal.getMenu(),
            "mapping fixture requires the live terminal menu");
        var menu = terminal.getMenu();
        ItemStack original = menu.getSlots(SlotSemantics.ENCODED_PATTERN).getFirst().getItem().copy();
        Object originalSession = field(SidebarClient.class, "session");

        try {
            // The smoke source set is absent from the release JAR. This explicit write is guarded above.
            var holder = client.level.getRecipeManager().byKey(ResourceLocation.withDefaultNamespace("crafting_table"))
                .orElseThrow(() -> new AssertionError("Client is missing the real vanilla crafting-table recipe"));
            require(holder.value() instanceof CraftingRecipe, "fixture uses a real synced crafting recipe");
            ItemStack[] inputs = new ItemStack[9];
            Arrays.fill(inputs, ItemStack.EMPTY);
            for (int slot : new int[] {0, 1, 3, 4}) inputs[slot] = new ItemStack(Items.OAK_PLANKS);
            ItemStack crafting = PatternDetailsHelper.encodeCraftingPattern(
                new RecipeHolder<>(holder.id(), (CraftingRecipe) holder.value()),
                inputs, new ItemStack(Items.CRAFTING_TABLE), false, false);
            require(PatternDetailsHelper.decodePattern(crafting, client.level) != null, "real encoded crafting sample decodes");
            String title = SidebarJeiPlugin.categoryTitle(ResourceLocation.withDefaultNamespace("crafting"));
            require(!title.isBlank(), "actual JEI crafting category has a display title");
            for (var mapping : SidebarMappings.availableMappings()) {
                if (List.of("minecraft:crafting", "crafting", title).contains(mapping.key()))
                    SidebarMappings.removeAlias(mapping.key());
            }
            SidebarMappings.tick(menu, crafting);
            require(SidebarMappings.hint().equals("配方类别：" + title), "unmapped crafting uses the actual localized JEI category title");
            require(SidebarMappings.matches(entry(title + "子网", 2)), "unmapped crafting still searches providers by category name");
            require(SidebarMappings.saveAlias("minecraft:crafting", FIXTURE_MACHINE), "save isolated shared mapping fixture");
            SidebarMappings.tick(menu, crafting);
            require(SidebarMappings.hint().equals("自动映射：" + FIXTURE_MACHINE), "crafting reuses the shared configured machine mapping");

            var other = entry("其他设备", 4);
            var full = entry(FIXTURE_MACHINE, 0);
            var first = entry(FIXTURE_MACHINE, 2);
            var second = entry(FIXTURE_MACHINE, 3);
            List<SidebarPackets.Entry> sorted = SidebarMappings.sortedEntries(List.of(other, full, first, second));
            require(sorted.equals(List.of(first, second, full, other)), "real Entry instances retain tokens and group recommendations");
            require(sorted.get(0) == first && sorted.get(1) == second && !first.token().equals(second.token()),
                "same-named machines remain independent upload targets");
            require(SidebarMappings.matches(first) && !SidebarMappings.matches(other), "only configured machine names match");

            SidebarMappings.tick(menu, original);
            require(SidebarMappings.hint().startsWith("机器未知"), "processing sample has no fabricated recipe context");
            SidebarMappings.setManualKey(FIXTURE_MACHINE);
            require(SidebarMappings.hint().equals("手选映射：" + FIXTURE_MACHINE), "explicit selection is distinguished from automatic context");
            ItemStack changed = PatternDetailsHelper.encodeProcessingPattern(
                List.of(new GenericStack(AEItemKey.of(Items.SAND), 1)),
                List.of(new GenericStack(AEItemKey.of(Items.GLASS), 1)));
            SidebarMappings.tick(menu, changed);
            require(SidebarMappings.hint().startsWith("机器未知"), "changed processing content clears previous selection");
            SidebarMappings.tick(menu, original);

            SidebarMappings.openSelector(terminal);
            Screen selector = client.screen;
            require(selector != terminal && SidebarMappings.isAuxiliaryScreen(selector, terminal), "selector belongs to the terminal");
            assertAuxiliaryPreservesContext(client, terminal, originalSession);
            SidebarMappings.openManager(selector);
            if (SidebarMappings.usesEaep()) {
                require(client.screen.getClass().getName().equals("com.extendedae_plus.client.screen.RecipeTypeMappingScreen"), "opens real EAEP manager");
                var api = Class.forName("com.extendedae_plus.util.uploadPattern.ExtendedAEPatternUploadUtil");
                var change = api.getMethod("addOrUpdateRecipeTypeMapping", String.class, String.class);
                require(Boolean.TRUE.equals(change.invoke(null, "minecraft:crafting", "EAEP外部修改测试")), "external EAEP edit succeeds");
                SidebarMappings.tick(menu, crafting);
                require(SidebarMappings.hint().equals("自动映射：EAEP外部修改测试"), "EAEP edits immediately change sidebar mapping");
                change.invoke(null, "minecraft:crafting", FIXTURE_MACHINE);
                SidebarMappings.tick(menu, original);
            } else {
                require(client.screen instanceof MappingManagerScreen, "opens independent mapping management screen");
            var manager = client.screen;
            var fields = manager.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).toList();
            require(fields.size() == 2, "independent editor has category and machine fields");
            fields.get(0).setValue("独立界面测试"); fields.get(1).setValue("独立机器一");
            press(manager, "保存 / 新增");
            require(SidebarMappings.availableMappings().stream().anyMatch(m -> m.key().equals("独立界面测试") && m.value().equals("独立机器一")), "manager creates mapping without Plus");
            fields = manager.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).toList();
            fields.get(1).setValue("独立机器二");
            press(manager, "保存 / 新增");
            require(SidebarMappings.availableMappings().stream().anyMatch(m -> m.key().equals("独立界面测试") && m.value().equals("独立机器二")), "manager edits mapping without Plus");
            press(manager, "删除此类别映射");
            require(SidebarMappings.availableMappings().stream().noneMatch(m -> m.key().equals("独立界面测试")), "manager removes only selected mapping");
            }
            assertAuxiliaryPreservesContext(client, terminal, originalSession);
            client.screen.onClose();
            require(client.screen == selector, "native manager returns to the same selector");
            assertAuxiliaryPreservesContext(client, terminal, originalSession);
            Button choice = selector.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.getMessage().getString().endsWith(" → " + FIXTURE_MACHINE)).findFirst()
                .orElseThrow(() -> new AssertionError("Selector does not list the existing crafting mapping"));
            require(choice.active, "existing mapping is selectable for the current processing pattern");
            choice.onPress();
            require(client.screen == terminal && client.player.containerMenu == menu, "selecting an existing mapping returns to the original menu");
            require(SidebarMappings.hint().equals("手选映射：" + FIXTURE_MACHINE), "selector applies explicit choice to actual encoded output");
            require(field(SidebarClient.class, "session") == originalSession, "auxiliary flow preserves the same request session");
            require(((List<?>) field(SidebarClient.class, "entries")).isEmpty(),
                "auxiliary flow clears stale provider tickets until the next terminal query");
            require(ItemStack.matches(original, menu.getSlots(SlotSemantics.ENCODED_PATTERN).getFirst().getItem()),
                "mapping operations never consume or replace the actual encoded source stack");
            if (SidebarMappings.usesEaep()) require(field(SidebarMappings.class, "mappingStore") == null,
                "EAEP mode never initializes or writes independent mappings");
            passed = true;
        } finally {
            if (client.player != null && client.player.containerMenu == menu) client.setScreen(terminal);
            SidebarMappings.tick(menu, original);
            SidebarMappings.setManualKey("");
        }
    }

    private static SidebarPackets.Entry entry(String name, int free) {
        return new SidebarPackets.Entry(UUID.randomUUID(), name, free, 9, new ItemStack(Items.CHEST));
    }

    private static void press(Screen screen, String label) {
        screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
            .filter(button -> button.getMessage().getString().equals(label)).findFirst().orElseThrow().onPress();
    }

    private static void assertAuxiliaryPreservesContext(Minecraft client, PatternEncodingTermScreen<?> terminal,
                                                       Object originalSession) throws Exception {
        require(client.player.containerMenu == terminal.getMenu(), "setScreen retains the live containerMenu");
        require(SidebarMappings.isAuxiliaryScreen(client.screen, terminal), "controller recognizes registered auxiliary screen");
        var synchronize = SidebarClient.class.getDeclaredMethod("synchronizeContext");
        synchronize.setAccessible(true);
        require(!(boolean) synchronize.invoke(null), "auxiliary screens suspend uploads and response acceptance");
        require(field(SidebarClient.class, "menu") == terminal.getMenu(), "suspended controller keeps the exact menu");
        require(field(SidebarClient.class, "session") == originalSession, "suspended controller keeps its session");
    }

    private static Object field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError("SMOKE_MAPPING_ASSERT: " + message);
    }

    static String report() {
        return "crafting_shared_mapping_real_recipe=" + (passed ? "PASS" : "NOT_RUN") + "\n"
            + "crafting_unmapped_localized_jei_category_fallback=" + (passed ? "PASS" : "NOT_RUN") + "\n"
            + "processing_unknown_and_manual_invalidation=" + (passed ? "PASS" : "NOT_RUN") + "\n"
            + "selector_native_manager_menu_preserved=" + (passed ? "PASS" : "NOT_RUN") + "\n"
            + "mapping_concrete_tokens_and_source_unchanged=" + (passed ? "PASS" : "NOT_RUN") + "\n";
    }
}
