package dev.echo.uploadsidebar.client;

import appeng.api.parts.IPartItem;
import appeng.client.gui.AEBaseScreen;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.menu.implementations.PatternProviderMenu;
import appeng.parts.AEBasePart;
import dev.echo.uploadsidebar.core.ProviderIdentity;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Read-only metadata from the host of the actual open provider menu, including disconnected new blocks. */
final class OpenedProvider {
    record Target(Object host, ProviderIdentity identity, String name, ItemStack icon) { }
    private OpenedProvider() { }

    static Optional<Target> resolve(AEBaseScreen<?> screen) {
        var client = Minecraft.getInstance();
        if (!(screen.getMenu() instanceof PatternProviderMenu menu) || client.player == null
                || client.player.containerMenu != menu || client.level == null || !menu.isValidMenu()) return Optional.empty();
        Object host = menu.getTarget();
        if (!(host instanceof PatternContainer)) return Optional.empty();
        try {
            ProviderIdentity identity;
            ItemStack icon;
            if (host instanceof AEBasePart part) {
                var side = part.getSide();
                var cable = part.getHost();
                var entity = part.getBlockEntity();
                // AE2 only initializes CableBusContainer.inWorld on the server. On the client,
                // verify the live world block entity and exact part at this face instead.
                if (side == null || cable == null || cable.getPart(side) != part
                        || !live(entity) || part.getPartItem() == null) return Optional.empty();
                var type = IPartItem.getId(part.getPartItem());
                if (type == null) return Optional.empty();
                var pos = entity.getBlockPos();
                identity = ProviderIdentity.part(client.level.dimension().location().toString(), type.toString(),
                    pos.getX(), pos.getY(), pos.getZ(), side.getSerializedName());
                icon = new ItemStack(part.getPartItem());
            } else if (host instanceof BlockEntity entity && live(entity)) {
                var type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType());
                if (type == null) return Optional.empty();
                var pos = entity.getBlockPos();
                identity = ProviderIdentity.block(client.level.dimension().location().toString(), type.toString(),
                    pos.getX(), pos.getY(), pos.getZ());
                icon = new ItemStack(entity.getBlockState().getBlock());
            } else return Optional.empty();
            String name = screen.getTitle().getString();
            return Optional.of(new Target(host, identity, name.isBlank() ? icon.getHoverName().getString() : name, icon));
        } catch (RuntimeException unsupportedHost) {
            return Optional.empty();
        }
    }

    private static boolean live(BlockEntity entity) {
        var level = Minecraft.getInstance().level;
        return entity != null && !entity.isRemoved() && entity.getLevel() == level && level != null
            && level.hasChunkAt(entity.getBlockPos()) && level.getBlockEntity(entity.getBlockPos()) == entity;
    }
}
