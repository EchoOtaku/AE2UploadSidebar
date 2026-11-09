package dev.echo.uploadsidebar.smoke;

import appeng.client.gui.me.items.PatternEncodingTermScreen;
import dev.echo.uploadsidebar.client.SidebarClient;
import dev.echo.uploadsidebar.client.SidebarPreferences;
import mezz.jei.common.gui.JeiTooltip;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Exercises JEI's real tooltip renderer across sidebar icons at its normal event priority. */
final class TooltipOverlapAssertions {
    private static int ticks;
    private static boolean active;
    private static SidebarPreferences preferences;

    static boolean tick(Minecraft client, PatternEncodingTermScreen<?> screen) throws Exception {
        if (!net.neoforged.fml.ModList.get().isLoaded("jei")) return true;
        if (ticks == 0) {
            var field = SidebarClient.class.getDeclaredField("panel");
            field.setAccessible(true);
            Object panel = field.get(null);
            var prefs = panel.getClass().getDeclaredField("preferences");
            prefs.setAccessible(true);
            preferences = (SidebarPreferences) prefs.get(panel);
            NeoForge.EVENT_BUS.addListener(TooltipOverlapAssertions::draw);
            active = true;
        }
        ticks++;
        if (ticks < 20 || (ticks > 30 && ticks < 40)) return false;
        String name = ticks < 40 ? "sidebar-jei-tooltip-ae2.png" : "sidebar-jei-tooltip-vanilla.png";
        SmokeScreenshots.request(client, name);
        if (!SmokeScreenshots.saved(name)) return false;
        if (ticks < 40) {
            preferences.setTheme(SidebarPreferences.Theme.VANILLA);
            ticks = 30;
            return false;
        }
        active = false;
        preferences.setTheme(SidebarPreferences.Theme.AE2);
        return true;
    }

    private static void draw(ScreenEvent.Render.Post event) {
        if (!active) return;
        var box = SidebarClient.exclusionZone(event.getScreen());
        if (box == null) throw new AssertionError("Sidebar absent during tooltip overlap test");
        var tooltip = new JeiTooltip();
        tooltip.add(Component.literal("JEI tooltip across sidebar icon"));
        tooltip.add(Component.literal("Text and background must remain visible"));
        tooltip.add(Component.literal("Minecraft").withStyle(net.minecraft.ChatFormatting.BLUE));
        tooltip.draw(event.getGuiGraphics(), box.getX() - 10, box.getY() + 90);
    }
}