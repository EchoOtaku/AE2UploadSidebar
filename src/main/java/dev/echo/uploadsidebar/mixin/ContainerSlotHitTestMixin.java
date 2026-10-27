package dev.echo.uploadsidebar.mixin;

import dev.echo.uploadsidebar.client.SidebarClient;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A floating sidebar occludes underlying slots without skipping container release cleanup. */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerSlotHitTestMixin {
    @Inject(method = "isHovering(Lnet/minecraft/world/inventory/Slot;DD)Z", at = @At("HEAD"), cancellable = true)
    private void sidebar$hideCoveredSlot(Slot slot, double mouseX, double mouseY,
                                        CallbackInfoReturnable<Boolean> callback) {
        // contains is a pure lookup restricted to our already-active encoding screen.
        // This covers both hover rendering and findSlot used by mouse release/drag.
        if (SidebarClient.contains((Screen) (Object) this, mouseX, mouseY)) callback.setReturnValue(false);
    }
}
