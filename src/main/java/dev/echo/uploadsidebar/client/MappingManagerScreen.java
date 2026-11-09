package dev.echo.uploadsidebar.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Owns only local mapping edits; never sends an inventory operation. */
public final class MappingManagerScreen extends Screen {
    private final Screen parent;
    private final Object menu;
    private EditBox key, value;
    private int page;
    private String message = SidebarText.PREFIX + "mappings.manager.instructions";

    public MappingManagerScreen(Screen parent) {
        super(SidebarText.component("mappings.manager.title"));
        this.parent = parent;
        var player = Minecraft.getInstance().player;
        menu = player == null ? null : player.containerMenu;
    }

    @Override protected void init() {
        int w = Math.min(380, width - 20), left = (width - w) / 2;
        if (width < 280 || height < 230) {
            addRenderableWidget(Button.builder(SidebarText.component("mappings.back"), b -> onClose())
                .bounds(Math.max(0, left), Math.max(0, height - 24), Math.max(1, w), 20).build());
            return;
        }
        String oldKey = key == null ? "" : key.getValue(), oldValue = value == null ? "" : value.getValue();
        key = addRenderableWidget(new EditBox(font, left, 44, w, 18, SidebarText.component("mappings.manager.category")));
        value = addRenderableWidget(new EditBox(font, left, 70, w, 18, SidebarText.component("mappings.manager.machine")));
        key.setMaxLength(512); value.setMaxLength(512);
        key.setHint(SidebarText.component("mappings.manager.category_hint"));
        value.setHint(SidebarText.component("mappings.manager.machine_hint"));
        key.setValue(oldKey); value.setValue(oldValue);
        addRenderableWidget(Button.builder(SidebarText.component("mappings.manager.save"), b -> {
            if (!validMenu()) { onClose(); return; }
            boolean saved = SidebarMappings.saveAlias(key.getValue(), value.getValue());
            message = SidebarText.PREFIX + (saved ? "mappings.manager.saved" : "mappings.manager.save_failed");
            rebuildWidgets();
        }).bounds(left, 96, w / 2 - 3, 20).build());
        addRenderableWidget(Button.builder(SidebarText.component("mappings.manager.delete"), b -> {
            if (!validMenu()) { onClose(); return; }
            boolean saved = SidebarMappings.removeAlias(key.getValue());
            message = saved ? SidebarText.PREFIX + "mappings.manager.deleted" : SidebarMappings.mappingError();
            if (saved) { key.setValue(""); value.setValue(""); }
            rebuildWidgets();
        }).bounds(left + w / 2 + 3, 96, w / 2 - 3, 20).build());
        var mappings = SidebarMappings.availableMappings();
        int size = Math.max(1, Math.min(8, (height - 202) / 22));
        page = Math.max(0, Math.min(page, Math.max(0, (mappings.size() - 1) / size)));
        for (int row = 0; row < size && page * size + row < mappings.size(); row++) {
            var mapping = mappings.get(page * size + row);
            String label = mapping.key() + " → " + mapping.value();
            var button = addRenderableWidget(Button.builder(Component.literal(label), b -> {
                key.setValue(mapping.key()); value.setValue(mapping.value());
            }).bounds(left, 122 + row * 22, w, 18).build());
            button.setTooltip(Tooltip.create(Component.literal(label)));
        }
        int bottom = 124 + size * 22;
        var previous = addRenderableWidget(Button.builder(SidebarText.component("mappings.previous"), b -> { page--; rebuildWidgets(); })
            .bounds(left, bottom, 70, 18).build());
        previous.active = page > 0;
        var next = addRenderableWidget(Button.builder(SidebarText.component("mappings.next"), b -> { page++; rebuildWidgets(); })
            .bounds(left + w - 70, bottom, 70, 18).build());
        next.active = (page + 1) * size < mappings.size();
        addRenderableWidget(Button.builder(SidebarText.component("mappings.back"), b -> onClose())
            .bounds(left, bottom + 24, w, 20).build());
    }
    private boolean validMenu() {
        var player = Minecraft.getInstance().player;
        return SidebarClient.isEnabled() && player != null && menu != null && player.containerMenu == menu;
    }
    @Override public void tick() { if (!validMenu()) onClose(); }
    @Override public void onClose() { Minecraft.getInstance().setScreen(validMenu() ? parent : null); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void render(GuiGraphics graphics, int x, int y, float partialTick) {
        super.render(graphics, x, y, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 10, 0xffffffff);
        String detail = width < 280 || height < 230 ? SidebarText.text("mappings.manager.small_viewport")
            : SidebarText.resolve(!SidebarMappings.mappingError().isEmpty() ? SidebarMappings.mappingError() : message);
        graphics.drawCenteredString(font, font.plainSubstrByWidth(detail, Math.max(1, width - 24)), width / 2, 27, 0xffcccccc);
    }
}
