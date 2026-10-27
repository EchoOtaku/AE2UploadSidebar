package dev.echo.uploadsidebar.client;

import appeng.api.ids.AEComponents;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.parts.encoding.EncodingMode;
import dev.echo.uploadsidebar.core.RecipeIngredients;
import dev.echo.uploadsidebar.core.RecipeIngredients.Amount;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** A successful JEI transfer belongs to one live menu and its full input/output recipe, never global history. */
public final class SidebarRecipeContext {
    public record Category(ResourceLocation id, String title) { }
    private static PatternEncodingTermMenu menu;
    private static Object level;
    private static Category category;
    private static List<List<Amount<AEKey>>> inputs = List.of(), outputs = List.of();
    private static boolean synchronizedDraft;

    private SidebarRecipeContext() { }

    public static void transferred(PatternEncodingTermMenu target, Category selected,
                                   List<List<GenericStack>> inputChoices, List<GenericStack> outputStacks) {
        clear();
        var client = Minecraft.getInstance();
        if (!SidebarClient.isEnabled() || client.player == null || client.level == null
                || client.player.containerMenu != target || inputChoices.isEmpty() || outputStacks.isEmpty()) return;
        menu = target;
        level = client.level;
        category = selected;
        inputs = inputChoices.stream().filter(list -> !list.isEmpty()).map(SidebarRecipeContext::amounts).toList();
        outputs = outputStacks.stream().map(stack -> List.of(new Amount<>(stack.what(), stack.amount()))).toList();
    }

    public static void tick() {
        if (menu == null) return;
        var client = Minecraft.getInstance();
        if (!SidebarClient.isEnabled() || client.player == null || client.level != level || client.player.containerMenu != menu) {
            clear();
            return;
        }
        // AE2 sends filters to the server and receives them asynchronously. Accept only the complete draft.
        boolean matches = menu.getMode() == EncodingMode.PROCESSING
            && RecipeIngredients.matches(inputs, amounts(Arrays.stream(menu.getProcessingInputSlots())
                .map(slot -> GenericStack.fromItemStack(slot.getItem())).filter(Objects::nonNull).toList()))
            && RecipeIngredients.matches(outputs, amounts(Arrays.stream(menu.getProcessingOutputSlots())
                .map(slot -> GenericStack.fromItemStack(slot.getItem())).filter(Objects::nonNull).toList()));
        if (matches) synchronizedDraft = true;
        else if (synchronizedDraft) clear();
    }

    public static Category forPattern(Object expectedMenu, ItemStack pattern) {
        tick();
        if (menu != expectedMenu || category == null || !synchronizedDraft) return null;
        var processing = pattern.get(AEComponents.ENCODED_PROCESSING_PATTERN);
        return processing != null && RecipeIngredients.matches(inputs, amounts(processing.sparseInputs()))
            && RecipeIngredients.matches(outputs, amounts(processing.sparseOutputs())) ? category : null;
    }

    private static List<Amount<AEKey>> amounts(List<GenericStack> stacks) {
        return stacks.stream().filter(Objects::nonNull).map(stack -> new Amount<>(stack.what(), stack.amount())).toList();
    }

    public static void clear() {
        menu = null; level = null; category = null; inputs = List.of(); outputs = List.of(); synchronizedDraft = false;
    }
}
