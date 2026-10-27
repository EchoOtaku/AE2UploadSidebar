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
    private String message = "选择下方映射编辑，或填写类别与机器名称新增";

    public MappingManagerScreen(Screen parent) {
        super(Component.literal("映射管理"));
        this.parent = parent;
        var player = Minecraft.getInstance().player;
        menu = player == null ? null : player.containerMenu;
    }

    @Override protected void init() {
        int w = Math.min(380, width - 20), left = (width - w) / 2;
        if (width < 280 || height < 230) {
            addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose())
                .bounds(Math.max(0, left), Math.max(0, height - 24), Math.max(1, w), 20).build());
            return;
        }
        String oldKey = key == null ? "" : key.getValue(), oldValue = value == null ? "" : value.getValue();
        key = addRenderableWidget(new EditBox(font, left, 44, w, 18, Component.literal("配方类别")));
        value = addRenderableWidget(new EditBox(font, left, 70, w, 18, Component.literal("机器名称")));
        key.setMaxLength(512); value.setMaxLength(512);
        key.setHint(Component.literal("类别 ID 或名称，例如 minecraft:crafting / 合成"));
        value.setHint(Component.literal("机器名称或私人备注，例如 分子装配室"));
        key.setValue(oldKey); value.setValue(oldValue);
        addRenderableWidget(Button.builder(Component.literal("保存 / 新增"), b -> {
            if (!validMenu()) { onClose(); return; }
            boolean saved = SidebarMappings.saveAlias(key.getValue(), value.getValue());
            message = saved ? "已保存" : "保存失败，请检查名称或配置文件";
            rebuildWidgets();
        }).bounds(left, 96, w / 2 - 3, 20).build());
        addRenderableWidget(Button.builder(Component.literal("删除此类别映射"), b -> {
            if (!validMenu()) { onClose(); return; }
            boolean saved = SidebarMappings.removeAlias(key.getValue());
            message = saved ? "已删除此类别映射" : SidebarMappings.mappingError();
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
        var previous = addRenderableWidget(Button.builder(Component.literal("上一页"), b -> { page--; rebuildWidgets(); })
            .bounds(left, bottom, 70, 18).build());
        previous.active = page > 0;
        var next = addRenderableWidget(Button.builder(Component.literal("下一页"), b -> { page++; rebuildWidgets(); })
            .bounds(left + w - 70, bottom, 70, 18).build());
        next.active = (page + 1) * size < mappings.size();
        addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose())
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
        String detail = width < 280 || height < 230 ? "请降低界面缩放以管理映射"
            : !SidebarMappings.mappingError().isEmpty() ? SidebarMappings.mappingError() : message;
        graphics.drawCenteredString(font, font.plainSubstrByWidth(detail, Math.max(1, width - 24)), width / 2, 27, 0xffcccccc);
    }
}
