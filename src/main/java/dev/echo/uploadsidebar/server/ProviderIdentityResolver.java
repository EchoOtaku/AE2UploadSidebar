package dev.echo.uploadsidebar.server;

import appeng.api.parts.IPartItem;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.parts.AEBasePart;
import dev.echo.uploadsidebar.core.ProviderIdentity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Optional;

/** Read-only location metadata for actual placed providers; unknown wrappers get no identity. */
final class ProviderIdentityResolver {
    private ProviderIdentityResolver() { }

    static Optional<ProviderIdentity> resolve(PatternContainer provider) {
        try {
            if (provider instanceof AEBasePart part) {
                var side = part.getSide();
                var host = part.getHost();
                var blockEntity = part.getBlockEntity();
                if (side == null || host == null || !host.isInWorld() || host.getPart(side) != part
                        || !live(blockEntity) || part.getPartItem() == null) return Optional.empty();
                var type = IPartItem.getId(part.getPartItem());
                if (type == null) return Optional.empty();
                var pos = blockEntity.getBlockPos();
                return Optional.of(ProviderIdentity.part(blockEntity.getLevel().dimension().location().toString(),
                        type.toString(), pos.getX(), pos.getY(), pos.getZ(), side.getSerializedName()));
            }
            if (provider instanceof BlockEntity blockEntity && live(blockEntity)) {
                var type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
                if (type == null) return Optional.empty();
                var pos = blockEntity.getBlockPos();
                return Optional.of(ProviderIdentity.block(blockEntity.getLevel().dimension().location().toString(),
                        type.toString(), pos.getX(), pos.getY(), pos.getZ()));
            }
        } catch (RuntimeException unsupportedProvider) {
            // Optional notes must never prevent an otherwise valid provider snapshot or upload.
        }
        return Optional.empty();
    }

    private static boolean live(BlockEntity blockEntity) {
        return blockEntity != null && !blockEntity.isRemoved()
                && blockEntity.getLevel() instanceof ServerLevel level
                && level.hasChunkAt(blockEntity.getBlockPos())
                && level.getBlockEntity(blockEntity.getBlockPos()) == blockEntity;
    }
}
