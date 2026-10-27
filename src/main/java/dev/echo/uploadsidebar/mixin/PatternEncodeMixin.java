package dev.echo.uploadsidebar.mixin;

import appeng.menu.me.items.PatternEncodingTermMenu;
import dev.echo.uploadsidebar.client.SidebarClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PatternEncodingTermMenu.class, remap = false)
public abstract class PatternEncodeMixin {
    @Inject(method = "encode()V", at = @At("HEAD"), remap = false)
    private void sidebar$encodeRequested(CallbackInfo callback) {
        PatternEncodingTermMenu menu = (PatternEncodingTermMenu) (Object) this;
        if (menu.isClientSide()) SidebarClient.encodeRequested(menu);
    }
}
