package dev.echo.uploadsidebar.client;

import appeng.client.gui.style.PaletteColor;
import appeng.client.gui.style.StyleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling;
import net.minecraft.resources.ResourceLocation;

/** Reads live resource-pack textures; no copied AE2 or vanilla textures are bundled. */
final class SidebarTheme {
    private static final ResourceLocation AE2_BACKGROUND = ResourceLocation.fromNamespaceAndPath("ae2", "textures/guis/background.png");
    private static final ResourceLocation VANILLA_BACKGROUND = ResourceLocation.withDefaultNamespace("popup/background");
    private final boolean vanilla;
    private final int text;
    private final int muted;
    private final int negative;
    private final int selection;

    SidebarTheme(SidebarPreferences.Theme theme) {
        vanilla = theme == SidebarPreferences.Theme.VANILLA;
        if (vanilla) {
            text = 0xffeeeeee;
            muted = 0xffbbbbbb;
            negative = 0xffffb6a8;
            selection = 0xff9cdfbc;
        } else {
            // AE2 owns this cache and invalidates it on resource reload. Do not retain its
            // ScreenStyle across frames: packs can replace both textures and palette.json.
            var style = StyleManager.loadStyleDoc("/screens/common/common.json");
            text = style.getColor(PaletteColor.DEFAULT_TEXT_COLOR).toARGB();
            muted = style.getColor(PaletteColor.MUTED_TEXT_COLOR).toARGB();
            negative = style.getColor(PaletteColor.ERROR).toARGB();
            selection = style.getColor(PaletteColor.SELECTION_COLOR).toARGB();
        }
    }
    int text() { return text; }
    int muted() { return muted; }
    int positive() { return vanilla ? 0xff9cdfbc : text; }
    int negative() { return negative; }
    int selection() { return selection; }
    boolean buttonShadow() { return vanilla; }
    int buttonText(boolean enabled) { return vanilla ? (enabled ? 0xffffffff : 0xffaaaaaa) : (enabled ? text : muted); }

    void background(GuiGraphics graphics, int x, int y, int width, int height) {
        if (vanilla) {
            // This is Minecraft's generic popup panel. Its GUI metadata defines a
            // nine-slice border, so resource packs can provide a panel designed for
            // arbitrary sizes instead of a short status-effect card being repeated.
            graphics.blitSprite(VANILLA_BACKGROUND, x, y, width, height);
            return;
        }
        int sourceWidth = 256;
        int sourceHeight = 256;
        int border = Math.min(2, Math.min(width / 2, height / 2));
        int[] outWidths = {border, width - 2 * border, border};
        int[] outHeights = {border, height - 2 * border, border};
        int[] sourceWidths = {border, sourceWidth - 2 * border, border};
        int[] sourceHeights = {border, sourceHeight - 2 * border, border};
        int destY = y, sourceY = 0;
        for (int row = 0; row < 3; row++) {
            int destX = x, sourceX = 0;
            for (int column = 0; column < 3; column++) {
                if (outWidths[column] > 0 && outHeights[row] > 0) {
                    graphics.blit(AE2_BACKGROUND, destX, destY, outWidths[column], outHeights[row],
                        (float) sourceX, (float) sourceY, sourceWidths[column], sourceHeights[row], sourceWidth, sourceHeight);
                }
                destX += outWidths[column]; sourceX += sourceWidths[column];
            }
            destY += outHeights[row]; sourceY += sourceHeights[row];
        }
    }

    void button(GuiGraphics graphics, int x, int y, int width, int height, boolean enabled, boolean hovered) {
        String name = !enabled ? "button_disabled" : hovered ? "button_highlighted" : "button";
        ResourceLocation sprite = vanilla ? ResourceLocation.withDefaultNamespace("widget/" + name)
            : ResourceLocation.fromNamespaceAndPath("ae2", name);
        if (vanilla && enabled && hovered) {
            var sprites = Minecraft.getInstance().getGuiSprites();
            if (sprites.getSpriteScaling(sprites.getSprite(sprite)) instanceof GuiSpriteScaling.NineSlice slice
                    && slice.border().left() + slice.border().right() + 16 > width) {
                plainHoverButton(graphics, x, y, width, height, true, true);
                return;
            }
        }
        graphics.blitSprite(sprite, x, y, width, height);
    }

    void providerRow(GuiGraphics graphics, int x, int y, int width, int height, boolean enabled, boolean hovered) {
        if (vanilla) plainHoverButton(graphics, x, y, width, height, enabled, hovered);
        else {
            String name = !enabled ? "button_disabled" : hovered ? "button_highlighted" : "button";
            var sprite = ResourceLocation.fromNamespaceAndPath("ae2", name);
            var sprites = Minecraft.getInstance().getGuiSprites();
            if (sprites.getSpriteScaling(sprites.getSprite(sprite)) instanceof GuiSpriteScaling.NineSlice slice) {
                // GUI nine-slice normally tiles the center. AE2's 20px button gradient
                // repeats inside our 26px rows, producing a seam through the second line.
                // Stretch each patch instead, preserving the pack's border dimensions.
                var texture = ResourceLocation.fromNamespaceAndPath("ae2", "textures/gui/sprites/" + name + ".png");
                int left = Math.min(slice.border().left(), width / 2);
                int right = Math.min(slice.border().right(), width / 2);
                int top = Math.min(slice.border().top(), height / 2);
                int bottom = Math.min(slice.border().bottom(), height / 2);
                int[] outW = {left, width - left - right, right};
                int[] outH = {top, height - top - bottom, bottom};
                int[] srcW = {slice.border().left(), slice.width() - slice.border().left() - slice.border().right(), slice.border().right()};
                int[] srcH = {slice.border().top(), slice.height() - slice.border().top() - slice.border().bottom(), slice.border().bottom()};
                int dy = y, sy = 0;
                for (int row = 0; row < 3; row++) {
                    int dx = x, sx = 0;
                    for (int col = 0; col < 3; col++) {
                        if (outW[col] > 0 && outH[row] > 0 && srcW[col] > 0 && srcH[row] > 0)
                            graphics.blit(texture, dx, dy, outW[col], outH[row], (float) sx, (float) sy,
                                srcW[col], srcH[row], slice.width(), slice.height());
                        dx += outW[col]; sx += srcW[col];
                    }
                    dy += outH[row]; sy += srcH[row];
                }
            } else graphics.blitSprite(sprite, x, y, width, height);
        }
    }

    private void plainHoverButton(GuiGraphics graphics, int x, int y, int width, int height, boolean enabled, boolean hovered) {
        // A list row contains an icon, two text lines and counts. Menu-button decorations
        // such as CozyUI's animated arrows have no reserved space in this content layout.
        button(graphics, x, y, width, height, enabled, false);
        if (enabled && hovered) {
            int inset = Math.min(3, Math.min(width / 2, height / 2));
            graphics.fill(x + inset, y + inset, x + width - inset, y + height - inset, 0x24ffffff);
            graphics.fill(x + 1, y + inset, x + 3, y + height - inset, selection);
        }
    }
}
