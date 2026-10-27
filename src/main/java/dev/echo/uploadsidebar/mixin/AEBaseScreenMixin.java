package dev.echo.uploadsidebar.mixin;

import appeng.client.gui.AEBaseScreen;
import dev.echo.uploadsidebar.client.SidebarClient;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** AE2 and wireless subclasses keep their original menu and full screen lifecycle. */
@Mixin(value = AEBaseScreen.class, remap = false)
public abstract class AEBaseScreenMixin {
    @Inject(method = "getExclusionZones", at = @At("RETURN"), cancellable = true, remap = false)
    private void sidebar$excludePanel(CallbackInfoReturnable<List<Rect2i>> callback) {
        if ((Object) this instanceof AEBaseScreen<?> current) {
            Rect2i region = SidebarClient.exclusionZone(current);
            if (region != null) {
                List<Rect2i> zones = new ArrayList<>(callback.getReturnValue());
                zones.add(region);
                callback.setReturnValue(zones);
            }
        }
    }

    @Inject(method = "hasClickedOutside(DDIII)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void sidebar$protectCarriedItem(double mouseX, double mouseY, int left, int top, int button,
                                            CallbackInfoReturnable<Boolean> callback) {
        if (SidebarClient.contains((Screen) (Object) this, mouseX, mouseY)) {
            callback.setReturnValue(false);
        }
    }
}
