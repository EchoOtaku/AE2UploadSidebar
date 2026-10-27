package dev.echo.uploadsidebar.client;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.ids.AEComponents;
import appeng.api.stacks.AEItemKey;
import dev.echo.uploadsidebar.core.RecipeMappings;

import dev.echo.uploadsidebar.core.RecipeMappings.RecipeTypeMapping;
import com.mojang.logging.LogUtils;
import dev.echo.uploadsidebar.core.ProviderRecommendations;
import dev.echo.uploadsidebar.core.MappingAutoUploadStore;
import net.neoforged.fml.loading.FMLPaths;
import net.minecraft.client.gui.components.Tooltip;
import dev.echo.uploadsidebar.net.SidebarPackets;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.fml.ModList;

/** Optional shared or standalone mappings. Never consumes another mod's search state or sends an upload. */
public final class SidebarMappings {
    private static final ProviderRecommendations.Selection selection = new ProviderRecommendations.Selection();
    private static final Map<Screen, Screen> auxiliaryScreens = new IdentityHashMap<>();
    private static Object menu;
    private static AEItemKey patternKey;
    private static Recipe<?> recipe;
    private static String unknownReason = "机器未知：从 JEI 转入或手动搜索";
    private static boolean warned;
    private static boolean automaticMapped;
    private static RecipeTypeMapping automaticMapping;
    private static MappingAutoUploadStore autoUploadStore;
    private static RecipeMappings mappingStore;

    private static RecipeMappings mappingStore() {
        if (mappingStore == null) mappingStore = new RecipeMappings(
            FMLPaths.CONFIGDIR.get().resolve("ae2-upload-sidebar-mappings.json"),
            FMLPaths.CONFIGDIR.get().resolve("extendedae_plus/recipe_type_names.json"));
        return mappingStore;
    }

    public static boolean usesEaep() { return ModList.get().isLoaded("extendedae_plus"); }
    public static String mappingSource() { return usesEaep() ? "EAEP 共用映射" : "独立映射"; }
    public static boolean removeAlias(String key) {
        try { return usesEaep() ? EaepMappings.remove(key) : mappingStore().remove(key); }
        catch (RuntimeException error) { report(error); return false; }
    }
    public static String mappingError() { return usesEaep() ? EaepMappings.error() : mappingStore().error(); }
    private static String resolveProvider(String key) {
        return usesEaep() ? EaepMappings.resolveProvider(key) : mappingStore().resolveProvider(key);
    }

    private static MappingAutoUploadStore autoUploadStore() {
        if (autoUploadStore == null) autoUploadStore = new MappingAutoUploadStore(
            FMLPaths.CONFIGDIR.get().resolve("ae2-upload-sidebar-auto-upload.properties"));
        return autoUploadStore;
    }

    public static boolean autoUploadEnabled(RecipeTypeMapping mapping) {
        return autoUploadStore().enabled(SidebarNotes.currentScope(), mapping.key(), mapping.value());
    }

    /** Only an actual, explicitly enabled mapping can authorize automatic upload. */
    public static String enabledAutoUploadMapping() {
        if (selection.current().source() != ProviderRecommendations.Source.AUTOMATIC
                || automaticMapping == null || !autoUploadEnabled(automaticMapping)) return "";
        return automaticMapping.key() + "\n" + automaticMapping.value() + "\n" + selection.current().key();
    }

    private SidebarMappings() { }

    /** Call only for the active encoding terminal and its actual encoded-output slot. */
    public static void tick(Object currentMenu, ItemStack pattern) {
        if (menu != currentMenu) {
            reset();
            menu = currentMenu;
        }
        capture(pattern);
    }

    /** Refreshes the same menu; ItemStack counts never identify a recipe. */
    public static void capture(ItemStack pattern) {
        AEItemKey next = pattern == null || pattern.isEmpty() ? null : AEItemKey.of(pattern);
        if (!Objects.equals(patternKey, next)) {
            patternKey = next;
            recipe = null;
            unknownReason = "机器未知：从 JEI 转入或手动搜索";
            if (next != null) resolveRecipe(pattern);
        }
        String key = "";
        automaticMapped = false;
        automaticMapping = null;
        if (recipe != null) {
            try {
                key = automaticKey(recipe);
                if (key.isEmpty()) unknownReason = "配方类别名称不可用，可手动选择";
            } catch (RuntimeException error) {
                report(error);
                unknownReason = "映射读取失败，可手动选择";
            }
        }
        if (recipe == null && next != null) {
            var transferred = SidebarRecipeContext.forPattern(menu, pattern);
            if (transferred != null) {
                try { key = categoryKey(transferred.id(), transferred.title()); }
                catch (RuntimeException error) { report(error); }
            }
        }
        selection.capture(menu, next, key);
    }

    private static void resolveRecipe(ItemStack pattern) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        try {
            // A processing pattern contains inputs/outputs, but no authoritative recipe ID.
            // Processing categories are supplied separately by a successful, menu-bound JEI transfer.
            ResourceLocation recipeId = null;
            var crafting = pattern.get(AEComponents.ENCODED_CRAFTING_PATTERN);
            var stonecutting = pattern.get(AEComponents.ENCODED_STONECUTTING_PATTERN);
            var smithing = pattern.get(AEComponents.ENCODED_SMITHING_TABLE_PATTERN);
            if (crafting != null) recipeId = crafting.recipeId();
            else if (stonecutting != null) recipeId = stonecutting.recipeId();
            else if (smithing != null) recipeId = smithing.recipeId();
            if (recipeId == null) return;
            if (PatternDetailsHelper.decodePattern(pattern, level) == null) {
                unknownReason = "样板配方无效：请选择映射";
                return;
            }
            recipe = level.getRecipeManager().byKey(recipeId).map(holder -> holder.value()).orElse(null);
            if (recipe == null) unknownReason = "配方未找到：请选择映射";
        } catch (RuntimeException error) {
            report(error);
            unknownReason = "配方无法识别：请选择映射";
        }
    }

    private static String automaticKey(Recipe<?> actualRecipe) {
        ResourceLocation type = BuiltInRegistries.RECIPE_TYPE.getKey(actualRecipe.getType());
        if (type == null) return "";
        String title = ModList.get().isLoaded("jei") ? SidebarJeiPlugin.categoryTitle(type) : "";
        return categoryKey(type, title);
    }

    private static String categoryKey(ResourceLocation type, String title) {
        String fallback = clean(title).isEmpty() ? type.getPath() : clean(title);
        String mapped = clean(usesEaep() ? EaepMappings.resolveType(type, fallback) : mappingStore().resolveType(type.toString(), fallback));
        String result = clean(resolveProvider(mapped));
        automaticMapped = !result.equals(fallback);
        var mappings = availableMappings();
        // Follow EAEP precedence: full recipe ID, path alias, then displayed-title alias.
        for (String candidate : List.of(type.toString(), type.getPath(), fallback)) {
            var matches = mappings.stream().filter(m -> m.key().equalsIgnoreCase(candidate)).toList();
            if (!matches.isEmpty()) {
                if (matches.size() == 1 && result.equals(clean(
                        resolveProvider(matches.getFirst().value()))))
                    automaticMapping = matches.getFirst();
                break;
            }
        }
        return result;
    }

    public static List<SidebarPackets.Entry> sortedEntries(List<SidebarPackets.Entry> entries) {
        return ProviderRecommendations.sortedEntries(entries, SidebarNotes::searchText,
            SidebarPackets.Entry::freeSlots, selection.current().key());
    }

    public static boolean matches(SidebarPackets.Entry entry) {
        return ProviderRecommendations.matches(entry.name(), selection.current().key())
            || ProviderRecommendations.matches(SidebarNotes.note(entry), selection.current().key());
    }

    public static String hint() {
        var current = selection.current();
        return switch (current.source()) {
            case EMPTY -> "先编码样板，再匹配机器";
            case AUTOMATIC -> (automaticMapped ? "自动映射：" : "配方类别：") + current.key();
            case MANUAL -> "手选映射：" + current.key();
            case UNKNOWN -> unknownReason;
        };
    }

    public static List<RecipeTypeMapping> availableMappings() {
        try {
            return (usesEaep() ? EaepMappings.list() : mappingStore().list()).stream()
                .filter(mapping -> !clean(mapping.key()).isEmpty() && !clean(mapping.value()).isEmpty())
                .toList();
        } catch (RuntimeException error) {
            report(error);
            return List.of();
        }
    }

    /** Empty restores automatic mode. A choice lasts only for this menu and pattern contents. */
    public static void setManualKey(String key) {
        String value = clean(key);
        if (!value.isEmpty()) {
            try {
                value = clean(resolveProvider(value));
            } catch (RuntimeException error) {
                report(error);
                return;
            }
        }
        selection.setManualKey(value);
    }

    /** Explicit user action only; writes the currently selected mapping backend. */
    public static boolean saveAlias(String type, String machine) {
        String key = clean(type);
        String value = clean(machine);
        if (key.isEmpty() || value.isEmpty()) return false;
        try {
            return usesEaep() ? EaepMappings.put(key, value) : mappingStore().put(key, value);
        } catch (RuntimeException error) {
            report(error);
            return false;
        }
    }

    public static void openSelector(Screen parent) {
        if (!SidebarClient.isEnabled()) return;
        var player = Minecraft.getInstance().player;
        var selector = new MappingSelectorScreen(parent, player == null ? null : player.containerMenu);
        registerAuxiliary(selector, parent);
        Minecraft.getInstance().setScreen(selector);
    }

    public static void openManager(Screen parent) {
        if (!SidebarClient.isEnabled()) return;
        try {
            Screen manager = usesEaep() ? EaepMappings.manager(parent) : new MappingManagerScreen(parent);
            registerAuxiliary(manager, parent);
            Minecraft.getInstance().setScreen(manager);
        } catch (RuntimeException error) { report(error); }
    }

    public static void registerAuxiliary(Screen child, Screen parent) {
        auxiliaryScreens.put(child, auxiliaryScreens.getOrDefault(parent, parent));
    }

    /** The controller must additionally verify the player's actual containerMenu identity. */
    public static boolean isAuxiliaryScreen(Screen candidate, Screen terminal) {
        return candidate != null && terminal != null && auxiliaryScreens.get(candidate) == terminal;
    }

    public static void reset() {
        menu = null;
        patternKey = null;
        recipe = null;
        automaticMapping = null;
        selection.capture(null, null, "");
        auxiliaryScreens.clear();
        unknownReason = "机器未知：从 JEI 转入或手动搜索";
    }

    private static String clean(String text) { return text == null ? "" : text.strip(); }

    private static void report(RuntimeException error) {
        if (warned) return;
        warned = true;
        LogUtils.getLogger().warn("AE2 sidebar could not resolve an existing machine mapping", error);
    }

    /** Independent mapping selection and private auto-upload options. */
    private static final class MappingSelectorScreen extends Screen {
        private final Screen parent;
        private final Object expectedMenu;
        private List<RecipeTypeMapping> mappings = List.of();
        private int page;
        private int pageSize;
        private boolean smallViewport;
        private String saveError = "";

        private MappingSelectorScreen(Screen parent, Object expectedMenu) {
            super(Component.literal("选择机器映射"));
            this.parent = parent;
            this.expectedMenu = expectedMenu;
        }

        @Override protected void init() {
            mappings = availableMappings();
            int contentWidth = Math.max(1, Math.min(380, width - 20));
            int left = (width - contentWidth) / 2;
            smallViewport = width < 280 || height < 134;
            if (smallViewport) {
                addRenderableWidget(Button.builder(Component.literal("返回"), button -> onClose())
                    .bounds(left, Math.max(0, height - 24), contentWidth, Math.min(20, height)).build());
                return;
            }
            pageSize = Math.max(1, Math.min(8, (height - 112) / 22));
            page = Math.max(0, Math.min(page, Math.max(0, (mappings.size() - 1) / pageSize)));
            addRenderableWidget(Button.builder(Component.literal("自动识别（清除手选）"), button -> choose(""))
                .bounds(left, 42, contentWidth, 18).build());
            for (int row = 0; row < pageSize; row++) {
                int index = page * pageSize + row;
                if (index >= mappings.size()) break;
                RecipeTypeMapping mapping = mappings.get(index);
                String label = mapping.key() + " → " + mapping.value();
                Button choice = addRenderableWidget(Button.builder(Component.literal(label), button -> choose(mapping.value()))
                    .bounds(left, 64 + row * 22, contentWidth - 88, 18).build());
                choice.active = selection.current().source() != ProviderRecommendations.Source.EMPTY;
                choice.setTooltip(Tooltip.create(Component.literal(label)));
                Button toggle = addRenderableWidget(Button.builder(
                    Component.literal(autoUploadEnabled(mapping) ? "自动上传：开" : "自动上传：关"), button -> {
                        if (!validMenu()) { onClose(); return; }
                        if (!availableMappings().contains(mapping)) { rebuildWidgets(); return; }
                        boolean saved = autoUploadStore().set(SidebarNotes.currentScope(), mapping.key(), mapping.value(),
                            !autoUploadEnabled(mapping));
                        saveError = saved ? "" : autoUploadStore().error();
                        rebuildWidgets();
                    }).bounds(left + contentWidth - 84, 64 + row * 22, 84, 18).build());
                toggle.setTooltip(Tooltip.create(Component.literal("编码后唯一匹配时上传 1 张；多目标手选。仅当前世界/服务器，默认关闭。")));
            }
            int bottom = 66 + pageSize * 22;
            int pageButtonWidth = Math.min(70, contentWidth / 2 - 3);
            Button previous = addRenderableWidget(Button.builder(Component.literal("上一页"), button -> changePage(-1))
                .bounds(left, bottom, pageButtonWidth, 18).build());
            previous.active = page > 0;
            Button next = addRenderableWidget(Button.builder(Component.literal("下一页"), button -> changePage(1))
                .bounds(left + contentWidth - pageButtonWidth, bottom, pageButtonWidth, 18).build());
            next.active = (page + 1) * pageSize < mappings.size();
            addRenderableWidget(Button.builder(Component.literal("管理映射…"), button -> openManager(this))
                .bounds(left, bottom + 24, contentWidth / 2 - 3, 20).build());
            addRenderableWidget(Button.builder(Component.literal("返回"), button -> onClose())
                .bounds(left + contentWidth / 2 + 3, bottom + 24, contentWidth / 2 - 3, 20).build());
        }

        private void changePage(int delta) {
            page += delta;
            rebuildWidgets();
        }

        private boolean validMenu() {
            var player = Minecraft.getInstance().player;
            return SidebarClient.isEnabled() && player != null && expectedMenu != null && player.containerMenu == expectedMenu;
        }

        private void choose(String value) {
            if (!validMenu()) { onClose(); return; }
            // The encoded output may have changed while a server packet arrived in this screen.
            // Capture before applying the explicit choice to prevent retaining stale fingerprints.
            capture(SidebarClient.encodedPattern());
            setManualKey(value);
            onClose();
        }

        @Override public void tick() {
            if (!validMenu()) onClose();
        }

        @Override public void onClose() {
            Minecraft.getInstance().setScreen(validMenu() ? parent : null);
        }

        @Override public boolean isPauseScreen() { return false; }

        @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            // In 1.21.1 Screen.render draws the background, so labels must follow it.
            super.render(graphics, mouseX, mouseY, partialTick);
            if (width >= 100 && height >= 80) {
                graphics.drawCenteredString(font, title.getString() + " · " + mappingSource(), width / 2, 10, 0xffffff);
                String detail = !saveError.isEmpty() ? saveError : !mappingError().isEmpty() ? mappingError() : smallViewport ? "请降低界面缩放以选择映射" : "逐条开启：编码后仅唯一匹配时自动上传";
                if (font.width(detail) > width - 24) detail = font.plainSubstrByWidth(detail, Math.max(1, width - 32)) + "…";
                graphics.drawCenteredString(font, detail, width / 2, 26, 0xc6c6c6);
                if (!smallViewport && mappings.isEmpty())
                    graphics.drawCenteredString(font, "暂无映射，请点击管理映射", width / 2, 69, 0xc6c6c6);
            }
        }
    }
}
