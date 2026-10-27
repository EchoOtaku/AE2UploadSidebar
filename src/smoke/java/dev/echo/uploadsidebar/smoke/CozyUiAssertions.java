package dev.echo.uploadsidebar.smoke;

import dev.echo.uploadsidebar.client.SidebarClient;
import dev.echo.uploadsidebar.client.SidebarPreferences;
import dev.echo.uploadsidebar.core.PanelGeometry;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

/** Optional real resource-pack fixture; selected only for this isolated client session. */
final class CozyUiAssertions {
    static final boolean ENABLED = Boolean.getBoolean("sidebar.smoke.cozy");
    private static CompletableFuture<Void> reload;
    private static boolean loaded, captured;
    private static int phase;
    private static long since;
    private static SidebarPreferences preferences;
    private static SidebarPreferences.Theme previousTheme;
    private static PanelGeometry.HeightMode previousHeight;

    static boolean prepare(Minecraft client) {
        if (!ENABLED || loaded) return true;
        if (reload == null) {
            var repository = client.getResourcePackRepository();
            repository.reload();
            String id = "file/CozyUI+_v1.10.zip";
            if (!repository.getAvailableIds().contains(id)) throw new AssertionError("Isolated CozyUI pack is missing");
            var selected = new ArrayList<>(repository.getSelectedIds());
            if (!selected.contains(id)) selected.add(id);
            repository.setSelected(selected);
            reload = client.reloadResourcePacks();
            return false;
        }
        if (!reload.isDone() || client.getOverlay() != null) return false;
        reload.join();
        verifySprites(client);
        loaded = true;
        return true;
    }

    private static void verifySprites(Minecraft client) {
        var manager = client.getGuiSprites();
        var background = manager.getSprite(ResourceLocation.withDefaultNamespace("popup/background"));
        if (background.contents().width() != 200 || background.contents().height() != 196
                || !(manager.getSpriteScaling(background) instanceof GuiSpriteScaling.NineSlice backgroundSlice)
                || backgroundSlice.width() != 50 || backgroundSlice.height() != 49
                || backgroundSlice.border().left() != 20)
            throw new AssertionError("Expected CozyUI's generic popup panel and its 50 x 49 nine-slice metadata");
        var hover = manager.getSprite(ResourceLocation.withDefaultNamespace("widget/button_highlighted"));
        if (hover.contents().width() != 320 || hover.contents().height() != 80
                || !(manager.getSpriteScaling(hover) instanceof GuiSpriteScaling.NineSlice slice) || slice.border().left() != 34)
            throw new AssertionError("Expected actual CozyUI arrow animation and large decorative borders");
    }

    static boolean capture(Minecraft client, appeng.client.gui.AEBaseScreen<?> screen) throws Exception {
        if (!ENABLED || captured) return true;
        switch (phase) {
            case 0 -> {
                var field = SidebarClient.class.getDeclaredField("panel"); field.setAccessible(true);
                Object panel = field.get(null);
                var preferenceField = panel.getClass().getDeclaredField("preferences"); preferenceField.setAccessible(true);
                preferences = (SidebarPreferences) preferenceField.get(panel);
                previousTheme = preferences.theme(); previousHeight = preferences.heightMode();
                preferences.setTheme(SidebarPreferences.Theme.VANILLA);
                preferences.setHeightMode(PanelGeometry.HeightMode.COMPACT);
                cursor(client, 2, 2);
                next(1);
            }
            case 1 -> {
                if (!elapsed()) return false;
                SmokeScreenshots.request(client, "sidebar-cozyui-normal.png");
                if (!SmokeScreenshots.saved("sidebar-cozyui-normal.png")) return false;
                var bounds = LayoutInteractionAssertions.controlBounds("row0");
                cursor(client, bounds.x() + bounds.width() - 3, bounds.y() + 8);
                next(2);
            }
            case 2 -> {
                if (!elapsed()) return false;
                SmokeScreenshots.request(client, "sidebar-cozyui-hover.png");
                if (!SmokeScreenshots.saved("sidebar-cozyui-hover.png")) return false;
                client.options.guiScale().set(4); client.resizeDisplay();
                preferences.setCollapsed(false);
                next(3);
            }
            case 3 -> {
                if (!elapsed()) return false;
                var bounds = LayoutInteractionAssertions.controlBounds("row0");
                cursor(client, bounds.x() + bounds.width() - 3, bounds.y() + 8);
                next(4);
            }
            case 4 -> {
                if (!elapsed()) return false;
                if (client.getWindow().getGuiScale() != 4) throw new AssertionError("Expected actual 4x GUI scale");
                SmokeScreenshots.request(client, "sidebar-cozyui-hover-scale4.png");
                if (!SmokeScreenshots.saved("sidebar-cozyui-hover-scale4.png")) return false;
                preferences.setTheme(previousTheme); preferences.setHeightMode(previousHeight);
                client.options.guiScale().set(2); client.resizeDisplay();
                cursor(client, 2, 2);
                captured = true;
                return true;
            }
        }
        return false;
    }

    private static void cursor(Minecraft client, double x, double y) {
        var window = client.getWindow();
        GLFW.glfwSetCursorPos(window.getWindow(), x * window.getScreenWidth() / window.getGuiScaledWidth(),
            y * window.getScreenHeight() / window.getGuiScaledHeight());
    }
    private static void next(int value) { phase = value; since = System.nanoTime(); }
    private static boolean elapsed() { return System.nanoTime() - since > 500_000_000L; }
    static String report() { return !ENABLED ? "" : "cozyui_actual_animated_resource_pack_atlas_and_hover_scale2_scale4=" + (captured ? "PASS" : "NOT_RUN") + "\n"; }
}
