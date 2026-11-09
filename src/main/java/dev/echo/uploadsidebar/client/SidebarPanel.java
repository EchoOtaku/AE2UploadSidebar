package dev.echo.uploadsidebar.client;

import appeng.client.gui.AEBaseScreen;
import com.mojang.logging.LogUtils;
import dev.echo.uploadsidebar.core.PanelGeometry;
import dev.echo.uploadsidebar.core.ProviderGroups;
import dev.echo.uploadsidebar.core.SidebarSession;
import dev.echo.uploadsidebar.net.SidebarPackets;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;

/** A movable region in AE2's existing screen; inline settings preserve the terminal. */
public final class SidebarPanel {
    private final AEBaseScreen<?> screen;
    private final EditBox search;
    private final Path preferencesPath;
    private final SidebarPreferences preferences;
    private int firstRow;
    private boolean settingsOpen;
    private boolean dragging;
    private double dragOffsetX;
    private double dragOffsetY;
    private String persistenceError = "";
    private Supplier<String> noteError = () -> "";
    private Supplier<String> iconFeedback = () -> "";
    private record GroupKey(String name, String note, boolean favorite) { }
    private final java.util.Set<GroupKey> expandedGroups = new java.util.HashSet<>();

    /** A drag captures a specific visible provider and its on-screen destination. */
    public record IconTarget(SidebarPackets.Entry entry, SidebarSession.Layout bounds) { }

    SidebarPanel(AEBaseScreen<?> screen) {
        this.screen = screen;
        preferencesPath = FMLPaths.CONFIGDIR.get().resolve("ae2-upload-sidebar-client.properties");
        SidebarPreferences loaded;
        try { loaded = SidebarPreferences.load(preferencesPath); }
        catch (IOException | IllegalArgumentException error) {
            loaded = new SidebarPreferences();
            persistenceError = SidebarText.PREFIX + "panel.error.read_preferences";
            LogUtils.getLogger().warn("Cannot read AE2 sidebar client preferences", error);
        }
        preferences = loaded;
        search = new EditBox(Minecraft.getInstance().font, 0, 0, 80, 16, SidebarText.component("panel.search.label"));
        search.setMaxLength(80);
        search.setHint(SidebarText.component("panel.search.hint"));
        search.setResponder(value -> firstRow = 0);
    }

    SidebarPreferences preferences() { return preferences; }

    SidebarSession.Layout layout() {
        var settings = preferences.geometry();
        var box = PanelGeometry.layout(screen.width, screen.height, screen.getGuiLeft(), screen.getGuiTop(), screen.getYSize(), settings);
        if (settingsOpen && !box.collapsed() && box.height() < 184) {
            settings = new PanelGeometry.Settings(settings.x(), settings.y(), settings.width(),
                3, PanelGeometry.HeightMode.STANDARD, settings.collapsed(), settings.forceExpanded());
            box = PanelGeometry.layout(screen.width, screen.height, screen.getGuiLeft(), screen.getGuiTop(), screen.getYSize(), settings);
        }
        return box;
    }

    private List<SidebarPackets.Entry> visibleEntries() {
        if (SidebarClient.isProviderView()) return SidebarClient.entries();
        String query = search.getValue().strip().toLowerCase(Locale.ROOT);
        return dev.echo.uploadsidebar.core.ProviderSearch.favoritesFirst(
            SidebarMappings.sortedEntries(SidebarClient.entries()), SidebarNotes::favorite).stream()
            .filter(entry -> dev.echo.uploadsidebar.core.ProviderSearch.matches(SidebarNotes.searchText(entry), query)).toList();
    }

    private List<ProviderGroups.Row<SidebarPackets.Entry, GroupKey>> visibleRows() {
        return ProviderGroups.rows(visibleEntries(), preferences.groupProviders() && !SidebarClient.isProviderView(),
            entry -> new GroupKey(entry.name(), SidebarNotes.note(entry), SidebarNotes.favorite(entry)), expandedGroups);
    }

    /** Includes read-only icons so a rejected drop cannot reach an AE2 slot underneath. */
    public List<IconTarget> iconTargets() {
        var client = Minecraft.getInstance();
        var box = layout();
        if (!SidebarClient.isEnabled() || client.screen != screen || client.player == null
                || client.player.containerMenu != screen.getMenu() || box.collapsed() || settingsOpen)
            return List.of();
        var filtered = visibleRows();
        int shown = PanelGeometry.rowCount(box);
        firstRow = Math.max(0, Math.min(firstRow, Math.max(0, filtered.size() - shown)));
        var targets = new ArrayList<IconTarget>();
        for (int row = 0; row < shown && firstRow + row < filtered.size(); row++) {
            var item = filtered.get(firstRow + row);
            if (!item.header()) targets.add(new IconTarget(item.members().getFirst(), controlBounds("icon" + row)));
        }
        return List.copyOf(targets);
    }

    /** Revalidate the captured row after a JEI drag, search, resize or server refresh. */
    public boolean acceptIcon(IconTarget captured, ItemStack stack) {
        if (captured == null || stack == null || stack.isEmpty()) return false;
        var current = iconTargets().stream().filter(target -> target.entry().token().equals(captured.entry().token())
            && target.bounds().equals(captured.bounds())).findFirst().orElse(null);
        if (current == null) {
            iconFeedback = () -> SidebarText.text("panel.feedback.target_changed");
            return false;
        }
        boolean saved = SidebarNotes.saveIcon(current.entry(), stack.copyWithCount(1));
        iconFeedback = saved ? () -> SidebarText.text("panel.feedback.icon_saved") : SidebarNotes::iconError;
        return saved;
    }

    /** Shared bounds make rendering, event routing and integration checks agree. */
    SidebarSession.Layout controlBounds(String id) {
        var box = layout();
        if ("collapse".equals(id)) return rect(Math.max(box.x(), box.x() + box.width() - (box.collapsed() ? 19 : 57)),
            box.y() + Math.min(3, box.height() - 1), Math.min(16, box.width()), Math.min(16, Math.max(1, box.height() - 3)));
        if (box.collapsed()) return null;
        if ("lock".equals(id)) return rect(box.x() + box.width() - 39, box.y() + 3, 16, 16);
        if ("settings".equals(id)) return rect(box.x() + box.width() - 21, box.y() + 3, 16, 16);
        if (settingsOpen) {
            int step = settingsStep(box);
            int height = step - 4;
            return switch (id) {
                case "theme" -> rect(box.x() + 7, box.y() + 28, box.width() - 14, height);
                case "height" -> rect(box.x() + 7, box.y() + 28 + step, box.width() - 14, height);
                case "width_minus" -> rect(box.x() + box.width() - 49, box.y() + 28 + step * 2, 18, height);
                case "width_plus" -> rect(box.x() + box.width() - 27, box.y() + 28 + step * 2, 18, height);
                case "rows_minus" -> rect(box.x() + box.width() - 49, box.y() + 28 + step * 3, 18, height);
                case "rows_plus" -> rect(box.x() + box.width() - 27, box.y() + 28 + step * 3, 18, height);
                case "reset" -> rect(box.x() + 7, box.y() + 28 + step * 4, box.width() - 14, height);
                case "mappings" -> rect(box.x() + 7, box.y() + 28 + step * 5, box.width() - 14, height);
                case "grouping" -> rect(box.x() + 7, box.y() + 28 + step * 6, box.width() - 14, height);
                default -> null;
            };
        }
        if ("search".equals(id)) return rect(box.x() + 7, box.y() + 25, box.width() - 14, 16);
        if ("mapping_selector".equals(id)) return rect(box.x() + box.width() - 39, box.y() + 44, 32, 18);
        if (id.startsWith("favorite")) {
            var row = controlBounds("row" + id.substring(8));
            return row == null ? null : rect(row.x() + row.width() - 18, row.y() + 2, 16, row.height() - 4);
        }
        if (id.startsWith("icon")) {
            var row = controlBounds("row" + id.substring(4));
            return row == null ? null : rect(row.x() + 3, row.y() + 4, 18, 18);
        }
        if (id.startsWith("row")) {
            try {
                int row = Integer.parseInt(id.substring(3));
                return row >= 0 && row < PanelGeometry.rowCount(box)
                    ? rect(box.x() + 5, box.y() + PanelGeometry.LIST_TOP + row * PanelGeometry.ROW_HEIGHT, box.width() - 10, PanelGeometry.ROW_HEIGHT - 2) : null;
            } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private static SidebarSession.Layout rect(int x, int y, int width, int height) {
        return new SidebarSession.Layout(x, y, width, height, false);
    }
    private static int settingsStep(SidebarSession.Layout box) {
        return Math.max(16, Math.min(22, (box.height() - 32) / 7));
    }
    private boolean hit(String id, double x, double y) {
        var box = controlBounds(id);
        return box != null && box.contains(x, y);
    }
    private void positionSearch() {
        var box = controlBounds("search");
        if (box == null) return;
        search.setX(box.x()); search.setY(box.y()); search.setWidth(box.width());
        // EditBox does not clip its placeholder; keep long translations inside the field.
        search.setHint(Component.literal(Minecraft.getInstance().font.plainSubstrByWidth(
            SidebarText.text("panel.search.hint"), Math.max(0, search.getInnerWidth()))));
    }

    void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        var box = layout();
        var theme = new SidebarTheme(preferences.theme());
        Font font = Minecraft.getInstance().font;
        graphics.pose().pushPose();
        // Item rendering adds another 150: keep every panel element below tooltips at 400.
        graphics.pose().translate(0, 0, 200);
        try {
            theme.background(graphics, box.x(), box.y(), box.width(), box.height());
            if (box.width() < 40 || box.height() < 20) { unfocus(); return; }
            boolean providerView = SidebarClient.isProviderView();
            clippedText(graphics, font, providerView ? SidebarText.text("panel.title.provider") : SidebarText.text("panel.title.upload"), box.x() + 7, box.y() + 7, box.width() - (box.collapsed() ? 29 : 65), theme.text());
            drawControl(graphics, theme, "collapse", box.collapsed() ? "+" : "−", mouseX, mouseY);
            if (box.collapsed()) {
                unfocus();
                if (box.contains(mouseX, mouseY)) graphics.renderTooltip(font,
                    Component.literal(preferences.locked() ? SidebarText.text("panel.tooltip.expand_locked") : SidebarText.text("panel.tooltip.expand_movable")), mouseX, mouseY);
                return;
            }
            drawControl(graphics, theme, "lock", preferences.locked() ? SidebarText.text("panel.button.locked") : SidebarText.text("panel.button.movable"), mouseX, mouseY);
            drawControl(graphics, theme, "settings", settingsOpen ? "×" : SidebarText.text("panel.button.settings"), mouseX, mouseY);
            if (settingsOpen) { renderSettings(graphics, theme, box, mouseX, mouseY); return; }
            positionSearch();
            if (providerView) clippedText(graphics, font, SidebarText.text("panel.provider.selected"), box.x() + 7, box.y() + 29, box.width() - 14, theme.text());
            else search.render(graphics, mouseX, mouseY, partialTick);
            clippedText(graphics, font, providerView ? SidebarText.text("panel.provider.manage") : SidebarMappings.hint(), box.x() + 7, box.y() + 49, box.width() - 49, theme.muted());
            drawControl(graphics, theme, "mapping_selector", SidebarText.text("panel.button.mapping"), mouseX, mouseY);
            clippedText(graphics, font, SidebarText.text("panel.list.heading"), box.x() + 7, box.y() + 63, box.width() - 43, theme.muted());
            graphics.drawString(font, SidebarText.text("panel.list.free_slots"), box.x() + box.width() - 8 - font.width(SidebarText.text("panel.list.free_slots")), box.y() + 63, theme.muted(), false);
            var filtered = visibleRows();
            int shown = PanelGeometry.rowCount(box);
            firstRow = Math.max(0, Math.min(firstRow, Math.max(0, filtered.size() - shown)));
            SidebarPackets.Entry hovered = null;
            boolean hoveredIcon = false;
            boolean hoveredFavorite = false;
            String hoveredGroup = null;
            boolean uploadEnabled = SidebarClient.canUpload();
            for (int row = 0; row < shown && firstRow + row < filtered.size(); row++) {
                var item = filtered.get(firstRow + row);
                var entry = item.members().getFirst();
                var bounds = controlBounds("row" + row);
                boolean hover = bounds.contains(mouseX, mouseY);
                if (item.header()) {
                    theme.providerRow(graphics, bounds.x(), bounds.y(), bounds.width(), bounds.height(), true, hover);
                    if (item.members().stream().anyMatch(SidebarMappings::matches))
                        graphics.fill(bounds.x() + 1, bounds.y() + 2, bounds.x() + 3, bounds.y() + bounds.height() - 2, theme.selection());
                    boolean expanded = expandedGroups.contains(item.key());
                    String label = (expanded ? "− " : "+ ") + SidebarNotes.displayName(entry);
                    graphics.renderItem(SidebarNotes.icon(entry), bounds.x() + 4, bounds.y() + 5);
                    clippedText(graphics, font, label, bounds.x() + 24, bounds.y() + 4, bounds.width() - 64, theme.buttonText(true), theme.buttonShadow());
                    long free = item.members().stream().mapToLong(SidebarPackets.Entry::freeSlots).sum();
                    long total = item.members().stream().mapToLong(SidebarPackets.Entry::totalSlots).sum();
                    clippedText(graphics, font, SidebarText.text("panel.group.slots", item.members().size(), free, total),
                        bounds.x() + 24, bounds.y() + 14, bounds.width() - 64, theme.buttonText(true));
                    var uploadBounds = groupUploadBounds(bounds);
                    boolean canUploadGroup = uploadEnabled && screen.getMenu().getCarried().isEmpty()
                        && item.members().stream().anyMatch(e -> e.freeSlots() > 0);
                    theme.button(graphics, uploadBounds.x(), uploadBounds.y(), uploadBounds.width(), uploadBounds.height(),
                        canUploadGroup, uploadBounds.contains(mouseX, mouseY));
                    graphics.drawString(font, SidebarText.text("panel.button.upload"), uploadBounds.x() + 4, uploadBounds.y() + 5,
                        theme.buttonText(canUploadGroup), theme.buttonShadow());
                    if (hover) hoveredGroup = uploadBounds.contains(mouseX, mouseY)
                        ? SidebarText.text("panel.group.upload")
                        : SidebarText.text(expanded ? "panel.group.collapse" : "panel.group.expand",
                            SidebarNotes.displayName(entry), item.members().size());
                    continue;
                }
                boolean enabled = uploadEnabled && entry.freeSlots() > 0 || SidebarNotes.canEdit(entry) || SidebarNotes.canEditIcon(entry);
                theme.providerRow(graphics, bounds.x(), bounds.y(), bounds.width(), bounds.height(), enabled, hover);
                if (item.child()) graphics.fill(bounds.x() + 1, bounds.y() + 3, bounds.x() + 2, bounds.y() + bounds.height() - 3, theme.muted());
                if (providerView || SidebarMappings.matches(entry))
                    graphics.fill(bounds.x() + 1, bounds.y() + 2, bounds.x() + 3, bounds.y() + bounds.height() - 2, theme.selection());
                graphics.renderItem(SidebarNotes.icon(entry), bounds.x() + 4, bounds.y() + 5);
                clippedText(graphics, font, SidebarNotes.displayName(entry), bounds.x() + 24, bounds.y() + 4, bounds.width() - 47,
                    theme.buttonText(enabled), theme.buttonShadow());
                graphics.drawString(font, entry.freeSlots() + "/" + entry.totalSlots(), bounds.x() + 24, bounds.y() + 14,
                    entry.freeSlots() > 0 ? theme.positive() : theme.negative(), theme.buttonShadow());
                graphics.drawString(font, SidebarNotes.favorite(entry) ? "★" : "☆",
                    bounds.x() + bounds.width() - 15, bounds.y() + 9,
                    SidebarNotes.favorite(entry) ? 0xFFFFD45A : theme.muted(), false);
                if (hover) { hovered = entry; hoveredIcon = hit("icon" + row, mouseX, mouseY);
                    hoveredFavorite = hit("favorite" + row, mouseX, mouseY); }
            }
            if (filtered.isEmpty()) clippedText(graphics, font, SidebarClient.entries().isEmpty() ? SidebarText.text("panel.list.empty") : SidebarText.text("panel.list.no_matches"),
                box.x() + 8, box.y() + PanelGeometry.LIST_TOP + 9, box.width() - 16, theme.muted());
            if (filtered.size() > shown) {
                int trackHeight = shown * PanelGeometry.ROW_HEIGHT - 2;
                int trackX = box.x() + box.width() - 3;
                int thumbHeight = Math.max(5, trackHeight * shown / filtered.size());
                int thumbY = box.y() + PanelGeometry.LIST_TOP + (trackHeight - thumbHeight) * firstRow / (filtered.size() - shown);
                graphics.fill(trackX, box.y() + PanelGeometry.LIST_TOP, trackX + 1, box.y() + PanelGeometry.LIST_TOP + trackHeight, theme.muted());
                graphics.fill(trackX, thumbY, trackX + 1, thumbY + thumbHeight, theme.text());
            }
            int footerY = box.y() + box.height() - 30;
            clippedText(graphics, font, providerView ? SidebarText.text("panel.footer.private_target") : SidebarText.text("panel.footer.encoded", SidebarClient.encodedCount()), box.x() + 7, footerY, box.width() - 14, theme.text());
            String currentIconFeedback = SidebarText.resolve(iconFeedback.get());
            String currentNoteError = SidebarText.resolve(noteError.get());
            String status = !currentIconFeedback.isEmpty() ? currentIconFeedback
                : !currentNoteError.isEmpty() ? currentNoteError
                : SidebarText.resolve(persistenceError.isEmpty() ? SidebarClient.status() : persistenceError);
            clippedText(graphics, font, status, box.x() + 7, footerY + 14, box.width() - 14, theme.muted());
            if (hoveredGroup != null) graphics.renderTooltip(font, Component.literal(hoveredGroup), mouseX, mouseY);
            else if (hovered != null) {
                String note = SidebarNotes.note(hovered);
                if (hoveredFavorite) graphics.renderTooltip(font,
                    Component.literal(SidebarNotes.favorite(hovered) ? SidebarText.text("panel.tooltip.unfavorite") : SidebarText.text("panel.tooltip.favorite")), mouseX, mouseY);
                else if (hoveredIcon) graphics.renderComponentTooltip(font, List.of(
                    SidebarText.component("panel.tooltip.original_name", hovered.name()),
                    SidebarText.component("panel.tooltip.icon", SidebarNotes.icon(hovered).getHoverName()),
                    SidebarText.component("panel.tooltip.icon_jei"),
                    SidebarText.component("panel.tooltip.icon_inventory"),
                    SidebarText.component("panel.tooltip.icon_reset"),
                    SidebarText.component("panel.tooltip.locate_icon"),
                    Component.literal(SidebarNotes.canEditIcon(hovered) ? (providerView ? SidebarText.text("panel.tooltip.icon_provider") : SidebarText.text("panel.tooltip.icon_terminal")) : SidebarNotes.iconError())), mouseX, mouseY);
                else graphics.renderComponentTooltip(font, List.of(SidebarText.component("panel.tooltip.original_name", hovered.name()),
                    SidebarText.component("panel.tooltip.note",
                        note.isEmpty() ? SidebarText.component("panel.tooltip.note_unset") : Component.literal(note)),
                    SidebarText.component("panel.tooltip.location", SidebarNotes.locationLabel(hovered)),
                    SidebarText.component("panel.tooltip.locate"),
                    SidebarText.component("panel.tooltip.free_slots", hovered.freeSlots(), hovered.totalSlots()),
                    Component.literal(SidebarMappings.matches(hovered) ? SidebarText.text("panel.tooltip.matches") : SidebarText.text("panel.tooltip.manual")),
                    Component.literal(uploadEnabled && hovered.freeSlots() > 0 ? SidebarText.text("panel.tooltip.upload") : SidebarText.resolve(SidebarClient.status())),
                    Component.literal(SidebarNotes.canEdit(hovered) ? SidebarText.text("panel.tooltip.edit_note") : SidebarNotes.unavailableReason())), mouseX, mouseY);
            } else if (mouseY >= footerY && box.contains(mouseX, mouseY)) {
                graphics.renderTooltip(font, Component.literal(status), mouseX, mouseY);
            } else if (mouseY >= box.y() + 44 && mouseY < box.y() + 62 && box.contains(mouseX, mouseY)) {
                graphics.renderTooltip(font, Component.literal(providerView ? SidebarText.text("panel.tooltip.mappings") : SidebarMappings.hint()), mouseX, mouseY);
            } else renderHeaderTooltip(graphics, mouseX, mouseY);
        } finally { graphics.pose().popPose(); }
    }

    private void renderSettings(GuiGraphics graphics, SidebarTheme theme, SidebarSession.Layout box, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        drawControl(graphics, theme, "theme", SidebarText.text("panel.settings.theme", preferences.theme() == SidebarPreferences.Theme.AE2 ? "AE2" : SidebarText.text("panel.settings.theme.vanilla")), mouseX, mouseY);
        String mode = switch (preferences.heightMode()) { case COMPACT -> SidebarText.text("panel.settings.height.compact"); case STANDARD -> SidebarText.text("panel.settings.height.standard"); case TERMINAL -> SidebarText.text("panel.settings.height.terminal"); };
        drawControl(graphics, theme, "height", SidebarText.text("panel.settings.height", mode), mouseX, mouseY);
        clippedText(graphics, font, SidebarText.text("panel.settings.width", preferences.width()), box.x() + 8, controlBounds("width_minus").y() + 4, box.width() - 62, theme.text());
        clippedText(graphics, font, SidebarText.text("panel.settings.rows", preferences.rows()), box.x() + 8, controlBounds("rows_minus").y() + 4, box.width() - 62, theme.text());
        for (String id : List.of("width_minus", "rows_minus")) drawControl(graphics, theme, id, "−", mouseX, mouseY);
        for (String id : List.of("width_plus", "rows_plus")) drawControl(graphics, theme, id, "+", mouseX, mouseY);
        drawControl(graphics, theme, "reset", SidebarText.text("panel.settings.reset"), mouseX, mouseY);
        drawControl(graphics, theme, "mappings", SidebarText.text("panel.settings.mappings"), mouseX, mouseY);
        drawControl(graphics, theme, "grouping", SidebarText.text("panel.settings.grouping",
            SidebarText.text(preferences.groupProviders() ? "panel.settings.on" : "panel.settings.off")), mouseX, mouseY);
        if (box.height() >= 206) clippedText(graphics, font, persistenceError.isEmpty() ? SidebarText.text("panel.settings.auto_saved") : SidebarText.resolve(persistenceError),
            box.x() + 8, box.y() + 188, box.width() - 16, theme.muted());
        if (hit("theme", mouseX, mouseY)) graphics.renderTooltip(font, SidebarText.component("panel.settings.theme.tooltip"), mouseX, mouseY);
        else if (hit("grouping", mouseX, mouseY)) graphics.renderTooltip(font, SidebarText.component("panel.settings.grouping.tooltip"), mouseX, mouseY);
        else if (hit("height", mouseX, mouseY)) graphics.renderTooltip(font, SidebarText.component("panel.settings.height.tooltip"), mouseX, mouseY);
        else if (hit("rows_minus", mouseX, mouseY) || hit("rows_plus", mouseX, mouseY)) graphics.renderTooltip(font, SidebarText.component("panel.settings.rows.tooltip"), mouseX, mouseY);
        else renderHeaderTooltip(graphics, mouseX, mouseY);
    }

    private void drawControl(GuiGraphics graphics, SidebarTheme theme, String id, String label, int mouseX, int mouseY) {
        var box = controlBounds(id);
        if (box == null) return;
        theme.button(graphics, box.x(), box.y(), box.width(), box.height(), true, box.contains(mouseX, mouseY));
        var font = Minecraft.getInstance().font;
        clippedText(graphics, font, label, box.x() + Math.max(2, (box.width() - font.width(label)) / 2),
            box.y() + (box.height() - 8) / 2, box.width() - 4, theme.buttonText(true), theme.buttonShadow());
    }

    private void renderHeaderTooltip(GuiGraphics graphics, int x, int y) {
        String tip = hit("lock", x, y) ? (preferences.locked() ? SidebarText.text("panel.tooltip.unlock") : SidebarText.text("panel.tooltip.lock"))
            : hit("settings", x, y) ? SidebarText.text("panel.tooltip.settings") : hit("collapse", x, y) ? SidebarText.text("panel.tooltip.collapse") : null;
        if (tip != null) graphics.renderTooltip(Minecraft.getInstance().font, Component.literal(tip), x, y);
    }

    boolean contains(double mouseX, double mouseY) { return layout().contains(mouseX, mouseY); }

    private static SidebarSession.Layout groupUploadBounds(SidebarSession.Layout row) {
        return rect(row.x() + row.width() - 36, row.y() + 3, 32, 20);
    }

    void mouseClicked(double mouseX, double mouseY, int button) {
        var box = layout();
        if (!box.contains(mouseX, mouseY)) return;
        if (button == 0 && hit("collapse", mouseX, mouseY)) {
            settingsOpen = false;
            preferences.setCollapsed(!box.collapsed());
            var after = layout();
            preferences.moveTo(PanelGeometry.normalized(box.x(), screen.width, after.width()),
                PanelGeometry.normalized(box.y(), screen.height, after.height()));
            unfocus(); save(); return;
        }
        if (!box.collapsed() && button == 0 && hit("lock", mouseX, mouseY)) {
            preferences.setLocked(!preferences.locked()); dragging = false; save(); return;
        }
        if (!box.collapsed() && button == 0 && hit("settings", mouseX, mouseY)) {
            settingsOpen = !settingsOpen; unfocus(); return;
        }
        if (button == 0 && mouseY < box.y() + 22) {
            unfocus();
            if (!preferences.locked()) { dragging = true; dragOffsetX = mouseX - box.x(); dragOffsetY = mouseY - box.y(); }
            return;
        }
        if (box.collapsed()) return;
        if (settingsOpen) { if (button == 0) clickSettings(mouseX, mouseY); return; }
        positionSearch();
        boolean searchClicked = !SidebarClient.isProviderView() && hit("search", mouseX, mouseY);
        search.setFocused(searchClicked);
        if (searchClicked) { search.mouseClicked(mouseX, mouseY, button); return; }
        if (button != 0 && button != 1 && button != 2) return;
        if (button == 0 && hit("mapping_selector", mouseX, mouseY)) {
            SidebarMappings.openSelector(screen);
            return;
        }
        var filtered = visibleRows();
        firstRow = Math.max(0, Math.min(firstRow, Math.max(0, filtered.size() - PanelGeometry.rowCount(box))));
        for (int row = 0; row < PanelGeometry.rowCount(box); row++) {
            if (hit("row" + row, mouseX, mouseY) && firstRow + row < filtered.size()) {
                var item = filtered.get(firstRow + row);
                if (item.header()) {
                    if (groupUploadBounds(controlBounds("row" + row)).contains(mouseX, mouseY)) {
                        if (button == 0 && SidebarClient.canUpload() && screen.getMenu().getCarried().isEmpty())
                            item.members().stream().filter(e -> e.freeSlots() > 0).findFirst().ifPresent(SidebarClient::upload);
                        return;
                    }
                    if (button == 0 && !expandedGroups.remove(item.key())) expandedGroups.add(item.key());
                    return;
                }
                var entry = item.members().getFirst();
                noteError = () -> "";
                iconFeedback = () -> "";
                if (hit("favorite" + row, mouseX, mouseY)) {
                    if (button == 0) {
                        boolean saved = SidebarNotes.toggleFavorite(entry);
                        boolean favorite = SidebarNotes.favorite(entry);
                        iconFeedback = saved ? () -> SidebarText.text(favorite ? "panel.feedback.favorite_added" : "panel.feedback.favorite_removed") : SidebarNotes::favoriteError;
                        if (saved) firstRow = 0;
                    }
                    return;
                }
                if (button == 2) {
                    Component message = SidebarLocator.toggleMessage(entry);
                    iconFeedback = message::getString;
                    return;
                }
                if (hit("icon" + row, mouseX, mouseY)) {
                    if (button == 1 && Screen.hasShiftDown()) {
                        boolean saved = SidebarNotes.resetIcon(entry);
                        iconFeedback = saved ? () -> SidebarText.text("panel.feedback.icon_reset") : SidebarNotes::iconError;
                        return;
                    }
                    var carried = screen.getMenu().getCarried();
                    if (button == 0 && !carried.isEmpty()) {
                        acceptIcon(new IconTarget(entry, controlBounds("icon" + row)), carried);
                        return;
                    }
                }
                if (button == 1 || SidebarClient.isProviderView()) {
                    if (!NoteEditorScreen.open(screen, entry)) noteError = SidebarNotes::unavailableReason;
                } else SidebarClient.upload(entry);
                return;
            }
        }
    }

    private void clickSettings(double x, double y) {
        if (hit("theme", x, y)) preferences.setTheme(preferences.theme() == SidebarPreferences.Theme.AE2 ? SidebarPreferences.Theme.VANILLA : SidebarPreferences.Theme.AE2);
        else if (hit("height", x, y)) {
            var modes = PanelGeometry.HeightMode.values();
            preferences.setHeightMode(modes[(preferences.heightMode().ordinal() + 1) % modes.length]);
        } else if (hit("width_minus", x, y)) preferences.setWidth(preferences.width() - 16);
        else if (hit("width_plus", x, y)) preferences.setWidth(preferences.width() + 16);
        else if (hit("rows_minus", x, y)) preferences.setRows(preferences.rows() - 1);
        else if (hit("rows_plus", x, y)) preferences.setRows(preferences.rows() + 1);
        else if (hit("grouping", x, y)) preferences.setGroupProviders(!preferences.groupProviders());
        else if (hit("reset", x, y)) { preferences.resetPosition(); settingsOpen = false; dragging = false; }
        else if (hit("mappings", x, y)) { SidebarMappings.openManager(screen); return; }
        else return;
        firstRow = 0;
        save();
    }

    void mouseDragged(double x, double y, int button) {
        if (button != 0 || !dragging || preferences.locked()) return;
        var box = layout();
        preferences.moveTo(PanelGeometry.normalized(x - dragOffsetX, screen.width, box.width()),
            PanelGeometry.normalized(y - dragOffsetY, screen.height, box.height()));
    }

    void mouseReleased(double x, double y, int button) {
        if (button != 0 || !dragging) return;
        mouseDragged(x, y, button);
        dragging = false;
        save();
    }

    void scroll(double delta) {
        if (layout().collapsed() || settingsOpen || delta == 0) return;
        int max = Math.max(0, visibleRows().size() - PanelGeometry.rowCount(layout()));
        firstRow = Math.max(0, Math.min(max, firstRow + (delta > 0 ? -1 : 1)));
    }
    void unfocus() { search.setFocused(false); }
    boolean keyPressed(int key, int scan, int modifiers) {
        if (settingsOpen && key == GLFW.GLFW_KEY_ESCAPE) { settingsOpen = false; return true; }
        if (!search.isFocused()) return false;
        if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) unfocus();
        else search.keyPressed(key, scan, modifiers);
        return true;
    }
    boolean charTyped(char codePoint, int modifiers) {
        if (!search.isFocused()) return false;
        search.charTyped(codePoint, modifiers);
        return true;
    }
    private void save() {
        try { preferences.save(preferencesPath); persistenceError = ""; }
        catch (IOException error) {
            persistenceError = SidebarText.PREFIX + "panel.error.save_preferences";
            LogUtils.getLogger().warn("Cannot save AE2 sidebar client preferences", error);
        }
    }
    private static void clippedText(GuiGraphics graphics, Font font, String text, int x, int y, int width, int color) {
        clippedText(graphics, font, text, x, y, width, color, false);
    }
    private static void clippedText(GuiGraphics graphics, Font font, String text, int x, int y, int width, int color, boolean shadow) {
        if (width <= 0) return;
        String visible = font.width(text) <= width ? text : font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
        graphics.drawString(font, visible, x, y, color, shadow);
    }
}
