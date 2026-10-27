package dev.echo.uploadsidebar.client;

import appeng.client.gui.me.items.PatternEncodingTermScreen;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.implementations.PatternProviderScreen;
import appeng.integration.modules.itemlists.EncodingHelper;
import appeng.menu.me.items.PatternEncodingTermMenu;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.fml.ModList;
import tamaized.ae2jeiintegration.integration.modules.jei.GenericEntryStackHelper;

/** Optional JEI integration for private icon drops and display-only recipe-category capture. */
@JeiPlugin
public final class SidebarJeiPlugin implements IModPlugin {
    private static IJeiRuntime runtime;

    @Override public void onRuntimeAvailable(IJeiRuntime available) { runtime = available; }
    @Override public void onRuntimeUnavailable() { runtime = null; SidebarRecipeContext.clear(); }

    public static String categoryTitle(ResourceLocation id) {
        if (runtime == null) return "";
        var manager = runtime.getRecipeManager();
        return manager.getRecipeType(id).map(manager::getRecipeCategory).map(category -> category.getTitle().getString()).orElse("");
    }

    /** Does not replace JEI's transfer handler or edit any slots; observes only an already successful transfer. */
    public static void recipeTransferred(AbstractContainerMenu target, IRecipeLayoutDrawable<?> layout) {
        if (!(target instanceof PatternEncodingTermMenu terminal) || !SidebarClient.isEnabled()) return;
        SidebarRecipeContext.clear();
        if (!ModList.get().isLoaded("ae2jeiintegration")) return;
        try {
            if (layout.getRecipe() instanceof RecipeHolder<?> holder && EncodingHelper.isSupportedCraftingRecipe(holder.value())) return;
            var category = layout.getRecipeCategory();
            SidebarRecipeContext.transferred(terminal,
                new SidebarRecipeContext.Category(category.getRecipeType().getUid(), category.getTitle().getString()),
                GenericEntryStackHelper.ofInputs(layout.getRecipeSlotsView()), GenericEntryStackHelper.ofOutputs(layout.getRecipeSlotsView()));
        } catch (RuntimeException | LinkageError error) {
            SidebarRecipeContext.clear();
            LogUtils.getLogger().warn("Cannot capture the transferred JEI recipe category for sidebar search", error);
        }
    }

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath("ae2_upload_sidebar", "private_icons");
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        var handler = new IconGhostHandler();
        registerScreen(registration, PatternEncodingTermScreen.class, handler);
        registerScreen(registration, PatternProviderScreen.class, handler);
        if (ModList.get().isLoaded("extendedae")) {
            try {
                Class<?> extended = Class.forName("com.glodblock.github.extendedae.client.gui.GuiExPatternProvider", false,
                    SidebarJeiPlugin.class.getClassLoader());
                if (PatternProviderScreen.class.isAssignableFrom(extended)) registerScreen(registration, extended, handler);
            } catch (ClassNotFoundException | LinkageError error) {
                LogUtils.getLogger().warn("Cannot register extended provider private-icon drops", error);
            }
        }
        // JEI 19.44 evaluates exact screen registrations before inherited ones.
        // Register WET exactly too, so an overlapping AE2 FakeSlot cannot win a drop.
        // This appends to JEI's handler list; it never replaces AE2's original handler.
        if (ModList.get().isLoaded("ae2wtlib")) {
            try {
                Class<?> wireless = Class.forName("de.mari_023.ae2wtlib.wet.WETScreen", false,
                    SidebarJeiPlugin.class.getClassLoader());
                if (PatternEncodingTermScreen.class.isAssignableFrom(wireless))
                    registerScreen(registration, wireless, handler);
            } catch (ClassNotFoundException | LinkageError error) {
                LogUtils.getLogger().warn("Cannot register optional wireless terminal private-icon drops", error);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void registerScreen(IGuiHandlerRegistration registration, Class<?> screenClass,
                                       IconGhostHandler handler) {
        registration.addGhostIngredientHandler((Class<AEBaseScreen<?>>) screenClass, handler);
    }

    private static final class IconGhostHandler implements IGhostIngredientHandler<AEBaseScreen<?>> {
        @Override
        public <I> List<Target<I>> getTargetsTyped(AEBaseScreen<?> screen,
                                                  ITypedIngredient<I> ingredient, boolean doStart) {
            if (ingredient.getItemStack().filter(stack -> !stack.isEmpty()).isEmpty()) return List.of();
            var client = Minecraft.getInstance();
            if (!SidebarClient.isEnabled() || client.screen != screen || client.player == null
                    || client.player.containerMenu != screen.getMenu()) return List.of();
            Object expectedMenu = screen.getMenu();
            var targets = new ArrayList<Target<I>>();
            for (var captured : SidebarClient.iconTargets(screen)) {
                targets.add(new Target<>() {
                    @Override
                    public Rect2i getArea() {
                        var bounds = captured.bounds();
                        return new Rect2i(bounds.x(), bounds.y(), bounds.width(), bounds.height());
                    }

                    @Override
                    public void accept(I value) {
                        // The controller and panel recheck menu, token and exact current bounds.
                        // A rejected void accept still consumes this target; it never falls through.
                        VanillaTypes.ITEM_STACK.castIngredient(value).filter(stack -> !stack.isEmpty())
                            .ifPresent(stack -> SidebarClient.acceptIcon(screen, expectedMenu, captured,
                                stack.copyWithCount(1)));
                    }
                });
            }
            return List.copyOf(targets);
        }

        @Override
        public void onComplete() { }
    }
}
