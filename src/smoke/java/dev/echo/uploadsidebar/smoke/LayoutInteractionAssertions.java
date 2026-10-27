package dev.echo.uploadsidebar.smoke;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.me.items.PatternEncodingTermScreen;
import com.mojang.logging.LogUtils;
import dev.echo.uploadsidebar.client.SidebarClient;
import dev.echo.uploadsidebar.client.SidebarPreferences;
import dev.echo.uploadsidebar.core.PanelGeometry;
import dev.echo.uploadsidebar.core.SidebarSession;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** Real NeoForge screen events; captures rendered states between control actions. */
final class LayoutInteractionAssertions {
    private static int phase;
    private static int renderedTicks;
    private static String pendingCapture;
    private static Slot coveredInventorySlot;
    private static double coveredInventoryX;
    private static double coveredInventoryY;

    static boolean tick(Minecraft client, PatternEncodingTermScreen<?> screen) throws Exception {
        if (SidebarClient.exclusionZone(screen) == null) return false;
        if (pendingCapture != null) {
            if (++renderedTicks < 8) return false;
            SmokeScreenshots.request(client, pendingCapture);
            if (!SmokeScreenshots.saved(pendingCapture)) return false;
            pendingCapture = null;
            renderedTicks = 0;
            return false;
        }
        SidebarPreferences preferences = preferences();
        LogUtils.getLogger().info("EMBEDDED_SMOKE: layout interaction phase {}", phase);
        switch (phase++) {
            case 0 -> {
                // Normalize only the isolated run's settings through the same controls a user uses.
                if (layout().collapsed()) clickControl(screen, "collapse");
                clickControl(screen, "settings");
                clickControl(screen, "reset");
                if (controlBounds("theme") == null) clickControl(screen, "settings");
                if (preferences.theme() != SidebarPreferences.Theme.AE2) clickControl(screen, "theme");
                for (int attempt = 0; preferences.heightMode() != PanelGeometry.HeightMode.STANDARD && attempt < 3; attempt++) {
                    clickControl(screen, "height");
                }
                for (int attempt = 0; preferences.rows() != 5 && attempt < 12; attempt++) {
                    clickControl(screen, preferences.rows() < 5 ? "rows_plus" : "rows_minus");
                }
                clickControl(screen, "settings");
                require(!layout().collapsed(), "normal viewport expands the sidebar");
                verifyProtection(screen);
                capture("sidebar-ae2-expanded.png");
            }
            case 1 -> {
                SidebarSession.Layout before = layout();
                drag(screen, before.x() + 14, before.y() + 10, before.x() + 94, before.y() + 45);
                SidebarSession.Layout moved = layout();
                require(moved.x() != before.x() || moved.y() != before.y(), "title drag moves actual panel bounds");
                coveredInventorySlot = screen.getMenu().slots.stream()
                    .filter(slot -> slot.container == client.player.getInventory() && slot.isActive())
                    .findFirst().orElseThrow(() -> new AssertionError("No actual player inventory slot in terminal"));
                coveredInventoryX = screen.getGuiLeft() + coveredInventorySlot.x + 8d;
                coveredInventoryY = screen.getGuiTop() + coveredInventorySlot.y + 8d;
                drag(screen, moved.x() + 14, moved.y() + 10, coveredInventoryX - 6, coveredInventoryY - 40);
                require(layout().contains(coveredInventoryX, coveredInventoryY), "floating sidebar covers the actual inventory slot");
                require(findSlot(screen, coveredInventoryX, coveredInventoryY) == null,
                    "sidebar occludes hidden inventory slot from real container hover and release hit testing");
                verifyProtection(screen);
                capture("sidebar-dragged.png");
            }
            case 2 -> {
                clickControl(screen, "settings");
                clickControl(screen, "reset");
                require(findSlot(screen, coveredInventoryX, coveredInventoryY) == coveredInventorySlot
                    && coveredInventorySlot.isActive() && coveredInventorySlot.mayPickup(client.player),
                    "reset restores original inventory slot hit testing and pickup availability");
                verifyMixedButtonDrag(screen);
                SidebarSession.Layout before = layout();
                drag(screen, before.x() + 14, before.y() + 10, screen.width * 2d, screen.height * 2d);
                SidebarSession.Layout clamped = layout();
                require(clamped.x() + clamped.width() == screen.width
                    && clamped.y() + clamped.height() == screen.height, "drag clamps to the viewport edge");
                var staleDrag = new ScreenEvent.MouseDragged.Pre(screen, clamped.x() + 20d,
                    clamped.y() + 12d, 0, -100, -100);
                NeoForge.EVENT_BUS.post(staleDrag);
                require(layout().equals(clamped), "release ends the title drag even outside the screen");
                verifyProtection(screen);
            }
            case 3 -> {
                clickControl(screen, "collapse");
                SidebarSession.Layout collapsed = layout();
                require(collapsed.collapsed() && collapsed.width() == 84 && collapsed.height() == 20,
                    "collapse produces the small interactive tab");
                drag(screen, collapsed.x() + 12, collapsed.y() + 10, collapsed.x() - 38, collapsed.y() - 30);
                require(layout().collapsed() && !layout().equals(collapsed), "collapsed tab remains draggable");
                verifyProtection(screen);
                capture("sidebar-collapsed.png");
            }
            case 4 -> {
                clickControl(screen, "collapse");
                require(!layout().collapsed(), "collapsed tab restores the provider list");
                clickControl(screen, "lock");
                require(preferences.locked(), "lock button enables position lock");
                SidebarSession.Layout locked = layout();
                drag(screen, locked.x() + 14, locked.y() + 10, locked.x() - 66, locked.y() - 35);
                require(layout().equals(locked), "locked title drag cannot change panel position");
                verifyProtection(screen);
                capture("sidebar-locked.png");
            }
            case 5 -> {
                clickControl(screen, "collapse");
                require(layout().collapsed() && preferences.locked(), "lock does not disable collapse");
                clickControl(screen, "collapse");
                require(!layout().collapsed() && preferences.locked(), "lock does not disable expansion");
                clickControl(screen, "lock");
                require(!preferences.locked(), "lock toggles off");
                clickControl(screen, "settings");
                int oldWidth = preferences.width();
                String widthDirection = oldWidth < PanelGeometry.MAX_WIDTH ? "width_plus" : "width_minus";
                clickControl(screen, widthDirection);
                require(preferences.width() != oldWidth, "width control changes stored width");
                require(layout().width() == preferences.width(), "dragged panel renders at selected width");
                clickControl(screen, widthDirection.equals("width_plus") ? "width_minus" : "width_plus");
                require(preferences.width() == oldWidth, "opposite width control restores selected width");
                int oldRows = preferences.rows();
                int oldHeight = layout().height();
                clickControl(screen, "rows_plus");
                require(preferences.rows() == oldRows + 1 && layout().height() > oldHeight,
                    "row control changes both preference and rendered height");
                clickControl(screen, "rows_minus");
                require(preferences.rows() == oldRows && layout().height() == oldHeight, "row decrement restores height");
                Set<PanelGeometry.HeightMode> modes = new HashSet<>();
                for (int i = 0; i < 3; i++) {
                    clickControl(screen, "height");
                    modes.add(preferences.heightMode());
                    clickControl(screen, "settings");
                    if (preferences.heightMode() == PanelGeometry.HeightMode.COMPACT) {
                        require(PanelGeometry.rowCount(layout()) == 3, "compact preset displays three rows");
                    }
                    if (preferences.heightMode() == PanelGeometry.HeightMode.TERMINAL) {
                        require(layout().height() == Math.min(screen.height,
                            Math.max(PanelGeometry.LIST_TOP + PanelGeometry.ROW_HEIGHT + PanelGeometry.FOOTER_HEIGHT,
                                screen.getYSize())), "terminal preset follows original AE2 height");
                    }
                    clickControl(screen, "settings");
                }
                require(modes.size() == 3 && preferences.heightMode() == PanelGeometry.HeightMode.STANDARD,
                    "height control cycles compact, standard and terminal modes");
                verifyProtection(screen);
                capture("sidebar-settings-ae2.png");
            }
            case 6 -> {
                clickControl(screen, "theme");
                require(preferences.theme() == SidebarPreferences.Theme.VANILLA, "theme control selects Vanilla");
                capture("sidebar-settings-vanilla.png");
            }
            case 7 -> {
                clickControl(screen, "settings");
                verifyProtection(screen);
                capture("sidebar-vanilla-expanded.png");
            }
            case 8 -> {
                clickControl(screen, "settings");
                clickControl(screen, "theme");
                require(preferences.theme() == SidebarPreferences.Theme.AE2, "theme control returns to AE2");
                int width = preferences.width(), rows = preferences.rows();
                clickControl(screen, "reset");
                require(preferences.x() == -1 && preferences.y() == -1
                    && !preferences.locked() && !preferences.collapsed(), "reset clears position, lock and collapse state");
                require(preferences.width() == width && preferences.rows() == rows
                    && preferences.theme() == SidebarPreferences.Theme.AE2, "position reset preserves visual choices");
                if (controlBounds("theme") != null) clickControl(screen, "settings");
                verifyProtection(screen);
                capture("sidebar-reset-position.png");
            }
            default -> { return true; }
        }
        return false;
    }

    static String report() {
        return "real_screen_pre_event_drag_and_release=PASS\n"
            + "drag_viewport_clamp=PASS\ncollapse_expand_and_collapsed_drag=PASS\n"
            + "lock_blocks_drag_and_allows_controls=PASS\n"
            + "width_rows_and_three_height_modes=PASS\n"
            + "ae2_vanilla_theme_switch_and_render=PASS\n"
            + "reset_position=PASS\njei_and_outside_click_bounds_follow_panel=PASS\n"
            + "sidebar_scroll_pre_event_consumed=PASS\n"
            + "sidebar_drag_outside_viewport_key_and_character_blocked=PASS\n"
            + "covered_inventory_slot_hover_release_blocked_and_reset_restored=PASS\n"
            + "secondary_mouse_button_preserves_primary_drag_and_blocks_underlay=PASS\n";
    }

    static SidebarSession.Layout controlBounds(String id) throws Exception {
        Object panel = panel();
        var method = panel.getClass().getDeclaredMethod("controlBounds", String.class);
        method.setAccessible(true);
        return (SidebarSession.Layout) method.invoke(panel, id);
    }

    static SidebarSession.Layout layout() throws Exception {
        Object panel = panel();
        var method = panel.getClass().getDeclaredMethod("layout");
        method.setAccessible(true);
        return (SidebarSession.Layout) method.invoke(panel);
    }

    static void clickControl(PatternEncodingTermScreen<?> screen, String id) throws Exception {
        SidebarSession.Layout target = controlBounds(id);
        require(target != null, "control is visible: " + id);
        double x = target.x() + target.width() / 2d, y = target.y() + target.height() / 2d;
        require(press(screen, x, y).isCanceled(), "control press consumed: " + id);
        require(release(screen, x, y).isCanceled(), "control release consumed: " + id);
    }

    static ScreenEvent.MouseButtonPressed.Pre press(PatternEncodingTermScreen<?> screen, double x, double y) {
        var event = new ScreenEvent.MouseButtonPressed.Pre(screen, x, y, 0);
        NeoForge.EVENT_BUS.post(event);
        return event;
    }

    private static ScreenEvent.MouseButtonReleased.Pre release(PatternEncodingTermScreen<?> screen, double x, double y) {
        var event = new ScreenEvent.MouseButtonReleased.Pre(screen, x, y, 0);
        NeoForge.EVENT_BUS.post(event);
        return event;
    }

    private static void drag(PatternEncodingTermScreen<?> screen, double startX, double startY, double endX, double endY) throws Exception {
        require(press(screen, startX, startY).isCanceled(), "title drag press consumed");
        var event = new ScreenEvent.MouseDragged.Pre(screen, endX, endY, 0, endX - startX, endY - startY);
        NeoForge.EVENT_BUS.post(event);
        require(event.isCanceled(), "sidebar title drag consumed");
        Minecraft client = Minecraft.getInstance();
        Field mouseX = client.mouseHandler.getClass().getDeclaredField("xpos");
        Field mouseY = client.mouseHandler.getClass().getDeclaredField("ypos");
        mouseX.setAccessible(true);
        mouseY.setAccessible(true);
        double previousX = mouseX.getDouble(client.mouseHandler);
        double previousY = mouseY.getDouble(client.mouseHandler);
        try {
            mouseX.setDouble(client.mouseHandler, -100);
            mouseY.setDouble(client.mouseHandler, -100);
            var hotkey = new ScreenEvent.KeyPressed.Pre(screen, GLFW.GLFW_KEY_Q, 0, 0);
            NeoForge.EVENT_BUS.post(hotkey);
            require(hotkey.isCanceled(), "sidebar-started drag blocks Q outside the viewport");
            var typed = new ScreenEvent.CharacterTyped.Pre(screen, 'x', 0);
            NeoForge.EVENT_BUS.post(typed);
            require(typed.isCanceled(), "sidebar-started drag blocks characters outside the viewport");
        } finally {
            mouseX.setDouble(client.mouseHandler, previousX);
            mouseY.setDouble(client.mouseHandler, previousY);
        }
        require(release(screen, endX, endY).isCanceled(), "sidebar title drag release consumed");
    }

    private static SidebarPreferences preferences() throws Exception {
        Object panel = panel();
        var method = panel.getClass().getDeclaredMethod("preferences");
        method.setAccessible(true);
        return (SidebarPreferences) method.invoke(panel);
    }

    private static void verifyMixedButtonDrag(PatternEncodingTermScreen<?> screen) throws Exception {
        SidebarSession.Layout initial = layout();
        double startX = initial.x() + 14d, startY = initial.y() + 10d;
        require(press(screen, startX, startY).isCanceled(), "mixed-button drag starts in sidebar");
        var firstDrag = new ScreenEvent.MouseDragged.Pre(screen, startX + 32, startY + 12, 0, 32, 12);
        NeoForge.EVENT_BUS.post(firstDrag);
        require(firstDrag.isCanceled() && !layout().equals(initial), "primary drag moves before secondary button");
        SidebarSession.Layout firstPosition = layout();

        double outsideX = screen.width - 2d, outsideY = 2d;
        require(!firstPosition.contains(outsideX, outsideY), "secondary button fixture points outside the sidebar");
        var secondaryPress = new ScreenEvent.MouseButtonPressed.Pre(screen, outsideX, outsideY, 1);
        NeoForge.EVENT_BUS.post(secondaryPress);
        require(secondaryPress.isCanceled(), "secondary mouse press during sidebar drag cannot reach the terminal");
        var secondaryRelease = new ScreenEvent.MouseButtonReleased.Pre(screen, outsideX, outsideY, 1);
        NeoForge.EVENT_BUS.post(secondaryRelease);
        require(secondaryRelease.isCanceled(), "secondary mouse release during sidebar drag cannot reach the terminal");

        var continuedDrag = new ScreenEvent.MouseDragged.Pre(screen, startX + 84, startY + 35, 0, 52, 23);
        NeoForge.EVENT_BUS.post(continuedDrag);
        require(continuedDrag.isCanceled() && !layout().equals(firstPosition),
            "secondary button release preserves the primary drag ownership");
        require(release(screen, startX + 84, startY + 35).isCanceled(), "primary release ends the mixed-button drag");
        SidebarSession.Layout releasedPosition = layout();
        var afterRelease = new ScreenEvent.MouseDragged.Pre(screen,
            releasedPosition.x() + 25d, releasedPosition.y() + 15d, 0, 100, 100);
        NeoForge.EVENT_BUS.post(afterRelease);
        require(layout().equals(releasedPosition), "mixed-button drag does not survive the primary release");
    }

    private static Slot findSlot(PatternEncodingTermScreen<?> screen, double x, double y) throws Exception {
        var method = AbstractContainerScreen.class.getDeclaredMethod("findSlot", double.class, double.class);
        method.setAccessible(true);
        return (Slot) method.invoke(screen, x, y);
    }

    private static Object panel() throws Exception {
        Field field = SidebarClient.class.getDeclaredField("panel");
        field.setAccessible(true);
        Object panel = field.get(null);
        require(panel != null, "real terminal has a sidebar panel");
        return panel;
    }

    private static void verifyProtection(PatternEncodingTermScreen<?> screen) throws Exception {
        SidebarSession.Layout actual = layout();
        Rect2i exclusion = SidebarClient.exclusionZone(screen);
        require(exclusion != null && exclusion.getX() == actual.x() && exclusion.getY() == actual.y()
            && exclusion.getWidth() == actual.width() && exclusion.getHeight() == actual.height(),
            "JEI exclusion uses the actual moved/collapsed panel bounds");
        require(screen.getExclusionZones().stream().anyMatch(zone -> zone.getX() == actual.x()
            && zone.getY() == actual.y() && zone.getWidth() == actual.width() && zone.getHeight() == actual.height()),
            "AE2 screen exposes the actual sidebar to JEI");
        var outside = AEBaseScreen.class.getDeclaredMethod("hasClickedOutside",
            double.class, double.class, int.class, int.class, int.class);
        outside.setAccessible(true);
        require(!(boolean) outside.invoke(screen, actual.x() + actual.width() / 2d,
            actual.y() + actual.height() / 2d, screen.getGuiLeft(), screen.getGuiTop(), 0),
            "actual sidebar area cannot drop a carried item outside the menu");
        var scroll = new ScreenEvent.MouseScrolled.Pre(screen, actual.x() + actual.width() / 2d,
            actual.y() + actual.height() / 2d, 0, 1);
        NeoForge.EVENT_BUS.post(scroll);
        require(scroll.isCanceled(), "scroll over the actual sidebar is consumed");
    }

    private static void capture(String name) { pendingCapture = name; renderedTicks = 0; }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError("SMOKE_LAYOUT_ASSERT: " + message);
    }
}
