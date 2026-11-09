package dev.echo.uploadsidebar.smoke;

import appeng.client.gui.me.items.PatternEncodingTermScreen;
import appeng.menu.SlotSemantics;
import com.mojang.logging.LogUtils;
import dev.echo.uploadsidebar.client.NoteEditorScreen;
import dev.echo.uploadsidebar.client.SidebarClient;
import dev.echo.uploadsidebar.client.SidebarMappings;
import dev.echo.uploadsidebar.client.SidebarNotes;
import dev.echo.uploadsidebar.client.SidebarText;
import dev.echo.uploadsidebar.net.SidebarPackets;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Real LanguageManager/resource reloads and framebuffer captures, confined to the smoke instance. */
final class LanguageAssertions {
    private static final List<String> LANGUAGES = List.of("en_us", "zh_tw", "zh_cn");
    private static final Map<String, String> TITLES = Map.of(
        "en_us", "Pattern Upload", "zh_tw", "樣板上傳", "zh_cn", "样板上传");
    private static final Component CACHED_TITLE = SidebarText.component("panel.title.upload");
    private static PatternEncodingTermScreen<?> terminal;
    private static ItemStack source;
    private static CompletableFuture<Void> reload;
    private static int phase, languageIndex, ticks;
    private static long phaseStarted;
    private static boolean passed;

    static boolean tick(Minecraft client) throws Exception {
        if (passed) return true;
        require(client.gameDirectory.toPath().toAbsolutePath().normalize().equals(SmokePaths.runDirectory().toAbsolutePath().normalize()),
            "language verification must remain in the isolated smoke instance");
        if (phaseStarted != 0) require(System.nanoTime() - phaseStarted < 45_000_000_000L,
            "language stage " + phase + " timed out");
        if (phase > 0) {
            require(client.player != null && client.player.containerMenu == terminal.getMenu(), "language changes retain the actual menu");
            require(ItemStack.matches(source, terminal.getMenu().getSlots(SlotSemantics.ENCODED_PATTERN).getFirst().getItem()),
                "language changes and editor previews never consume or change the pattern");
        }
        switch (phase) {
            case 0 -> {
                if (!(client.screen instanceof PatternEncodingTermScreen<?> screen)
                        || SidebarClient.exclusionZone(screen) == null || entries().size() != 2) return false;
                var output = screen.getMenu().getSlots(SlotSemantics.ENCODED_PATTERN).getFirst().getItem();
                if (output.getCount() != 3) return false;
                terminal = screen;
                source = output.copy();
                switchLanguage(client);
            }
            case 1 -> {
                if (!reload.isDone() || client.getOverlay() != null) return false;
                reload.join();
                String language = currentLanguage();
                require(client.getLanguageManager().getSelected().equals(language), "LanguageManager selected " + language);
                require(CACHED_TITLE.getString().equals(TITLES.get(language)), "cached translatable component follows actual resource reload: " + language);
                require(SidebarText.text("panel.title.upload").equals(TITLES.get(language)), "render-time text follows actual language: " + language);
                for (String locale : LANGUAGES) require(client.getResourceManager().getResource(
                    ResourceLocation.fromNamespaceAndPath("ae2_upload_sidebar", "lang/" + locale + ".json")).isPresent(),
                    "running mod artifact supplies " + locale);
                client.setScreen(terminal);
                next(2);
            }
            case 2 -> {
                if (client.getOverlay() != null || entries().size() != 2 || ++ticks < 8) return false;
                if (!capture(client, "panel")) return false;
                SidebarMappings.openSelector(terminal);
                require(client.screen != terminal, "mapping selector opened");
                next(3);
            }
            case 3 -> {
                if (++ticks < 8) return false;
                assertWidgets(client, client.screen);
                if (!capture(client, "mappings")) return false;
                client.screen.onClose();
                require(client.screen == terminal, "mapping selector returns to terminal");
                next(4);
            }
            case 4 -> {
                if (entries().size() != 2 || ++ticks < 8) return false;
                var entry = entries().getFirst();
                if (!SidebarNotes.canEdit(entry)) return false;
                require(NoteEditorScreen.open(terminal, entry), "note editor opened for an actual provider");
                next(5);
            }
            case 5 -> {
                if (++ticks < 8) return false;
                assertWidgets(client, client.screen);
                if (!capture(client, "notes")) return false;
                client.screen.onClose();
                require(client.screen == terminal, "canceling note preview returns to terminal");
                if (++languageIndex == LANGUAGES.size()) {
                    // The existing interaction suite expects Chinese labels; do not alter its assertions.
                    require(client.getLanguageManager().getSelected().equals("zh_cn"), "Chinese restored for existing smoke suite");
                    passed = true;
                    LogUtils.getLogger().info("EMBEDDED_SMOKE: actual three-language reload and UI preview assertions PASS");
                } else switchLanguage(client);
            }
            default -> throw new AssertionError("Unknown language smoke stage " + phase);
        }
        return passed;
    }

    private static void switchLanguage(Minecraft client) {
        String locale = currentLanguage();
        require(client.getLanguageManager().getLanguage(locale) != null, "game provides locale " + locale);
        client.getLanguageManager().setSelected(locale);
        client.options.languageCode = locale;
        // No options.save(): the isolated session switches language exactly as the game does,
        // while avoiding any persistent user-preference changes.
        LogUtils.getLogger().info("EMBEDDED_SMOKE: loading actual language resources {}", locale);
        reload = client.reloadResourcePacks();
        next(1);
    }

    private static void assertWidgets(Minecraft client, Screen screen) {
        require(!screen.getTitle().getString().contains("ae2_upload_sidebar."), "screen title resolves");
        require(client.font.width(screen.getTitle()) <= screen.width - 20, "localized title fits the screen");
        screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).forEach(widget -> {
            require(!widget.getMessage().getString().contains("ae2_upload_sidebar."), "widget label resolves");
            require(widget.getX() >= 0 && widget.getY() >= 0
                && widget.getX() + widget.getWidth() <= screen.width
                && widget.getY() + widget.getHeight() <= screen.height, "localized widgets stay within viewport");
        });
    }

    private static boolean capture(Minecraft client, String page) {
        String name = "sidebar-language-" + currentLanguage() + "-" + page + ".png";
        SmokeScreenshots.request(client, name);
        return SmokeScreenshots.saved(name);
    }

    private static String currentLanguage() { return LANGUAGES.get(languageIndex); }
    private static void next(int step) { phase = step; ticks = 0; phaseStarted = System.nanoTime(); }

    @SuppressWarnings("unchecked")
    private static List<SidebarPackets.Entry> entries() throws Exception {
        Field field = SidebarClient.class.getDeclaredField("entries");
        field.setAccessible(true);
        return (List<SidebarPackets.Entry>) field.get(null);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError("SMOKE_LANGUAGE_ASSERT: " + message);
    }

    static String report() {
        return "language_en_us_zh_tw_zh_cn_real_reload=" + (passed ? "PASS" : "NOT_RUN") + "\n"
            + "language_switch_preserves_encoded_pattern=" + (passed ? "PASS" : "NOT_RUN") + "\n"
            + "language_selector_note_widget_bounds=" + (passed ? "PASS" : "NOT_RUN") + "\n";
    }
}
