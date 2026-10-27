package dev.echo.uploadsidebar.client;

import appeng.client.gui.AEBaseScreen;
import dev.echo.uploadsidebar.core.SidebarSession;
import dev.echo.uploadsidebar.net.SidebarPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Local note editing only. It never renames an in-world provider or sends an upload. */
public final class NoteEditorScreen extends Screen {
    private static final int MAX_CODE_POINTS = 64;
    private final Screen parent;
    private final SidebarPackets.Entry entry;
    private final Object expectedMenu;
    private final Object expectedLevel;
    private final Object expectedConnection;
    private String draft;
    private String errorMessage = "";
    private EditBox noteInput;
    private Button saveButton;
    private Button deleteButton;
    private Button cancelButton;
    private boolean smallViewport;
    private int contentLeft;
    private int contentTop;
    private int contentWidth;

    private NoteEditorScreen(Screen parent, SidebarPackets.Entry entry, Object expectedMenu) {
        super(Component.literal("私人供应器备注"));
        this.parent = parent;
        this.entry = entry;
        this.expectedMenu = expectedMenu;
        expectedLevel = Minecraft.getInstance().level;
        expectedConnection = Minecraft.getInstance().getConnection();
        draft = limit(SidebarNotes.note(entry), MAX_CODE_POINTS);
    }

    public static boolean open(Screen parent, SidebarPackets.Entry entry) {
        Minecraft client = Minecraft.getInstance();
        if (!SidebarClient.isEnabled() || client.screen != parent || client.player == null
                || !(parent instanceof AEBaseScreen<?> terminal) || !SidebarClient.supportsScreen(parent)
                || client.player.containerMenu != terminal.getMenu() || !SidebarNotes.canEdit(entry)) return false;
        var editor = new NoteEditorScreen(parent, entry, client.player.containerMenu);
        SidebarMappings.registerAuxiliary(editor, parent);
        client.setScreen(editor);
        return true;
    }

    @Override protected void init() {
        contentWidth = Math.max(1, Math.min(380, width - 24));
        contentLeft = (width - contentWidth) / 2;
        contentTop = Math.max(0, (height - 184) / 2);
        smallViewport = width < 180 || height < 176;
        noteInput = null;
        saveButton = null;
        deleteButton = null;
        if (smallViewport) {
            cancelButton = addRenderableWidget(Button.builder(Component.literal("取消"), button -> onClose())
                .bounds(contentLeft, Math.max(0, height - 24), contentWidth, Math.max(1, Math.min(20, height))).build());
            return;
        }
        noteInput = new CodePointEditBox(font, contentLeft, contentTop + 78, contentWidth, 20);
        noteInput.setMaxLength(MAX_CODE_POINTS * 2);
        noteInput.setValue(draft);
        noteInput.setHint(Component.literal("给自己看的备注"));
        noteInput.setResponder(value -> { draft = value; errorMessage = ""; });
        addRenderableWidget(noteInput);
        int buttonWidth = (contentWidth - 12) / 3;
        int buttonY = contentTop + 144;
        saveButton = addRenderableWidget(Button.builder(Component.literal("保存"), button -> saveNote(draft))
            .bounds(contentLeft, buttonY, buttonWidth, 20).build());
        deleteButton = addRenderableWidget(Button.builder(Component.literal("删除备注"), button -> saveNote(""))
            .bounds(contentLeft + buttonWidth + 6, buttonY, buttonWidth, 20).build());
        cancelButton = addRenderableWidget(Button.builder(Component.literal("取消"), button -> onClose())
            .bounds(contentLeft + (buttonWidth + 6) * 2, buttonY, contentWidth - (buttonWidth + 6) * 2, 20).build());
        updateButtons();
        setInitialFocus(noteInput);
    }

    /** Actual widget bounds used by integration tests and kept valid after GUI resize. */
    SidebarSession.Layout controlBounds(String id) {
        var widget = switch (id) {
            case "input" -> noteInput;
            case "save" -> saveButton;
            case "delete" -> deleteButton;
            case "cancel" -> cancelButton;
            default -> null;
        };
        return widget == null ? null : new SidebarSession.Layout(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight(), false);
    }

    private boolean sameMenu() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.player.containerMenu == expectedMenu
            && client.level == expectedLevel && client.getConnection() == expectedConnection
            && parent instanceof AEBaseScreen<?> terminal && SidebarClient.supportsScreen(parent) && terminal.getMenu() == expectedMenu;
    }

    private void updateButtons() {
        if (saveButton == null) return;
        boolean editable = SidebarClient.isEnabled() && sameMenu() && SidebarNotes.canEdit(entry);
        saveButton.active = editable;
        deleteButton.active = editable && !SidebarNotes.note(entry).isEmpty();
    }

    private void saveNote(String value) {
        Minecraft client = Minecraft.getInstance();
        if (client.screen != this) return;
        if (!SidebarClient.isEnabled() || !sameMenu()) { onClose(); return; }
        if (!SidebarNotes.canEdit(entry)) {
            errorMessage = SidebarNotes.unavailableReason();
            updateButtons();
            return;
        }
        if (!SidebarNotes.save(entry, limit(value, MAX_CODE_POINTS))) {
            errorMessage = SidebarNotes.error();
            if (errorMessage == null || errorMessage.isEmpty()) errorMessage = "备注保存失败，请重试";
            return;
        }
        onClose();
    }

    @Override public void tick() {
        if (Minecraft.getInstance().screen != this) return;
        if (!SidebarClient.isEnabled() || !sameMenu()) { onClose(); return; }
        updateButtons();
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override public void onClose() {
        Minecraft client = Minecraft.getInstance();
        if (client.screen == this) client.setScreen(sameMenu() ? parent : null);
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Screen.render already draws the background in 1.21.1. Labels must follow it.
        super.render(graphics, mouseX, mouseY, partialTick);
        if (smallViewport) {
            if (width >= 100 && height >= 80) drawClipped(graphics, "请降低界面缩放以编辑备注", contentLeft, 18, contentWidth, 0xffeeeeee);
            return;
        }
        graphics.drawCenteredString(font, title, width / 2, contentTop + 6, 0xffffffff);
        drawClipped(graphics, "原名：" + entry.name(), contentLeft, contentTop + 28, contentWidth, 0xffdddddd);
        drawClipped(graphics, "位置：" + SidebarNotes.locationLabel(entry), contentLeft, contentTop + 43, contentWidth, 0xffbbbbbb);
        drawClipped(graphics, "私人备注", contentLeft, contentTop + 63, contentWidth, 0xffeeeeee);
        String count = draft.codePointCount(0, draft.length()) + " / " + MAX_CODE_POINTS;
        graphics.drawString(font, count, contentLeft + contentWidth - font.width(count), contentTop + 104, 0xffbbbbbb, true);
        String feedback = !errorMessage.isEmpty() ? errorMessage : !SidebarNotes.canEdit(entry)
            ? SidebarNotes.unavailableReason() : "仅自己可见，不修改供应器原名";
        drawClipped(graphics, feedback, contentLeft, contentTop + 123, contentWidth,
            !errorMessage.isEmpty() || !SidebarNotes.canEdit(entry) ? 0xffffb6a8 : 0xffbbbbbb);
        if (mouseX >= contentLeft && mouseX < contentLeft + contentWidth && mouseY >= contentTop + 26 && mouseY < contentTop + 57) {
            graphics.renderTooltip(font, Component.literal("原名：" + entry.name() + "；位置：" + SidebarNotes.locationLabel(entry)), mouseX, mouseY);
        } else if (!errorMessage.isEmpty() && mouseX >= contentLeft && mouseX < contentLeft + contentWidth
                && mouseY >= contentTop + 120 && mouseY < contentTop + 139) {
            graphics.renderTooltip(font, Component.literal(errorMessage), mouseX, mouseY);
        }
    }

    private void drawClipped(GuiGraphics graphics, String value, int x, int y, int maxWidth, int color) {
        String text = font.width(value) <= maxWidth ? value : font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width("…"))) + "…";
        graphics.drawString(font, text, x, y, color, true);
    }

    private static String limit(String value, int maximum) {
        if (value == null || maximum <= 0) return "";
        return value.codePointCount(0, value.length()) <= maximum ? value : value.substring(0, value.offsetByCodePoints(0, maximum));
    }

    /** EditBox's native limit is UTF-16 units; apply the user-facing code-point budget first. */
    private static final class CodePointEditBox extends EditBox {
        CodePointEditBox(Font font, int x, int y, int width, int height) {
            super(font, x, y, width, height, Component.literal("私人供应器备注"));
        }
        @Override public void setValue(String value) { super.setValue(limit(value, MAX_CODE_POINTS)); }
        @Override public void insertText(String value) {
            String current = getValue();
            String selected = getHighlighted();
            int available = MAX_CODE_POINTS - current.codePointCount(0, current.length()) + selected.codePointCount(0, selected.length());
            super.insertText(limit(value, available));
        }
    }
}
