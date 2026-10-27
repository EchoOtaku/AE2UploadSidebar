package dev.echo.uploadsidebar.mixin;

import dev.echo.uploadsidebar.client.SidebarJeiPlugin;
import java.util.Optional;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferManager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observe the exact category after a successful real transfer; previews and rejected transfers do nothing. */
@Pseudo
@Mixin(targets = "mezz.jei.common.transfer.RecipeTransferUtil", remap = false)
public abstract class JeiRecipeTransferMixin {
    @Inject(method = "transferRecipe(Lmezz/jei/api/recipe/transfer/IRecipeTransferManager;Lnet/minecraft/world/inventory/AbstractContainerMenu;Lmezz/jei/api/gui/IRecipeLayoutDrawable;Lnet/minecraft/world/entity/player/Player;ZZ)Ljava/util/Optional;",
        at = @At("RETURN"), remap = false, require = 0)
    private static void sidebar$rememberCategory(IRecipeTransferManager manager, AbstractContainerMenu menu,
            IRecipeLayoutDrawable<?> layout, Player player, boolean maxTransfer, boolean doTransfer,
            CallbackInfoReturnable<Optional<IRecipeTransferError>> callback) {
        if (doTransfer && callback.getReturnValue().isEmpty()) SidebarJeiPlugin.recipeTransferred(menu, layout);
    }
}
