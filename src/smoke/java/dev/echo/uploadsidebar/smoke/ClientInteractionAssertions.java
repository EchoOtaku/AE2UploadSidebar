package dev.echo.uploadsidebar.smoke;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.me.items.PatternEncodingTermScreen;
import appeng.menu.SlotSemantics;
import com.mojang.blaze3d.platform.InputConstants;
import dev.echo.uploadsidebar.client.SidebarClient;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.renderer.Rect2i;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

/** Drives actual screen events and waits for a real C2S/S2C upload, never mocks a result. */
final class ClientInteractionAssertions {
    private static boolean clicked;
    private static boolean completed;
    private static Object originalMenu;
    private static Object originalScreen;
    private static long clickedAt;

    static void reset() {
        clicked = false;
        completed = false;
        originalMenu = null;
        originalScreen = null;
        clickedAt = 0;
    }

    static boolean tick(Minecraft client, PatternEncodingTermScreen<?> screen) throws Exception {
        if (completed) return true;
        if (clicked) {
            require(client.screen == originalScreen && client.player.containerMenu == originalMenu,
                    "upload must retain the real terminal screen and menu");
            int count = screen.getMenu().getSlots(SlotSemantics.ENCODED_PATTERN).getFirst().getItem().getCount();
            String status = (String) field(SidebarClient.class, null, "status");
            if (count == 2 && status.equals("已上传 1 张样板")) {
                completed = true;
                return true;
            }
            require(System.nanoTime() - clickedAt < 15_000_000_000L,
                    "real upload timed out, count=" + count + ", status=" + status);
            return false;
        }
        List<?> entries = (List<?>) field(SidebarClient.class, null, "entries");
        var canUpload = SidebarClient.class.getDeclaredMethod("canUpload");
        canUpload.setAccessible(true);
        if (entries.size() != 2 || !(boolean) canUpload.invoke(null)) return false;
        Rect2i box = SidebarClient.exclusionZone(screen);
        require(box != null && box.getWidth() >= 104, "embedded panel is expanded");
        var searchBounds = LayoutInteractionAssertions.controlBounds("search");
        var rowBounds = LayoutInteractionAssertions.controlBounds("row0");
        require(searchBounds != null && rowBounds != null, "search and provider row are exposed");
        double searchX = searchBounds.x() + 5d, searchY = searchBounds.y() + 7d;
        double providerX = rowBounds.x() + 12d, providerY = rowBounds.y() + 12d;
        require(screen.getExclusionZones().stream().anyMatch(zone -> sameBounds(zone, box)), "JEI excludes actual sidebar");
        var outside = AEBaseScreen.class.getDeclaredMethod("hasClickedOutside", double.class, double.class, int.class, int.class, int.class);
        outside.setAccessible(true);
        require(!(boolean) outside.invoke(screen, box.getX() + 10d, box.getY() + 70d,
                screen.getGuiLeft(), screen.getGuiTop(), 0), "sidebar is not an outside-container drop target");

        // Search interaction is consumed and edits only the new search field.
        require(press(screen, searchX, searchY).isCanceled(), "search press consumed");
        NeoForge.EVENT_BUS.post(new ScreenEvent.MouseButtonReleased.Pre(screen, searchX, searchY, 0));
        var typed = new ScreenEvent.CharacterTyped.Pre(screen, 'x', 0);
        NeoForge.EVENT_BUS.post(typed);
        require(typed.isCanceled(), "search typing consumed");
        Object panel = field(SidebarClient.class, null, "panel");
        EditBox search = (EditBox) field(panel.getClass(), panel, "search");
        require(search.getValue().equals("x"), "sidebar search receives typed character");
        var backspace = new ScreenEvent.KeyPressed.Pre(screen, GLFW.GLFW_KEY_BACKSPACE, 0, 0);
        NeoForge.EVENT_BUS.post(backspace);
        require(backspace.isCanceled() && search.getValue().isEmpty(), "search backspace consumed");
        NeoForge.EVENT_BUS.post(new ScreenEvent.KeyPressed.Pre(screen, GLFW.GLFW_KEY_ESCAPE, 0, 0));

        // Point at the sidebar without search focus: inventory hotkeys must not use AE2's stale hovered slot.
        Field mouseX = client.mouseHandler.getClass().getDeclaredField("xpos");
        Field mouseY = client.mouseHandler.getClass().getDeclaredField("ypos");
        mouseX.setAccessible(true); mouseY.setAccessible(true);
        double previousX = mouseX.getDouble(client.mouseHandler), previousY = mouseY.getDouble(client.mouseHandler);
        try {
            mouseX.setDouble(client.mouseHandler, (box.getX() + 15d) * client.getWindow().getScreenWidth() / screen.width);
            mouseY.setDouble(client.mouseHandler, (box.getY() + 70d) * client.getWindow().getScreenHeight() / screen.height);
            for (int key : new int[] {GLFW.GLFW_KEY_Q, GLFW.GLFW_KEY_1, GLFW.GLFW_KEY_F}) {
                var hotkey = new ScreenEvent.KeyPressed.Pre(screen, key, 0, 0);
                NeoForge.EVENT_BUS.post(hotkey);
                require(hotkey.isCanceled(), "sidebar inventory hotkey must be consumed: " + key);
            }
            var inventoryKey = client.options.keyInventory;
            var oldKey = inventoryKey.getKey();
            var oldModifier = inventoryKey.getKeyModifier();
            try {
                inventoryKey.setKeyModifierAndCode(KeyModifier.CONTROL, InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_Q));
                var bareQ = new ScreenEvent.KeyPressed.Pre(screen, GLFW.GLFW_KEY_Q, 0, 0);
                NeoForge.EVENT_BUS.post(bareQ);
                require(bareQ.isCanceled(), "bare Q remains blocked when close-inventory is Ctrl+Q");
            } finally {
                inventoryKey.setKeyModifierAndCode(oldModifier, oldKey);
            }
            var escape = new ScreenEvent.KeyPressed.Pre(screen, GLFW.GLFW_KEY_ESCAPE, 0, 0);
            NeoForge.EVENT_BUS.post(escape);
            require(!escape.isCanceled(), "Escape can still close the terminal");
        } finally {
            mouseX.setDouble(client.mouseHandler, previousX);
            mouseY.setDouble(client.mouseHandler, previousY);
        }

        // Main-terminal input is NOT swallowed by the sidebar event layer.
        var originalClick = press(screen, screen.getGuiLeft() + 90, screen.getGuiTop() + 60);
        require(!originalClick.isCanceled(), "original terminal area remains interactive");
        var releaseFromTerminal = new ScreenEvent.MouseButtonReleased.Pre(screen, box.getX() + 10, box.getY() + 70, 0);
        NeoForge.EVENT_BUS.post(releaseFromTerminal);
        require(!releaseFromTerminal.isCanceled(), "terminal drag release keeps original cleanup");

        require(screen.getMenu().getSlots(SlotSemantics.ENCODED_PATTERN).getFirst().getItem().getCount() == 3,
                "fixture contains three patterns before the actual click");
        originalScreen = client.screen;
        originalMenu = client.player.containerMenu;
        clickedAt = System.nanoTime();
        require(press(screen, providerX, providerY).isCanceled(), "provider press consumed");
        var release = new ScreenEvent.MouseButtonReleased.Pre(screen, providerX, providerY, 0);
        NeoForge.EVENT_BUS.post(release);
        require(release.isCanceled(), "sidebar-started release consumed");
        // Repeated click during the same pending request must not send a second upload.
        require(press(screen, providerX, providerY).isCanceled(), "duplicate press stays in sidebar");
        NeoForge.EVENT_BUS.post(new ScreenEvent.MouseButtonReleased.Pre(screen, providerX, providerY, 0));
        clicked = true;
        return false;
    }

    private static ScreenEvent.MouseButtonPressed.Pre press(PatternEncodingTermScreen<?> screen, double x, double y) {
        var event = new ScreenEvent.MouseButtonPressed.Pre(screen, x, y, 0);
        NeoForge.EVENT_BUS.post(event);
        return event;
    }
    private static boolean sameBounds(Rect2i a, Rect2i b) {
        return a.getX() == b.getX() && a.getY() == b.getY() && a.getWidth() == b.getWidth() && a.getHeight() == b.getHeight();
    }
    private static Object field(Class<?> owner, Object object, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError("SMOKE_CLIENT_ASSERT: " + message);
    }
}
